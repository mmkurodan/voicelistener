package com.micklab.voicelistener

class DefaultSpeechRecognizerEngineFactory : SpeechRecognizerEngineFactory {
    override fun create(config: SpeechRecognizerConfig): SpeechRecognizerEngine {
        return VoskEngine(config.modelPath)
    }
}
