package com.micklab.voicelistener

import android.util.Log
import kotlin.concurrent.withLock
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class WhisperEngine @JvmOverloads constructor(
    private val sampleRateHz: Int = 16_000,
    private val language: String = "ja",
    private val threadCount: Int = SpeechRecognizerConfig.defaultThreadCount()
) : SpeechRecognizerEngine {
    private val lock = ReentrantLock()
    private val inferenceExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>()
    ) { runnable ->
        Thread(runnable, INFERENCE_THREAD_NAME).apply {
            isDaemon = false
        }
    }
    private val retryBuffer = WhisperRecognitionBuffer(RETRY_RETAIN_SAMPLES)
    private val pendingChunks = ArrayDeque<ShortArray>()

    private var nativeHandle: Long = 0L
    private var loadedModelPath: String? = null
    private var started = false
    private var pendingSampleCount = 0

    fun loadModel(modelPath: String) {
        require(modelPath.isNotBlank()) { "Whisper model path must not be blank." }
        val enteredNs = System.nanoTime()
        lock.withLock {
            val lockWaitMs = elapsedMs(enteredNs)
            if (nativeHandle != 0L && loadedModelPath == modelPath) {
                logWhisperPerf(
                    "engine.load.reuse",
                    "path=$modelPath lockWaitMs=$lockWaitMs sampleRateHz=$sampleRateHz language=$language threadCount=$threadCount"
                )
                return
            }
            check(!inferenceExecutor.isShutdown) { "Whisper inference executor is shut down." }
            val nativeStartedNs = System.nanoTime()
            val nextHandle = nativeLoadModel(modelPath, sampleRateHz, language, threadCount)
            check(nextHandle != 0L) { "Failed to load Whisper model: $modelPath" }
            val nativeLoadMs = elapsedMs(nativeStartedNs)

            val previousHandle = nativeHandle
            nativeHandle = nextHandle
            loadedModelPath = modelPath
            clearStreamingState()

            if (previousHandle != 0L) {
                nativeRelease(previousHandle)
            }
            logWhisperPerf(
                "engine.load",
                "path=$modelPath sampleRateHz=$sampleRateHz language=$language threadCount=$threadCount lockWaitMs=$lockWaitMs nativeLoadMs=$nativeLoadMs replacedHandle=${previousHandle != 0L}"
            )
            Log.i(TAG, "Whisper model loaded: $modelPath")
        }
    }

    override fun start() {
        lock.withLock {
            check(nativeHandle != 0L) { "Whisper model is not loaded." }
            started = true
            clearStreamingState()
            logWhisperPerf("engine.start", "hasHandle=${nativeHandle != 0L} modelPath=$loadedModelPath")
        }
    }

    override fun stop() {
        lock.withLock {
            started = false
            clearStreamingState()
            logWhisperPerf("engine.stop", "hasHandle=${nativeHandle != 0L} modelPath=$loadedModelPath")
        }
    }

    override fun transcribe(buffer: ShortArray): String {
        val enteredNs = System.nanoTime()
        return lock.withLock {
            val lockWaitMs = elapsedMs(enteredNs)
            if (!started || nativeHandle == 0L || buffer.isEmpty()) {
                logWhisperPerf(
                    "engine.transcribe.skip",
                    "samples=${buffer.size} lockWaitMs=$lockWaitMs started=$started hasHandle=${nativeHandle != 0L}"
                )
                return ""
            }

            val traceId = RecognitionTraceContext.currentId()
            val submittedAtMs = System.currentTimeMillis()
            val queueLength = inferenceExecutor.queue.size
            logWhisperPerfTrace(
                traceId,
                "engine.chunk.submit",
                "submittedAtMs=$submittedAtMs chunkSamples=${buffer.size} chunkMs=${samplesToMillis(buffer.size)} queueLength=$queueLength pendingSamples=$pendingSampleCount"
            )

            val batchStartedNs = System.nanoTime()
            val batchResult = awaitBatchResult(
                inferenceExecutor.submit(Callable {
                    enqueueAndProcess(buffer, traceId, queueLength)
                })
            )
            val batchMs = elapsedMs(batchStartedNs)
            val finishedAtMs = System.currentTimeMillis()
            logWhisperPerfTrace(
                traceId,
                "engine.transcribe",
                "submittedAtMs=$submittedAtMs finishedAtMs=$finishedAtMs samples=${buffer.size} bufferMs=${samplesToMillis(buffer.size)} chars=${batchResult.text.length} lockWaitMs=$lockWaitMs batchMs=$batchMs nativeCalls=${batchResult.nativeCalls} queueLength=$queueLength pendingSamples=${batchResult.pendingSamples}"
            )
            batchResult.text
        }
    }

    override fun flush(): String {
        val enteredNs = System.nanoTime()
        return lock.withLock {
            val lockWaitMs = elapsedMs(enteredNs)
            if (!started || nativeHandle == 0L) {
                logWhisperPerf(
                    "engine.flush.skip",
                    "lockWaitMs=$lockWaitMs started=$started hasHandle=${nativeHandle != 0L}"
                )
                return ""
            }
            if (pendingSampleCount == 0 && !retryBuffer.hasPendingAudio()) {
                logWhisperPerf(
                    "engine.flush.skip",
                    "lockWaitMs=$lockWaitMs reason=no-pending-audio"
                )
                return ""
            }

            val traceId = RecognitionTraceContext.currentId()
            val submittedAtMs = System.currentTimeMillis()
            val queueLength = inferenceExecutor.queue.size
            logWhisperPerfTrace(
                traceId,
                "engine.flush.submit",
                "submittedAtMs=$submittedAtMs queueLength=$queueLength pendingSamples=$pendingSampleCount retrySamples=${retryBuffer.pendingSampleCount()}"
            )

            val flushStartedNs = System.nanoTime()
            val batchResult = awaitBatchResult(
                inferenceExecutor.submit(Callable {
                    flushBufferedAudio(traceId, queueLength)
                })
            )
            val flushMs = elapsedMs(flushStartedNs)
            val finishedAtMs = System.currentTimeMillis()
            logWhisperPerfTrace(
                traceId,
                "engine.flush",
                "submittedAtMs=$submittedAtMs finishedAtMs=$finishedAtMs chars=${batchResult.text.length} lockWaitMs=$lockWaitMs flushMs=$flushMs nativeCalls=${batchResult.nativeCalls} queueLength=$queueLength pendingSamples=${batchResult.pendingSamples}"
            )
            batchResult.text
        }
    }

    override fun release() {
        lock.withLock {
            started = false
            clearStreamingState()
            val hadHandle = nativeHandle != 0L
            if (nativeHandle != 0L) {
                nativeRelease(nativeHandle)
            }
            nativeHandle = 0L
            loadedModelPath = null
            inferenceExecutor.shutdown()
            logWhisperPerf("engine.release", "hadHandle=$hadHandle")
        }
    }

    private fun enqueueAndProcess(
        buffer: ShortArray,
        traceId: Long,
        queueLength: Int
    ): BatchResult {
        appendStreamingChunks(buffer)
        val recognizedParts = ArrayList<String>()
        var nativeCalls = 0
        while (pendingSampleCount >= INFERENCE_WINDOW_SAMPLES) {
            val nextChunk = drainPendingSamples(INFERENCE_WINDOW_SAMPLES, force = false) ?: break
            val result = runInferenceChunk(nextChunk, traceId, queueLength, flush = false)
            nativeCalls++
            if (result.isNotBlank()) {
                recognizedParts.add(result)
            }
        }
        return BatchResult(
            text = joinRecognized(recognizedParts),
            nativeCalls = nativeCalls,
            pendingSamples = pendingSampleCount
        )
    }

    private fun flushBufferedAudio(
        traceId: Long,
        queueLength: Int
    ): BatchResult {
        val recognizedParts = ArrayList<String>()
        var nativeCalls = 0
        while (pendingSampleCount > 0) {
            val nextChunk = drainPendingSamples(INFERENCE_WINDOW_SAMPLES, force = true) ?: break
            val result = runInferenceChunk(nextChunk, traceId, queueLength, flush = true)
            nativeCalls++
            if (result.isNotBlank()) {
                recognizedParts.add(result)
            }
        }
        if (nativeCalls == 0 && retryBuffer.hasPendingAudio()) {
            val result = runInferenceChunk(shortArrayOf(), traceId, queueLength, flush = true)
            nativeCalls++
            if (result.isNotBlank()) {
                recognizedParts.add(result)
            }
        }
        return BatchResult(
            text = joinRecognized(recognizedParts),
            nativeCalls = nativeCalls,
            pendingSamples = pendingSampleCount
        )
    }

    private fun runInferenceChunk(
        chunk: ShortArray,
        traceId: Long,
        queueLength: Int,
        flush: Boolean
    ): String {
        val preparedChunk = retryBuffer.prepare(chunk)
        if (preparedChunk.isEmpty()) {
            if (flush) {
                retryBuffer.reset()
            }
            return ""
        }

        val startedAtMs = System.currentTimeMillis()
        logWhisperPerfTrace(
            traceId,
            "engine.chunk.begin",
            "startedAtMs=$startedAtMs chunkSamples=${chunk.size} chunkMs=${samplesToMillis(chunk.size)} preparedSamples=${preparedChunk.size} preparedMs=${samplesToMillis(preparedChunk.size)} queueLength=$queueLength pendingSamples=$pendingSampleCount retrySamples=${retryBuffer.pendingSampleCount()} flush=$flush"
        )

        val nativeStartedNs = System.nanoTime()
        val result = nativeTranscribe(nativeHandle, preparedChunk, traceId, queueLength).orEmpty()
        val nativeMs = elapsedMs(nativeStartedNs)
        val finishedAtMs = System.currentTimeMillis()

        if (result.isBlank()) {
            if (flush) {
                retryBuffer.reset()
            } else {
                retryBuffer.retainForRetry(preparedChunk)
            }
        } else {
            retryBuffer.reset()
        }

        logWhisperPerfTrace(
            traceId,
            "engine.chunk.end",
            "startedAtMs=$startedAtMs finishedAtMs=$finishedAtMs chunkSamples=${chunk.size} preparedSamples=${preparedChunk.size} chars=${result.length} inferMs=$nativeMs queueLength=$queueLength pendingSamples=$pendingSampleCount retrySamples=${retryBuffer.pendingSampleCount()} flush=$flush"
        )
        return result
    }

    private fun appendStreamingChunks(buffer: ShortArray) {
        var offset = 0
        while (offset < buffer.size) {
            val nextOffset = minOf(offset + STREAM_INPUT_SAMPLES, buffer.size)
            val chunk = buffer.copyOfRange(offset, nextOffset)
            pendingChunks.addLast(chunk)
            pendingSampleCount += chunk.size
            offset = nextOffset
        }
    }

    private fun drainPendingSamples(targetSamples: Int, force: Boolean): ShortArray? {
        if (pendingSampleCount <= 0) {
            return null
        }
        if (!force && pendingSampleCount < targetSamples) {
            return null
        }

        val requestedSamples = if (force) pendingSampleCount else targetSamples
        val merged = ShortArray(requestedSamples)
        var writeOffset = 0
        while (writeOffset < requestedSamples && pendingChunks.isNotEmpty()) {
            val chunk = pendingChunks.removeFirst()
            val copyLength = minOf(chunk.size, requestedSamples - writeOffset)
            System.arraycopy(chunk, 0, merged, writeOffset, copyLength)
            writeOffset += copyLength
            pendingSampleCount -= copyLength
            if (copyLength < chunk.size) {
                pendingChunks.addFirst(chunk.copyOfRange(copyLength, chunk.size))
            }
        }
        return if (writeOffset == merged.size) merged else merged.copyOf(writeOffset)
    }

    private fun clearStreamingState() {
        pendingChunks.clear()
        pendingSampleCount = 0
        retryBuffer.reset()
    }

    private fun awaitBatchResult(future: Future<BatchResult>): BatchResult {
        return try {
            future.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Whisper inference interrupted.", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Whisper inference failed.", cause ?: e)
            }
        }
    }

    private fun elapsedMs(startedNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
    }

    private fun samplesToMillis(sampleCount: Int): Long {
        if (sampleCount <= 0) {
            return 0L
        }
        return Math.round(sampleCount * 1000.0 / sampleRateHz)
    }

    private fun joinRecognized(parts: List<String>): String {
        return parts
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")
            .trim()
    }

    private fun logWhisperPerf(stage: String, details: String) {
        logWhisperPerfTrace(RecognitionTraceContext.currentId(), stage, details)
    }

    private fun logWhisperPerfTrace(traceId: Long, stage: String, details: String) {
        WhisperPerfLogger.logTrace(traceId, stage, details)
    }

    private external fun nativeLoadModel(
        modelPath: String,
        sampleRateHz: Int,
        language: String,
        threadCount: Int
    ): Long

    private external fun nativeTranscribe(
        nativeHandle: Long,
        audioBuffer: ShortArray,
        traceId: Long,
        queueLength: Int
    ): String?

    private external fun nativeRelease(nativeHandle: Long)

    private data class BatchResult(
        val text: String,
        val nativeCalls: Int,
        val pendingSamples: Int
    )

    companion object {
        private const val TAG = "WhisperEngine"
        private const val INFERENCE_THREAD_NAME = "WhisperInferenceThread"
        private const val STREAM_INPUT_SAMPLES = 1_024
        private const val INFERENCE_WINDOW_SAMPLES = 4_096
        private const val RETRY_RETAIN_SAMPLES = 2_048

        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
