package com.micklab.voicelistener

class DefaultSpeechRecognizerEngineFactory : SpeechRecognizerEngineFactory {
    override fun create(config: SpeechRecognizerConfig): SpeechRecognizerEngine {
        return when (config.engineType) {
            EngineType.VOSK -> VoskEngine(config.modelPath)
            EngineType.WHISPER -> WhisperEngine(
                sampleRateHz = config.sampleRateHz,
                language = config.language,
                threadCount = config.threadCount
            ).apply {
                loadModel(config.modelPath)
            }
        }
    }
}
