package com.micklab.voicelistener;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

public class VoiceListenerService extends Service implements RecognitionListener {
    
    private static final String TAG = "VoiceListenerService";
    private static final String CHANNEL_ID = "VoiceListenerChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private LogManager logManager;
    private boolean isListening = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VoiceListenerService created");
        
        // ログマネージャーを初期化
        logManager = new LogManager(this);
        
        // 通知チャンネル作成
        createNotificationChannel();
        
        // 音声認識の準備
        setupSpeechRecognizer();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VoiceListenerService started");
        
        // フォアグラウンド通知を開始
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 音声監視開始
        startListening();
        
        // システムによってサービスが終了されても自動的に再起動
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "VoiceListenerService destroyed");
        
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
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
            .setContentText("日本語音声を継続的に監視しています")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(this);
            
            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.JAPAN.toLanguageTag());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        } else {
            Log.e(TAG, "音声認識が利用できません");
        }
    }
    
    private void startListening() {
        if (speechRecognizer != null && !isListening) {
            isListening = true;
            speechRecognizer.startListening(recognizerIntent);
            Log.d(TAG, "音声監視開始");
        }
    }
    
    private void restartListening() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            // 短時間待機後に再開
            new android.os.Handler().postDelayed(() -> {
                if (!isDestroyed()) {
                    startListening();
                }
            }, 1000);
        }
    }
    
    private boolean isDestroyed() {
        // サービスが破棄されているかチェック（簡易版）
        return speechRecognizer == null;
    }
    
    // RecognitionListener メソッド実装
    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "音声入力準備完了");
    }
    
    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "音声入力開始");
    }
    
    @Override
    public void onRmsChanged(float rmsdB) {
        // 音量レベル変化（必要に応じて実装）
    }
    
    @Override
    public void onBufferReceived(byte[] buffer) {
        // バッファ受信（必要に応じて実装）
    }
    
    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "音声入力終了");
        isListening = false;
    }
    
    @Override
    public void onError(int error) {
        Log.e(TAG, "音声認識エラー: " + error);
        isListening = false;

        restartListening();
    }
    
    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String recognizedText = matches.get(0).trim();
            Log.d(TAG, "認識結果: " + recognizedText);
            
            if (!recognizedText.isEmpty()) {
                logManager.writeLog("認識: " + recognizedText);
            }
        }
        
        // 継続監視のため再開
        restartListening();
    }
    
    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String partialText = matches.get(0);
            Log.d(TAG, "部分認識: " + partialText);
            // 部分結果は必要に応じてログ記録
        }
    }
    
    @Override
    public void onEvent(int eventType, Bundle params) {
        // イベント処理（必要に応じて実装）
    }
}
