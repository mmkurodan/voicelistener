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

public class SummaryActivity extends Activity {
    private static final int UPDATE_INTERVAL_MS = 2000;

    private final SimpleDateFormat summaryTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
    private Handler uiHandler;
    private Runnable periodicUpdateRunnable;
    private TextView summaryStatusText;
    private EditText summaryText;

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
        summaryStatusText.setTextSize(15);
        summaryStatusText.setPadding(0, 0, 0, 16);
        root.addView(summaryStatusText);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, 0, 0, 16);
        root.addView(actionRow);

        Button closeButton = createActionButton("閉じる");
        closeButton.setOnClickListener(v -> finish());
        actionRow.addView(closeButton);

        Button copyAllButton = createActionButton("全文コピー");
        copyAllButton.setOnClickListener(v -> copyAllSummaryText());
        actionRow.addView(copyAllButton);

        Button clearButton = createActionButton("クリア");
        clearButton.setOnClickListener(v -> {
            LiveSummaryStore.clearSummarySession(this);
            updateSummaryDisplay();
            Toast.makeText(this, "要約をクリアしました", Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(clearButton);

        root.addView(createSectionLabel("要約"));
        summaryText = createReadOnlyTextArea();
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

    private void updateSummaryDisplay() {
        if (summaryStatusText == null || summaryText == null) {
            return;
        }
        LiveSummaryState state = LiveSummaryStore.loadSummaryState(this);
        summaryText.setText(SummaryTextFormatter.formatSummary(state.getSummary()));

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

    private void copyAllSummaryText() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(this, "クリップボードにアクセスできません", Toast.LENGTH_SHORT).show();
            return;
        }
        LiveSummaryState state = LiveSummaryStore.loadSummaryState(this);
        clipboardManager.setPrimaryClip(ClipData.newPlainText("summary", SummaryTextFormatter.buildCopyText(state)));
        Toast.makeText(this, "要約をコピーしました", Toast.LENGTH_SHORT).show();
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
        if (uiHandler != null && periodicUpdateRunnable != null) {
            uiHandler.removeCallbacks(periodicUpdateRunnable);
        }
    }
}
