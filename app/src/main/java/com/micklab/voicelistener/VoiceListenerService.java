package com.micklab.voicelistener;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import android.content.SharedPreferences;

public class VoiceListenerService extends Service {
    private static final String TAG = "VoiceListenerService";
    private static final String CHANNEL_ID = "VoiceListenerChannel";
    private static final int NOTIFICATION_ID = 1;

    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SAMPLES = 320;
    private static final int MAX_SILENCE_FRAMES = 15;
    private static final int MIN_SPEECH_FRAMES = 8;
    private static final double RMS_THRESHOLD = 900.0;
    private static final long SUMMARY_DEBOUNCE_MS = 4000L;

    private static final String LEGACY_VOSK_MODEL_FOLDER = "vosk-model-ja";
    private static final String MODELS_FOLDER = "models";
    private static final String MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip";

    private static final String PREFS_NAME = "VoiceListenerPrefs";
    private static final String PREF_RMS_THRESHOLD = "rms_threshold";
    private static final String PREF_MON_STATE = "monitor_state";
    private static final String PREF_ACTIVE_MODEL_NAME = "active_model_name";
    private static final String PREF_ACTIVE_MODEL_URL = "active_model_url"; // legacy
    private static final String PREF_MODEL_DOWNLOAD_ACTIVE = "model_download_active";
    private static final String PREF_MODEL_DOWNLOAD_PROGRESS = "model_download_progress";
    private static final String PREF_MODEL_DOWNLOAD_NAME = "model_download_name";
    private static final String PREF_CURRENT_RMS = "current_rms";
    private static final String MON_STATE_RUNNING = "running";
    private static final String MON_STATE_PENDING = "pending";
    private static final String MON_STATE_STOPPED = "stopped";

    public static final String ACTION_INSTALL_MODEL = "com.micklab.voicelistener.action.INSTALL_MODEL";
    public static final String ACTION_SELECT_MODEL = "com.micklab.voicelistener.action.SELECT_MODEL";
    public static final String ACTION_DELETE_MODEL = "com.micklab.voicelistener.action.DELETE_MODEL";
    public static final String ACTION_STOP_MONITORING = "com.micklab.voicelistener.action.STOP_MONITORING";
    public static final String EXTRA_MODEL_URL = "com.micklab.voicelistener.extra.MODEL_URL";
    public static final String EXTRA_MODEL_NAME = "com.micklab.voicelistener.extra.MODEL_NAME";
    public static final String EXTRA_MODEL_REPLACE = "com.micklab.voicelistener.extra.MODEL_REPLACE";

    private LogManager2 logManager;
    private VoiceActivityDetector vad;
    private OfflineAsrEngine asrEngine;
    private ExecutorService transcriptionExecutor;
    private ExecutorService modelInstallerExecutor;
    private ScheduledExecutorService summaryExecutor;
    private SharedPreferences sharedPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private final OllamaClient ollamaClient = new OllamaClient();

    private AudioRecord audioRecord;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl automaticGainControl;
    private AcousticEchoCanceler acousticEchoCanceler;
    private Thread captureThread;
    private volatile boolean isCapturing = false;
    private long lastRmsPublishMs = 0L;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiWakeLock;
    private ScheduledFuture<?> pendingSummaryFuture;
    private int activeAudioSource = MediaRecorder.AudioSource.MIC;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VoiceListenerService created");

        logManager = new LogManager2(this);

        // VAD閾値は SharedPreferences から取得して初期化する
        sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float prefThreshold = sharedPrefs.getFloat(PREF_RMS_THRESHOLD, (float) RMS_THRESHOLD);
        vad = new VoiceActivityDetector(prefThreshold, MAX_SILENCE_FRAMES, MIN_SPEECH_FRAMES);

        prefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (PREF_RMS_THRESHOLD.equals(key)) {
                    float newVal = sharedPreferences.getFloat(PREF_RMS_THRESHOLD, (float) RMS_THRESHOLD);
                    if (vad != null) {
                        vad.setRmsThreshold(newVal);
                        try { logManager.writeLog("VAD閾値更新: " + newVal); } catch (Exception ignored) {}
                    }
                }
            }
        };
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener);

        transcriptionExecutor = Executors.newSingleThreadExecutor();
        modelInstallerExecutor = Executors.newSingleThreadExecutor();
        summaryExecutor = Executors.newSingleThreadScheduledExecutor();

        createNotificationChannel();
        initializeRunLocks();
        initializeAsrEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VoiceListenerService started");

        startForeground(NOTIFICATION_ID, createNotification());

        // intent がモデルインストール要求または停止要求を含む場合は先に処理する
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_INSTALL_MODEL.equals(action)) {
            String url = normalizeModelUrl(intent.getStringExtra(EXTRA_MODEL_URL));
            File modelDir = getModelDirForUrl(url);
            // 同一URLは常に削除して再ダウンロードする
            installModelFromUrlAsync(modelDir, url, true, !isCapturing, true);
            // モデルインストールリクエスト時は音声認識はここで開始しない
        } else if (ACTION_SELECT_MODEL.equals(action)) {
            String modelName = normalizeModelName(intent != null ? intent.getStringExtra(EXTRA_MODEL_NAME) : null);
            if (modelName == null && intent != null) {
                String legacyUrl = intent.getStringExtra(EXTRA_MODEL_URL);
                if (legacyUrl != null && !legacyUrl.trim().isEmpty()) {
                    modelName = getModelNameFromUrl(legacyUrl);
                }
            }
            boolean switched = switchToModelName(modelName, true);
            if (logManager != null) {
                logManager.writeLog(switched ? ("モデル切替完了: " + modelName) : ("モデル切替失敗（未ダウンロード）: " + modelName), false);
            }
            if (!isCapturing) {
                stopSelf();
            }
        } else if (ACTION_DELETE_MODEL.equals(action)) {
            String modelName = normalizeModelName(intent != null ? intent.getStringExtra(EXTRA_MODEL_NAME) : null);
            if (modelName == null && intent != null) {
                String legacyUrl = intent.getStringExtra(EXTRA_MODEL_URL);
                if (legacyUrl != null && !legacyUrl.trim().isEmpty()) {
                    modelName = getModelNameFromUrl(legacyUrl);
                }
            }
            boolean deleted = deleteModelByName(modelName);
            if (logManager != null) {
                logManager.writeLog(deleted ? ("モデル削除完了: " + modelName) : ("モデル削除対象なし: " + modelName), false);
            }
            if (!isCapturing) {
                stopSelf();
            }
        } else if (ACTION_STOP_MONITORING.equals(action)) {
            // UIの停止要求: 録音は停止し、既にキューに入っている処理を完了したらサービスを終了する
            try { if (logManager != null) logManager.writeLog("監視停止要求を受信: 録音を停止し、保留処理完了後に終了します", false); } catch (Exception ignored) {}
            // set pending state
            try { if (sharedPrefs != null) sharedPrefs.edit().putString(PREF_MON_STATE, MON_STATE_PENDING).apply(); } catch (Exception ignored) {}
            stopAudioCapture();
            ExecutorService drainingExecutor = transcriptionExecutor;
            if (drainingExecutor != null) {
                if (modelInstallerExecutor == null) modelInstallerExecutor = Executors.newSingleThreadExecutor();
                modelInstallerExecutor.execute(() -> {
                    try {
                        drainingExecutor.shutdown();
                        boolean terminated = drainingExecutor.awaitTermination(120, TimeUnit.SECONDS);
                        if (!terminated) {
                            drainingExecutor.shutdownNow();
                        }
                        cancelPendingSummaryTask();
                        shutdownSummaryExecutor(true);
                        refreshLiveSummary(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        try { if (logManager != null) logManager.writeLog("監視停止待機中に割込: " + e.getMessage(), false); } catch (Exception ignored) {}
                    } finally {
                        if (transcriptionExecutor == drainingExecutor) {
                            transcriptionExecutor = null;
                        }
                        try { if (logManager != null) logManager.writeLog("保留処理完了、サービスを停止します", false); } catch (Exception ignored) {}
                        // set stopped state
                        try { if (sharedPrefs != null) sharedPrefs.edit().putString(PREF_MON_STATE, MON_STATE_STOPPED).apply(); } catch (Exception ignored) {}
                        stopSelf();
                    }
                });
            } else {
                if (modelInstallerExecutor == null) modelInstallerExecutor = Executors.newSingleThreadExecutor();
                modelInstallerExecutor.execute(() -> {
                    cancelPendingSummaryTask();
                    shutdownSummaryExecutor(true);
                    refreshLiveSummary(true);
                    try { if (logManager != null) logManager.writeLog("保留処理なし、サービスを停止します", false); } catch (Exception ignored) {}
                    try { if (sharedPrefs != null) sharedPrefs.edit().putString(PREF_MON_STATE, MON_STATE_STOPPED).apply(); } catch (Exception ignored) {}
                    stopSelf();
                });
            }
        } else {
            startAudioCapture();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "VoiceListenerService destroyed");

        stopAudioCapture();
        cancelPendingSummaryTask();
        shutdownSummaryExecutor(false);

        if (transcriptionExecutor != null) {
            transcriptionExecutor.shutdownNow();
            transcriptionExecutor = null;
        }

        if (asrEngine != null) {
            asrEngine.shutdown();
            asrEngine = null;
        }
        if (modelInstallerExecutor != null) {
            modelInstallerExecutor.shutdownNow();
            modelInstallerExecutor = null;
        }

        // ensure state is stopped
        try { if (sharedPrefs != null) sharedPrefs.edit().putString(PREF_MON_STATE, MON_STATE_STOPPED).apply(); } catch (Exception ignored) {}
        try {
            if (sharedPrefs != null) {
                sharedPrefs.edit()
                    .putBoolean(PREF_MODEL_DOWNLOAD_ACTIVE, false)
                    .putInt(PREF_MODEL_DOWNLOAD_PROGRESS, 0)
                    .putFloat(PREF_CURRENT_RMS, 0f)
                    .apply();
            }
        } catch (Exception ignored) {}

        if (sharedPrefs != null && prefsListener != null) {
            try { sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener); } catch (Exception ignored) {}
            prefsListener = null;
            sharedPrefs = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "音声監視サービス",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("常時音声監視を実行中");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder
            .setContentTitle("音声監視中")
            .setContentText("AudioRecordでスリープ中も監視を継続中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void initializeRunLocks() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":cpu");
            cpuWakeLock.setReferenceCounted(false);
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            wifiWakeLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG + ":wifi");
            wifiWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireRunLocks() {
        try {
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
                cpuWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire CPU wake lock", e);
        }
        try {
            if (wifiWakeLock != null && !wifiWakeLock.isHeld()) {
                wifiWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire Wi-Fi lock", e);
        }
    }

    private void releaseRunLocks() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release CPU wake lock", e);
        }
        try {
            if (wifiWakeLock != null && wifiWakeLock.isHeld()) {
                wifiWakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release Wi-Fi lock", e);
        }
    }

    private void initializeAsrEngine() {
        File preferredModelDir = resolvePreferredModelDir();
        if (initializeAsrEngineWithModel(preferredModelDir)) {
            return;
        }

        asrEngine = new NoOpOfflineAsrEngine();
        asrEngine.initialize();
        Log.w(TAG, "ASR engine fallback: " + asrEngine.name() + " (model missing)");
    }

    private void installModelIfMissingAsync(File modelDir) {
        installModelFromUrlAsync(modelDir, MODEL_ZIP_URL, false, false, true);
    }

    private boolean initializeAsrEngineWithModel(File modelDir) {
        if (!hasModelContent(modelDir)) {
            return false;
        }
        OfflineAsrEngine newEngine = new VoskOfflineAsrEngine(modelDir.getAbsolutePath());
        if (!newEngine.initialize()) {
            try { newEngine.shutdown(); } catch (Exception ignored) {}
            return false;
        }
        replaceAsrEngine(newEngine);
        Log.i(TAG, "ASR engine: " + newEngine.name() + " @ " + modelDir.getAbsolutePath());
        return true;
    }

    private void installModelFromUrlAsync(File modelDir, String url, boolean replace, boolean stopServiceOnFinish, boolean activateAfterInstall) {
        final String normalizedUrl = normalizeModelUrl(url);
        final String modelName = getModelNameFromUrl(normalizedUrl);
        if (!replace && modelDir.exists() && modelDir.isDirectory() && modelDir.listFiles() != null && modelDir.listFiles().length > 0) {
            // 既に存在する
            return;
        }
        if (replace && modelDir.exists()) {
            deleteRecursively(modelDir);
        }
        if (modelInstallerExecutor == null) {
            modelInstallerExecutor = Executors.newSingleThreadExecutor();
        }
        modelInstallerExecutor.execute(() -> {
            int finalProgress = 0;
            try {
                updateDownloadProgress(true, 0, modelName);
                logManager.writeLog("モデル取得開始: " + url);
            } catch (Exception e) {
                Log.w(TAG, "ログ書き込み失敗", e);
            }

            String modelKey = hashUrl(normalizedUrl);
            File cacheZip = new File(getCacheDir(), "vosk_model_" + modelKey + ".zip");
            File extractDir = new File(getCacheDir(), "vosk_model_extract_" + modelKey);
            try {
                if (cacheZip.exists()) {
                    deleteRecursively(cacheZip);
                }
                if (extractDir.exists()) {
                    deleteRecursively(extractDir);
                }

                boolean dlOk = downloadFile(normalizedUrl, cacheZip, modelName);
                if (!dlOk) {
                    logManager.writeLog("モデルダウンロード失敗");
                    if (stopServiceOnFinish) stopSelf();
                    return;
                }

                // 解凍
                boolean unzipOk = unzipToDir(cacheZip, extractDir);
                if (!unzipOk) {
                    logManager.writeLog("モデル解凍失敗");
                    if (stopServiceOnFinish) stopSelf();
                    return;
                }

                // コピー先を作成
                if (!modelDir.exists()) {
                    if (!modelDir.mkdirs()) {
                        logManager.writeLog("モデルフォルダ作成失敗: " + modelDir.getAbsolutePath());
                        if (stopServiceOnFinish) stopSelf();
                        return;
                    }
                }

                // 解凍先の構造により中身をmodelDirへコピー
                File[] children = extractDir.listFiles();
                File source = extractDir;
                if (children != null && children.length == 1 && children[0].isDirectory()) {
                    source = children[0];
                }

                boolean copyOk = copyDirectory(source, modelDir);
                if (!copyOk) {
                    logManager.writeLog("モデルコピー失敗");
                    if (stopServiceOnFinish) stopSelf();
                    return;
                }

                // クリーンアップ
                deleteRecursively(cacheZip);
                deleteRecursively(extractDir);

                logManager.writeLog("モデル取得・展開完了: " + modelDir.getAbsolutePath());
                finalProgress = 100;

                if (activateAfterInstall && sharedPrefs != null) {
                    sharedPrefs.edit()
                        .putString(PREF_ACTIVE_MODEL_NAME, modelName)
                        .remove(PREF_ACTIVE_MODEL_URL)
                        .apply();
                }

                if (activateAfterInstall) {
                    if (switchToModelName(modelName, false)) {
                        logManager.writeLog("ASRエンジン初期化成功");
                    } else {
                        logManager.writeLog("ASRエンジン初期化失敗");
                    }
                }

            } catch (Exception e) {
                try { logManager.writeLog("モデル取得中に例外発生: " + e.getMessage()); } catch (Exception ignored) {}
                Log.e(TAG, "モデル取得例外", e);
            } finally {
                try { updateDownloadProgress(false, finalProgress, modelName); } catch (Exception ignored) {}
                if (stopServiceOnFinish) {
                    try { stopSelf(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private String normalizeModelUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return MODEL_ZIP_URL;
        }
        return url.trim();
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null) return null;
        String trimmed = modelName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String getModelNameFromUrl(String url) {
        String normalizedUrl = normalizeModelUrl(url);
        String fallback = "model_" + hashUrl(normalizedUrl);
        try {
            Uri uri = Uri.parse(normalizedUrl);
            String segment = uri != null ? uri.getLastPathSegment() : null;
            if (segment == null || segment.trim().isEmpty()) {
                return fallback;
            }
            segment = Uri.decode(segment).trim();
            if (segment.isEmpty()) {
                return fallback;
            }
            if (segment.toLowerCase(Locale.US).endsWith(".zip")) {
                segment = segment.substring(0, segment.length() - 4);
            }
            String sanitized = segment.replaceAll("[^A-Za-z0-9._-]", "_");
            return sanitized.isEmpty() ? fallback : sanitized;
        } catch (Exception e) {
            return fallback;
        }
    }

    private File getVoiceListenerRootDir() {
        File documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) {
            documentsDir = getFilesDir();
        }
        File rootDir = new File(documentsDir, "VoiceListener");
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return rootDir;
    }

    private File getModelsRootDir() {
        File modelsDir = new File(getVoiceListenerRootDir(), MODELS_FOLDER);
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        return modelsDir;
    }

    private File getLegacyModelDir() {
        return new File(getVoiceListenerRootDir(), LEGACY_VOSK_MODEL_FOLDER);
    }

    private File getModelDirForUrl(String url) {
        return getModelDirForName(getModelNameFromUrl(url));
    }

    private File getModelDirForName(String modelName) {
        String normalized = normalizeModelName(modelName);
        if (normalized == null) {
            return null;
        }
        return new File(getModelsRootDir(), normalized);
    }

    private boolean hasModelContent(File modelDir) {
        if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory()) {
            return false;
        }
        File[] files = modelDir.listFiles();
        return files != null && files.length > 0;
    }

    private File resolvePreferredModelDir() {
        String activeModelName = sharedPrefs != null ? sharedPrefs.getString(PREF_ACTIVE_MODEL_NAME, null) : null;
        if (activeModelName != null) {
            File activeDir = getModelDirForName(activeModelName);
            if (hasModelContent(activeDir)) {
                return activeDir;
            }
        }

        String legacyActiveUrl = sharedPrefs != null ? sharedPrefs.getString(PREF_ACTIVE_MODEL_URL, null) : null;
        if (legacyActiveUrl != null) {
            File legacyActiveDir = getModelDirForUrl(legacyActiveUrl);
            if (hasModelContent(legacyActiveDir)) {
                return legacyActiveDir;
            }
        }

        File legacyDir = getLegacyModelDir();
        if (hasModelContent(legacyDir)) {
            return legacyDir;
        }

        File[] modelDirs = getModelsRootDir().listFiles();
        if (modelDirs != null) {
            for (File modelDir : modelDirs) {
                if (hasModelContent(modelDir)) {
                    return modelDir;
                }
            }
        }
        return null;
    }

    private boolean switchToModelName(String modelName, boolean persistSelection) {
        String normalizedModelName = normalizeModelName(modelName);
        File modelDir = getModelDirForName(normalizedModelName);
        if (!hasModelContent(modelDir)) {
            return false;
        }

        OfflineAsrEngine newEngine = new VoskOfflineAsrEngine(modelDir.getAbsolutePath());
        if (!newEngine.initialize()) {
            try { newEngine.shutdown(); } catch (Exception ignored) {}
            return false;
        }

        replaceAsrEngine(newEngine);
        if (persistSelection && sharedPrefs != null) {
            sharedPrefs.edit()
                .putString(PREF_ACTIVE_MODEL_NAME, normalizedModelName)
                .remove(PREF_ACTIVE_MODEL_URL)
                .apply();
        }
        return true;
    }

    private boolean deleteModelByName(String modelName) {
        String normalizedModelName = normalizeModelName(modelName);
        File modelDir = getModelDirForName(normalizedModelName);
        if (modelDir == null || !modelDir.exists()) {
            return false;
        }

        deleteRecursively(modelDir);
        boolean deleted = !modelDir.exists();
        if (deleted && sharedPrefs != null) {
            String activeModelName = sharedPrefs.getString(PREF_ACTIVE_MODEL_NAME, null);
            if (normalizedModelName.equals(activeModelName)) {
                sharedPrefs.edit()
                    .remove(PREF_ACTIVE_MODEL_NAME)
                    .remove(PREF_ACTIVE_MODEL_URL)
                    .apply();
                initializeAsrEngine();
            }
        }
        return deleted;
    }

    private void replaceAsrEngine(OfflineAsrEngine newEngine) {
        Runnable swapTask = () -> {
            OfflineAsrEngine oldEngine = asrEngine;
            asrEngine = newEngine;
            if (oldEngine != null && oldEngine != newEngine) {
                try { oldEngine.shutdown(); } catch (Exception ignored) {}
            }
        };

        if (!isCapturing) {
            swapTask.run();
            return;
        }

        ensureTranscriptionExecutor();
        try {
            transcriptionExecutor.execute(swapTask);
        } catch (RejectedExecutionException e) {
            swapTask.run();
        }
    }

    private void ensureTranscriptionExecutor() {
        if (transcriptionExecutor == null || transcriptionExecutor.isShutdown() || transcriptionExecutor.isTerminated()) {
            transcriptionExecutor = Executors.newSingleThreadExecutor();
        }
    }

    private void ensureSummaryExecutor() {
        if (summaryExecutor == null || summaryExecutor.isShutdown() || summaryExecutor.isTerminated()) {
            summaryExecutor = Executors.newSingleThreadScheduledExecutor();
        }
    }

    private void scheduleSummaryRefresh() {
        scheduleSummaryRefresh(SUMMARY_DEBOUNCE_MS);
    }

    private void triggerImmediateSummaryRefresh() {
        scheduleSummaryRefresh(0L);
    }

    private void scheduleSummaryRefresh(long delayMs) {
        ensureSummaryExecutor();
        cancelPendingSummaryTask();
        pendingSummaryFuture = summaryExecutor.schedule(() -> {
            pendingSummaryFuture = null;
            refreshLiveSummary(false);
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void cancelPendingSummaryTask() {
        if (pendingSummaryFuture == null) {
            return;
        }
        pendingSummaryFuture.cancel(false);
        pendingSummaryFuture = null;
    }

    private void shutdownSummaryExecutor(boolean awaitTermination) {
        if (summaryExecutor == null) {
            return;
        }
        try {
            if (awaitTermination) {
                summaryExecutor.shutdown();
                if (!summaryExecutor.awaitTermination(150, TimeUnit.SECONDS)) {
                    summaryExecutor.shutdownNow();
                }
            } else {
                summaryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            summaryExecutor.shutdownNow();
        } finally {
            summaryExecutor = null;
        }
    }

    private void refreshLiveSummary(boolean force) {
        long summaryRevision = LiveSummaryStore.getSummaryRevision(this);
        LiveSummaryState previousState = LiveSummaryStore.loadSummaryState(this);
        String pendingLogs = LiveSummaryStore.getPendingSummaryLogs(this);
        if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
            return;
        }
        if (pendingLogs.isEmpty()) {
            if (force) {
                if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                    return;
                }
                LiveSummaryStore.saveSummaryState(this, new LiveSummaryState(
                    previousState.getSummary(),
                    previousState.getDecisions(),
                    previousState.getTodos(),
                    "要約待機中",
                    previousState.getUpdatedAtMillis()
                ));
            }
            return;
        }

        try {
            String baseUrl = LiveSummaryStore.getOllamaBaseUrl(this);
            String model = LiveSummaryStore.getOllamaModel(this);
            String prompt = ollamaClient.buildSummaryPrompt(pendingLogs, previousState);
            long requestStartedAt = System.currentTimeMillis();
            if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                return;
            }
            LiveSummaryStore.saveOllamaDebugState(this, new OllamaDebugState(
                baseUrl,
                model,
                prompt,
                "",
                "Ollama応答待ち",
                requestStartedAt
            ));
            OllamaClient.SummaryGenerationResult result = ollamaClient.generateSummaryFromPrompt(
                baseUrl,
                model,
                prompt
            );
            if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                return;
            }
            long updatedAt = System.currentTimeMillis();
            LiveSummaryStore.removePendingSummaryLogs(this, pendingLogs);
            if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                return;
            }
            LiveSummaryStore.saveOllamaDebugState(this, new OllamaDebugState(
                baseUrl,
                model,
                result.getPrompt(),
                result.getRawResponse(),
                "Ollama応答受信",
                updatedAt
            ));
            LiveSummaryStore.saveSummaryState(this, new LiveSummaryState(
                result.getState().getSummary(),
                result.getState().getDecisions(),
                result.getState().getTodos(),
                "要約更新済み",
                updatedAt
            ));
        } catch (OllamaClient.SummaryGenerationException e) {
            if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                return;
            }
            long failedAt = System.currentTimeMillis();
            String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LiveSummaryStore.saveOllamaDebugState(this, new OllamaDebugState(
                LiveSummaryStore.getOllamaBaseUrl(this),
                LiveSummaryStore.getOllamaModel(this),
                e.getPrompt(),
                e.getRawResponse(),
                "Ollama応答失敗: " + errorMessage,
                failedAt
            ));
            try { if (logManager != null) logManager.writeLog("要約更新失敗: " + errorMessage, false); } catch (Exception ignored) {}
            LiveSummaryStore.saveSummaryState(this, new LiveSummaryState(
                previousState.getSummary(),
                previousState.getDecisions(),
                previousState.getTodos(),
                "要約更新失敗: " + errorMessage,
                previousState.getUpdatedAtMillis()
            ));
        } catch (IOException e) {
            if (LiveSummaryStore.getSummaryRevision(this) != summaryRevision) {
                return;
            }
            String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            OllamaDebugState debugState = LiveSummaryStore.loadOllamaDebugState(this);
            LiveSummaryStore.saveOllamaDebugState(this, new OllamaDebugState(
                debugState.getBaseUrl(),
                debugState.getModel(),
                debugState.getPrompt(),
                debugState.getResponse(),
                "Ollama応答失敗: " + errorMessage,
                System.currentTimeMillis()
            ));
            try { if (logManager != null) logManager.writeLog("要約更新失敗: " + errorMessage, false); } catch (Exception ignored) {}
            LiveSummaryStore.saveSummaryState(this, new LiveSummaryState(
                previousState.getSummary(),
                previousState.getDecisions(),
                previousState.getTodos(),
                "要約更新失敗: " + errorMessage,
                previousState.getUpdatedAtMillis()
            ));
        }
    }

    private String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private boolean downloadFile(String urlStr, File destination, String modelName) {
        InputStream in = null;
        OutputStream out = null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) {
                Log.e(TAG, "Download failed, HTTP code: " + conn.getResponseCode());
                return false;
            }

            in = new BufferedInputStream(conn.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(destination));
            long contentLength = conn.getContentLengthLong();
            long downloaded = 0L;
            int lastProgress = -2;
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                downloaded += len;
                if (contentLength > 0) {
                    int progress = (int) Math.min(100L, (downloaded * 100L) / contentLength);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        updateDownloadProgress(true, progress, modelName);
                    }
                } else if (lastProgress != -1) {
                    lastProgress = -1;
                    updateDownloadProgress(true, -1, modelName);
                }
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "downloadFile error", e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private void updateDownloadProgress(boolean active, int progress, String modelName) {
        if (sharedPrefs == null) return;
        sharedPrefs.edit()
            .putBoolean(PREF_MODEL_DOWNLOAD_ACTIVE, active)
            .putInt(PREF_MODEL_DOWNLOAD_PROGRESS, progress)
            .putString(PREF_MODEL_DOWNLOAD_NAME, modelName)
            .apply();
    }

    private boolean unzipToDir(File zipFile, File outDir) {
        ZipInputStream zis = null;
        try {
            if (outDir.exists()) deleteRecursively(outDir);
            outDir.mkdirs();
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(outDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                        fos.flush();
                    }
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "unzipToDir error", e);
            return false;
        } finally {
            try { if (zis != null) zis.close(); } catch (IOException ignored) {}
        }
    }

    private boolean copyDirectory(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] files = src.listFiles();
            if (files == null) return true;
            for (File f : files) {
                File destFile = new File(dst, f.getName());
                if (!copyDirectory(f, destFile)) return false;
            }
            return true;
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "copyDirectory file error", e);
                return false;
            }
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        try { f.delete(); } catch (Exception ignored) {}
    }
    private void startAudioCapture() {
        if (isCapturing) {
            return;
        }
        ensureTranscriptionExecutor();
        ensureSummaryExecutor();
        if (asrEngine == null || (asrEngine instanceof NoOpOfflineAsrEngine)) {
            initializeAsrEngine();
        }

        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("権限不足: ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append(", ");
                String p = missing.get(i);
                if (Manifest.permission.RECORD_AUDIO.equals(p)) sb.append("録音");
                else if (Manifest.permission.POST_NOTIFICATIONS.equals(p)) sb.append("通知");
                else sb.append(p);
            }
            String msg = sb.toString();
            Log.e(TAG, msg);
            if (logManager != null) {
                try {
                    logManager.writeLog(msg);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write permission log", e);
                }
            }
            stopSelf();
            return;
        }

        int minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Failed to get AudioRecord min buffer size: " + minBufferBytes);
            return;
        }

        int recordBufferBytes = Math.max(minBufferBytes * 2, FRAME_SAMPLES * 2 * 4);
        audioRecord = createAudioRecord(recordBufferBytes);
        if (audioRecord == null) {
            Log.e(TAG, "AudioRecord initialization failed for all audio sources");
            return;
        }

        enableAudioEffects();
        acquireRunLocks();

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Failed to start recording", e);
            releaseAudioEffects();
            releaseRunLocks();
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isCapturing = true;
        captureThread = new Thread(this::captureLoop, "AudioCaptureThread");
        captureThread.start();
        Log.i(TAG, "AudioRecord capture started: " + audioSourceLabel(activeAudioSource));
        try { if (sharedPrefs != null) sharedPrefs.edit().putString(PREF_MON_STATE, MON_STATE_RUNNING).apply(); } catch (Exception ignored) {}
        try { if (logManager != null) logManager.writeLog("録音開始 (" + audioSourceLabel(activeAudioSource) + ")"); } catch (Exception ignored) {}
    }

    private void captureLoop() {
        short[] readBuffer = new short[FRAME_SAMPLES];
        while (isCapturing && audioRecord != null) {
            int readSamples = audioRecord.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
            if (readSamples <= 0) {
                if (readSamples != AudioRecord.ERROR_INVALID_OPERATION
                    && readSamples != AudioRecord.ERROR_BAD_VALUE) {
                    continue;
                }
                Log.w(TAG, "AudioRecord read failed: " + readSamples);
                continue;
            }

            short[] frame = Arrays.copyOf(readBuffer, readSamples);
            publishCurrentRms(frame);
            short[] segment = vad.processFrame(frame);
            if (segment != null && segment.length > 0) {
                submitForTranscription(segment);
            }
        }
    }

    private void publishCurrentRms(short[] frame) {
        if (sharedPrefs == null || frame == null || frame.length == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRmsPublishMs < 120L) {
            return;
        }
        lastRmsPublishMs = now;
        double sum = 0.0;
        for (short sample : frame) {
            sum += sample * (double) sample;
        }
        float rms = (float) Math.sqrt(sum / frame.length);
        sharedPrefs.edit().putFloat(PREF_CURRENT_RMS, rms).apply();
    }

    private void submitForTranscription(short[] segment) {
        ensureTranscriptionExecutor();
        if (transcriptionExecutor == null) return;

        Runnable task = () -> {
            try {
                OfflineAsrEngine engine = asrEngine; // snapshot to avoid race with shutdown
                if (engine == null) return;
                String recognizedText = engine.transcribe(segment, SAMPLE_RATE_HZ);
                String normalizedText = recognizedText == null ? "" : recognizedText.replaceAll("\\s+", " ").trim();
                if (!normalizedText.isEmpty()) {
                    if (logManager != null) {
                        try { logManager.writeLog("認識: " + normalizedText); } catch (Exception ignored) {}
                    }
                    LiveSummaryStore.appendPendingSummaryLog(this, normalizedText);
                    if (LiveSummaryStore.getPendingSummaryLogCharCount(this) >= LiveSummaryStore.getSummaryForceCharThreshold(this)) {
                        triggerImmediateSummaryRefresh();
                    } else {
                        scheduleSummaryRefresh();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Transcription task failed", e);
                try { if (logManager != null) logManager.writeLog("Transcription例外: " + e.getMessage()); } catch (Exception ignored) {}
            }
        };

        try {
            transcriptionExecutor.execute(task);
        } catch (RejectedExecutionException ex) {
            Log.w(TAG, "Transcription executor rejected task, recreating executor");
            ensureTranscriptionExecutor();
            try {
                transcriptionExecutor.execute(task);
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Transcription task dropped after executor recreate", e);
                try { if (logManager != null) logManager.writeLog("Transcription投入失敗: " + e.getMessage(), false); } catch (Exception ignored) {}
            }
        }
    }

    private void stopAudioCapture() {
        isCapturing = false;

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord stop failed", e);
            }
        }

        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            releaseAudioEffects();
            audioRecord.release();
            audioRecord = null;
        }
        releaseRunLocks();

        if (vad != null) {
            short[] flushed = vad.flush();
            if (flushed != null && flushed.length > 0) {
                submitForTranscription(flushed);
            }
        }
        try { if (sharedPrefs != null) sharedPrefs.edit().putFloat(PREF_CURRENT_RMS, 0f).apply(); } catch (Exception ignored) {}
    }

    private AudioRecord createAudioRecord(int recordBufferBytes) {
        int[] candidates = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        };
        for (int source : candidates) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    recordBufferBytes
                );
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    activeAudioSource = source;
                    return candidate;
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "AudioRecord create failed for source=" + source, e);
            }
            if (candidate != null) {
                try { candidate.release(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String audioSourceLabel(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        if (source == MediaRecorder.AudioSource.MIC) {
            return "MIC";
        }
        return "source=" + source;
    }

    private void enableAudioEffects() {
        releaseAudioEffects();
        enableAutomaticGainControl();
        enableAcousticEchoCanceler();
        enableNoiseSuppressor();
    }

    private void releaseAudioEffects() {
        releaseAutomaticGainControl();
        releaseAcousticEchoCanceler();
        releaseNoiseSuppressor();
    }

    private void enableAutomaticGainControl() {
        if (audioRecord == null || !AutomaticGainControl.isAvailable()) {
            return;
        }
        try {
            automaticGainControl = AutomaticGainControl.create(audioRecord.getAudioSessionId());
            if (automaticGainControl != null) {
                automaticGainControl.setEnabled(true);
                try { if (logManager != null) logManager.writeLog("AGC有効化: " + automaticGainControl.getEnabled(), false); } catch (Exception ignored) {}
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
            Log.w(TAG, "Failed to enable AutomaticGainControl", e);
            releaseAutomaticGainControl();
        }
    }

    private void releaseAutomaticGainControl() {
        if (automaticGainControl == null) {
            return;
        }
        try {
            automaticGainControl.release();
        } catch (IllegalStateException e) {
            Log.w(TAG, "AutomaticGainControl release failed", e);
        } finally {
            automaticGainControl = null;
        }
    }

    private void enableAcousticEchoCanceler() {
        if (audioRecord == null || !AcousticEchoCanceler.isAvailable()) {
            return;
        }
        try {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (acousticEchoCanceler != null) {
                acousticEchoCanceler.setEnabled(true);
                try { if (logManager != null) logManager.writeLog("AEC有効化: " + acousticEchoCanceler.getEnabled(), false); } catch (Exception ignored) {}
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
            Log.w(TAG, "Failed to enable AcousticEchoCanceler", e);
            releaseAcousticEchoCanceler();
        }
    }

    private void releaseAcousticEchoCanceler() {
        if (acousticEchoCanceler == null) {
            return;
        }
        try {
            acousticEchoCanceler.release();
        } catch (IllegalStateException e) {
            Log.w(TAG, "AcousticEchoCanceler release failed", e);
        } finally {
            acousticEchoCanceler = null;
        }
    }

    private void enableNoiseSuppressor() {
        if (audioRecord == null) {
            return;
        }
        if (!NoiseSuppressor.isAvailable()) {
            Log.i(TAG, "NoiseSuppressor is not available on this device");
            try { if (logManager != null) logManager.writeLog("NS未対応デバイス: VADのみで監視継続", false); } catch (Exception ignored) {}
            return;
        }
        try {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
            if (noiseSuppressor == null) {
                Log.w(TAG, "NoiseSuppressor.create returned null");
                try { if (logManager != null) logManager.writeLog("NS初期化失敗: VADのみで監視継続", false); } catch (Exception ignored) {}
                return;
            }
            noiseSuppressor.setEnabled(true);
            try { if (logManager != null) logManager.writeLog("NS有効化: " + noiseSuppressor.getEnabled(), false); } catch (Exception ignored) {}
        } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
            Log.w(TAG, "Failed to enable NoiseSuppressor", e);
            try { if (logManager != null) logManager.writeLog("NS有効化例外: " + e.getMessage(), false); } catch (Exception ignored) {}
            releaseNoiseSuppressor();
        }
    }

    private void releaseNoiseSuppressor() {
        if (noiseSuppressor == null) {
            return;
        }
        try {
            noiseSuppressor.release();
        } catch (IllegalStateException e) {
            Log.w(TAG, "NoiseSuppressor release failed", e);
        } finally {
            noiseSuppressor = null;
        }
    }
}
