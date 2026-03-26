package com.micklab.voicelistener

import android.util.Log
import kotlin.concurrent.withLock
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
        lock.withLock {
            if (nativeHandle != 0L && loadedModelPath == modelPath) {
                return
            }
            val nextHandle = nativeLoadModel(modelPath, sampleRateHz, language, threadCount)
            check(nextHandle != 0L) { "Failed to load Whisper model: $modelPath" }

            val previousHandle = nativeHandle
            nativeHandle = nextHandle
            loadedModelPath = modelPath

            if (previousHandle != 0L) {
                nativeRelease(previousHandle)
            }
            Log.i(TAG, "Whisper model loaded: $modelPath")
        }
    }

    override fun start() {
        lock.withLock {
            check(nativeHandle != 0L) { "Whisper model is not loaded." }
            started = true
        }
    }

    override fun stop() {
        lock.withLock {
            started = false
        }
    }

    override fun transcribe(buffer: ShortArray): String = lock.withLock {
        if (!started || nativeHandle == 0L || buffer.isEmpty()) {
            return ""
        }
        return nativeTranscribe(nativeHandle, buffer).orEmpty()
    }

    override fun release() {
        lock.withLock {
            started = false
            if (nativeHandle != 0L) {
                nativeRelease(nativeHandle)
            }
            nativeHandle = 0L
            loadedModelPath = null
        }
    }

    private external fun nativeLoadModel(
        modelPath: String,
        sampleRateHz: Int,
        language: String,
        threadCount: Int
    ): Long

    private external fun nativeTranscribe(nativeHandle: Long, audioBuffer: ShortArray): String?

    private external fun nativeRelease(nativeHandle: Long)

    companion object {
        private const val TAG = "WhisperEngine"

        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
