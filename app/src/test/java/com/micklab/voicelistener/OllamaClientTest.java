package com.micklab.voicelistener;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

        assertTrue(prompt.contains("前回要約と新規認識ログ差分を使って、会議全体の要約を更新してください。"));
        assertTrue(prompt.contains("決定事項やToDoは配列で個別に返さず、重要であればsummary本文の中で自然に触れてください。"));
        assertTrue(prompt.contains("{\"summary\":\"160文字以内\"}"));
        assertTrue(prompt.contains("前回の要約:\n既存の要約"));
        assertTrue(prompt.contains("新規認識ログ差分:\n新しい発話ログ"));
        assertFalse(prompt.contains("既存の決定事項:"));
        assertFalse(prompt.contains("既存のToDo:"));
    }
}
