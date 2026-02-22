package com.micklab.voicelistener;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogManager {
    
    private static final String TAG = "LogManager";
    private static final String LOG_FOLDER = "VoiceListener";
    private static final String LOG_FILE_PREFIX = "voice_log_";
    private static final String LOG_FILE_EXTENSION = ".txt";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    
    private Context context;
    private SimpleDateFormat dateFormat;
    private SimpleDateFormat fileNameFormat;
    
    public LogManager(Context context) {
        this.context = context;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
        this.fileNameFormat = new SimpleDateFormat("yyyyMMdd", Locale.JAPAN);
        
        // ログフォルダを作成
        createLogFolder();
    }
    
    private void createLogFolder() {
        File logFolder = getLogFolder();
        if (!logFolder.exists()) {
            if (logFolder.mkdirs()) {
                Log.d(TAG, "ログフォルダ作成: " + logFolder.getAbsolutePath());
            } else {
                Log.e(TAG, "ログフォルダ作成失敗: " + logFolder.getAbsolutePath());
            }
        }
    }
    
    private File getLogFolder() {
        // 外部ストレージのDocumentsフォルダ内にVoiceListenerフォルダを作成
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(documentsDir, LOG_FOLDER);
    }
    
    public void writeLog(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        
        try {
            File logFile = getCurrentLogFile();
            
            // ファイルサイズチェック
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                logFile = createNewLogFile();
            }
            
            // ログエントリを作成
            String timestamp = dateFormat.format(new Date());
            String logEntry = String.format("[%s] %s%n", timestamp, message);
            
            // ファイルに書き込み
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(logEntry);
                writer.flush();
            }
            
            Log.d(TAG, "ログ記録: " + message);
            
        } catch (IOException e) {
            Log.e(TAG, "ログ書き込みエラー", e);
        }
    }
    
    private File getCurrentLogFile() {
        File logFolder = getLogFolder();
        String fileName = LOG_FILE_PREFIX + fileNameFormat.format(new Date()) + LOG_FILE_EXTENSION;
        return new File(logFolder, fileName);
    }
    
    private File createNewLogFile() {
        File logFolder = getLogFolder();
        String baseFileName = LOG_FILE_PREFIX + fileNameFormat.format(new Date());
        int counter = 1;
        File logFile;
        
        do {
            String fileName = baseFileName + "_" + counter + LOG_FILE_EXTENSION;
            logFile = new File(logFolder, fileName);
            counter++;
        } while (logFile.exists() && logFile.length() > MAX_FILE_SIZE);
        
        return logFile;
    }
    
    public String getLogFolderPath() {
        return getLogFolder().getAbsolutePath();
    }
    
    public File[] getLogFiles() {
        File logFolder = getLogFolder();
        if (logFolder.exists()) {
            return logFolder.listFiles((dir, name) -> 
                name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION));
        }
        return new File[0];
    }
    
    public void clearOldLogs(int daysToKeep) {
        File[] logFiles = getLogFiles();
        if (logFiles == null) return;
        
        long cutoffTime = System.currentTimeMillis() - (daysToKeep * 24L * 60L * 60L * 1000L);
        
        for (File file : logFiles) {
            if (file.lastModified() < cutoffTime) {
                if (file.delete()) {
                    Log.d(TAG, "古いログファイル削除: " + file.getName());
                } else {
                    Log.w(TAG, "ログファイル削除失敗: " + file.getName());
                }
            }
        }
    }
}