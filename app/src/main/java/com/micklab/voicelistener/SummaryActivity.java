package com.micklab.voicelistener;

import android.app.Activity;
import android.content.Intent;
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

public class SummaryActivity extends Activity {
    private static final int UPDATE_INTERVAL_MS = 2000;

    private final SimpleDateFormat summaryTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
    private Handler uiHandler;
    private Runnable periodicUpdateRunnable;
    private TextView summaryStatusText;
    private SelectableTextArea summaryText;
    private Button refreshSummaryButton;
    private boolean applyingSummaryText = false;
    private String lastAppliedSummaryText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createUi();
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        summaryStatusText = new TextView(this);
        summaryStatusText.setTextSize(16);
        summaryStatusText.setPadding(0, 0, 0, 16);
        root.addView(summaryStatusText);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, 0, 0, 16);
        root.addView(actionRow);

        Button closeButton = createActionButton("閉じる");
        closeButton.setOnClickListener(v -> finish());
        actionRow.addView(closeButton);

        Button clearButton = createActionButton("クリア");
        clearButton.setOnClickListener(v -> {
            LiveSummaryStore.clearSummarySession(this);
            updateSummaryDisplay();
            Toast.makeText(this, "要約をクリアしました", Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(clearButton);

        refreshSummaryButton = createActionButton("要約実行");
        refreshSummaryButton.setOnClickListener(v -> requestManualSummaryRefresh());
        actionRow.addView(refreshSummaryButton);

        root.addView(createSectionLabel("要約"));
        summaryText = createEditableTextArea(0, 1f);
        summaryText.setHint("要約はまだありません");
        summaryText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (applyingSummaryText) {
                    return;
                }
                LiveSummaryStore.saveEditedSummary(SummaryActivity.this, s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(summaryText);

        setContentView(root);
        updateSummaryDisplay();
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

    private void updateSummaryDisplay() {
        if (summaryStatusText == null || summaryText == null) {
            return;
        }
        LiveSummaryState state = LiveSummaryStore.loadSummaryState(this);
        String persistedSummary = state.getSummary();
        String currentEditorText = String.valueOf(summaryText.getText());
        boolean shouldReplaceEditorText = !summaryText.hasFocus() || currentEditorText.equals(lastAppliedSummaryText);
        if (shouldReplaceEditorText && !persistedSummary.equals(currentEditorText)) {
            applyingSummaryText = true;
            summaryText.setText(persistedSummary);
            summaryText.setSelection(summaryText.length());
            applyingSummaryText = false;
            lastAppliedSummaryText = persistedSummary;
        }
        if (!summaryText.hasFocus()) {
            lastAppliedSummaryText = persistedSummary;
        }

        String status = state.getStatus().isEmpty() ? "要約待機中" : state.getStatus();
        int pendingChars = LiveSummaryStore.getPendingSummaryLogCharCount(this);
        if (pendingChars > 0) {
            status = status + " / 未要約ログ: " + pendingChars + "文字";
        }
        if (state.getUpdatedAtMillis() > 0L) {
            status = status + " (" + summaryTimeFormat.format(new java.util.Date(state.getUpdatedAtMillis())) + ")";
        }
        SummaryUpdateMode updateMode = LiveSummaryStore.getSummaryUpdateMode(this);
        summaryStatusText.setText("要約モード: " + updateMode.getDisplayName() + " / 要約状態: " + status);
    }

    private void persistSummaryText() {
        if (summaryText == null) {
            return;
        }
        LiveSummaryStore.saveEditedSummary(this, String.valueOf(summaryText.getText()));
    }

    private void requestManualSummaryRefresh() {
        persistSummaryText();
        Intent intent = new Intent(this, VoiceListenerService.class);
        intent.setAction(VoiceListenerService.ACTION_REFRESH_SUMMARY);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "要約実行を要求しました", Toast.LENGTH_SHORT).show();
        updateSummaryDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummaryDisplay();
        if (uiHandler == null) {
            uiHandler = new Handler(Looper.getMainLooper());
        }
        if (periodicUpdateRunnable == null) {
            periodicUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateSummaryDisplay();
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
        persistSummaryText();
        if (uiHandler != null && periodicUpdateRunnable != null) {
            uiHandler.removeCallbacks(periodicUpdateRunnable);
        }
    }
}
