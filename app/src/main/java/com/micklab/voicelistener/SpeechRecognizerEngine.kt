package com.micklab.voicelistener

interface SpeechRecognizerEngine {
    fun start()
    fun stop()
    fun transcribe(buffer: ShortArray): String
    fun release()
}
