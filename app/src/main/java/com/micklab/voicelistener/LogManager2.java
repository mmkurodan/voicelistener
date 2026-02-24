package com.micklab.voicelistener;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class LogManager2 {
    private static final String TAG = "LogManager";
    private static final String LOG_FOLDER = "VoiceListener";
    private static final String LOG_FILE_PREFIX = "voice_log_";
    private static final String LOG_FILE_EXTENSION = ".txt";
    private static final String LOG_PREFS_NAME = "VoiceListenerLogPrefs";
    private static final String PREF_ACTIVE_LOG_FILE_NAME = "active_log_file_name";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private final Context context;
    private final SharedPreferences logPrefs;
    private final SimpleDateFormat minuteFormat;

    private String activeLogFileName;
    private String lastMinute = null;
    private String lastLogFilePath = null;

    public LogManager2(Context context) {
        this.context = context.getApplicationContext();
        this.logPrefs = this.context.getSharedPreferences(LOG_PREFS_NAME, Context.MODE_PRIVATE);
        this.minuteFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN);
        createLogFolder();
        this.activeLogFileName = getOrCreateActiveLogFileName();
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
        File externalDocs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (externalDocs != null) {
            return new File(externalDocs, LOG_FOLDER);
        } else {
            return new File(context.getFilesDir(), LOG_FOLDER);
        }
    }

    private synchronized String getOrCreateActiveLogFileName() {
        String fileName = logPrefs.getString(PREF_ACTIVE_LOG_FILE_NAME, null);
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = generateUniqueLogFileName();
            logPrefs.edit().putString(PREF_ACTIVE_LOG_FILE_NAME, fileName).apply();
        }
        return fileName;
    }

    private synchronized void setActiveLogFileName(String fileName) {
        activeLogFileName = fileName;
        logPrefs.edit().putString(PREF_ACTIVE_LOG_FILE_NAME, fileName).apply();
    }

    private String generateUniqueLogFileName() {
        return LOG_FILE_PREFIX + UUID.randomUUID().toString().replace("-", "") + LOG_FILE_EXTENSION;
    }

    // デフォルトは永続化あり
    public synchronized void writeLog(String message) {
        writeLog(message, true);
    }

    // persist == false の場合はファイルに書き込まずログ出力のみ行う
    public synchronized void writeLog(String message, boolean persist) {
        if (message == null || message.trim().isEmpty()) return;

        if (!persist) {
            Log.d(TAG, "TRANSIENT: " + message);
            return;
        }

        try {
            File logFile = getCurrentLogFile();
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String logFilePath = logFile.getAbsolutePath();

            if (lastLogFilePath == null || !lastLogFilePath.equals(logFilePath)) {
                lastMinute = null;
                lastLogFilePath = logFilePath;
            }

            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                logFile = createNewLogFile();
                lastMinute = null;
                lastLogFilePath = logFile.getAbsolutePath();
            }

            String msgToWrite = message.replaceFirst("^認識[:：]\\s*", "");
            String currentMinute = minuteFormat.format(new Date());

            try (FileWriter writer = new FileWriter(logFile, true)) {
                if (!currentMinute.equals(lastMinute)) {
                    writer.write(String.format("[%s]%n", currentMinute));
                    lastMinute = currentMinute;
                }
                writer.write(msgToWrite + System.lineSeparator());
                writer.flush();
            }

            Log.d(TAG, "ログ記録: " + message);
        } catch (IOException e) {
            Log.e(TAG, "ログ書き込みエラー", e);
        }
    }

    private File getCurrentLogFile() {
        return new File(getLogFolder(), activeLogFileName);
    }

    private File createNewLogFile() {
        File logFolder = getLogFolder();
        String fileName;
        File logFile;
        do {
            fileName = generateUniqueLogFileName();
            logFile = new File(logFolder, fileName);
        } while (logFile.exists());
        setActiveLogFileName(fileName);
        return logFile;
    }

    public String getLogFolderPath() {
        return getLogFolder().getAbsolutePath();
    }

    public File[] getLogFiles() {
        File logFolder = getLogFolder();
        if (logFolder.exists()) {
            return logFolder.listFiles((dir, name) -> name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_EXTENSION));
        }
        return new File[0];
    }

    public synchronized File getLatestLogFile() {
        File[] logFiles = getLogFiles();
        if (logFiles == null || logFiles.length == 0) {
            return null;
        }
        File latest = null;
        long latestTime = Long.MIN_VALUE;
        for (File file : logFiles) {
            if (file != null && file.lastModified() >= latestTime) {
                latestTime = file.lastModified();
                latest = file;
            }
        }
        return latest;
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

    public void clearAllLogs() {
        File[] logFiles = getLogFiles();
        if (logFiles == null) return;
        for (File f : logFiles) {
            try (FileWriter writer = new FileWriter(f, false)) {
                writer.write("");
                writer.flush();
                Log.d(TAG, "ログファイル内容初期化: " + f.getName());
            } catch (Exception e) {
                Log.w(TAG, "ログ初期化中例外: " + f.getName(), e);
            }
        }
        lastMinute = null;
        lastLogFilePath = null;
    }
}
