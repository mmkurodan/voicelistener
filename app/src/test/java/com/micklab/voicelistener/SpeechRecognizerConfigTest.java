package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechRecognizerConfigTest {
    @Test
    public void defaultWhisperLanguage_enablesAutoDetection() {
        assertEquals("auto", SpeechRecognizerConfig.defaultWhisperLanguage());
        assertTrue(SpeechRecognizerConfig.isWhisperAutoDetectLanguage(
            SpeechRecognizerConfig.defaultWhisperLanguage()
        ));
    }

    @Test
    public void normalizeWhisperLanguage_trimsAndLowercasesCodes() {
        assertEquals("ja", SpeechRecognizerConfig.normalizeWhisperLanguage(" JA "));
        assertEquals("auto", SpeechRecognizerConfig.normalizeWhisperLanguage(" "));
        assertEquals("auto", SpeechRecognizerConfig.normalizeWhisperLanguage(null));
    }

    @Test
    public void whisperInferenceWindowSamples_usesLongerWindowForAutoDetection() {
        assertEquals(16_000, SpeechRecognizerConfig.whisperInferenceWindowSamples(16_000, "auto"));
        assertEquals(8_000, SpeechRecognizerConfig.whisperInferenceWindowSamples(8_000, null));
        assertEquals(4_096, SpeechRecognizerConfig.whisperInferenceWindowSamples(16_000, "ja"));
        assertFalse(SpeechRecognizerConfig.isWhisperAutoDetectLanguage("ja"));
    }
}
