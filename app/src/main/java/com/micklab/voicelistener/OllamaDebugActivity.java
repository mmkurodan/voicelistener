package com.micklab.voicelistener;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
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
    private SelectableTextArea promptTemplateText;
    private SelectableTextArea historyText;

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

        root.addView(createSectionLabel("要約プロンプト"));

        TextView promptTemplateHint = new TextView(this);
        promptTemplateHint.setText(
            "利用可能プレースホルダ: "
                + OllamaClient.SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY
                + " / "
                + OllamaClient.SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS
        );
        promptTemplateHint.setTextSize(12);
        promptTemplateHint.setPadding(0, 0, 0, 8);
        root.addView(promptTemplateHint);

        Button resetPromptButton = new Button(this);
        resetPromptButton.setText("プロンプトを初期化");
        resetPromptButton.setOnClickListener(v -> resetSummaryPromptTemplate());
        root.addView(resetPromptButton);

        promptTemplateText = createEditableTextArea(280, 0f);
        promptTemplateText.setHint("要約プロンプト");
        promptTemplateText.setText(LiveSummaryStore.getSummaryPromptTemplate(this));
        promptTemplateText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                LiveSummaryStore.setSummaryPromptTemplate(
                    OllamaDebugActivity.this,
                    s == null ? "" : s.toString()
                );
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(promptTemplateText);

        root.addView(createSectionLabel("履歴"));
        historyText = createReadOnlyTextArea();
        root.addView(historyText);

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

    private SelectableTextArea createEditableTextArea(int heightPx, float weight) {
        SelectableTextArea output = new SelectableTextArea(this);
        output.setTextSize(15);
        output.setBackgroundColor(0xFFF3F3F3);
        output.setTextColor(0xFF111111);
        output.setPadding(10, 10, 10, 10);
        output.configureEditableText();
        if (weight > 0f) {
            output.setWeightedHeight(weight);
        } else {
            output.setFixedHeight(heightPx);
        }
        return output;
    }

    private SelectableTextArea createReadOnlyTextArea() {
        SelectableTextArea output = new SelectableTextArea(this);
        output.setTextSize(12);
        output.setBackgroundColor(0xFFF3F3F3);
        output.setTextColor(0xFF111111);
        output.setPadding(10, 10, 10, 10);
        output.configureReadOnlyText();
        output.setWeightedHeight(1f);
        return output;
    }

    private void updateDisplay() {
        if (statusText == null || historyText == null) {
            return;
        }
        if (promptTemplateText != null && !promptTemplateText.hasFocus()) {
            String savedPrompt = LiveSummaryStore.getSummaryPromptTemplate(this);
            String currentPrompt = String.valueOf(promptTemplateText.getText());
            if (!savedPrompt.equals(currentPrompt)) {
                promptTemplateText.setText(savedPrompt);
                promptTemplateText.setSelection(promptTemplateText.length());
            }
        }
        OllamaDebugState state = LiveSummaryStore.loadOllamaDebugState(this);
        historyText.setText(formatText(state.getHistory(), "まだ履歴はありません"));

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

    private void resetSummaryPromptTemplate() {
        if (promptTemplateText == null) {
            return;
        }
        promptTemplateText.setText(OllamaClient.DEFAULT_SUMMARY_PROMPT_TEMPLATE);
        promptTemplateText.setSelection(promptTemplateText.length());
        Toast.makeText(this, "要約プロンプトを初期化しました", Toast.LENGTH_SHORT).show();
    }

    private void copyAllText() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(this, "クリップボードにアクセスできません", Toast.LENGTH_SHORT).show();
            return;
        }
        OllamaDebugState state = LiveSummaryStore.loadOllamaDebugState(this);
        clipboardManager.setPrimaryClip(ClipData.newPlainText(
            "ollama-debug",
            formatText(state.getHistory(), "まだ履歴はありません")
        ));
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
