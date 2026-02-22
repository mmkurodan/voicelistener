package com.micklab.voicelistener;

public interface OfflineAsrEngine {
    boolean initialize();
    String transcribe(short[] pcm16, int sampleRateHz);
    void shutdown();
    String name();
}
