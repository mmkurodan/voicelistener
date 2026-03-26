package com.micklab.voicelistener

import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

class SpeechRecognizerFacade @JvmOverloads constructor(
    private val engineFactory: SpeechRecognizerEngineFactory = DefaultSpeechRecognizerEngineFactory()
) {
    private val lock = ReentrantLock()
    private var currentEngine: SpeechRecognizerEngine = NoOpSpeechRecognizerEngine()
    private var currentEngineType: EngineType? = null
    private var started = false

    fun selectEngine(config: SpeechRecognizerConfig) {
        val nextEngine = engineFactory.create(config)
        lock.withLock {
            replaceEngineLocked(nextEngine, config.engineType)
        }
    }

    fun setFallbackToNoOp() {
        lock.withLock {
            replaceEngineLocked(NoOpSpeechRecognizerEngine(), null)
        }
    }

    fun hasActiveEngine(): Boolean = lock.withLock { currentEngineType != null }

    fun currentEngineType(): EngineType? = lock.withLock { currentEngineType }

    override fun toString(): String = lock.withLock {
        "SpeechRecognizerFacade(engineType=$currentEngineType, started=$started)"
    }

    fun start() {
        lock.withLock {
            started = true
            currentEngine.start()
        }
    }

    fun stop() {
        lock.withLock {
            started = false
            currentEngine.stop()
        }
    }

    fun transcribe(buffer: ShortArray): String = lock.withLock {
        if (buffer.isEmpty()) {
            return ""
        }
        currentEngine.transcribe(buffer)
    }

    fun release() {
        lock.withLock {
            started = false
            currentEngine.stop()
            currentEngine.release()
            currentEngine = NoOpSpeechRecognizerEngine()
            currentEngineType = null
        }
    }

    private fun replaceEngineLocked(nextEngine: SpeechRecognizerEngine, nextType: EngineType?) {
        val previousEngine = currentEngine
        currentEngine = nextEngine
        currentEngineType = nextType
        if (started) {
            currentEngine.start()
        } else {
            currentEngine.stop()
        }
        if (previousEngine !== nextEngine) {
            previousEngine.stop()
            previousEngine.release()
        }
    }

    companion object {
        @JvmStatic
        fun createDefault(): SpeechRecognizerFacade = SpeechRecognizerFacade()
    }
}
