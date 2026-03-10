package com.dragon.rokidclaw

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio from the glasses microphone and returns WAV bytes.
 * Used instead of SpeechRecognizer (not available on Rokid).
 * Audio is sent to the bridge server for STT processing on PC.
 */
class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioData = ByteArrayOutputStream()

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 15000L // Max 15 seconds
    }

    fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return false
            }

            audioData.reset()
            isRecording = true
            audioRecord?.startRecording()

            val startTime = System.currentTimeMillis()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording && (System.currentTimeMillis() - startTime) < MAX_DURATION_MS) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(audioData) {
                            audioData.write(buffer, 0, read)
                        }
                    }
                }
                if (isRecording) {
                    // Auto-stop after max duration
                    isRecording = false
                }
            }
            recordingThread?.start()

            Log.d(TAG, "Recording started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return false
        }
    }

    /**
     * Stop recording and return WAV file bytes.
     */
    fun stopRecording(): ByteArray? {
        isRecording = false
        recordingThread?.join(2000)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData: ByteArray
        synchronized(audioData) {
            pcmData = audioData.toByteArray()
        }

        if (pcmData.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            return null
        }

        Log.d(TAG, "Recorded ${pcmData.size} bytes of PCM audio")
        return pcmToWav(pcmData)
    }

    val isCurrentlyRecording: Boolean get() = isRecording

    /**
     * Convert raw PCM to WAV format
     */
    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)

        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        // WAV header
        writer.writeBytes("RIFF")
        writer.writeIntLE(chunkSize)
        writer.writeBytes("WAVE")
        writer.writeBytes("fmt ")
        writer.writeIntLE(16) // subchunk size
        writer.writeShortLE(1) // PCM format
        writer.writeShortLE(channels)
        writer.writeIntLE(SAMPLE_RATE)
        writer.writeIntLE(byteRate)
        writer.writeShortLE(blockAlign)
        writer.writeShortLE(bitsPerSample)
        writer.writeBytes("data")
        writer.writeIntLE(dataSize)
        writer.write(pcm)

        return output.toByteArray()
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(buf.array())
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        write(buf.array())
    }
}
