package com.micklab.voicelistener

class NoOpSpeechRecognizerEngine : SpeechRecognizerEngine {
    override fun start() {
    }

    override fun stop() {
    }

    override fun transcribe(buffer: ShortArray): String = ""

    override fun release() {
    }
}
