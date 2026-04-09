package com.micklab.voicelistener;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeechRecognizerFacadeTest {
    @Test
    public void currentEngineType_doesNotBlockWhileDelegateTranscribeIsRunning() throws Exception {
        BlockingSpeechRecognizerEngine blockingEngine = new BlockingSpeechRecognizerEngine();
        SpeechRecognizerFacade facade = new SpeechRecognizerFacade(config -> blockingEngine);
        facade.selectEngine(new SpeechRecognizerConfig(EngineType.VOSK, "test-model", 16000, "ja", 2));
        facade.start();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> transcribeFuture = executor.submit(() -> facade.transcribe(new short[] {1, 2, 3, 4}));
            assertTrue(blockingEngine.awaitTranscribeStart(1, TimeUnit.SECONDS));

            Future<EngineType> engineTypeFuture = executor.submit(facade::currentEngineType);
            assertEquals(EngineType.VOSK, engineTypeFuture.get(200, TimeUnit.MILLISECONDS));

            blockingEngine.releaseTranscribe();
            assertEquals("", transcribeFuture.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class BlockingSpeechRecognizerEngine implements SpeechRecognizerEngine {
        private final CountDownLatch transcribeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTranscribe = new CountDownLatch(1);

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public String transcribe(short[] buffer) {
            transcribeStarted.countDown();
            try {
                if (!releaseTranscribe.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release transcribe.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for transcribe release.", e);
            }
            return "";
        }

        @Override
        public String flush() {
            return "";
        }

        @Override
        public void release() {
        }

        boolean awaitTranscribeStart(long timeout, TimeUnit unit) throws InterruptedException {
            return transcribeStarted.await(timeout, unit);
        }

        void releaseTranscribe() {
            releaseTranscribe.countDown();
        }
    }
}
