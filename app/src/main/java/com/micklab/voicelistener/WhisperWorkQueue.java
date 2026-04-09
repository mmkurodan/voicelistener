package com.micklab.voicelistener;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class WhisperWorkQueue {
    private static final String DEFAULT_TRIGGER = "stream.frame";

    private final int maxBatchSamples;
    private final int maxPendingSamples;
    private final ArrayDeque<short[]> pendingAudio = new ArrayDeque<>();

    private int pendingSampleCount = 0;
    private boolean flushRequested = false;
    private String flushTriggerReason = DEFAULT_TRIGGER;
    private int flushRelatedSamples = 0;
    private boolean workerRunning = false;
    private int droppedSampleCount = 0;

    public WhisperWorkQueue(int maxBatchSamples, int maxPendingSamples) {
        if (maxBatchSamples <= 0) {
            throw new IllegalArgumentException("maxBatchSamples must be > 0");
        }
        if (maxPendingSamples < maxBatchSamples) {
            throw new IllegalArgumentException("maxPendingSamples must be >= maxBatchSamples");
        }
        this.maxBatchSamples = maxBatchSamples;
        this.maxPendingSamples = maxPendingSamples;
    }

    public synchronized boolean offer(
        short[] audioChunk,
        boolean flushAfter,
        String triggerReason,
        int relatedSamples
    ) {
        appendAudioLocked(audioChunk);
        if (flushAfter) {
            flushRequested = true;
            flushTriggerReason = normalizeTriggerReason(triggerReason);
            flushRelatedSamples = Math.max(flushRelatedSamples, Math.max(0, relatedSamples));
        }
        if (workerRunning || isIdleLocked()) {
            return false;
        }
        workerRunning = true;
        return true;
    }

    public synchronized WorkItem pollNextWork() {
        if (isIdleLocked()) {
            workerRunning = false;
            return null;
        }

        int samplesToDrain = Math.min(maxBatchSamples, pendingSampleCount);
        boolean flushAfter = flushRequested && pendingSampleCount <= maxBatchSamples;
        short[] audioBuffer = samplesToDrain == 0
            ? new short[0]
            : drainAudioLocked(samplesToDrain);
        int droppedSamples = droppedSampleCount;
        droppedSampleCount = 0;
        String triggerReason = flushAfter ? flushTriggerReason : DEFAULT_TRIGGER;
        int relatedSamples = flushAfter ? flushRelatedSamples : 0;
        if (flushAfter) {
            flushRequested = false;
            flushTriggerReason = DEFAULT_TRIGGER;
            flushRelatedSamples = 0;
        }
        return new WorkItem(
            audioBuffer,
            flushAfter,
            triggerReason,
            relatedSamples,
            droppedSamples,
            pendingSampleCount
        );
    }

    public synchronized void cancelWorker() {
        workerRunning = false;
    }

    public synchronized void reset() {
        pendingAudio.clear();
        pendingSampleCount = 0;
        flushRequested = false;
        flushTriggerReason = DEFAULT_TRIGGER;
        flushRelatedSamples = 0;
        workerRunning = false;
        droppedSampleCount = 0;
    }

    public synchronized int pendingSampleCount() {
        return pendingSampleCount;
    }

    private boolean isIdleLocked() {
        return pendingSampleCount == 0 && !flushRequested;
    }

    private void appendAudioLocked(short[] audioChunk) {
        if (audioChunk == null || audioChunk.length == 0) {
            return;
        }

        short[] normalizedChunk = Arrays.copyOf(audioChunk, audioChunk.length);
        if (normalizedChunk.length >= maxPendingSamples) {
            droppedSampleCount += pendingSampleCount;
            pendingAudio.clear();
            pendingSampleCount = 0;

            int retainedFrom = normalizedChunk.length - maxPendingSamples;
            if (retainedFrom > 0) {
                droppedSampleCount += retainedFrom;
            }
            short[] retainedChunk = Arrays.copyOfRange(
                normalizedChunk,
                Math.max(0, retainedFrom),
                normalizedChunk.length
            );
            pendingAudio.addLast(retainedChunk);
            pendingSampleCount = retainedChunk.length;
            return;
        }

        int overflowSamples = (pendingSampleCount + normalizedChunk.length) - maxPendingSamples;
        if (overflowSamples > 0) {
            dropOldestSamplesLocked(overflowSamples);
        }

        pendingAudio.addLast(normalizedChunk);
        pendingSampleCount += normalizedChunk.length;
    }

    private void dropOldestSamplesLocked(int sampleCount) {
        int remainingToDrop = Math.max(0, sampleCount);
        while (remainingToDrop > 0 && !pendingAudio.isEmpty()) {
            short[] oldest = pendingAudio.removeFirst();
            if (oldest.length <= remainingToDrop) {
                droppedSampleCount += oldest.length;
                pendingSampleCount -= oldest.length;
                remainingToDrop -= oldest.length;
                continue;
            }

            short[] retainedTail = Arrays.copyOfRange(oldest, remainingToDrop, oldest.length);
            pendingAudio.addFirst(retainedTail);
            droppedSampleCount += remainingToDrop;
            pendingSampleCount -= remainingToDrop;
            remainingToDrop = 0;
        }
    }

    private short[] drainAudioLocked(int sampleCount) {
        short[] merged = new short[sampleCount];
        int writeOffset = 0;
        while (writeOffset < sampleCount && !pendingAudio.isEmpty()) {
            short[] chunk = pendingAudio.removeFirst();
            int copyLength = Math.min(chunk.length, sampleCount - writeOffset);
            System.arraycopy(chunk, 0, merged, writeOffset, copyLength);
            writeOffset += copyLength;
            pendingSampleCount -= copyLength;
            if (copyLength < chunk.length) {
                pendingAudio.addFirst(Arrays.copyOfRange(chunk, copyLength, chunk.length));
            }
        }
        return writeOffset == merged.length ? merged : Arrays.copyOf(merged, writeOffset);
    }

    private static String normalizeTriggerReason(String triggerReason) {
        String normalized = triggerReason == null ? "" : triggerReason.trim();
        return normalized.isEmpty() ? DEFAULT_TRIGGER : normalized;
    }

    public static final class WorkItem {
        private final short[] audioBuffer;
        private final boolean flushAfter;
        private final String triggerReason;
        private final int relatedSamples;
        private final int droppedSampleCount;
        private final int pendingSampleCountAfterDrain;

        private WorkItem(
            short[] audioBuffer,
            boolean flushAfter,
            String triggerReason,
            int relatedSamples,
            int droppedSampleCount,
            int pendingSampleCountAfterDrain
        ) {
            this.audioBuffer = audioBuffer;
            this.flushAfter = flushAfter;
            this.triggerReason = triggerReason;
            this.relatedSamples = relatedSamples;
            this.droppedSampleCount = droppedSampleCount;
            this.pendingSampleCountAfterDrain = pendingSampleCountAfterDrain;
        }

        public short[] getAudioBuffer() {
            return audioBuffer;
        }

        public boolean shouldFlushAfter() {
            return flushAfter;
        }

        public String getTriggerReason() {
            return triggerReason;
        }

        public int getRelatedSamples() {
            return relatedSamples;
        }

        public int getDroppedSampleCount() {
            return droppedSampleCount;
        }

        public int getPendingSampleCountAfterDrain() {
            return pendingSampleCountAfterDrain;
        }
    }
}
