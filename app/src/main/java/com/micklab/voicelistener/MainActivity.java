package com.micklab.voicelistener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.ArrayAdapter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;

public class MainActivity extends Activity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int LOG_EXPORT_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO
    };
    
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView logPathText;
    private TextView fileMetaText;
    private EditText logContentText;
    private ScrollView logScrollView;

    private Handler uiHandler;
    private Runnable periodicUpdateRunnable;
    private Runnable indicatorUpdateRunnable;
    private static final int UPDATE_INTERVAL_MS = 5000; // 5s
    private static final int INDICATOR_UPDATE_INTERVAL_MS = 250;
    private static final int MODEL_LIST_REFRESH_INTERVAL_MS = 2000;
    private static final int VAD_MIN = 100;
    private static final int VAD_MAX = 5000;

    private LogManager2 logManager;
    private File pendingExportLogFile;
    private boolean isServiceRunning = false;
    private SharedPreferences prefs;
    private static final String PREF_MON_STATE = "monitor_state";
    private static final String MON_STATE_RUNNING = "running";
    private static final String MON_STATE_PENDING = "pending";
    private static final String MON_STATE_STOPPED = "stopped";
    private static final String PREF_MODEL_DOWNLOAD_ACTIVE = "model_download_active";
    private static final String PREF_MODEL_DOWNLOAD_PROGRESS = "model_download_progress";
    private static final String PREF_MODEL_DOWNLOAD_NAME = "model_download_name";
    private static final String PREF_CURRENT_RMS = "current_rms";

    private Spinner modelSpinner;
    private ArrayAdapter<String> modelSpinnerAdapter;
    private ProgressBar modelDownloadProgressBar;
    private TextView modelDownloadProgressText;
    private SeekBar volumeIndicatorSeekBar;
    private TextView volumeIndicatorLabel;
    private long lastModelListRefreshMs = 0L;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        logManager = new LogManager2(this);
        prefs = getSharedPreferences("VoiceListenerPrefs", MODE_PRIVATE);
        
        createUI();
        checkPermissions();
    }
    
    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        
        // タイトル
        TextView title = new TextView(this);
        title.setText("音声監視アプリ");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 20);
        layout.addView(title);
        
        // ステータス表示
        statusText = new TextView(this);
        statusText.setText("ステータス: 停止中");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 10);
        layout.addView(statusText);
        
        // ログパス表示
        logPathText = new TextView(this);
        logPathText.setText("ログ保存先: " + logManager.getLogFolderPath());
        logPathText.setTextSize(12);
        logPathText.setPadding(0, 0, 0, 6);
        layout.addView(logPathText);

        // ログファイルのメタ情報（サイズ・最終更新）
        fileMetaText = new TextView(this);
        fileMetaText.setText("ログファイル: -");
        fileMetaText.setTextSize(12);
        fileMetaText.setPadding(0, 0, 0, 20);
        layout.addView(fileMetaText);
        
        // VAD閾値スライダー + 音量インジケータ（同縮尺）
        float savedThreshold = prefs.getFloat("rms_threshold", 900.0f);
        int savedInt = (int) savedThreshold;
        if (savedInt < VAD_MIN) savedInt = VAD_MIN;
        if (savedInt > VAD_MAX) savedInt = VAD_MAX;

        LinearLayout meterRow = new LinearLayout(this);
        meterRow.setOrientation(LinearLayout.HORIZONTAL);
        meterRow.setPadding(0, 10, 0, 10);
        meterRow.setWeightSum(2f);

        LinearLayout sensitivityColumn = new LinearLayout(this);
        sensitivityColumn.setOrientation(LinearLayout.VERTICAL);
        sensitivityColumn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView vadLabel = new TextView(this);
        vadLabel.setText("音声感度 (閾値): " + savedInt);
        vadLabel.setPadding(0, 0, 10, 10);
        sensitivityColumn.addView(vadLabel);

        SeekBar vadSeekBar = new SeekBar(this);
        vadSeekBar.setMax(VAD_MAX - VAD_MIN);
        vadSeekBar.setProgress(savedInt - VAD_MIN);
        vadSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + VAD_MIN;
                vadLabel.setText("音声感度 (閾値): " + value);
                prefs.edit().putFloat("rms_threshold", value).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sensitivityColumn.addView(vadSeekBar);

        LinearLayout volumeColumn = new LinearLayout(this);
        volumeColumn.setOrientation(LinearLayout.VERTICAL);
        volumeColumn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        volumeIndicatorLabel = new TextView(this);
        volumeIndicatorLabel.setText("入力音量 (RMS): 0");
        volumeIndicatorLabel.setPadding(10, 0, 0, 10);
        volumeColumn.addView(volumeIndicatorLabel);

        volumeIndicatorSeekBar = new SeekBar(this);
        volumeIndicatorSeekBar.setMax(VAD_MAX - VAD_MIN);
        volumeIndicatorSeekBar.setProgress(0);
        volumeIndicatorSeekBar.setEnabled(false);
        volumeIndicatorSeekBar.setFocusable(false);
        volumeColumn.addView(volumeIndicatorSeekBar);

        meterRow.addView(sensitivityColumn);
        meterRow.addView(volumeColumn);
        layout.addView(meterRow);

        // 開始ボタン
        startButton = new Button(this);
        startButton.setText("音声監視開始");
        startButton.setOnClickListener(v -> startVoiceListening());
        layout.addView(startButton);
        
        // 停止ボタン
        stopButton = new Button(this);
        stopButton.setText("音声監視停止");
        stopButton.setOnClickListener(v -> stopVoiceListening());
        stopButton.setEnabled(false);
        layout.addView(stopButton);

        // モデルURL入力
        final EditText urlInput = new EditText(this);
        urlInput.setHint("モデルZIPのURLを入力 (例: https://...)");
        urlInput.setText("https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip");
        layout.addView(urlInput);

        // モデルロード（同一URLは削除後に再取得）
        Button replaceModelButton = new Button(this);
        replaceModelButton.setText("モデルロード/再DL");
        replaceModelButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_INSTALL_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_URL, url);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデルロードを開始しました", Toast.LENGTH_SHORT).show();
        });
        layout.addView(replaceModelButton);

        modelDownloadProgressText = new TextView(this);
        modelDownloadProgressText.setText("モデルDL進捗: 待機中");
        modelDownloadProgressText.setPadding(0, 6, 0, 6);
        layout.addView(modelDownloadProgressText);

        modelDownloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelDownloadProgressBar.setMax(100);
        modelDownloadProgressBar.setProgress(0);
        layout.addView(modelDownloadProgressBar);

        TextView modelSelectLabel = new TextView(this);
        modelSelectLabel.setText("ダウンロード済みモデル:");
        modelSelectLabel.setPadding(0, 14, 0, 6);
        layout.addView(modelSelectLabel);

        modelSpinner = new Spinner(this);
        modelSpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        modelSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelSpinnerAdapter);
        layout.addView(modelSpinner);

        Button refreshModelListButton = new Button(this);
        refreshModelListButton.setText("モデル一覧更新");
        refreshModelListButton.setOnClickListener(v -> refreshModelSpinner(true));
        layout.addView(refreshModelListButton);

        // 選択モデルへ切替
        Button switchModelButton = new Button(this);
        switchModelButton.setText("選択モデルへ切替");
        switchModelButton.setOnClickListener(v -> {
            String modelName = getSelectedModelName();
            if (modelName == null) {
                Toast.makeText(this, "切替対象モデルがありません", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_SELECT_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデル切替を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
        });
        layout.addView(switchModelButton);

        // 選択モデルの削除
        Button deleteModelButton = new Button(this);
        deleteModelButton.setText("選択モデル削除");
        deleteModelButton.setOnClickListener(v -> {
            String modelName = getSelectedModelName();
            if (modelName == null) {
                Toast.makeText(this, "削除対象モデルがありません", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_DELETE_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデル削除を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
        });
        layout.addView(deleteModelButton);

        // ログ初期化ボタン
        Button clearLogsButton = new Button(this);
        clearLogsButton.setText("ログ初期化");
        clearLogsButton.setOnClickListener(v -> {
            logManager.clearAllLogs();
            updateLogDisplay();
            logPathText.setText("ログ保存先: " + logManager.getLogFolderPath());
            Toast.makeText(this, "ログファイルのデータを初期化しました", Toast.LENGTH_SHORT).show();
        });
        layout.addView(clearLogsButton);

        // 任意場所にログを保存
        Button exportLogButton = new Button(this);
        exportLogButton.setText("ログダウンロード");
        exportLogButton.setOnClickListener(v -> exportLatestLog());
        layout.addView(exportLogButton);

        // ログ表示更新ボタン
        Button refreshLogButton = new Button(this);
        refreshLogButton.setText("ログ表示更新");
        refreshLogButton.setOnClickListener(v -> updateLogDisplay());
        layout.addView(refreshLogButton);
        
        // ログ内容表示
        TextView logLabel = new TextView(this);
        logLabel.setText("最新ログ内容:");
        logLabel.setTextSize(14);
        logLabel.setPadding(0, 20, 0, 10);
        layout.addView(logLabel);
        
        logContentText = new EditText(this);
        logContentText.setTextSize(10);
        logContentText.setBackgroundColor(0xFF000000);
        logContentText.setTextColor(0xFF00FF00);
        logContentText.setPadding(10, 10, 10, 10);
        // 非編集モードで選択とコピーを許可
        logContentText.setKeyListener(null);
        logContentText.setFocusable(false);
        logContentText.setCursorVisible(false);
        logContentText.setLongClickable(true);
        logContentText.setTextIsSelectable(true);
        logContentText.setHorizontallyScrolling(false);
        
        logScrollView = new ScrollView(this);
        logScrollView.addView(logContentText);
        logScrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 500));
        layout.addView(logScrollView);

        refreshModelSpinner(false);
        updateDownloadProgressIndicator();
        updateVolumeIndicator();
        
        setContentView(layout);
    }
    
    private void checkPermissions() {
        String[] missing = getMissingPermissions();
        if (missing.length > 0) {
            String msg = "権限不足: " + joinPermLabels(missing);
            logManager.writeLog(msg);
            statusText.setText("ステータス: " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            requestPermissions(missing, PERMISSION_REQUEST_CODE);
        } else {
            statusText.setText("ステータス: 停止中");
            updateLogDisplay();
        }
    }
    
    private String[] getMissingPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        return missing.toArray(new String[0]);
    }
    
    private String permissionLabel(String perm) {
        if (Manifest.permission.RECORD_AUDIO.equals(perm)) return "録音";
        if (Manifest.permission.POST_NOTIFICATIONS.equals(perm)) return "通知";
        return perm;
    }
    
    private String joinPermLabels(String[] perms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perms.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(permissionLabel(perms[i]));
        }
        return sb.toString();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            ArrayList<String> denied = new ArrayList<>();
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    denied.add(permissions[i]);
                }
            }
            
            if (denied.isEmpty()) {
                Toast.makeText(this, "権限が許可されました", Toast.LENGTH_SHORT).show();
                logManager.writeLog("権限がすべて許可されました");
                statusText.setText("ステータス: 停止中");
                updateLogDisplay();
            } else {
                String msg = "権限未許可: " + joinPermLabels(denied.toArray(new String[0]));
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                logManager.writeLog(msg);
                statusText.setText("ステータス: " + msg);
            }
        }
    }
    
    private void startVoiceListening() {
        String[] missing = getMissingPermissions();
        if (missing.length > 0) {
            String msg = "権限不足のため開始できません: " + joinPermLabels(missing);
            logManager.writeLog(msg);
            statusText.setText("ステータス: " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        isServiceRunning = true;
        updateButtons();
        updateStatus();
        
        Toast.makeText(this, "音声監視を開始しました", Toast.LENGTH_SHORT).show();
    }
    
    private void stopVoiceListening() {
        // 録音を停止し、保留の音声断片に対する認識とログ書き出しのみ続けたのちにサービスを停止する
        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        serviceIntent.setAction(VoiceListenerService.ACTION_STOP_MONITORING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isServiceRunning = false;
        updateButtons();
        statusText.setText("ステータス: 停止中（保留処理中）");
        Toast.makeText(this, "録音を停止しました。保留処理完了後にサービスが停止します。", Toast.LENGTH_SHORT).show();
    }
    
    private void updateButtons() {
        startButton.setEnabled(!isServiceRunning);
        stopButton.setEnabled(isServiceRunning);
    }
    
    private void updateStatus() {
        updateStatusFromPrefs();
    }

    private void updateStatusFromPrefs() {
        String state = prefs.getString(PREF_MON_STATE, null);
        if (state == null) {
            state = isServiceRunning ? MON_STATE_RUNNING : MON_STATE_STOPPED;
        }
        String display;
        if (MON_STATE_RUNNING.equals(state)) {
            display = "監視中";
            isServiceRunning = true;
        } else if (MON_STATE_PENDING.equals(state)) {
            display = "停止中（保留処理中）";
            isServiceRunning = false;
        } else {
            display = "停止中";
            isServiceRunning = false;
        }
        statusText.setText("ステータス: " + display);
        updateButtons();
    }

    private File getModelsRootDir() {
        File documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) {
            documentsDir = getFilesDir();
        }
        return new File(new File(documentsDir, "VoiceListener"), "models");
    }

    private String getSelectedModelName() {
        if (modelSpinner == null || modelSpinner.getSelectedItem() == null) {
            return null;
        }
        String selected = String.valueOf(modelSpinner.getSelectedItem()).trim();
        return selected.isEmpty() ? null : selected;
    }

    private void refreshModelSpinner(boolean keepSelection) {
        if (modelSpinnerAdapter == null || modelSpinner == null) return;

        String selectedBefore = keepSelection ? getSelectedModelName() : null;
        ArrayList<String> modelNames = new ArrayList<>();
        File modelsRoot = getModelsRootDir();
        File[] modelDirs = modelsRoot.listFiles();
        if (modelDirs != null) {
            Arrays.sort(modelDirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File modelDir : modelDirs) {
                if (modelDir != null && modelDir.isDirectory()) {
                    File[] children = modelDir.listFiles();
                    if (children != null && children.length > 0) {
                        modelNames.add(modelDir.getName());
                    }
                }
            }
        }

        modelSpinnerAdapter.clear();
        modelSpinnerAdapter.addAll(modelNames);
        modelSpinnerAdapter.notifyDataSetChanged();

        if (!modelNames.isEmpty()) {
            int index = selectedBefore != null ? modelNames.indexOf(selectedBefore) : -1;
            modelSpinner.setSelection(index >= 0 ? index : 0);
        }
        lastModelListRefreshMs = System.currentTimeMillis();
    }

    private void updateDownloadProgressIndicator() {
        if (modelDownloadProgressBar == null || modelDownloadProgressText == null) return;
        boolean active = prefs.getBoolean(PREF_MODEL_DOWNLOAD_ACTIVE, false);
        int progress = prefs.getInt(PREF_MODEL_DOWNLOAD_PROGRESS, 0);
        String modelName = prefs.getString(PREF_MODEL_DOWNLOAD_NAME, "");

        if (active) {
            if (progress < 0) {
                modelDownloadProgressBar.setIndeterminate(true);
                modelDownloadProgressText.setText("モデルDL中: " + modelName);
            } else {
                int safeProgress = Math.max(0, Math.min(progress, 100));
                modelDownloadProgressBar.setIndeterminate(false);
                modelDownloadProgressBar.setProgress(safeProgress);
                modelDownloadProgressText.setText("モデルDL中 (" + modelName + "): " + safeProgress + "%");
            }
        } else {
            int safeProgress = Math.max(0, Math.min(progress, 100));
            modelDownloadProgressBar.setIndeterminate(false);
            modelDownloadProgressBar.setProgress(safeProgress);
            if (safeProgress >= 100 && modelName != null && !modelName.isEmpty()) {
                modelDownloadProgressText.setText("モデルDL完了: " + modelName);
            } else {
                modelDownloadProgressText.setText("モデルDL進捗: 待機中");
            }
        }
    }

    private void updateVolumeIndicator() {
        if (volumeIndicatorSeekBar == null || volumeIndicatorLabel == null) return;
        float rmsValue = prefs.getFloat(PREF_CURRENT_RMS, 0f);
        int rmsInt = Math.max(0, Math.round(rmsValue));
        volumeIndicatorLabel.setText("入力音量 (RMS): " + rmsInt);
        int clamped = Math.max(VAD_MIN, Math.min(rmsInt, VAD_MAX));
        int progress = clamped - VAD_MIN;
        if (rmsInt < VAD_MIN) {
            progress = 0;
        }
        volumeIndicatorSeekBar.setProgress(progress);
    }
    
    private void updateLogDisplay() {
        try {
            File[] logFiles = logManager.getLogFiles();
            if (logFiles == null || logFiles.length == 0) {
                logContentText.setText("ログファイルがありません");
                fileMetaText.setText("ログファイル: -");
                return;
            }

            // 最新のログファイルを取得
            File latestLogFile = null;
            long latestTime = 0;
            for (File file : logFiles) {
                if (file.lastModified() > latestTime) {
                    latestTime = file.lastModified();
                    latestLogFile = file;
                }
            }

            if (latestLogFile != null) {
                // メタ情報を更新
                updateLogMeta(latestLogFile);

                // ログは末尾200行を表示する
                int maxLines = 200;
                Deque<String> deque = new ArrayDeque<>(maxLines);
                try (BufferedReader reader = new BufferedReader(new FileReader(latestLogFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (deque.size() == maxLines) deque.removeFirst();
                        deque.addLast(line);
                    }
                }

                StringBuilder logContent = new StringBuilder();
                String lastMinute = null;
                for (java.util.Iterator<String> it = deque.descendingIterator(); it.hasNext(); ) {
                    String l = it.next();
                    String minute = null;
                    String msg = l;
                    if (l.startsWith("[") && l.contains("]")) {
                        int end = l.indexOf(']');
                        if (end > 1) {
                            String ts = l.substring(1, end);
                            if (ts.length() >= 16) {
                                try { minute = ts.substring(11, 16); } catch (Exception ignored) { minute = ts; }
                            } else {
                                minute = ts;
                            }
                            if (l.length() > end + 2) msg = l.substring(end + 2).trim(); else msg = "";
                        }
                    }

                    // 認識プレフィックスを削除
                    msg = msg.replaceFirst("^認識[:：]\\s*", "");

                    if (minute != null) {
                        if (!minute.equals(lastMinute)) {
                            logContent.append("[").append(minute).append("] ");
                            lastMinute = minute;
                        }
                    }
                    logContent.append(msg).append('\n');
                }

                logContentText.setText(logContent.toString());

                // 最新を先頭に表示するため先頭へスクロール
                logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_UP));
            }

        } catch (IOException e) {
            logContentText.setText("ログ読み込みエラー: " + e.getMessage());
        }
    }

    private void updateLogMeta(File latestLogFile) {
        if (latestLogFile == null) return;
        String name = latestLogFile.getName();
        long size = latestLogFile.length();
        long lm = latestLogFile.lastModified();
        String lmStr = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", new java.util.Date(lm)).toString();
        fileMetaText.setText(String.format("ログファイル: %s  サイズ: %d bytes  最終更新: %s", name, size, lmStr));
    }

    private void exportLatestLog() {
        File latestLogFile = logManager.getLatestLogFile();
        if (latestLogFile == null || !latestLogFile.exists()) {
            Toast.makeText(this, "ダウンロード対象のログがありません", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportLogFile = latestLogFile;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, latestLogFile.getName());
        startActivityForResult(intent, LOG_EXPORT_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != LOG_EXPORT_REQUEST_CODE) return;

        if (resultCode != RESULT_OK || data == null || pendingExportLogFile == null) {
            pendingExportLogFile = null;
            return;
        }

        Uri destinationUri = data.getData();
        if (destinationUri == null) {
            pendingExportLogFile = null;
            return;
        }

        try (FileInputStream in = new FileInputStream(pendingExportLogFile);
             OutputStream out = getContentResolver().openOutputStream(destinationUri, "w")) {
            if (out == null) throw new IOException("保存先を開けません");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.flush();
            Toast.makeText(this, "ログを保存しました", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "ログ保存エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            pendingExportLogFile = null;
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateLogDisplay();
        updateStatusFromPrefs();
        refreshModelSpinner(true);
        updateDownloadProgressIndicator();
        updateVolumeIndicator();
        // 定期更新を開始
        if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
        if (periodicUpdateRunnable == null) {
            periodicUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateLogDisplay();
                        updateStatusFromPrefs();
                        refreshModelSpinner(true);
                    } catch (Exception ignored) {}
                    uiHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            };
        }
        if (indicatorUpdateRunnable == null) {
            indicatorUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateDownloadProgressIndicator();
                        updateVolumeIndicator();
                        long now = System.currentTimeMillis();
                        if (now - lastModelListRefreshMs >= MODEL_LIST_REFRESH_INTERVAL_MS) {
                            refreshModelSpinner(true);
                        }
                    } catch (Exception ignored) {}
                    uiHandler.postDelayed(this, INDICATOR_UPDATE_INTERVAL_MS);
                }
            };
        }
        uiHandler.post(periodicUpdateRunnable);
        uiHandler.post(indicatorUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (uiHandler != null && periodicUpdateRunnable != null) {
            uiHandler.removeCallbacks(periodicUpdateRunnable);
        }
        if (uiHandler != null && indicatorUpdateRunnable != null) {
            uiHandler.removeCallbacks(indicatorUpdateRunnable);
        }
    }
}
