package com.micklab.voicelistener

interface SpeechRecognizerEngineFactory {
    fun create(config: SpeechRecognizerConfig): SpeechRecognizerEngine
}
