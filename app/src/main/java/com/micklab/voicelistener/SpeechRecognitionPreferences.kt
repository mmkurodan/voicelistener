package com.micklab.voicelistener

import android.content.Context

object SpeechRecognitionPreferences {
    private const val PREFS_NAME = "VoiceListenerPrefs"
    private const val PREF_ACTIVE_ENGINE = "active_engine"

    @JvmStatic
    fun getActiveEngine(context: Context): EngineType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return EngineType.fromPreference(prefs.getString(PREF_ACTIVE_ENGINE, null))
    }

    @JvmStatic
    fun setActiveEngine(context: Context, engineType: EngineType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ACTIVE_ENGINE, engineType.name)
            .apply()
    }
}
