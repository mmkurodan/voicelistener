package com.micklab.voicelistener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class MainActivity extends Activity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
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
        boolean allPermissionsGranted = true;
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
            }
        }
        
        if (!allPermissionsGranted) {
            String[] permissionsToRequest = REQUIRED_PERMISSIONS;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest = new String[REQUIRED_PERMISSIONS.length + 1];
                System.arraycopy(REQUIRED_PERMISSIONS, 0, permissionsToRequest, 0, REQUIRED_PERMISSIONS.length);
                permissionsToRequest[REQUIRED_PERMISSIONS.length] = Manifest.permission.POST_NOTIFICATIONS;
            }
            
            requestPermissions(permissionsToRequest, PERMISSION_REQUEST_CODE);
        } else {
            updateLogDisplay();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "権限が許可されました", Toast.LENGTH_SHORT).show();
                updateLogDisplay();
            } else {
                Toast.makeText(this, "権限が必要です", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void startVoiceListening() {
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
        
        // ログに記録
        logManager.writeLog("アプリから音声監視開始");
    }
    
    private void stopVoiceListening() {
        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        stopService(serviceIntent);
        
        isServiceRunning = false;
        updateButtons();
        updateStatus();
        
        Toast.makeText(this, "音声監視を停止しました", Toast.LENGTH_SHORT).show();
        
        // ログに記録
        logManager.writeLog("アプリから音声監視停止");
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
