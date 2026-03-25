package com.micklab.voicelistener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public final class OllamaDebugState {
    private final String baseUrl;
    private final String model;
    private final String history;
    private final String status;
    private final long updatedAtMillis;

    public OllamaDebugState(String baseUrl, String model, String history, String status, long updatedAtMillis) {
        this.baseUrl = sanitizeText(baseUrl);
        this.model = sanitizeText(model);
        this.history = sanitizeMultilineText(history);
        this.status = sanitizeText(status);
        this.updatedAtMillis = Math.max(updatedAtMillis, 0L);
    }

    public static OllamaDebugState empty() {
        return new OllamaDebugState("", "", "", "Ollama待機中", 0L);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getHistory() {
        return history;
    }

    public String getStatus() {
        return status;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public JSONObject toJsonObject() {
        JSONObject object = new JSONObject();
        try {
            object.put("baseUrl", baseUrl);
            object.put("model", model);
            object.put("history", history);
            object.put("status", status);
            object.put("updatedAtMillis", updatedAtMillis);
        } catch (JSONException e) {
            throw new IllegalStateException("OllamaDebugState serialization failed", e);
        }
        return object;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }

    public static OllamaDebugState fromJsonString(String json) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        try {
            JSONObject object = new JSONObject(json);
            long updatedAtMillis = object.optLong("updatedAtMillis", 0L);
            String history = sanitizeMultilineText(object.optString("history", ""));
            if (history.isEmpty()) {
                history = buildLegacyHistory(
                    object.optString("prompt", ""),
                    object.optString("response", ""),
                    updatedAtMillis
                );
            }
            return new OllamaDebugState(
                object.optString("baseUrl", ""),
                object.optString("model", ""),
                history,
                object.optString("status", "Ollama待機中"),
                updatedAtMillis
            );
        } catch (JSONException e) {
            return empty();
        }
    }

    public OllamaDebugState appendHistoryEntry(String nextBaseUrl, String nextModel, String label, String content, String nextStatus, long entryAtMillis) {
        return new OllamaDebugState(
            nextBaseUrl,
            nextModel,
            appendHistory(history, label, content, entryAtMillis),
            nextStatus,
            entryAtMillis
        );
    }

    private static String buildLegacyHistory(String prompt, String response, long updatedAtMillis) {
        String history = appendHistory("", "プロンプト", prompt, updatedAtMillis);
        return appendHistory(history, "レスポンス", response, updatedAtMillis);
    }

    private static String appendHistory(String currentHistory, String label, String content, long entryAtMillis) {
        String normalizedHistory = sanitizeMultilineText(currentHistory);
        String normalizedLabel = sanitizeText(label);
        String normalizedContent = sanitizeMultilineText(content);
        if (normalizedLabel.isEmpty() || normalizedContent.isEmpty()) {
            return normalizedHistory;
        }
        StringBuilder builder = new StringBuilder();
        if (!normalizedHistory.isEmpty()) {
            builder.append(normalizedHistory).append("\n\n");
        }
        builder.append('[')
            .append(formatTimestamp(entryAtMillis))
            .append("] ")
            .append(normalizedLabel)
            .append('\n')
            .append(normalizedContent);
        return builder.toString();
    }

    private static String formatTimestamp(long updatedAtMillis) {
        if (updatedAtMillis <= 0L) {
            return "--:--:--";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date(updatedAtMillis));
    }

    private static String sanitizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeMultilineText(String value) {
        return value == null ? "" : value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim();
    }
}
