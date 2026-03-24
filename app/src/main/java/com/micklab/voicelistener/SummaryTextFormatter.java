package com.micklab.voicelistener;

import java.util.List;

public final class SummaryTextFormatter {
    private SummaryTextFormatter() {
    }

    public static String formatSummary(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        return normalized.isEmpty() ? "要約はまだありません" : normalized;
    }

    public static String formatList(List<String> values, String emptyMessage) {
        if (values == null || values.isEmpty()) {
            return emptyMessage;
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("・").append(value);
        }
        return builder.toString();
    }

    public static String buildCopyText(LiveSummaryState state) {
        LiveSummaryState safeState = state == null ? LiveSummaryState.empty() : state;
        StringBuilder builder = new StringBuilder();
        builder.append("要約\n")
            .append(formatSummary(safeState.getSummary()))
            .append("\n\n決定事項\n")
            .append(formatList(safeState.getDecisions(), "決定事項はまだありません"))
            .append("\n\nToDo\n")
            .append(formatList(safeState.getTodos(), "ToDoはまだありません"));
        return builder.toString();
    }
}
