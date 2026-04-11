package com.micklab.voicelistener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

public class VoiceActivityDetector {
    private static final int PRE_SPEECH_FRAMES = 12;
    private static final int START_SPEECH_FRAMES = 2;
    private static final int DEFAULT_MAX_CONTINUOUS_SPEECH_FRAMES = 64;
    private static final double NOISE_FLOOR_ALPHA = 0.04;
    private static final double DYNAMIC_THRESHOLD_MULTIPLIER = 2.4;

    private double rmsThreshold;
    private final int maxSilenceFrames;
    private final int minSpeechFrames;
    private final int maxContinuousSpeechFrames;

    private final ArrayList<short[]> bufferedFrames = new ArrayList<>();
    private final ArrayList<short[]> speechCandidateFrames = new ArrayList<>();
    private final Deque<short[]> preSpeechFrames = new ArrayDeque<>();
    private int speechFrames = 0;
    private int speechCandidateFrameCount = 0;
    private int silenceFrames = 0;
    private boolean inSpeech = false;
    private double noiseFloorRms;

    public VoiceActivityDetector(double rmsThreshold, int maxSilenceFrames, int minSpeechFrames) {
        this(rmsThreshold, maxSilenceFrames, minSpeechFrames, DEFAULT_MAX_CONTINUOUS_SPEECH_FRAMES);
    }

    public VoiceActivityDetector(double rmsThreshold, int maxSilenceFrames, int minSpeechFrames, int maxContinuousSpeechFrames) {
        this.rmsThreshold = rmsThreshold;
        this.maxSilenceFrames = maxSilenceFrames;
        this.minSpeechFrames = minSpeechFrames;
        this.maxContinuousSpeechFrames = Math.max(minSpeechFrames, maxContinuousSpeechFrames);
        this.noiseFloorRms = Math.max(1.0, rmsThreshold * 0.25);
    }

    public synchronized void setRmsThreshold(double rmsThreshold) {
        this.rmsThreshold = rmsThreshold;
        if (noiseFloorRms <= 0.0) {
            noiseFloorRms = Math.max(1.0, rmsThreshold * 0.25);
        }
    }

    public synchronized double getRmsThreshold() {
        return this.rmsThreshold;
    }

    public synchronized short[] processFrame(short[] frame) {
        if (frame == null || frame.length == 0) {
            return null;
        }

        double frameRms = computeRms(frame);
        double effectiveThreshold = getEffectiveThreshold();
        boolean isSpeech = frameRms >= effectiveThreshold;
        boolean enteredSpeech = false;

        if (!inSpeech) {
            updateNoiseFloor(frameRms, effectiveThreshold);
            if (!isSpeech) {
                rememberPreSpeechFrame(frame);
                clearSpeechCandidates();
                return null;
            }
            speechCandidateFrames.add(frame);
            speechCandidateFrameCount++;
            if (speechCandidateFrameCount < START_SPEECH_FRAMES) {
                return null;
            }
            inSpeech = true;
            enteredSpeech = true;
            bufferedFrames.clear();
            bufferedFrames.addAll(preSpeechFrames);
            bufferedFrames.addAll(speechCandidateFrames);
            preSpeechFrames.clear();
            speechFrames = speechCandidateFrameCount;
            clearSpeechCandidates();
            silenceFrames = 0;
        } else {
            bufferedFrames.add(frame);
        }

        if (isSpeech) {
            if (!enteredSpeech) {
                speechFrames++;
            }
            silenceFrames = 0;
        } else {
            silenceFrames++;
        }

        if (isSpeech && speechFrames >= maxContinuousSpeechFrames) {
            return emitBufferedSegmentAndContinue();
        }
        if (silenceFrames >= maxSilenceFrames) {
            short[] segment = speechFrames >= minSpeechFrames ? concatFrames(bufferedFrames) : null;
            reset();
            return segment;
        }
        return null;
    }

    public synchronized short[] flush() {
        short[] segment = speechFrames >= minSpeechFrames ? concatFrames(bufferedFrames) : null;
        reset();
        return segment;
    }

    private void reset() {
        bufferedFrames.clear();
        preSpeechFrames.clear();
        clearSpeechCandidates();
        speechFrames = 0;
        silenceFrames = 0;
        inSpeech = false;
    }

    private short[] emitBufferedSegmentAndContinue() {
        short[] segment = speechFrames >= minSpeechFrames ? concatFrames(bufferedFrames) : null;
        bufferedFrames.clear();
        clearSpeechCandidates();
        speechFrames = 0;
        silenceFrames = 0;
        return segment;
    }

    private void clearSpeechCandidates() {
        speechCandidateFrames.clear();
        speechCandidateFrameCount = 0;
    }

    private void rememberPreSpeechFrame(short[] frame) {
        if (preSpeechFrames.size() == PRE_SPEECH_FRAMES) {
            preSpeechFrames.removeFirst();
        }
        preSpeechFrames.addLast(frame);
    }

    private void updateNoiseFloor(double frameRms, double effectiveThreshold) {
        double sample = Math.min(frameRms, effectiveThreshold);
        noiseFloorRms = (noiseFloorRms * (1.0 - NOISE_FLOOR_ALPHA)) + (sample * NOISE_FLOOR_ALPHA);
        if (noiseFloorRms < 1.0) {
            noiseFloorRms = 1.0;
        }
    }

    private double getEffectiveThreshold() {
        return Math.max(rmsThreshold, noiseFloorRms * DYNAMIC_THRESHOLD_MULTIPLIER);
    }

    private double computeRms(short[] frame) {
        double sum = 0.0;
        for (short sample : frame) {
            sum += sample * (double) sample;
        }
        return Math.sqrt(sum / frame.length);
    }

    private short[] concatFrames(ArrayList<short[]> frames) {
        int totalSamples = 0;
        for (short[] chunk : frames) {
            totalSamples += chunk.length;
        }
        short[] merged = new short[totalSamples];
        int offset = 0;
        for (short[] chunk : frames) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }
}
