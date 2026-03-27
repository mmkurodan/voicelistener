package com.micklab.voicelistener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WhisperModelManagerTest {

    @Test
    public void normalizeModelUrl_usesDefaultForBlankInput() {
        assertEquals(
            WhisperModelManager.DEFAULT_MODEL_URL,
            WhisperModelManager.normalizeModelUrl("   ")
        );
    }

    @Test
    public void normalizeModelName_returnsNullForBlankInput() {
        assertNull(WhisperModelManager.normalizeModelName("  "));
    }

    @Test
    public void deriveModelNameFromUrl_keepsGgufFileName() {
        assertEquals(
            "whisper-medium-q8_0.gguf",
            WhisperModelManager.deriveModelNameFromUrl(WhisperModelManager.DEFAULT_MODEL_URL)
        );
    }

    @Test
    public void describeQuantization_readsQuantizedModelName() {
        assertEquals("q8_0", WhisperModelManager.describeQuantization("whisper-medium-q8_0.gguf"));
    }

    @Test
    public void deriveModelNameFromUrl_sanitizesUnsafeCharacters() {
        assertEquals(
            "my_model_v2.gguf",
            WhisperModelManager.deriveModelNameFromUrl("https://example.com/models/my%20model%2Fv2.gguf")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void deriveModelNameFromUrl_rejectsNonGgufFiles() {
        WhisperModelManager.deriveModelNameFromUrl("https://example.com/models/whisper-large-v3.bin");
    }
}
