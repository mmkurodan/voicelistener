package com.micklab.voicelistener

import android.util.Log
import kotlin.concurrent.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class WhisperEngine @JvmOverloads constructor(
    private val sampleRateHz: Int = 16_000,
    private val language: String = "ja",
    private val threadCount: Int = SpeechRecognizerConfig.defaultThreadCount()
) : SpeechRecognizerEngine {
    private val lock = ReentrantLock()
    private var nativeHandle: Long = 0L
    private var loadedModelPath: String? = null
    private var started = false

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
            val nativeStartedNs = System.nanoTime()
            val nextHandle = nativeLoadModel(modelPath, sampleRateHz, language, threadCount)
            check(nextHandle != 0L) { "Failed to load Whisper model: $modelPath" }
            val nativeLoadMs = elapsedMs(nativeStartedNs)

            val previousHandle = nativeHandle
            nativeHandle = nextHandle
            loadedModelPath = modelPath

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
            logWhisperPerf("engine.start", "hasHandle=${nativeHandle != 0L} modelPath=$loadedModelPath")
        }
    }

    override fun stop() {
        lock.withLock {
            started = false
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
            val nativeStartedNs = System.nanoTime()
            val result = nativeTranscribe(nativeHandle, buffer, RecognitionTraceContext.currentId()).orEmpty()
            val nativeMs = elapsedMs(nativeStartedNs)
            logWhisperPerf(
                "engine.transcribe",
                "samples=${buffer.size} bufferMs=${samplesToMillis(buffer.size)} chars=${result.length} lockWaitMs=$lockWaitMs nativeMs=$nativeMs threadCount=$threadCount"
            )
            result
        }
    }

    override fun release() {
        lock.withLock {
            started = false
            val hadHandle = nativeHandle != 0L
            if (nativeHandle != 0L) {
                nativeRelease(nativeHandle)
            }
            nativeHandle = 0L
            loadedModelPath = null
            logWhisperPerf("engine.release", "hadHandle=$hadHandle")
        }
    }

    private external fun nativeLoadModel(
        modelPath: String,
        sampleRateHz: Int,
        language: String,
        threadCount: Int
    ): Long

    private external fun nativeTranscribe(nativeHandle: Long, audioBuffer: ShortArray, traceId: Long): String?

    private external fun nativeRelease(nativeHandle: Long)

    private fun elapsedMs(startedNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
    }

    private fun samplesToMillis(sampleCount: Int): Long {
        if (sampleCount <= 0) {
            return 0L
        }
        return Math.round(sampleCount * 1000.0 / sampleRateHz)
    }

    private fun logWhisperPerf(stage: String, details: String) {
        WhisperPerfLogger.logTrace(RecognitionTraceContext.currentId(), stage, details)
    }

    companion object {
        private const val TAG = "WhisperEngine"

        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
