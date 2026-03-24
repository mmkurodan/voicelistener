package com.micklab.voicelistener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class LiveSummaryState {
    private final String summary;
    private final List<String> decisions;
    private final List<String> todos;
    private final String status;
    private final long updatedAtMillis;

    public LiveSummaryState(String summary, List<String> decisions, List<String> todos, String status, long updatedAtMillis) {
        this.summary = sanitizeText(summary);
        this.decisions = Collections.unmodifiableList(sanitizeList(decisions));
        this.todos = Collections.unmodifiableList(sanitizeList(todos));
        this.status = sanitizeText(status);
        this.updatedAtMillis = Math.max(updatedAtMillis, 0L);
    }

    public static LiveSummaryState empty() {
        return new LiveSummaryState("", new ArrayList<>(), new ArrayList<>(), "要約待機中", 0L);
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getDecisions() {
        return decisions;
    }

    public List<String> getTodos() {
        return todos;
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
            object.put("summary", summary);
            object.put("decisions", toJsonArray(decisions));
            object.put("todos", toJsonArray(todos));
            object.put("status", status);
            object.put("updatedAtMillis", updatedAtMillis);
        } catch (JSONException e) {
            throw new IllegalStateException("LiveSummaryState serialization failed", e);
        }
        return object;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }

    public static LiveSummaryState fromJsonString(String json) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        try {
            JSONObject object = new JSONObject(json);
            return new LiveSummaryState(
                object.optString("summary", ""),
                toStringList(object.optJSONArray("decisions")),
                toStringList(object.optJSONArray("todos")),
                object.optString("status", "要約待機中"),
                object.optLong("updatedAtMillis", 0L)
            );
        } catch (JSONException e) {
            return empty();
        }
    }

    private static JSONArray toJsonArray(List<String> items) {
        JSONArray array = new JSONArray();
        if (items == null) {
            return array;
        }
        for (String item : items) {
            array.put(item);
        }
        return array;
    }

    private static ArrayList<String> toStringList(JSONArray array) {
        ArrayList<String> items = new ArrayList<>();
        if (array == null) {
            return items;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = sanitizeText(array.optString(i, ""));
            if (!value.isEmpty()) {
                items.add(value);
            }
        }
        return items;
    }

    private static ArrayList<String> sanitizeList(List<String> source) {
        ArrayList<String> items = new ArrayList<>();
        if (source == null) {
            return items;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : source) {
            String normalized = sanitizeText(value);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        items.addAll(unique);
        return items;
    }

    private static String sanitizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
