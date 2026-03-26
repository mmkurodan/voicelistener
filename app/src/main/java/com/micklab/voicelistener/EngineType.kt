package com.micklab.voicelistener

enum class EngineType(val displayName: String) {
    VOSK("VOSK"),
    WHISPER("Whisper");

    companion object {
        @JvmStatic
        fun fromPreference(rawValue: String?): EngineType {
            val normalized = rawValue?.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: VOSK
        }
    }
}
