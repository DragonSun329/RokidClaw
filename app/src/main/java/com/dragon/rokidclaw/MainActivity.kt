package com.dragon.rokidclaw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Rokid OpenClaw — Voice assistant on AR glasses.
 * Tap → record → Groq Whisper STT (on PC) → OpenClaw → TTS reply
 */
class MainActivity : AppCompatActivity() {

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var openClawClient: OpenClawClient
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var inputText: TextView
    private lateinit var responseText: TextView

    private var isRecording = false
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        inputText = findViewById(R.id.input_text)
        responseText = findViewById(R.id.response_text)

        openClawClient = OpenClawClient(BuildConfig.OPENCLAW_GATEWAY_URL)
        audioRecorder = AudioRecorder(this)

        // Init TTS - try Chinese first, fallback to English
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val zhResult = tts?.setLanguage(Locale.CHINESE)
                if (zhResult == TextToSpeech.LANG_MISSING_DATA || zhResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Chinese TTS not available, trying English")
                    val enResult = tts?.setLanguage(Locale.US)
                    if (enResult == TextToSpeech.LANG_MISSING_DATA || enResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "No TTS language available")
                        ttsReady = false
                        return@TextToSpeech
                    }
                }
                ttsReady = true
                // Check available engines
                val engines = tts?.engines
                Log.d(TAG, "TTS ready. Engines: ${engines?.map { it.label }}")
            } else {
                Log.e(TAG, "TTS init failed with status $status")
            }
        }

        statusText.text = "⚡ Mia\nConnecting..."

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            checkConnection()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 100
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            checkConnection()
        } else {
            statusText.text = "❌ Need mic permission"
        }
    }

    private fun checkConnection() {
        scope.launch {
            val ok = openClawClient.ping()
            statusText.text = if (ok) {
                "⚡ Mia Ready\nTap to talk"
            } else {
                "⚠️ Cannot reach Mia\nTap to retry"
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (isProcessing) return true
                if (isRecording) stopAndSend() else startRecording()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isRecording) {
                    // Cancel recording
                    isRecording = false
                    audioRecorder.stopRecording()
                    statusText.text = "⚡ Mia Ready\nTap to talk"
                } else {
                    // Move to back, don't destroy
                    moveTaskToBack(true)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun startRecording() {
        if (isRecording || isProcessing) return
        val started = audioRecorder.startRecording()
        if (started) {
            isRecording = true
            statusText.text = "🎤 Recording...\nTap to send"
            inputText.text = ""
            responseText.text = ""
            handler.postDelayed({ if (isRecording) stopAndSend() }, 15000)
        } else {
            statusText.text = "❌ Mic error\nTap to retry"
        }
    }

    private fun stopAndSend() {
        if (!isRecording) return
        isRecording = false
        isProcessing = true
        handler.removeCallbacksAndMessages(null)
        statusText.text = "🧠 Thinking..."

        scope.launch {
            val wavBytes = withContext(Dispatchers.IO) { audioRecorder.stopRecording() }

            if (wavBytes == null || wavBytes.size < 1000) {
                statusText.text = "⚠️ Too short\nTap to retry"
                isProcessing = false
                return@launch
            }

            inputText.text = "🎤 Transcribing..."

            val result = openClawClient.sendAudio(wavBytes)

            result.onSuccess { reply ->
                // Show transcription if available
                inputText.text = ""
                responseText.text = reply

                if (ttsReady && reply.isNotEmpty()) {
                    statusText.text = "🔊 Speaking..."
                    speak(reply) {
                        runOnUiThread {
                            statusText.text = "⚡ Ready\nTap to talk"
                            isProcessing = false
                        }
                    }
                } else {
                    statusText.text = "⚡ Ready\nTap to talk"
                    isProcessing = false
                }
            }

            result.onFailure { error ->
                responseText.text = error.message?.take(100) ?: "Unknown error"
                statusText.text = "❌ Error\nTap to retry"
                isProcessing = false
            }
        }
    }

    private fun speak(text: String, onDone: () -> Unit) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone() }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) { onDone() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) audioRecorder.stopRecording()
        tts?.shutdown()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "RokidOpenClaw"
    }
}
