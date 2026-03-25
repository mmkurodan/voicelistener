package com.micklab.voicelistener;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class OllamaDebugActivity extends Activity {
    private static final int UPDATE_INTERVAL_MS = 2000;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
    private Handler uiHandler;
    private Runnable periodicUpdateRunnable;
    private TextView statusText;
    private EditText promptText;
    private EditText responseText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUi();
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, 0, 0, 16);
        root.addView(statusText);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, 0, 0, 16);
        root.addView(actionRow);

        Button closeButton = createActionButton("閉じる");
        closeButton.setOnClickListener(v -> finish());
        actionRow.addView(closeButton);

        Button copyAllButton = createActionButton("全文コピー");
        copyAllButton.setOnClickListener(v -> copyAllText());
        actionRow.addView(copyAllButton);

        root.addView(createSectionLabel("プロンプト"));
        promptText = createReadOnlyTextArea();
        root.addView(promptText);

        root.addView(createSectionLabel("レスポンス"));
        responseText = createReadOnlyTextArea();
        root.addView(responseText);

        setContentView(root);
        updateDisplay();
    }

    private Button createActionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        return button;
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setPadding(0, 0, 0, 8);
        return label;
    }

    private EditText createReadOnlyTextArea() {
        EditText output = new EditText(this);
        output.setTextSize(12);
        output.setBackgroundColor(0xFFF3F3F3);
        output.setTextColor(0xFF111111);
        output.setPadding(10, 10, 10, 10);
        output.setKeyListener(null);
        output.setCursorVisible(false);
        output.setLongClickable(true);
        output.setTextIsSelectable(true);
        output.setFocusable(true);
        output.setFocusableInTouchMode(true);
        output.setShowSoftInputOnFocus(false);
        output.setHorizontallyScrolling(false);
        output.setVerticalScrollBarEnabled(true);
        output.setMovementMethod(ScrollingMovementMethod.getInstance());
        output.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        output.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));
        return output;
    }

    private void updateDisplay() {
        if (statusText == null || promptText == null || responseText == null) {
            return;
        }
        OllamaDebugState state = LiveSummaryStore.loadOllamaDebugState(this);
        promptText.setText(formatText(state.getPrompt(), "まだプロンプトはありません"));
        responseText.setText(formatText(state.getResponse(), "まだレスポンスはありません"));

        String status = state.getStatus().isEmpty() ? "Ollama待機中" : state.getStatus();
        if (!state.getModel().isEmpty()) {
            status = status + " / model: " + state.getModel();
        }
        if (!state.getBaseUrl().isEmpty()) {
            status = status + " / " + state.getBaseUrl();
        }
        if (state.getUpdatedAtMillis() > 0L) {
            status = status + " (" + timeFormat.format(new java.util.Date(state.getUpdatedAtMillis())) + ")";
        }
        statusText.setText("Ollama状態: " + status);
    }

    private String formatText(String value, String emptyMessage) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? emptyMessage : normalized;
    }

    private void copyAllText() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(this, "クリップボードにアクセスできません", Toast.LENGTH_SHORT).show();
            return;
        }
        OllamaDebugState state = LiveSummaryStore.loadOllamaDebugState(this);
        StringBuilder builder = new StringBuilder();
        builder.append("プロンプト\n")
            .append(formatText(state.getPrompt(), "まだプロンプトはありません"))
            .append("\n\nレスポンス\n")
            .append(formatText(state.getResponse(), "まだレスポンスはありません"));
        clipboardManager.setPrimaryClip(ClipData.newPlainText("ollama-debug", builder.toString()));
        Toast.makeText(this, "Ollama入出力をコピーしました", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDisplay();
        if (uiHandler == null) {
            uiHandler = new Handler(Looper.getMainLooper());
        }
        if (periodicUpdateRunnable == null) {
            periodicUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateDisplay();
                    } catch (Exception ignored) {
                    }
                    uiHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            };
        }
        uiHandler.post(periodicUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (uiHandler != null && periodicUpdateRunnable != null) {
            uiHandler.removeCallbacks(periodicUpdateRunnable);
        }
    }
}
