package com.micklab.voicelistener;

import java.util.Arrays;

public final class WhisperRecognitionBuffer {
    private final int maxRetainedSamples;
    private short[] pendingSamples = new short[0];

    public WhisperRecognitionBuffer(int maxRetainedSamples) {
        this.maxRetainedSamples = Math.max(0, maxRetainedSamples);
    }

    public synchronized short[] prepare(short[] nextSegment) {
        short[] normalizedNext = nextSegment == null ? new short[0] : nextSegment;
        if (pendingSamples.length == 0) {
            return Arrays.copyOf(normalizedNext, normalizedNext.length);
        }
        if (normalizedNext.length == 0) {
            return Arrays.copyOf(pendingSamples, pendingSamples.length);
        }

        short[] merged = new short[pendingSamples.length + normalizedNext.length];
        System.arraycopy(pendingSamples, 0, merged, 0, pendingSamples.length);
        System.arraycopy(normalizedNext, 0, merged, pendingSamples.length, normalizedNext.length);
        return merged;
    }

    public synchronized void retainForRetry(short[] attemptedSegment) {
        short[] normalizedAttempt = attemptedSegment == null ? new short[0] : attemptedSegment;
        if (normalizedAttempt.length == 0) {
            pendingSamples = new short[0];
            return;
        }
        if (maxRetainedSamples == 0 || normalizedAttempt.length <= maxRetainedSamples) {
            pendingSamples = Arrays.copyOf(normalizedAttempt, normalizedAttempt.length);
            return;
        }
        pendingSamples = Arrays.copyOfRange(
            normalizedAttempt,
            normalizedAttempt.length - maxRetainedSamples,
            normalizedAttempt.length
        );
    }

    public synchronized void reset() {
        pendingSamples = new short[0];
    }

    public synchronized boolean hasPendingAudio() {
        return pendingSamples.length > 0;
    }
}
