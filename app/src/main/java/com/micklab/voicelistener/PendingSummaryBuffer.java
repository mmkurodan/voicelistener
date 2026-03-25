package com.micklab.voicelistener;

public final class PendingSummaryBuffer {
    private PendingSummaryBuffer() {
    }

    public static String appendEntry(String current, String entry) {
        String normalizedCurrent = normalizeBlock(current);
        String normalizedEntry = normalizeSummaryEntry(entry);
        if (normalizedEntry.isEmpty()) {
            return normalizedCurrent;
        }
        if (normalizedCurrent.isEmpty()) {
            return normalizedEntry;
        }
        return normalizedCurrent + "\n" + normalizedEntry;
    }

    public static String removeConsumedPrefix(String current, String consumed) {
        String normalizedCurrent = normalizeBlock(current);
        String normalizedConsumed = normalizeBlock(consumed);
        if (normalizedConsumed.isEmpty() || normalizedCurrent.isEmpty()) {
            return normalizedCurrent;
        }
        if (normalizedCurrent.equals(normalizedConsumed)) {
            return "";
        }
        String consumedPrefix = normalizedConsumed + "\n";
        if (normalizedCurrent.startsWith(consumedPrefix)) {
            return normalizeBlock(normalizedCurrent.substring(consumedPrefix.length()));
        }
        if (normalizedCurrent.startsWith(normalizedConsumed)) {
            return normalizeBlock(normalizedCurrent.substring(normalizedConsumed.length()));
        }
        return normalizedCurrent;
    }

    public static int length(String value) {
        return normalizeBlock(value).length();
    }

    public static String normalizeBlock(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String normalizedLine = normalizeSummaryEntry(line);
            if (normalizedLine.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(normalizedLine);
        }
        return builder.toString();
    }

    public static String normalizeSummaryEntry(String entry) {
        if (entry == null) {
            return "";
        }
        String normalized = entry
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String token : normalized.split(" ")) {
            String normalizedToken = token.trim();
            if (normalizedToken.isEmpty() || isMeaninglessSoundToken(normalizedToken)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(normalizedToken);
        }
        return builder.toString().trim();
    }

    private static boolean isMeaninglessSoundToken(String token) {
        String normalized = token == null ? "" : token
            .replace("〜", "")
            .replace("ー", "")
            .replace("…", "")
            .replace("・", "")
            .replaceAll("[、。,.!！?？]", "")
            .trim();
        if (normalized.isEmpty()) {
            return true;
        }
        return normalized.matches("(ん+|あ+|え+|う+ん*|えっと|えと|えーと|あの+)");
    }
}
