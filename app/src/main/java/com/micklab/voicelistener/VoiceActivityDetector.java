package com.micklab.voicelistener;

import java.util.ArrayList;

public class VoiceActivityDetector {
    private double rmsThreshold;
    private final int maxSilenceFrames;
    private final int minSpeechFrames;

    private final ArrayList<short[]> bufferedFrames = new ArrayList<>();
    private int speechFrames = 0;
    private int silenceFrames = 0;
    private boolean inSpeech = false;

    public VoiceActivityDetector(double rmsThreshold, int maxSilenceFrames, int minSpeechFrames) {
        this.rmsThreshold = rmsThreshold;
        this.maxSilenceFrames = maxSilenceFrames;
        this.minSpeechFrames = minSpeechFrames;
    }

    public synchronized void setRmsThreshold(double rmsThreshold) {
        this.rmsThreshold = rmsThreshold;
    }

    public synchronized double getRmsThreshold() {
        return this.rmsThreshold;
    }

    public synchronized short[] processFrame(short[] frame) {
        if (frame == null || frame.length == 0) {
            return null;
        }

        boolean isSpeech = computeRms(frame) >= rmsThreshold;
        if (!inSpeech) {
            if (!isSpeech) {
                return null;
            }
            inSpeech = true;
            bufferedFrames.clear();
            speechFrames = 0;
            silenceFrames = 0;
        }

        bufferedFrames.add(frame);
        if (isSpeech) {
            speechFrames++;
            silenceFrames = 0;
        } else {
            silenceFrames++;
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
        speechFrames = 0;
        silenceFrames = 0;
        inSpeech = false;
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
        for (short[] frame : frames) {
            totalSamples += frame.length;
        }
        short[] merged = new short[totalSamples];
        int offset = 0;
        for (short[] frame : frames) {
            System.arraycopy(frame, 0, merged, offset, frame.length);
            offset += frame.length;
        }
        return merged;
    }
}
