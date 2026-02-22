package com.micklab.voicelistener;

public class NoOpOfflineAsrEngine implements OfflineAsrEngine {
    @Override
    public boolean initialize() {
        return true;
    }

    @Override
    public String transcribe(short[] pcm16, int sampleRateHz) {
        return null;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public String name() {
        return "NoOp";
    }
}
