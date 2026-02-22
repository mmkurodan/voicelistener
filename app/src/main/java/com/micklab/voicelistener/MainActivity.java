package com.micklab.voicelistener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends Activity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO
    };
    
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView logPathText;
    private TextView logContentText;
    private ScrollView logScrollView;
    private LogManager logManager;
    private boolean isServiceRunning = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        logManager = new LogManager(this);
        
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
        logPathText.setPadding(0, 0, 0, 20);
        layout.addView(logPathText);
        
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

        // モデル入れ替えボタン
        Button replaceModelButton = new Button(this);
        replaceModelButton.setText("モデル入れ替え");
        replaceModelButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_INSTALL_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_URL, url);
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_REPLACE, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデル入れ替えを開始しました", Toast.LENGTH_SHORT).show();
        });
        layout.addView(replaceModelButton);

        // ログ初期化ボタン
        Button clearLogsButton = new Button(this);
        clearLogsButton.setText("ログ初期化");
        clearLogsButton.setOnClickListener(v -> {
            logManager.clearAllLogs();
            updateLogDisplay();
            logPathText.setText("ログ保存先: " + logManager.getLogFolderPath());
            Toast.makeText(this, "ログを初期化しました", Toast.LENGTH_SHORT).show();
        });
        layout.addView(clearLogsButton);

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
        
        logContentText = new TextView(this);
        logContentText.setTextSize(10);
        logContentText.setBackgroundColor(0xFF000000);
        logContentText.setTextColor(0xFF00FF00);
        logContentText.setPadding(10, 10, 10, 10);
        
        logScrollView = new ScrollView(this);
        logScrollView.addView(logContentText);
        logScrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 500));
        layout.addView(logScrollView);
        
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
        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        stopService(serviceIntent);
        
        isServiceRunning = false;
        updateButtons();
        updateStatus();
        
        Toast.makeText(this, "音声監視を停止しました", Toast.LENGTH_SHORT).show();
    }
    
    private void updateButtons() {
        startButton.setEnabled(!isServiceRunning);
        stopButton.setEnabled(isServiceRunning);
    }
    
    private void updateStatus() {
        statusText.setText("ステータス: " + (isServiceRunning ? "監視中" : "停止中"));
    }
    
    private void updateLogDisplay() {
        try {
            File[] logFiles = logManager.getLogFiles();
            if (logFiles == null || logFiles.length == 0) {
                logContentText.setText("ログファイルがありません");
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
                StringBuilder logContent = new StringBuilder();
                
                try (BufferedReader reader = new BufferedReader(new FileReader(latestLogFile))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null && lineCount < 100) {
                        logContent.append(line).append("\n");
                        lineCount++;
                    }
                }
                
                logContentText.setText(logContent.toString());
                
                // スクロールを最下部に移動
                logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
            
        } catch (IOException e) {
            logContentText.setText("ログ読み込みエラー: " + e.getMessage());
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateLogDisplay();
    }
}
