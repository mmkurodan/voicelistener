package com.micklab.voicelistener;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class OllamaClient {
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    static final String LEGACY_DEFAULT_BASE_URL = "http://10.0.2.2:11434";
    private static final String TAG = "OllamaClient";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int MAX_LOG_CONTEXT_CHARS = 6000;

    public static final class SummaryGenerationResult {
        private final String prompt;
        private final String rawResponse;
        private final LiveSummaryState state;

        SummaryGenerationResult(String prompt, String rawResponse, LiveSummaryState state) {
            this.prompt = prompt == null ? "" : prompt;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
            this.state = state == null ? LiveSummaryState.empty() : state;
        }

        public String getPrompt() {
            return prompt;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public LiveSummaryState getState() {
            return state;
        }
    }

    public static final class SummaryGenerationException extends IOException {
        private final String prompt;
        private final String rawResponse;

        SummaryGenerationException(String message, String prompt, String rawResponse, Throwable cause) {
            super(message, cause);
            this.prompt = prompt == null ? "" : prompt;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
        }

        SummaryGenerationException(String message, String prompt, String rawResponse) {
            this(message, prompt, rawResponse, null);
        }

        public String getPrompt() {
            return prompt;
        }

        public String getRawResponse() {
            return rawResponse;
        }
    }

    public List<String> listModelNames(String baseUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(normalizeBaseUrl(baseUrl) + "/api/tags");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.connect();
            ensureSuccess(connection);

            JSONObject object = new JSONObject(readResponseBody(connection));
            JSONArray models = object.optJSONArray("models");
            ArrayList<String> names = new ArrayList<>();
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject model = models.optJSONObject(i);
                    if (model == null) {
                        continue;
                    }
                    String name = model.optString("name", "").trim();
                    if (name.isEmpty()) {
                        name = model.optString("model", "").trim();
                    }
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            return mergeDefaultModel(names);
        } catch (JSONException e) {
            throw new IOException("モデル一覧のJSON解析に失敗しました", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public SummaryGenerationResult generateSummary(String baseUrl, String model, String recentRecognitionLogs, LiveSummaryState previousState) throws IOException {
        if (recentRecognitionLogs == null || recentRecognitionLogs.trim().isEmpty()) {
            LiveSummaryState safeState = previousState == null ? LiveSummaryState.empty() : previousState;
            return new SummaryGenerationResult("", "", safeState);
        }
        return generateSummaryFromPrompt(baseUrl, model, buildSummaryPrompt(recentRecognitionLogs, previousState));
    }

    SummaryGenerationResult generateSummaryFromPrompt(String baseUrl, String model, String prompt) throws IOException {
        String safePrompt = prompt == null ? "" : prompt.trim();
        if (safePrompt.isEmpty()) {
            throw new SummaryGenerationException("要約プロンプトが空です", safePrompt, "");
        }

        HttpURLConnection connection = null;
        String rawResponse = "";
        try {
            URL url = new URL(normalizeBaseUrl(baseUrl) + "/api/generate");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", normalizeModel(model));
            requestBody.put("stream", false);
            requestBody.put("prompt", safePrompt);

            try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                out.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            int code = connection.getResponseCode();
            rawResponse = code / 100 == 2
                ? readResponseBody(connection)
                : readStream(connection.getErrorStream());
            if (code / 100 != 2) {
                throw new SummaryGenerationException("HTTP " + code + ": " + rawResponse, safePrompt, rawResponse);
            }

            JSONObject response = new JSONObject(rawResponse);
            String body = response.optString("response", "").trim();
            JSONObject parsed = new JSONObject(extractJsonObject(body));
            return new SummaryGenerationResult(
                safePrompt,
                rawResponse,
                new LiveSummaryState(
                    parsed.optString("summary", ""),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    "",
                    0L
                )
            );
        } catch (JSONException e) {
            throw new SummaryGenerationException("要約レスポンスのJSON解析に失敗しました", safePrompt, rawResponse, e);
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new SummaryGenerationException(message, safePrompt, rawResponse, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void ensureSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code / 100 == 2) {
            return;
        }
        String errorBody = readStream(connection.getErrorStream());
        throw new IOException("HTTP " + code + ": " + errorBody);
    }

    private String readResponseBody(HttpURLConnection connection) throws IOException {
        return readStream(new BufferedInputStream(connection.getInputStream()));
    }

    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString().trim();
        }
    }

    String buildSummaryPrompt(String recentRecognitionLogs, LiveSummaryState previousState) {
        String clippedLogs = recentRecognitionLogs.trim();
        if (clippedLogs.length() > MAX_LOG_CONTEXT_CHARS) {
            clippedLogs = clippedLogs.substring(clippedLogs.length() - MAX_LOG_CONTEXT_CHARS);
        }

        LiveSummaryState safePrevious = previousState == null ? LiveSummaryState.empty() : previousState;
        return "あなたは会議要約更新アシスタントです。\n"
            + "以下の前回要約と新規認識ログ差分を使って、会議全体の要約を更新してください。\n"
            + "決定事項やToDoは配列で個別に返さず、重要であればsummary本文の中で自然に触れてください。\n"
            + "必ずJSONオブジェクトのみを返してください。説明文は不要です。\n"
            + "形式:\n"
            + "{\"summary\":\"160文字以内\"}\n"
            + "ルール:\n"
            + "- 事実のみを書く。\n"
            + "- summaryは前回要約を引き継ぎつつ、新規ログ差分を反映した最新の全体要約にする。\n"
            + "- 決定事項・未完了ToDo・懸念点などの重要事項はsummary本文の中で簡潔に触れる。\n"
            + "- 前回要約を削除・修正するのは、新規ログにより明確な矛盾・撤回・完了が確認できる場合のみにする。\n"
            + "- 新規ログに根拠がない内容は追加しない。\n"
            + "- 固有名詞や数値は認識ログに現れた内容を優先する。\n"
            + "- 情報が不足する場合は空文字にする。\n"
            + "前回の要約:\n"
            + formatPromptText(safePrevious.getSummary())
            + "\n新規認識ログ差分:\n"
            + clippedLogs;
    }

    private ArrayList<String> toStringList(JSONArray array) {
        ArrayList<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String extractJsonObject(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("要約レスポンスが空です");
        }
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                if (start < 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return value.substring(start, i + 1);
                }
            }
        }
        Log.w(TAG, "JSON object not found in response: " + value);
        throw new IOException("JSONオブジェクトを抽出できませんでした");
    }

    private List<String> mergeDefaultModel(List<String> models) {
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

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            trimmed = DEFAULT_BASE_URL;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String normalizeModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        return trimmed.isEmpty() ? "default" : trimmed;
    }

    private String formatPromptText(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "（なし）" : normalized;
    }
}
