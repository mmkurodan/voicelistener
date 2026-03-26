package com.micklab.voicelistener

data class SpeechRecognizerConfig @JvmOverloads constructor(
    val engineType: EngineType,
    val modelPath: String,
    val sampleRateHz: Int = 16_000,
    val language: String = "ja",
    val threadCount: Int = defaultThreadCount()
) {
    companion object {
        @JvmStatic
        fun defaultThreadCount(): Int {
            val available = Runtime.getRuntime().availableProcessors()
            return available.coerceIn(2, 8)
        }
    }
}
