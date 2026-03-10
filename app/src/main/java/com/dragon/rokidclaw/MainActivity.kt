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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.status_text)
        inputText    = findViewById(R.id.input_text)
        responseText = findViewById(R.id.response_text)

        openClawClient = OpenClawClient()   // uses BuildConfig.MAC_MINI_IP
        audioRecorder  = AudioRecorder(this)

        // TTS: 中文优先，fallback English
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val zh = tts?.setLanguage(Locale.CHINESE)
                ttsReady = zh != TextToSpeech.LANG_MISSING_DATA && zh != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    ttsReady = tts?.setLanguage(Locale.US).let {
                        it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                }
                Log.d(TAG, "TTS ready=$ttsReady")
            }
        }

        statusText.text = "⚡ Mia\nConnecting..."

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            checkConnection()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) checkConnection()
        else statusText.text = "❌ Need mic permission"
    }

    private fun checkConnection() {
        scope.launch {
            val ok = openClawClient.ping()
            statusText.text = if (ok) "⚡ Mia Ready\nTap to talk" else "⚠️ No connection\nCheck WiFi"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (!isProcessing) { if (isRecording) stopAndSend() else startRecording() }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isRecording) {
                    isRecording = false
                    audioRecorder.stopRecording()
                    statusText.text = "⚡ Mia Ready\nTap to talk"
                } else moveTaskToBack(true)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun startRecording() {
        if (isRecording || isProcessing) return
        if (audioRecorder.startRecording()) {
            isRecording = true
            statusText.text = "🎤 Listening...\nTap to send"
            inputText.text = ""
            responseText.text = ""
            // 自动停止 15s
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
        inputText.text = "Transcribing..."

        scope.launch {
            val wavBytes = withContext(Dispatchers.IO) { audioRecorder.stopRecording() }

            if (wavBytes == null || wavBytes.size < 1000) {
                statusText.text = "⚠️ Too short\nTap to retry"
                inputText.text = ""
                isProcessing = false
                return@launch
            }

            val result = openClawClient.processAudio(wavBytes)

            result.onSuccess { (transcript, reply) ->
                inputText.text = "🎤 $transcript"
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

            result.onFailure { err ->
                val msg = err.message ?: "Unknown error"
                Log.e(TAG, "Error: $msg", err)
                responseText.text = "❌ $msg"
                inputText.text = ""
                statusText.text = "⚠️ Error\nTap to retry"
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

    companion object { private const val TAG = "RokidMia" }
}
