package com.micklab.voicelistener;

import android.util.Log;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

public class VoskOfflineAsrEngine implements OfflineAsrEngine {
    private static final String TAG = "VoskOfflineAsrEngine";

    private final String modelPath;
    private Model model;
    private Recognizer recognizer;

    public VoskOfflineAsrEngine(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public boolean initialize() {
        try {
            File modelDir = new File(modelPath);
            if (!modelDir.exists() || !modelDir.isDirectory()) {
                Log.w(TAG, "Vosk model directory not found: " + modelPath);
                return false;
            }

            model = new Model(modelDir.getAbsolutePath());
            recognizer = new Recognizer(model, 16000.0f);
            Log.i(TAG, "Vosk engine initialized: " + modelPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Vosk engine", e);
            shutdown();
            return false;
        }
    }

    @Override
    public String transcribe(short[] pcm16, int sampleRateHz) {
        if (recognizer == null || pcm16 == null || pcm16.length == 0) {
            return null;
        }

        try {
            byte[] audioBytes = toLittleEndianBytes(pcm16);
            recognizer.acceptWaveForm(audioBytes, audioBytes.length);
            String resultJson = recognizer.getFinalResult();
            return parseText(resultJson);
        } catch (Exception e) {
            Log.e(TAG, "Vosk transcription failed", e);
            return null;
        }
    }

    @Override
    public void shutdown() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }

    @Override
    public String name() {
        return "Vosk";
    }

    private byte[] toLittleEndianBytes(short[] pcm16) {
        ByteBuffer buffer = ByteBuffer.allocate(pcm16.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm16) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    private String parseText(String resultJson) {
        if (resultJson == null || resultJson.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject jsonObject = new JSONObject(resultJson);
            String text = jsonObject.optString("text", "").trim();
            return text.isEmpty() ? null : text;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse Vosk result: " + resultJson, e);
            return null;
        }
    }
}
