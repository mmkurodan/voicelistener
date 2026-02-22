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
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private static final String VOSK_MODEL_FOLDER = "vosk-model-ja";
    private static final String MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip";

    private LogManager logManager;
    private VoiceActivityDetector vad;
    private OfflineAsrEngine asrEngine;
    private ExecutorService transcriptionExecutor;
    private ExecutorService modelInstallerExecutor;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean isCapturing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VoiceListenerService created");

        logManager = new LogManager(this);
        vad = new VoiceActivityDetector(RMS_THRESHOLD, MAX_SILENCE_FRAMES, MIN_SPEECH_FRAMES);
        transcriptionExecutor = Executors.newSingleThreadExecutor();
        modelInstallerExecutor = Executors.newSingleThreadExecutor();

        createNotificationChannel();
        initializeAsrEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VoiceListenerService started");

        startForeground(NOTIFICATION_ID, createNotification());
        startAudioCapture();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "VoiceListenerService destroyed");

        stopAudioCapture();

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
            .setContentText("AudioRecordでオフライン監視を実行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void initializeAsrEngine() {
        File documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) {
            documentsDir = getFilesDir();
        }
        File modelDir = new File(new File(documentsDir, "VoiceListener"), VOSK_MODEL_FOLDER);

        OfflineAsrEngine voskEngine = new VoskOfflineAsrEngine(modelDir.getAbsolutePath());
        if (voskEngine.initialize()) {
            asrEngine = voskEngine;
            Log.i(TAG, "ASR engine: " + asrEngine.name());
            return;
        }

        // モデルがないためフォールバックし、非同期でモデル取得を試みる
        asrEngine = new NoOpOfflineAsrEngine();
        asrEngine.initialize();
        Log.w(TAG, "ASR engine fallback: " + asrEngine.name() + " (model missing)");
        installModelIfMissingAsync(modelDir);
    }

    private void installModelIfMissingAsync(File modelDir) {
        if (modelDir.exists() && modelDir.isDirectory() && modelDir.listFiles() != null && modelDir.listFiles().length > 0) {
            // 既に存在する
            return;
        }
        if (modelInstallerExecutor == null) {
            modelInstallerExecutor = Executors.newSingleThreadExecutor();
        }
        modelInstallerExecutor.execute(() -> {
            try {
                logManager.writeLog("モデル未検出: ダウンロードを開始します");
            } catch (Exception e) {
                Log.w(TAG, "ログ書き込み失敗", e);
            }

            File cacheZip = new File(getCacheDir(), "vosk_model.zip");
            File extractDir = new File(getCacheDir(), "vosk_model_extract");
            try {
                boolean dlOk = downloadFile(MODEL_ZIP_URL, cacheZip);
                if (!dlOk) {
                    logManager.writeLog("モデルダウンロード失敗");
                    return;
                }

                // 解凍
                boolean unzipOk = unzipToDir(cacheZip, extractDir);
                if (!unzipOk) {
                    logManager.writeLog("モデル解凍失敗");
                    return;
                }

                // コピー先を作成
                if (!modelDir.exists()) {
                    if (!modelDir.mkdirs()) {
                        logManager.writeLog("モデルフォルダ作成失敗: " + modelDir.getAbsolutePath());
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
                    return;
                }

                // クリーンアップ
                deleteRecursively(cacheZip);
                deleteRecursively(extractDir);

                logManager.writeLog("モデル取得・展開完了: " + modelDir.getAbsolutePath());

                // 再初期化
                OfflineAsrEngine newEngine = new VoskOfflineAsrEngine(modelDir.getAbsolutePath());
                if (newEngine.initialize()) {
                    if (asrEngine != null) {
                        try { asrEngine.shutdown(); } catch (Exception ignored) {}
                    }
                    asrEngine = newEngine;
                    logManager.writeLog("ASRエンジン初期化成功: " + asrEngine.name());
                } else {
                    logManager.writeLog("ASRエンジン初期化失敗");
                }

            } catch (Exception e) {
                try { logManager.writeLog("モデル取得中に例外発生: " + e.getMessage()); } catch (Exception ignored) {}
                Log.e(TAG, "モデル取得例外", e);
            }
        });
    }

    private boolean downloadFile(String urlStr, File destination) {
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
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
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
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            recordBufferBytes
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Failed to start recording", e);
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isCapturing = true;
        captureThread = new Thread(this::captureLoop, "AudioCaptureThread");
        captureThread.start();
        Log.i(TAG, "AudioRecord capture started");
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
            short[] segment = vad.processFrame(frame);
            if (segment != null && segment.length > 0) {
                submitForTranscription(segment);
            }
        }
    }

    private void submitForTranscription(short[] segment) {
        if (transcriptionExecutor == null) {
            return;
        }
        transcriptionExecutor.execute(() -> {
            String recognizedText = asrEngine == null ? null : asrEngine.transcribe(segment, SAMPLE_RATE_HZ);
            if (recognizedText != null && !recognizedText.trim().isEmpty()) {
                logManager.writeLog("認識: " + recognizedText.trim());
            }
        });
    }

    private void stopAudioCapture() {
        isCapturing = false;

        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord stop failed", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (vad != null) {
            short[] flushed = vad.flush();
            if (flushed != null && flushed.length > 0) {
                submitForTranscription(flushed);
            }
        }
    }
}
