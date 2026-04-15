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
    static final int MAX_SUMMARY_RETRIES = 10;
    private static final int MAX_SUMMARY_ATTEMPTS = MAX_SUMMARY_RETRIES + 1;
    public static final String SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY = "{{previous_summary}}";
    public static final String SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS = "{{new_recognition_logs}}";
    static final String LEGACY_DEFAULT_SUMMARY_PROMPT_TEMPLATE = "あなたは会議要約アシスタントです。\n"
        + "会議内容の要約をしてください。\n"
        + "決定事項やToDoは配列で個別に返さず、重要であればsummary本文の中で自然に触れてください。\n"
        + "\n"
        + "必ずJSONオブジェクトのみを返してください。説明文は不要です。\n"
        + "形式: {\"summary\":\"更新後の全文要約\"}\n"
        + "\n"
        + "\n"
        + SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY
        + "\n"
        + SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS;
    public static final String DEFAULT_SUMMARY_PROMPT_TEMPLATE = "あなたは会議要約アシスタントです。\n"
        + "会議内容を日本語のMarkdownで要約してください。\n"
        + "\n"
        + "## 要約の形式\n"
        + "- 見出しや箇条書きを使って、重要事項がすぐ把握できる構成にする\n"
        + "- 決定事項やToDoは配列で個別に返さず、重要であれば summary 本文の見出しや箇条書きの中で自然に触れる\n"
        + "\n"
        + "## 出力仕様\n"
        + "- 必ず JSON オブジェクトのみを返す\n"
        + "- 説明文やコードフェンスは不要\n"
        + "- 形式: {\"summary\":\"Markdown形式の更新後全文要約\"}\n"
        + "\n"
        + "## 入力プレースホルダ\n"
        + SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY
        + "\n"
        + SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS;

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
        private final boolean retryable;

        SummaryGenerationException(String message, String prompt, String rawResponse, boolean retryable, Throwable cause) {
            super(message, cause);
            this.prompt = prompt == null ? "" : prompt;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
            this.retryable = retryable;
        }

        SummaryGenerationException(String message, String prompt, String rawResponse, boolean retryable) {
            this(message, prompt, rawResponse, retryable, null);
        }

        public String getPrompt() {
            return prompt;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public boolean isRetryable() {
            return retryable;
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
            throw new SummaryGenerationException("要約プロンプトが空です", safePrompt, "", false);
        }

        for (int attempt = 1; attempt <= MAX_SUMMARY_ATTEMPTS; attempt++) {
            try {
                return requestSummaryAttempt(baseUrl, model, safePrompt);
            } catch (SummaryGenerationException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                if (attempt >= MAX_SUMMARY_ATTEMPTS) {
                    throw exhaustedRetries(e);
                }
                logRetryFailure(attempt, e);
            }
        }
        throw new SummaryGenerationException("要約生成に失敗しました", safePrompt, "", true);
    }

    SummaryGenerationResult requestSummaryAttempt(String baseUrl, String model, String safePrompt) throws IOException {
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
                throw new SummaryGenerationException("HTTP " + code + ": " + rawResponse, safePrompt, rawResponse, true);
            }

            return parseSummaryResponse(safePrompt, rawResponse);
        } catch (JSONException e) {
            throw new SummaryGenerationException("要約リクエストのJSON生成に失敗しました", safePrompt, rawResponse, false, e);
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new SummaryGenerationException(message, safePrompt, rawResponse, true, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    SummaryGenerationResult parseSummaryResponse(String prompt, String rawResponse) throws IOException {
        try {
            JSONObject response = new JSONObject(rawResponse);
            Object responseBody = response.opt("response");
            if (!(responseBody instanceof String)) {
                throw new SummaryGenerationException("要約レスポンスが想定JSON形式ではありません", prompt, rawResponse, true);
            }

            JSONObject parsed = new JSONObject(extractJsonObject(((String) responseBody).trim()));
            Object summaryValue = parsed.opt("summary");
            if (!(summaryValue instanceof String)) {
                throw new SummaryGenerationException("要約レスポンスが想定JSON形式ではありません", prompt, rawResponse, true);
            }

            return new SummaryGenerationResult(
                prompt,
                rawResponse,
                new LiveSummaryState(
                    ((String) summaryValue).trim(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    "",
                    0L
                )
            );
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (JSONException e) {
            throw new SummaryGenerationException("要約レスポンスのJSON解析に失敗しました", prompt, rawResponse, true, e);
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new SummaryGenerationException(message, prompt, rawResponse, true, e);
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
        return buildSummaryPrompt(recentRecognitionLogs, previousState, null);
    }

    String buildSummaryPrompt(String recentRecognitionLogs, LiveSummaryState previousState, String promptTemplate) {
        String clippedLogs = recentRecognitionLogs.trim();
        if (clippedLogs.length() > MAX_LOG_CONTEXT_CHARS) {
            clippedLogs = clippedLogs.substring(clippedLogs.length() - MAX_LOG_CONTEXT_CHARS);
        }

        LiveSummaryState safePrevious = previousState == null ? LiveSummaryState.empty() : previousState;
        return resolveSummaryPromptTemplate(promptTemplate)
            .replace(SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY, formatPromptText(safePrevious.getSummary()))
            .replace(SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS, clippedLogs);
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

    void logRetryFailure(int attempt, SummaryGenerationException cause) {
        Log.w(TAG, "Summary generation failed on attempt " + attempt + "/" + MAX_SUMMARY_ATTEMPTS + ", retrying", cause);
    }

    private SummaryGenerationException exhaustedRetries(SummaryGenerationException cause) {
        return new SummaryGenerationException(
            "要約更新が" + MAX_SUMMARY_RETRIES + "回のリトライ後も失敗しました: " + cause.getMessage(),
            cause.getPrompt(),
            cause.getRawResponse(),
            false,
            cause
        );
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

    private String resolveSummaryPromptTemplate(String promptTemplate) {
        return promptTemplate == null
            ? DEFAULT_SUMMARY_PROMPT_TEMPLATE
            : promptTemplate.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String formatPromptText(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "（なし）" : normalized;
    }
}
