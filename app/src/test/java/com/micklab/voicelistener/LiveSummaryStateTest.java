package com.micklab.voicelistener;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LiveSummaryStateTest {
    @Test
    public void toJsonAndBack_preservesNormalizedContent() {
        LiveSummaryState original = new LiveSummaryState(
            " 進捗要約 ",
            Arrays.asList("決定A", "決定A", "決定B"),
            Arrays.asList("TODO1", "TODO2", "TODO1"),
            " 更新済み ",
            1234L
        );

        LiveSummaryState restored = LiveSummaryState.fromJsonString(original.toJsonString());

        assertEquals("進捗要約", restored.getSummary());
        assertEquals(Arrays.asList("決定A", "決定B"), restored.getDecisions());
        assertEquals(Arrays.asList("TODO1", "TODO2"), restored.getTodos());
        assertEquals("更新済み", restored.getStatus());
        assertEquals(1234L, restored.getUpdatedAtMillis());
    }

    @Test
    public void fromInvalidJson_returnsEmptyState() {
        LiveSummaryState state = LiveSummaryState.fromJsonString("not-json");

        assertTrue(state.getSummary().isEmpty());
        assertTrue(state.getDecisions().isEmpty());
        assertTrue(state.getTodos().isEmpty());
        assertEquals("要約待機中", state.getStatus());
    }
}
