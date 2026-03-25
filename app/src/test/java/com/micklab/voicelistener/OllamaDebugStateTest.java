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
            " [12:00:00] プロンプト\n prompt body ",
            " 応答受信 ",
            4321L
        );

        OllamaDebugState restored = OllamaDebugState.fromJsonString(original.toJsonString());

        assertEquals("http://127.0.0.1:11434/", restored.getBaseUrl());
        assertEquals("llama3.1", restored.getModel());
        assertEquals("[12:00:00] プロンプト\n prompt body", restored.getHistory());
        assertEquals("応答受信", restored.getStatus());
        assertEquals(4321L, restored.getUpdatedAtMillis());
    }

    @Test
    public void fromLegacyJson_migratesPromptAndResponseIntoHistory() {
        String legacyJson = "{\"baseUrl\":\"http://127.0.0.1:11434/\",\"model\":\"llama3.1\",\"prompt\":\"prompt body\",\"response\":\"response body\",\"status\":\"応答受信\",\"updatedAtMillis\":4321}";

        OllamaDebugState restored = OllamaDebugState.fromJsonString(legacyJson);

        assertTrue(restored.getHistory().contains("プロンプト"));
        assertTrue(restored.getHistory().contains("prompt body"));
        assertTrue(restored.getHistory().contains("レスポンス"));
        assertTrue(restored.getHistory().contains("response body"));
    }

    @Test
    public void fromInvalidJson_returnsEmptyState() {
        OllamaDebugState state = OllamaDebugState.fromJsonString("not-json");

        assertTrue(state.getHistory().isEmpty());
        assertEquals("Ollama待機中", state.getStatus());
    }
}
