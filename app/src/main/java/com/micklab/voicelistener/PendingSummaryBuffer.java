package com.micklab.voicelistener;

public final class PendingSummaryBuffer {
    private PendingSummaryBuffer() {
    }

    public static String appendEntry(String current, String entry) {
        String normalizedCurrent = normalizeBlock(current);
        String normalizedEntry = normalizeEntry(entry);
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
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim();
    }

    private static String normalizeEntry(String entry) {
        if (entry == null) {
            return "";
        }
        return entry
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
            .trim();
    }
}
