package com.micklab.voicelistener;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OllamaClientTest {
    @Test
    public void buildSummaryPrompt_updatesSummaryFromPreviousSummaryAndDiffLogs() {
        OllamaClient client = new OllamaClient();
        LiveSummaryState previousState = new LiveSummaryState(
            "既存の要約",
            Arrays.asList("決定A"),
            Arrays.asList("TODO1"),
            "要約更新済み",
            100L
        );

        String prompt = client.buildSummaryPrompt("新しい発話ログ", previousState);

        assertEquals(
            "あなたは会議要約アシスタントです。\n"
                + "次の会議発話ログを読み、更新された要約を作成してください。\n"
                + "要点を短く箇条書きで示し、重要な決定事項やToDoを含めてください。\n"
                + "\n"
                + "出力はJSONオブジェクトのみで返してください。\n"
                + "形式: {\"summary\":\"更新後の全文要約\"}\n"
                + "\n"
                + "\n"
                + "既存の要約\n"
                + "新しい発話ログ",
            prompt
        );
    }

    @Test
    public void buildSummaryPrompt_replacesEditableTemplatePlaceholders() {
        OllamaClient client = new OllamaClient();
        String prompt = client.buildSummaryPrompt(
            "新しい発話ログ",
            new LiveSummaryState("", Collections.emptyList(), Collections.emptyList(), "要約待機中", 0L),
            "前回:\n"
                + OllamaClient.SUMMARY_PROMPT_PLACEHOLDER_PREVIOUS_SUMMARY
                + "\n差分:\n"
                + OllamaClient.SUMMARY_PROMPT_PLACEHOLDER_NEW_LOGS
        );

        assertEquals("前回:\n（なし）\n差分:\n新しい発話ログ", prompt);
    }

    @Test
    public void parseSummaryResponse_rejectsUnexpectedJsonFormat() {
        OllamaClient client = new OllamaClient();

        try {
            client.parseSummaryResponse("prompt", "{\"response\":\"{}\"}");
            fail("expected SummaryGenerationException");
        } catch (OllamaClient.SummaryGenerationException e) {
            assertTrue(e.isRetryable());
            assertEquals("要約レスポンスが想定JSON形式ではありません", e.getMessage());
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }

    @Test
    public void generateSummaryFromPrompt_retriesUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OllamaClient client = new OllamaClient() {
            @Override
            void logRetryFailure(int attempt, SummaryGenerationException cause) {
            }

            @Override
            SummaryGenerationResult requestSummaryAttempt(String baseUrl, String model, String safePrompt) throws java.io.IOException {
                if (attempts.incrementAndGet() < 3) {
                    throw new SummaryGenerationException("要約レスポンスのJSON解析に失敗しました", safePrompt, "{}", true);
                }
                return new SummaryGenerationResult(
                    safePrompt,
                    "{\"response\":\"{\\\"summary\\\":\\\"# 要約\\\"}\"}",
                    new LiveSummaryState("# 要約", Collections.emptyList(), Collections.emptyList(), "", 0L)
                );
            }
        };

        OllamaClient.SummaryGenerationResult result = client.generateSummaryFromPrompt("http://127.0.0.1:11434", "default", "prompt");

        assertEquals(3, attempts.get());
        assertEquals("# 要約", result.getState().getSummary());
    }

    @Test
    public void generateSummaryFromPrompt_stopsAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger();
        OllamaClient client = new OllamaClient() {
            @Override
            void logRetryFailure(int attempt, SummaryGenerationException cause) {
            }

            @Override
            SummaryGenerationResult requestSummaryAttempt(String baseUrl, String model, String safePrompt) throws java.io.IOException {
                attempts.incrementAndGet();
                throw new SummaryGenerationException("HTTP 500", safePrompt, "server-error", true);
            }
        };

        try {
            client.generateSummaryFromPrompt("http://127.0.0.1:11434", "default", "prompt");
            fail("expected SummaryGenerationException");
        } catch (OllamaClient.SummaryGenerationException e) {
            assertEquals(OllamaClient.MAX_SUMMARY_RETRIES + 1, attempts.get());
            assertTrue(e.getMessage().contains("10回のリトライ後も失敗しました"));
        } catch (Exception e) {
            fail("unexpected exception: " + e);
        }
    }
}
