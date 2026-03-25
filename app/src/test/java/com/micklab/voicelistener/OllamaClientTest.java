package com.micklab.voicelistener;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OllamaClientTest {
    @Test
    public void buildSummaryPrompt_keepsExistingStateUnlessContradicted() {
        OllamaClient client = new OllamaClient();
        LiveSummaryState previousState = new LiveSummaryState(
            "既存の要約",
            Arrays.asList("決定A"),
            Arrays.asList("TODO1"),
            "要約更新済み",
            100L
        );

        String prompt = client.buildSummaryPrompt("新しい発話ログ", previousState);

        assertTrue(prompt.contains("既存の要約・決定事項・ToDoは会議の継続文脈として扱い、新規ログと矛盾しない限り優先して残す。"));
        assertTrue(prompt.contains("既存の要約:\n既存の要約"));
        assertTrue(prompt.contains("既存の決定事項:\n・決定A"));
        assertTrue(prompt.contains("既存のToDo:\n・TODO1"));
        assertTrue(prompt.contains("新規認識ログ差分:\n新しい発話ログ"));
    }
}
