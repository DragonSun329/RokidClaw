package com.dragon.rokidclaw

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Rokid-OpenClaw Bridge.
 * Sends audio WAV to bridge for STT + OpenClaw processing.
 */
class OpenClawClient(private val bridgeUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Send audio WAV bytes to bridge for STT → OpenClaw → reply
     */
    suspend fun sendAudio(wavBytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", "recording.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$bridgeUrl/voice")
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Bridge returned ${response.code}: ${response.body?.string()}")
                )
            }

            val responseBody = response.body?.string() ?: ""
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val reply = json.get("reply")?.asString ?: responseBody

            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send text message (fallback)
     */
    suspend fun sendMessage(message: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("text", message)
            }

            val request = Request.Builder()
                .url("$bridgeUrl/message")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Bridge returned ${response.code}")
                )
            }

            val json = gson.fromJson(response.body?.string() ?: "", JsonObject::class.java)
            Result.success(json.get("reply")?.asString ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$bridgeUrl/health").get().build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
