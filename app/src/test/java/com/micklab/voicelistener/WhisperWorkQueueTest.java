package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WhisperWorkQueueTest {
    @Test
    public void offer_schedulesSingleWorkerUntilPendingAudioIsDrained() {
        WhisperWorkQueue queue = new WhisperWorkQueue(4, 8);

        assertTrue(queue.offer(new short[] {1, 2}, false, "stream.frame", 0));
        assertFalse(queue.offer(new short[] {3, 4}, false, "stream.frame", 0));

        WhisperWorkQueue.WorkItem first = queue.pollNextWork();
        assertNotNull(first);
        assertArrayEquals(new short[] {1, 2, 3, 4}, first.getAudioBuffer());
        assertFalse(first.shouldFlushAfter());

        assertNull(queue.pollNextWork());
        assertTrue(queue.offer(new short[] {5}, false, "stream.frame", 0));
    }

    @Test
    public void pollNextWork_defersFlushUntilBufferedAudioFitsSingleBatch() {
        WhisperWorkQueue queue = new WhisperWorkQueue(4, 12);

        assertTrue(queue.offer(new short[] {1, 2, 3, 4, 5, 6}, false, "stream.frame", 0));
        assertFalse(queue.offer(null, true, "vad.segment", 6));

        WhisperWorkQueue.WorkItem first = queue.pollNextWork();
        assertNotNull(first);
        assertArrayEquals(new short[] {1, 2, 3, 4}, first.getAudioBuffer());
        assertFalse(first.shouldFlushAfter());
        assertEquals("stream.frame", first.getTriggerReason());

        WhisperWorkQueue.WorkItem second = queue.pollNextWork();
        assertNotNull(second);
        assertArrayEquals(new short[] {5, 6}, second.getAudioBuffer());
        assertTrue(second.shouldFlushAfter());
        assertEquals("vad.segment", second.getTriggerReason());
        assertEquals(6, second.getRelatedSamples());

        assertNull(queue.pollNextWork());
    }

    @Test
    public void pollNextWork_dropsOldestSamplesWhenPendingAudioOverflows() {
        WhisperWorkQueue queue = new WhisperWorkQueue(4, 6);

        assertTrue(queue.offer(new short[] {1, 2, 3, 4}, false, "stream.frame", 0));
        assertFalse(queue.offer(new short[] {5, 6, 7, 8}, false, "stream.frame", 0));

        WhisperWorkQueue.WorkItem first = queue.pollNextWork();
        assertNotNull(first);
        assertArrayEquals(new short[] {3, 4, 5, 6}, first.getAudioBuffer());
        assertEquals(2, first.getDroppedSampleCount());
        assertEquals(2, first.getPendingSampleCountAfterDrain());

        WhisperWorkQueue.WorkItem second = queue.pollNextWork();
        assertNotNull(second);
        assertArrayEquals(new short[] {7, 8}, second.getAudioBuffer());
        assertEquals(0, second.getDroppedSampleCount());

        assertNull(queue.pollNextWork());
    }

    @Test
    public void pollNextWork_returnsFlushOnlyWorkWhenNoAudioRemains() {
        WhisperWorkQueue queue = new WhisperWorkQueue(4, 8);

        assertTrue(queue.offer(null, true, "capture.stop", 0));

        WhisperWorkQueue.WorkItem workItem = queue.pollNextWork();
        assertNotNull(workItem);
        assertArrayEquals(new short[0], workItem.getAudioBuffer());
        assertTrue(workItem.shouldFlushAfter());
        assertEquals("capture.stop", workItem.getTriggerReason());

        assertNull(queue.pollNextWork());
    }
}
