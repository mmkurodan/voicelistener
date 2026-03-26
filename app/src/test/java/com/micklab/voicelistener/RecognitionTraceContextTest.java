package com.micklab.voicelistener;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class RecognitionTraceContextTest {
    @After
    public void tearDown() {
        RecognitionTraceContext.clear();
    }

    @Test
    public void currentId_defaultsToNoTrace() {
        assertEquals(RecognitionTraceContext.NO_TRACE_ID, RecognitionTraceContext.currentId());
    }

    @Test
    public void set_and_clear_updatesCurrentTraceId() {
        RecognitionTraceContext.set(42L);

        assertEquals(42L, RecognitionTraceContext.currentId());

        RecognitionTraceContext.clear();

        assertEquals(RecognitionTraceContext.NO_TRACE_ID, RecognitionTraceContext.currentId());
    }

    @Test
    public void currentId_isThreadLocal() throws Exception {
        RecognitionTraceContext.set(7L);
        AtomicLong workerInitial = new AtomicLong(Long.MIN_VALUE);
        AtomicLong workerAfterSet = new AtomicLong(Long.MIN_VALUE);

        Thread worker = new Thread(() -> {
            workerInitial.set(RecognitionTraceContext.currentId());
            RecognitionTraceContext.set(21L);
            workerAfterSet.set(RecognitionTraceContext.currentId());
            RecognitionTraceContext.clear();
        });
        worker.start();
        worker.join();

        assertEquals(RecognitionTraceContext.NO_TRACE_ID, workerInitial.get());
        assertEquals(21L, workerAfterSet.get());
        assertEquals(7L, RecognitionTraceContext.currentId());
    }
}
