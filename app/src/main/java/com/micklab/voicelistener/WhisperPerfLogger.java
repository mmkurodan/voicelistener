package com.micklab.voicelistener;

import android.util.Log;

public final class WhisperPerfLogger {
    private static final String TAG = "WhisperPerfLogger";
    private static final String PREFIX = "WhisperPerf";

    private static volatile LogManager2 logManager;

    private WhisperPerfLogger() {
    }

    public static void initialize(LogManager2 nextLogManager) {
        if (nextLogManager == null) {
            throw new IllegalArgumentException("LogManager2 must not be null.");
        }
        logManager = nextLogManager;
        logTrace(
            RecognitionTraceContext.NO_TRACE_ID,
            "logger.init",
            "path=" + nextLogManager.getCurrentLogFilePath()
        );
    }

    public static void logTrace(long traceId, String stage, String details) {
        String normalizedStage = stage == null ? "" : stage.trim();
        if (normalizedStage.isEmpty()) {
            throw new IllegalArgumentException("WhisperPerf stage must not be blank.");
        }

        String normalizedDetails = details == null ? "" : details.trim();
        String traceValue = traceId < 0L ? "none" : Long.toString(traceId);
        StringBuilder builder = new StringBuilder();
        builder.append("trace=").append(traceValue).append(" stage=").append(normalizedStage);
        if (!normalizedDetails.isEmpty()) {
            builder.append(' ').append(normalizedDetails);
        }
        logLine(builder.toString());
    }

    public static void logFromNative(long traceId, String stage, String details) {
        logTrace(traceId, stage, details);
    }

    public static void logLine(String body) {
        String normalizedBody = body == null ? "" : body.trim();
        if (normalizedBody.isEmpty()) {
            return;
        }

        String line = normalizedBody.startsWith(PREFIX) ? normalizedBody : PREFIX + " " + normalizedBody;
        Log.i(TAG, line);

        LogManager2 currentLogManager = logManager;
        if (currentLogManager == null) {
            Log.w(TAG, "Whisper perf logger is not initialized; file persistence unavailable: " + line);
            return;
        }
        currentLogManager.writeLog(line);
    }
}
