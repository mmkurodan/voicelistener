package com.micklab.voicelistener

import android.util.Log
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

class VoskEngine(private val modelPath: String) : SpeechRecognizerEngine {
    private val lock = ReentrantLock()
    private var delegate: VoskOfflineAsrEngine? = null
    private var started = false

    init {
        require(modelPath.isNotBlank()) { "Vosk model path must not be blank." }
        val engine = VoskOfflineAsrEngine(modelPath)
        check(engine.initialize()) { "Failed to initialize Vosk model: $modelPath" }
        delegate = engine
        Log.i(TAG, "VoskEngine prepared: $modelPath")
    }

    override fun start() {
        lock.withLock {
            started = true
        }
    }

    override fun stop() {
        lock.withLock {
            started = false
        }
    }

    override fun transcribe(buffer: ShortArray): String = lock.withLock {
        val engine = delegate ?: return ""
        if (!started || buffer.isEmpty()) {
            return ""
        }
        return engine.transcribe(buffer, SAMPLE_RATE_HZ).orEmpty()
    }

    override fun release() {
        lock.withLock {
            started = false
            delegate?.shutdown()
            delegate = null
        }
    }

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE_HZ = 16_000
    }
}
