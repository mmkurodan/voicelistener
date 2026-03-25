package com.micklab.voicelistener;

import org.json.JSONException;
import org.json.JSONObject;

public final class OllamaDebugState {
    private final String baseUrl;
    private final String model;
    private final String prompt;
    private final String response;
    private final String status;
    private final long updatedAtMillis;

    public OllamaDebugState(String baseUrl, String model, String prompt, String response, String status, long updatedAtMillis) {
        this.baseUrl = sanitizeText(baseUrl);
        this.model = sanitizeText(model);
        this.prompt = sanitizeText(prompt);
        this.response = sanitizeText(response);
        this.status = sanitizeText(status);
        this.updatedAtMillis = Math.max(updatedAtMillis, 0L);
    }

    public static OllamaDebugState empty() {
        return new OllamaDebugState("", "", "", "", "Ollama待機中", 0L);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getResponse() {
        return response;
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
            object.put("prompt", prompt);
            object.put("response", response);
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
            return new OllamaDebugState(
                object.optString("baseUrl", ""),
                object.optString("model", ""),
                object.optString("prompt", ""),
                object.optString("response", ""),
                object.optString("status", "Ollama待機中"),
                object.optLong("updatedAtMillis", 0L)
            );
        } catch (JSONException e) {
            return empty();
        }
    }

    private static String sanitizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
