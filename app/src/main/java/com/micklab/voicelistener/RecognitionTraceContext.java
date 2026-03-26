package com.micklab.voicelistener;

public final class RecognitionTraceContext {
    public static final long NO_TRACE_ID = -1L;

    private static final ThreadLocal<Long> CURRENT_TRACE_ID = new ThreadLocal<>();

    private RecognitionTraceContext() {
    }

    public static void set(long traceId) {
        if (traceId < 0L) {
            clear();
            return;
        }
        CURRENT_TRACE_ID.set(traceId);
    }

    public static long currentId() {
        Long traceId = CURRENT_TRACE_ID.get();
        return traceId == null ? NO_TRACE_ID : traceId;
    }

    public static void clear() {
        CURRENT_TRACE_ID.remove();
    }
}
