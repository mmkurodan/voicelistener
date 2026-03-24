package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class VoiceActivityDetectorTest {
    @Test
    public void processFrame_keepsPreSpeechFramesInReturnedSegment() {
        VoiceActivityDetector detector = new VoiceActivityDetector(100.0, 2, 2);

        assertNull(detector.processFrame(frame(10, 10)));
        assertNull(detector.processFrame(frame(200, 200)));
        assertNull(detector.processFrame(frame(220, 220)));
        assertNull(detector.processFrame(frame(10, 10)));

        short[] segment = detector.processFrame(frame(10, 10));

        assertNotNull(segment);
        assertEquals(10, segment.length);
        assertEquals(10, segment[0]);
        assertEquals(200, segment[2]);
    }

    @Test
    public void processFrame_discardsTooShortSpeech() {
        VoiceActivityDetector detector = new VoiceActivityDetector(100.0, 1, 2);

        assertNull(detector.processFrame(frame(220, 220)));
        assertNull(detector.processFrame(frame(10, 10)));
        assertNull(detector.flush());
    }

    private short[] frame(int first, int second) {
        return new short[] {(short) first, (short) second};
    }
}
