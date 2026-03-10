package com.dragon.rokidclaw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MiaClaw Client v2
 * WAV → local Whisper STT (Mac Mini :8765) → text → MiaClaw gateway (:18789) → reply
 */
class OpenClawClient(
    private val macMiniIp: String = BuildConfig.MAC_MINI_IP
) {
    private val sttUrl  get() = "http://$macMiniIp:8765"
    private val clawUrl get() = "http://$macMiniIp:18789"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** WAV bytes → text via local Whisper */
    suspend fun transcribe(wavBytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "rec.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType()))
                .build()
            val res = client.newCall(
                Request.Builder().url("$sttUrl/stt").post(body).build()
            ).execute()
            if (!res.isSuccessful) return@withContext Result.failure(IOException("STT ${res.code}"))
            val text = JSONObject(res.body!!.string()).getString("text").trim()
            Result.success(text)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Text → MiaClaw gateway → brief reply */
    suspend fun ask(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("message", "[Rokid] $text")
            }
            val res = client.newCall(
                Request.Builder()
                    .url("$clawUrl/api/message")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            if (!res.isSuccessful) return@withContext Result.failure(IOException("Gateway ${res.code}"))
            val raw = res.body!!.string()
            val reply = try { JSONObject(raw).optString("reply", raw) } catch (e: Exception) { raw }
            Result.success(reply.trim())
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Full pipeline: WAV → STT → Mia → (transcript, reply) */
    suspend fun processAudio(wavBytes: ByteArray): Result<Pair<String, String>> {
        val sttResult = transcribe(wavBytes)
        if (sttResult.isFailure) return Result.failure(sttResult.exceptionOrNull()!!)
        val transcript = sttResult.getOrThrow()
        if (transcript.isBlank()) return Result.failure(IOException("Empty transcription"))
        val replyResult = ask(transcript)
        if (replyResult.isFailure) return Result.failure(replyResult.exceptionOrNull()!!)
        return Result.success(transcript to replyResult.getOrThrow())
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try { client.newCall(Request.Builder().url("$sttUrl/health").build()).execute().isSuccessful }
        catch (e: Exception) { false }
    }
}
