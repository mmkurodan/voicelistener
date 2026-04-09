package com.micklab.voicelistener

import java.util.Locale

data class SpeechRecognizerConfig @JvmOverloads constructor(
    val engineType: EngineType,
    val modelPath: String,
    val sampleRateHz: Int = 16_000,
    val language: String = DEFAULT_LANGUAGE,
    val threadCount: Int = defaultThreadCount()
) {
    companion object {
        private const val DEFAULT_WHISPER_INFERENCE_WINDOW_SAMPLES = 4_096
        const val DEFAULT_LANGUAGE = "ja"
        const val AUTO_DETECT_LANGUAGE = "auto"

        @JvmStatic
        fun defaultThreadCount(): Int {
            val available = Runtime.getRuntime().availableProcessors()
            return available.coerceIn(2, 8)
        }

        @JvmStatic
        fun defaultWhisperLanguage(): String = AUTO_DETECT_LANGUAGE

        @JvmStatic
        fun normalizeWhisperLanguage(language: String?): String {
            val normalized = language?.trim().orEmpty()
            if (normalized.isEmpty()) {
                return AUTO_DETECT_LANGUAGE
            }
            return normalized.lowercase(Locale.US)
        }

        @JvmStatic
        fun isWhisperAutoDetectLanguage(language: String?): Boolean {
            return normalizeWhisperLanguage(language) == AUTO_DETECT_LANGUAGE
        }

        @JvmStatic
        fun whisperInferenceWindowSamples(sampleRateHz: Int, language: String?): Int {
            if (!isWhisperAutoDetectLanguage(language)) {
                return DEFAULT_WHISPER_INFERENCE_WINDOW_SAMPLES
            }
            return maxOf(DEFAULT_WHISPER_INFERENCE_WINDOW_SAMPLES, sampleRateHz)
        }
    }
}
