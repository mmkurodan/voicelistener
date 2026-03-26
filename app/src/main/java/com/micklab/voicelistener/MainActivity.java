package com.micklab.voicelistener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int LOG_EXPORT_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO
    };
    
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private EditText logContentText;

    private Handler uiHandler;
    private Runnable periodicUpdateRunnable;
    private Runnable indicatorUpdateRunnable;
    private static final int UPDATE_INTERVAL_MS = 5000; // 5s
    private static final int INDICATOR_UPDATE_INTERVAL_MS = 250;
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
    private Spinner whisperModelSpinner;
    private ArrayAdapter<String> whisperModelSpinnerAdapter;
    private Spinner engineSpinner;
    private ArrayAdapter<String> engineSpinnerAdapter;
    private TextView recognizerStatusText;
    private TextView whisperModelStatusText;
    private ProgressBar modelDownloadProgressBar;
    private TextView modelDownloadProgressText;
    private ProgressBar whisperModelDownloadProgressBar;
    private TextView whisperModelDownloadProgressText;
    private SeekBar volumeIndicatorSeekBar;
    private TextView volumeIndicatorLabel;
    private LinearLayout voskModelSection;
    private LinearLayout whisperModelSection;
    private EditText whisperModelUrlInput;
    private boolean wasModelDownloadActive = false;
    private boolean wasWhisperModelDownloadActive = false;
    private EditText ollamaBaseUrlInput;
    private Spinner ollamaModelSpinner;
    private ArrayAdapter<String> ollamaModelSpinnerAdapter;
    private EditText summaryForceCharsInput;
    private TextView summaryStatusText;
    private ExecutorService ollamaExecutor;
    private ExecutorService backgroundExecutor;
    private final OllamaClient ollamaClient = new OllamaClient();
    private final SimpleDateFormat summaryTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
    private boolean suppressOllamaSelectionCallback = false;
    private boolean suppressEngineSelectionCallback = false;
    private volatile boolean whisperModelPreparationInProgress = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        logManager = new LogManager2(this);
        prefs = getSharedPreferences("VoiceListenerPrefs", MODE_PRIVATE);
        ollamaExecutor = Executors.newSingleThreadExecutor();
        backgroundExecutor = Executors.newSingleThreadExecutor();
        
        createUI();
        checkPermissions();
    }
    
    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        
        // ステータス表示
        statusText = new TextView(this);
        statusText.setText("ステータス: 停止中");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 10);
        layout.addView(statusText);

        TextView recognizerLabel = new TextView(this);
        recognizerLabel.setText("認識エンジン:");
        recognizerLabel.setPadding(0, 6, 0, 6);
        layout.addView(recognizerLabel);

        engineSpinner = new Spinner(this);
        engineSpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        engineSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        engineSpinnerAdapter.add(EngineType.VOSK.name());
        engineSpinnerAdapter.add(EngineType.WHISPER.name());
        engineSpinner.setAdapter(engineSpinnerAdapter);
        engineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressEngineSelectionCallback) {
                    return;
                }
                saveRecognizerSettingsFromInputs();
                updateRecognizerUiState();
                if (getSelectedEngineType() == EngineType.WHISPER) {
                    ensureWhisperModelReadyAsync(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        layout.addView(engineSpinner);

        recognizerStatusText = new TextView(this);
        recognizerStatusText.setPadding(0, 8, 0, 6);
        layout.addView(recognizerStatusText);

        Button applyEngineButton = new Button(this);
        applyEngineButton.setText("選択エンジンを反映");
        applyEngineButton.setOnClickListener(v -> applyRecognizerSelection());
        layout.addView(applyEngineButton);

        whisperModelSection = new LinearLayout(this);
        whisperModelSection.setOrientation(LinearLayout.VERTICAL);
        layout.addView(whisperModelSection);

        TextView whisperSectionLabel = new TextView(this);
        whisperSectionLabel.setText("Whisper モデル");
        whisperSectionLabel.setTextSize(16);
        whisperSectionLabel.setPadding(0, 18, 0, 8);
        whisperModelSection.addView(whisperSectionLabel);

        whisperModelUrlInput = new EditText(this);
        whisperModelUrlInput.setHint("Whisper .gguf のURLを入力 (例: https://...)");
        whisperModelUrlInput.setText(WhisperModelManager.DEFAULT_MODEL_URL);
        whisperModelSection.addView(whisperModelUrlInput);

        Button replaceWhisperModelButton = new Button(this);
        replaceWhisperModelButton.setText("Whisperモデルロード/再DL");
        replaceWhisperModelButton.setOnClickListener(v -> {
            String url = whisperModelUrlInput.getText().toString().trim();
            try {
                WhisperModelManager.deriveModelNameFromUrl(url);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "WhisperモデルURLが不正です: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_INSTALL_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.WHISPER.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_URL, url);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Whisperモデルロードを開始しました", Toast.LENGTH_SHORT).show();
        });
        whisperModelSection.addView(replaceWhisperModelButton);

        whisperModelDownloadProgressText = new TextView(this);
        whisperModelDownloadProgressText.setText("WhisperモデルDL進捗: 待機中");
        whisperModelDownloadProgressText.setPadding(0, 6, 0, 6);
        whisperModelSection.addView(whisperModelDownloadProgressText);

        whisperModelDownloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        whisperModelDownloadProgressBar.setMax(100);
        whisperModelDownloadProgressBar.setProgress(0);
        whisperModelSection.addView(whisperModelDownloadProgressBar);

        TextView whisperModelSelectLabel = new TextView(this);
        whisperModelSelectLabel.setText("ダウンロード済みWhisperモデル:");
        whisperModelSelectLabel.setPadding(0, 14, 0, 6);
        whisperModelSection.addView(whisperModelSelectLabel);

        whisperModelSpinner = new Spinner(this);
        whisperModelSpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        whisperModelSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        whisperModelSpinner.setAdapter(whisperModelSpinnerAdapter);
        whisperModelSection.addView(whisperModelSpinner);

        Button switchWhisperModelButton = new Button(this);
        switchWhisperModelButton.setText("選択Whisperモデルへ切替");
        switchWhisperModelButton.setOnClickListener(v -> {
            String modelName = getSelectedWhisperModelName();
            if (modelName == null) {
                Toast.makeText(this, "切替対象のWhisperモデルがありません", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_SELECT_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.WHISPER.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Whisperモデル切替を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
        });
        whisperModelSection.addView(switchWhisperModelButton);

        Button deleteWhisperModelButton = new Button(this);
        deleteWhisperModelButton.setText("選択Whisperモデル削除");
        deleteWhisperModelButton.setOnClickListener(v -> {
            String modelName = getSelectedWhisperModelName();
            if (modelName == null) {
                Toast.makeText(this, "削除対象のWhisperモデルがありません", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction(VoiceListenerService.ACTION_DELETE_MODEL);
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.WHISPER.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Whisperモデル削除を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
            if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
            uiHandler.postDelayed(() -> refreshWhisperModelSpinner(true), 500);
        });
        whisperModelSection.addView(deleteWhisperModelButton);

        whisperModelStatusText = new TextView(this);
        whisperModelStatusText.setPadding(0, 4, 0, 12);
        whisperModelSection.addView(whisperModelStatusText);
         
        // VAD閾値スライダー + 音量インジケータ（同縮尺）
        float savedThreshold = prefs.getFloat("rms_threshold", 900.0f);
        int savedInt = (int) savedThreshold;
        if (savedInt < VAD_MIN) savedInt = VAD_MIN;
        if (savedInt > VAD_MAX) savedInt = VAD_MAX;

        LinearLayout meterRow = new LinearLayout(this);
        meterRow.setOrientation(LinearLayout.VERTICAL);
        meterRow.setPadding(0, 10, 0, 10);

        LinearLayout sensitivityColumn = new LinearLayout(this);
        sensitivityColumn.setOrientation(LinearLayout.VERTICAL);
        sensitivityColumn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        volumeColumn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        volumeIndicatorLabel = new TextView(this);
        volumeIndicatorLabel.setText("入力音量 (RMS): 0");
        volumeIndicatorLabel.setPadding(0, 10, 0, 10);
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

        voskModelSection = new LinearLayout(this);
        voskModelSection.setOrientation(LinearLayout.VERTICAL);
        layout.addView(voskModelSection);

        TextView voskSectionLabel = new TextView(this);
        voskSectionLabel.setText("VOSK モデル");
        voskSectionLabel.setTextSize(16);
        voskSectionLabel.setPadding(0, 18, 0, 8);
        voskModelSection.addView(voskSectionLabel);

        // モデルURL入力
        final EditText urlInput = new EditText(this);
        urlInput.setHint("モデルZIPのURLを入力 (例: https://...)");
        urlInput.setText("https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip");
        voskModelSection.addView(urlInput);

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
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.VOSK.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_URL, url);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデルロードを開始しました", Toast.LENGTH_SHORT).show();
        });
        voskModelSection.addView(replaceModelButton);

        modelDownloadProgressText = new TextView(this);
        modelDownloadProgressText.setText("モデルDL進捗: 待機中");
        modelDownloadProgressText.setPadding(0, 6, 0, 6);
        voskModelSection.addView(modelDownloadProgressText);

        modelDownloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelDownloadProgressBar.setMax(100);
        modelDownloadProgressBar.setProgress(0);
        voskModelSection.addView(modelDownloadProgressBar);

        TextView modelSelectLabel = new TextView(this);
        modelSelectLabel.setText("ダウンロード済みモデル:");
        modelSelectLabel.setPadding(0, 14, 0, 6);
        voskModelSection.addView(modelSelectLabel);

        modelSpinner = new Spinner(this);
        modelSpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        modelSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelSpinnerAdapter);
        voskModelSection.addView(modelSpinner);

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
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.VOSK.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデル切替を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
        });
        voskModelSection.addView(switchModelButton);

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
            intent.putExtra(VoiceListenerService.EXTRA_ENGINE_TYPE, EngineType.VOSK.name());
            intent.putExtra(VoiceListenerService.EXTRA_MODEL_NAME, modelName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "モデル削除を要求しました: " + modelName, Toast.LENGTH_SHORT).show();
            if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
            uiHandler.postDelayed(() -> refreshModelSpinner(true), 500);
        });
        voskModelSection.addView(deleteModelButton);

        TextView ollamaSectionLabel = new TextView(this);
        ollamaSectionLabel.setText("Ollama互換要約");
        ollamaSectionLabel.setTextSize(16);
        ollamaSectionLabel.setPadding(0, 18, 0, 8);
        layout.addView(ollamaSectionLabel);

        ollamaBaseUrlInput = new EditText(this);
        ollamaBaseUrlInput.setHint(OllamaClient.DEFAULT_BASE_URL);
        ollamaBaseUrlInput.setText(LiveSummaryStore.getOllamaBaseUrl(this));
        layout.addView(ollamaBaseUrlInput);

        Button refreshOllamaModelsButton = new Button(this);
        refreshOllamaModelsButton.setText("Ollamaモデル一覧取得");
        refreshOllamaModelsButton.setOnClickListener(v -> fetchOllamaModels());
        layout.addView(refreshOllamaModelsButton);

        TextView ollamaModelLabel = new TextView(this);
        ollamaModelLabel.setText("要約モデル:");
        ollamaModelLabel.setPadding(0, 10, 0, 6);
        layout.addView(ollamaModelLabel);

        ollamaModelSpinner = new Spinner(this);
        ollamaModelSpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        ollamaModelSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ollamaModelSpinner.setAdapter(ollamaModelSpinnerAdapter);
        ollamaModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressOllamaSelectionCallback || ollamaModelSpinner.getSelectedItem() == null) {
                    return;
                }
                LiveSummaryStore.setOllamaModel(MainActivity.this, String.valueOf(ollamaModelSpinner.getSelectedItem()));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        layout.addView(ollamaModelSpinner);

        TextView summaryThresholdLabel = new TextView(this);
        summaryThresholdLabel.setText("強制要約文字数:");
        summaryThresholdLabel.setPadding(0, 10, 0, 6);
        layout.addView(summaryThresholdLabel);

        summaryForceCharsInput = new EditText(this);
        summaryForceCharsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        summaryForceCharsInput.setHint(String.valueOf(LiveSummaryStore.getSummaryForceCharThreshold(this)));
        summaryForceCharsInput.setText(String.valueOf(LiveSummaryStore.getSummaryForceCharThreshold(this)));
        layout.addView(summaryForceCharsInput);

        summaryStatusText = new TextView(this);
        summaryStatusText.setPadding(0, 10, 0, 10);
        layout.addView(summaryStatusText);

        Button openSummaryButton = new Button(this);
        openSummaryButton.setText("要約画面を開く");
        openSummaryButton.setOnClickListener(v -> {
            saveSummarySettingsFromInputs();
            startActivity(new Intent(this, SummaryActivity.class));
        });
        layout.addView(openSummaryButton);

        Button openOllamaDebugButton = new Button(this);
        openOllamaDebugButton.setText("Ollama入出力画面を開く");
        openOllamaDebugButton.setOnClickListener(v -> {
            saveSummarySettingsFromInputs();
            startActivity(new Intent(this, OllamaDebugActivity.class));
        });
        layout.addView(openOllamaDebugButton);

        // ログ初期化ボタン
        Button clearLogsButton = new Button(this);
        clearLogsButton.setText("ログ初期化");
        clearLogsButton.setOnClickListener(v -> {
            logManager.clearAllLogs();
            updateLogDisplay();
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
        configureReadOnlyLogTextArea(logContentText, 500);
        layout.addView(logContentText);

        refreshModelSpinner(false);
        refreshWhisperModelSpinner(false);
        refreshOllamaModelSpinner(false);
        syncRecognizerSettingsInputs();
        updateDownloadProgressIndicator();
        updateWhisperDownloadProgressIndicator();
        updateVolumeIndicator();
        updateSummaryDisplay();
        refreshWhisperModelStatus();
        ensureWhisperModelReadyAsync(false);

        ScrollView rootScrollView = new ScrollView(this);
        rootScrollView.setFillViewport(true);
        rootScrollView.addView(layout);
        setContentView(rootScrollView);
    }

    private void configureReadOnlyLogTextArea(EditText textArea, int heightPx) {
        textArea.setKeyListener(null);
        textArea.setCursorVisible(false);
        textArea.setLongClickable(true);
        textArea.setTextIsSelectable(true);
        textArea.setFocusable(true);
        textArea.setFocusableInTouchMode(true);
        textArea.setShowSoftInputOnFocus(false);
        textArea.setHorizontallyScrolling(false);
        textArea.setVerticalScrollBarEnabled(true);
        textArea.setMovementMethod(ScrollingMovementMethod.getInstance());
        textArea.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        textArea.setOnTouchListener((v, event) -> {
            if (v.getParent() == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void setLogContentTextKeepingViewport(String text) {
        if (logContentText == null) return;
        String nextText = text == null ? "" : text;
        CharSequence currentText = logContentText.getText();
        if (currentText != null && nextText.contentEquals(currentText)) {
            return;
        }

        int scrollX = logContentText.getScrollX();
        int scrollY = logContentText.getScrollY();
        logContentText.setTextKeepState(nextText);
        logContentText.post(() -> {
            if (logContentText == null || logContentText.getLayout() == null) {
                return;
            }
            int maxScrollY = Math.max(
                0,
                logContentText.getLayout().getHeight()
                    - logContentText.getHeight()
                    + logContentText.getCompoundPaddingTop()
                    + logContentText.getCompoundPaddingBottom());
            logContentText.scrollTo(scrollX, Math.min(scrollY, maxScrollY));
        });
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

        saveRecognizerSettingsFromInputs();
        if (getSelectedEngineType() == EngineType.WHISPER && !WhisperModelManager.hasAnyModelSource(this)) {
            String msg = "Whisperモデルが未準備です。URLから .gguf をダウンロードするか、assets/models/ に .gguf を追加してください。";
            logManager.writeLog(msg);
            statusText.setText("ステータス: " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        if (getSelectedEngineType() == EngineType.WHISPER) {
            ensureWhisperModelReadyAsync(false);
        }
        saveSummarySettingsFromInputs();
        String selectedOllamaModel = getSelectedOllamaModelName();
        if (selectedOllamaModel != null) {
            LiveSummaryStore.setOllamaModel(this, selectedOllamaModel);
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
        if (recognizerStatusText != null) {
            recognizerStatusText.setText("現在の認識エンジン設定: " + SpeechRecognitionPreferences.getActiveEngine(this).getDisplayName());
        }
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

    private String getSelectedWhisperModelName() {
        if (whisperModelSpinner == null || whisperModelSpinner.getSelectedItem() == null) {
            return null;
        }
        String selected = String.valueOf(whisperModelSpinner.getSelectedItem()).trim();
        return selected.isEmpty() ? null : selected;
    }

    private String getSelectedOllamaModelName() {
        if (ollamaModelSpinner == null || ollamaModelSpinner.getSelectedItem() == null) {
            return null;
        }
        String selected = String.valueOf(ollamaModelSpinner.getSelectedItem()).trim();
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
    }

    private void refreshWhisperModelSpinner(boolean keepSelection) {
        if (whisperModelSpinnerAdapter == null || whisperModelSpinner == null) return;

        String selectedBefore = keepSelection ? getSelectedWhisperModelName() : null;
        String savedModel = WhisperModelManager.getSelectedModelName(this);
        ArrayList<String> modelNames = new ArrayList<>(WhisperModelManager.listDownloadedModelNames(this));

        whisperModelSpinnerAdapter.clear();
        whisperModelSpinnerAdapter.addAll(modelNames);
        whisperModelSpinnerAdapter.notifyDataSetChanged();

        if (!modelNames.isEmpty()) {
            String target = selectedBefore != null ? selectedBefore : savedModel;
            int index = target != null ? modelNames.indexOf(target) : -1;
            whisperModelSpinner.setSelection(index >= 0 ? index : 0);
        }
    }

    private void refreshOllamaModelSpinner(boolean keepSelection) {
        if (ollamaModelSpinnerAdapter == null || ollamaModelSpinner == null) return;

        String selectedBefore = keepSelection ? getSelectedOllamaModelName() : null;
        String savedModel = LiveSummaryStore.getOllamaModel(this);
        ArrayList<String> modelNames = LiveSummaryStore.getCachedModelNames(this);
        if (!modelNames.contains(savedModel)) {
            modelNames.add(savedModel);
        }

        suppressOllamaSelectionCallback = true;
        ollamaModelSpinnerAdapter.clear();
        ollamaModelSpinnerAdapter.addAll(modelNames);
        ollamaModelSpinnerAdapter.notifyDataSetChanged();

        if (!modelNames.isEmpty()) {
            String target = selectedBefore != null ? selectedBefore : savedModel;
            int index = modelNames.indexOf(target);
            ollamaModelSpinner.setSelection(index >= 0 ? index : 0);
        }
        suppressOllamaSelectionCallback = false;
    }

    private void saveOllamaBaseUrlFromInput() {
        if (ollamaBaseUrlInput == null) return;
        LiveSummaryStore.setOllamaBaseUrl(this, ollamaBaseUrlInput.getText().toString());
    }

    private void saveSummaryForceCharsFromInput() {
        if (summaryForceCharsInput == null) return;
        String raw = String.valueOf(summaryForceCharsInput.getText()).trim();
        int threshold = LiveSummaryStore.getSummaryForceCharThreshold(this);
        if (!raw.isEmpty()) {
            try {
                threshold = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
            }
        }
        LiveSummaryStore.setSummaryForceCharThreshold(this, threshold);
        summaryForceCharsInput.setText(String.valueOf(LiveSummaryStore.getSummaryForceCharThreshold(this)));
    }

    private void saveSummarySettingsFromInputs() {
        saveOllamaBaseUrlFromInput();
        saveSummaryForceCharsFromInput();
    }

    private void saveRecognizerSettingsFromInputs() {
        SpeechRecognitionPreferences.setActiveEngine(this, getSelectedEngineType());
    }

    private void syncSummarySettingsInputs() {
        if (summaryForceCharsInput != null) {
            summaryForceCharsInput.setText(String.valueOf(LiveSummaryStore.getSummaryForceCharThreshold(this)));
        }
        if (ollamaBaseUrlInput != null) {
            ollamaBaseUrlInput.setText(LiveSummaryStore.getOllamaBaseUrl(this));
        }
    }

    private void syncRecognizerSettingsInputs() {
        if (engineSpinnerAdapter == null || engineSpinner == null) {
            return;
        }
        EngineType activeEngine = SpeechRecognitionPreferences.getActiveEngine(this);
        int index = engineSpinnerAdapter.getPosition(activeEngine.name());
        suppressEngineSelectionCallback = true;
        if (index >= 0) {
            engineSpinner.setSelection(index);
        }
        suppressEngineSelectionCallback = false;
        updateRecognizerUiState();
    }

    private EngineType getSelectedEngineType() {
        if (engineSpinner == null || engineSpinner.getSelectedItem() == null) {
            return SpeechRecognitionPreferences.getActiveEngine(this);
        }
        return EngineType.fromPreference(String.valueOf(engineSpinner.getSelectedItem()));
    }

    private void updateRecognizerUiState() {
        EngineType selectedEngine = getSelectedEngineType();
        boolean whisperSelected = selectedEngine == EngineType.WHISPER;
        if (recognizerStatusText != null) {
            recognizerStatusText.setText("現在の認識エンジン設定: " + selectedEngine.getDisplayName());
        }
        if (voskModelSection != null) {
            voskModelSection.setVisibility(whisperSelected ? View.GONE : View.VISIBLE);
        }
        if (whisperModelSection != null) {
            whisperModelSection.setVisibility(whisperSelected ? View.VISIBLE : View.GONE);
        }
        refreshWhisperModelStatus();
    }

    private void applyRecognizerSelection() {
        saveRecognizerSettingsFromInputs();
        updateRecognizerUiState();
        if (getSelectedEngineType() == EngineType.WHISPER) {
            if (!WhisperModelManager.hasAnyModelSource(this)) {
                Toast.makeText(this, "Whisperモデルが未準備です。URLから .gguf を取得するか、assets/models/ に .gguf を追加してください。", Toast.LENGTH_LONG).show();
                return;
            }
            ensureWhisperModelReadyAsync(false);
        }
        if (!isServiceRunning) {
            Toast.makeText(this, "認識エンジン設定を保存しました", Toast.LENGTH_SHORT).show();
            updateStatusFromPrefs();
            return;
        }

        Intent intent = new Intent(this, VoiceListenerService.class);
        intent.setAction(VoiceListenerService.ACTION_REFRESH_RECOGNIZER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "認識エンジン切替を要求しました", Toast.LENGTH_SHORT).show();
    }

    private void refreshWhisperModelStatus() {
        if (whisperModelStatusText == null) {
            return;
        }
        if (whisperModelPreparationInProgress) {
            whisperModelStatusText.setText("Whisperモデル: 内部ストレージへコピー中...");
            return;
        }

        String selectedModelName = WhisperModelManager.getSelectedModelName(this);
        File selectedModel = WhisperModelManager.resolveSelectedDownloadedModelFile(this);
        if (selectedModel != null) {
            whisperModelStatusText.setText("Whisperモデル: " + selectedModel.getName() + " (ダウンロード済み / 選択中)");
            return;
        }

        File preferredDownloadedModel = WhisperModelManager.resolvePreferredDownloadedModelFile(this);
        if (preferredDownloadedModel != null) {
            if (selectedModelName != null) {
                whisperModelStatusText.setText("Whisperモデル: " + selectedModelName + " は見つかりません。代わりに " + preferredDownloadedModel.getName() + " を利用可能です");
            } else {
                whisperModelStatusText.setText("Whisperモデル: " + preferredDownloadedModel.getName() + " (ダウンロード済み)");
            }
            return;
        }

        String assetName = WhisperModelAssetInstaller.getBundledModelAssetName(this);
        File installedModel = WhisperModelAssetInstaller.getInstalledModelFile(this);
        if (installedModel != null) {
            whisperModelStatusText.setText("Whisperモデル: " + installedModel.getName() + " (assets/models 由来 / 準備済み)");
        } else if (assetName != null) {
            whisperModelStatusText.setText("Whisperモデル: " + assetName + " (assets/models 由来 / 未コピー)");
        } else {
            whisperModelStatusText.setText("Whisperモデル: 未ダウンロード。URLから .gguf を取得してください");
        }
    }

    private void ensureWhisperModelReadyAsync(boolean announceResult) {
        if (backgroundExecutor == null || whisperModelPreparationInProgress) {
            refreshWhisperModelStatus();
            return;
        }
        if (WhisperModelManager.resolvePreferredDownloadedModelFile(this) != null
            || WhisperModelAssetInstaller.getInstalledModelFile(this) != null
            || !WhisperModelAssetInstaller.hasBundledModel(this)) {
            refreshWhisperModelStatus();
            return;
        }

        whisperModelPreparationInProgress = true;
        refreshWhisperModelStatus();
        backgroundExecutor.execute(() -> {
            File preparedModel = null;
            Exception failure = null;
            try {
                preparedModel = WhisperModelAssetInstaller.ensureBundledModelCopied(this);
            } catch (Exception e) {
                failure = e;
            }

            File resultModel = preparedModel;
            Exception resultFailure = failure;
            runOnUiThread(() -> {
                whisperModelPreparationInProgress = false;
                refreshWhisperModelStatus();
                if (announceResult) {
                    if (resultFailure != null) {
                        Toast.makeText(this, "Whisperモデル準備失敗: " + resultFailure.getMessage(), Toast.LENGTH_LONG).show();
                    } else if (resultModel != null) {
                        Toast.makeText(this, "Whisperモデルを準備しました: " + resultModel.getName(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    private void updateWhisperDownloadProgressIndicator() {
        if (whisperModelDownloadProgressBar == null || whisperModelDownloadProgressText == null) return;
        boolean active = prefs.getBoolean(WhisperModelManager.PREF_MODEL_DOWNLOAD_ACTIVE, false);
        int progress = prefs.getInt(WhisperModelManager.PREF_MODEL_DOWNLOAD_PROGRESS, 0);
        String modelName = prefs.getString(WhisperModelManager.PREF_MODEL_DOWNLOAD_NAME, "");

        if (active) {
            if (progress < 0) {
                whisperModelDownloadProgressBar.setIndeterminate(true);
                whisperModelDownloadProgressText.setText("WhisperモデルDL中: " + modelName);
            } else {
                int safeProgress = Math.max(0, Math.min(progress, 100));
                whisperModelDownloadProgressBar.setIndeterminate(false);
                whisperModelDownloadProgressBar.setProgress(safeProgress);
                whisperModelDownloadProgressText.setText("WhisperモデルDL中 (" + modelName + "): " + safeProgress + "%");
            }
        } else {
            int safeProgress = Math.max(0, Math.min(progress, 100));
            whisperModelDownloadProgressBar.setIndeterminate(false);
            whisperModelDownloadProgressBar.setProgress(safeProgress);
            if (safeProgress >= 100 && modelName != null && !modelName.isEmpty()) {
                whisperModelDownloadProgressText.setText("WhisperモデルDL完了: " + modelName);
            } else {
                whisperModelDownloadProgressText.setText("WhisperモデルDL進捗: 待機中");
            }
        }
        if (wasWhisperModelDownloadActive && !active) {
            refreshWhisperModelSpinner(true);
            refreshWhisperModelStatus();
        }
        wasWhisperModelDownloadActive = active;
    }

    private void ensureOllamaExecutor() {
        if (ollamaExecutor == null || ollamaExecutor.isShutdown()) {
            ollamaExecutor = Executors.newSingleThreadExecutor();
        }
    }

    private void fetchOllamaModels() {
        saveOllamaBaseUrlFromInput();
        ensureOllamaExecutor();
        String baseUrl = LiveSummaryStore.getOllamaBaseUrl(this);
        Toast.makeText(this, "Ollamaモデル一覧を取得しています", Toast.LENGTH_SHORT).show();
        ollamaExecutor.execute(() -> {
            try {
                List<String> models = new ArrayList<>(ollamaClient.listModelNames(baseUrl));
                String currentModel = LiveSummaryStore.getOllamaModel(this);
                if (!models.contains(currentModel)) {
                    models.add(currentModel);
                }
                LiveSummaryStore.setCachedModelNames(this, models);
                runOnUiThread(() -> {
                    refreshOllamaModelSpinner(true);
                    Toast.makeText(this, "Ollamaモデル一覧を更新しました", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Ollamaモデル一覧取得失敗: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
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
        if (wasModelDownloadActive && !active) {
            refreshModelSpinner(true);
        }
        wasModelDownloadActive = active;
    }

    private void updateSummaryDisplay() {
        if (summaryStatusText == null) {
            return;
        }
        LiveSummaryState state = LiveSummaryStore.loadSummaryState(this);
        String status = state.getStatus().isEmpty() ? "要約待機中" : state.getStatus();
        int pendingChars = LiveSummaryStore.getPendingSummaryLogCharCount(this);
        if (pendingChars > 0) {
            status = status + " / 未要約ログ: " + pendingChars + "文字";
        }
        if (state.getUpdatedAtMillis() > 0L) {
            status = status + " (" + summaryTimeFormat.format(new java.util.Date(state.getUpdatedAtMillis())) + ")";
        }
        summaryStatusText.setText("要約状態: " + status);
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
                setLogContentTextKeepingViewport("ログファイルがありません");
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

                setLogContentTextKeepingViewport(logContent.toString());
            }

        } catch (IOException e) {
            setLogContentTextKeepingViewport("ログ読み込みエラー: " + e.getMessage());
        }
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
        refreshWhisperModelSpinner(true);
        refreshOllamaModelSpinner(true);
        syncRecognizerSettingsInputs();
        syncSummarySettingsInputs();
        updateDownloadProgressIndicator();
        updateWhisperDownloadProgressIndicator();
        updateVolumeIndicator();
        refreshWhisperModelStatus();
        updateSummaryDisplay();
        // 定期更新を開始
        if (uiHandler == null) uiHandler = new Handler(Looper.getMainLooper());
        if (periodicUpdateRunnable == null) {
            periodicUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateLogDisplay();
                        updateStatusFromPrefs();
                        updateSummaryDisplay();
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
                        updateWhisperDownloadProgressIndicator();
                        updateVolumeIndicator();
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
        saveSummarySettingsFromInputs();
        if (uiHandler != null && periodicUpdateRunnable != null) {
            uiHandler.removeCallbacks(periodicUpdateRunnable);
        }
        if (uiHandler != null && indicatorUpdateRunnable != null) {
            uiHandler.removeCallbacks(indicatorUpdateRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ollamaExecutor != null) {
            ollamaExecutor.shutdownNow();
            ollamaExecutor = null;
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
            backgroundExecutor = null;
        }
    }
}
