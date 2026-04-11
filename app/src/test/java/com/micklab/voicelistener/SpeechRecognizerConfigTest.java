package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeechRecognizerConfigTest {
    @Test
    public void defaultLanguage_isJapanese() {
        assertEquals("ja", SpeechRecognizerConfig.DEFAULT_LANGUAGE);
    }

    @Test
    public void defaultThreadCount_staysWithinSafeBounds() {
        int threadCount = SpeechRecognizerConfig.defaultThreadCount();

        assertTrue(threadCount >= 2);
        assertTrue(threadCount <= 8);
    }
}
