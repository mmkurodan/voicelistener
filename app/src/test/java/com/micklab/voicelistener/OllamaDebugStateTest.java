package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OllamaDebugStateTest {
    @Test
    public void toJsonAndBack_preservesNormalizedContent() {
        OllamaDebugState original = new OllamaDebugState(
            " http://127.0.0.1:11434/ ",
            " llama3.1 ",
            " prompt body ",
            " response body ",
            " 応答受信 ",
            4321L
        );

        OllamaDebugState restored = OllamaDebugState.fromJsonString(original.toJsonString());

        assertEquals("http://127.0.0.1:11434/", restored.getBaseUrl());
        assertEquals("llama3.1", restored.getModel());
        assertEquals("prompt body", restored.getPrompt());
        assertEquals("response body", restored.getResponse());
        assertEquals("応答受信", restored.getStatus());
        assertEquals(4321L, restored.getUpdatedAtMillis());
    }

    @Test
    public void fromInvalidJson_returnsEmptyState() {
        OllamaDebugState state = OllamaDebugState.fromJsonString("not-json");

        assertTrue(state.getPrompt().isEmpty());
        assertTrue(state.getResponse().isEmpty());
        assertEquals("Ollama待機中", state.getStatus());
    }
}
