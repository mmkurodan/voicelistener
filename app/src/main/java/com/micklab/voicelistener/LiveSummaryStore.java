package com.micklab.voicelistener;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

public final class LiveSummaryStore {
    private static final String PREFS_NAME = "VoiceListenerPrefs";
    private static final String PREF_OLLAMA_BASE_URL = "ollama_base_url";
    private static final String PREF_OLLAMA_MODEL = "ollama_model";
    private static final String PREF_OLLAMA_MODELS_JSON = "ollama_models_json";
    private static final String PREF_LIVE_SUMMARY_JSON = "live_summary_json";

    private LiveSummaryStore() {
    }

    public static String getOllamaBaseUrl(Context context) {
        return getPrefs(context).getString(PREF_OLLAMA_BASE_URL, OllamaClient.DEFAULT_BASE_URL);
    }

    public static void setOllamaBaseUrl(Context context, String baseUrl) {
        getPrefs(context).edit()
            .putString(PREF_OLLAMA_BASE_URL, normalizeBaseUrl(baseUrl))
            .apply();
    }

    public static String getOllamaModel(Context context) {
        return normalizeModel(getPrefs(context).getString(PREF_OLLAMA_MODEL, "default"));
    }

    public static void setOllamaModel(Context context, String model) {
        getPrefs(context).edit()
            .putString(PREF_OLLAMA_MODEL, normalizeModel(model))
            .apply();
    }

    public static ArrayList<String> getCachedModelNames(Context context) {
        ArrayList<String> models = new ArrayList<>();
        String raw = getPrefs(context).getString(PREF_OLLAMA_MODELS_JSON, null);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String model = normalizeModel(array.optString(i, ""));
                    if (!model.isEmpty()) {
                        models.add(model);
                    }
                }
            } catch (JSONException ignored) {
                models.clear();
            }
        }
        return mergeDefaultModel(models);
    }

    public static void setCachedModelNames(Context context, List<String> models) {
        JSONArray array = new JSONArray();
        for (String model : mergeDefaultModel(models)) {
            array.put(model);
        }
        getPrefs(context).edit()
            .putString(PREF_OLLAMA_MODELS_JSON, array.toString())
            .apply();
    }

    public static LiveSummaryState loadSummaryState(Context context) {
        return LiveSummaryState.fromJsonString(getPrefs(context).getString(PREF_LIVE_SUMMARY_JSON, null));
    }

    public static void saveSummaryState(Context context, LiveSummaryState state) {
        LiveSummaryState safeState = state == null ? LiveSummaryState.empty() : state;
        getPrefs(context).edit()
            .putString(PREF_LIVE_SUMMARY_JSON, safeState.toJsonString())
            .apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static ArrayList<String> mergeDefaultModel(List<String> models) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add("default");
        if (models != null) {
            for (String model : models) {
                String normalized = normalizeModel(model);
                if (!normalized.isEmpty()) {
                    merged.add(normalized);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        return trimmed.isEmpty() ? OllamaClient.DEFAULT_BASE_URL : trimmed;
    }

    private static String normalizeModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        return trimmed.isEmpty() ? "default" : trimmed;
    }
}
