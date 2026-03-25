package com.micklab.voicelistener;

public final class SummaryTextFormatter {
    private SummaryTextFormatter() {
    }

    public static String formatSummary(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        return normalized.isEmpty() ? "要約はまだありません" : normalized;
    }

    public static String buildCopyText(LiveSummaryState state) {
        LiveSummaryState safeState = state == null ? LiveSummaryState.empty() : state;
        return "要約\n" + formatSummary(safeState.getSummary());
    }
}
