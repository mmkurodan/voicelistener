package com.micklab.voicelistener

enum class SummaryUpdateMode(val displayName: String) {
    AUTO("自動更新"),
    MANUAL("手動更新");

    companion object {
        @JvmStatic
        fun fromPreference(rawValue: String?): SummaryUpdateMode {
            val normalized = rawValue?.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: AUTO
        }
    }
}
