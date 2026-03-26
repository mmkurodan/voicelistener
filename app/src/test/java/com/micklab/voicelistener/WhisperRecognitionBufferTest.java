package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WhisperRecognitionBufferTest {
    @Test
    public void prepare_returnsCurrentSegmentWhenRetryBufferIsEmpty() {
        WhisperRecognitionBuffer buffer = new WhisperRecognitionBuffer(8);

        assertArrayEquals(new short[] {1, 2, 3}, buffer.prepare(new short[] {1, 2, 3}));
    }

    @Test
    public void retainForRetry_prependsPendingAudioToNextSegment() {
        WhisperRecognitionBuffer buffer = new WhisperRecognitionBuffer(8);

        buffer.retainForRetry(new short[] {1, 2, 3});

        assertTrue(buffer.hasPendingAudio());
        assertArrayEquals(new short[] {1, 2, 3, 4, 5}, buffer.prepare(new short[] {4, 5}));
    }

    @Test
    public void retainForRetry_trimsOldAudioToConfiguredMaximum() {
        WhisperRecognitionBuffer buffer = new WhisperRecognitionBuffer(4);

        buffer.retainForRetry(new short[] {1, 2, 3, 4, 5, 6});

        assertArrayEquals(new short[] {3, 4, 5, 6, 7}, buffer.prepare(new short[] {7}));
    }

    @Test
    public void reset_clearsPendingAudio() {
        WhisperRecognitionBuffer buffer = new WhisperRecognitionBuffer(8);

        buffer.retainForRetry(new short[] {1, 2, 3});
        buffer.reset();

        assertFalse(buffer.hasPendingAudio());
        assertArrayEquals(new short[] {4}, buffer.prepare(new short[] {4}));
    }

    @Test
    public void pendingSampleCount_tracksRetainedAudioLength() {
        WhisperRecognitionBuffer buffer = new WhisperRecognitionBuffer(4);

        buffer.retainForRetry(new short[] {1, 2, 3, 4, 5, 6});

        assertEquals(4, buffer.pendingSampleCount());

        buffer.reset();

        assertEquals(0, buffer.pendingSampleCount());
    }
}
