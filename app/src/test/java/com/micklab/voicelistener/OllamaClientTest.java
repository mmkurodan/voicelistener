package com.micklab.voicelistener;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
                + "会議内容の要約をしてください。\n"
                + "決定事項やToDoは配列で個別に返さず、重要であればsummary本文の中で自然に触れてください。\n"
                + "\n"
                + "必ずJSONオブジェクトのみを返してください。説明文は不要です。\n"
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
}
