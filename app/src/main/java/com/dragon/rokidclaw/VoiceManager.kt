package com.dragon.rokidclaw

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages STT (Speech-to-Text) and TTS (Text-to-Speech) on the glasses.
 *
 * STT: Uses Android SpeechRecognizer (can be swapped for Whisper later)
 * TTS: Uses Android TextToSpeech engine (can be swapped for ElevenLabs later)
 */
class VoiceManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStateChange: (VoiceState) -> Unit
) {
    enum class VoiceState {
        IDLE, LISTENING, PROCESSING, SPEAKING
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initialize() {
        // Init TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ttsReady = true
                Log.d(TAG, "TTS initialized")
            }
        }

        // Init STT
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createListener())
            Log.d(TAG, "STT initialized")
        } else {
            Log.w(TAG, "Speech recognition not available on this device")
        }
    }

    /**
     * Start listening for voice input.
     * Call this when the user presses the button or wake word is detected.
     */
    fun startListening() {
        onStateChange(VoiceState.LISTENING)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Speak text aloud via TTS.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready")
            onDone?.invoke()
            return
        }

        onStateChange(VoiceState.SPEAKING)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onStateChange(VoiceState.IDLE)
                onDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onStateChange(VoiceState.IDLE)
                onDone?.invoke()
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "openclaw_response")
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            onStateChange(VoiceState.PROCESSING)
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                else -> "Error $error"
            }
            Log.w(TAG, "STT error: $msg")
            onStateChange(VoiceState.IDLE)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                Log.d(TAG, "Recognized: $text")
                onResult(text)
            } else {
                onStateChange(VoiceState.IDLE)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Could show partial text on glasses display
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun destroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "VoiceManager"
    }
}
