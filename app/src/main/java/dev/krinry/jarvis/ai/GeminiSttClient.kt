package dev.krinry.jarvis.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GeminiSttClient — Speech-to-Text using Gemini's native audio understanding.
 *
 * Sends raw audio (WAV/MP3/OGG) directly to Gemini's multimodal API.
 * Much better Hindi/Hinglish recognition than Whisper.
 *
 * Model: gemini-2.0-flash (or user-selected audio model)
 * API: generativelanguage.googleapis.com/v1beta
 */
object GeminiSttClient {

    private const val TAG = "GeminiSttClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val DEFAULT_MODEL = "gemini-2.0-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe audio file using Gemini's native audio support.
     * Returns transcribed text or null on failure.
     */
    suspend fun transcribeAudio(
        context: Context, audioFile: File, language: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val apiKey = SecureKeyStore.getProviderApiKey(context, "gemini")
            if (apiKey.isNullOrEmpty()) {
                Log.e(TAG, "Gemini API key needed for STT")
                return@withContext null
            }

            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val mimeType = when (audioFile.extension.lowercase()) {
                "m4a" -> "audio/m4a"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "audio/wav"
            }

            val langHint = language ?: "hi"
            val promptText = "Transcribe this audio EXACTLY as spoken. " +
                "The speaker is using Hindi, English, or Hinglish (Hindi in Latin script). " +
                "Output ONLY the transcription text. No explanation, no translation. " +
                "If Hindi words are spoken, write them in Roman/Latin script (e.g., 'Mom ko call karo')."

            // Build Gemini multimodal request
            val audioPart = JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", mimeType)
                    put("data", base64Audio)
                })
            }
            val textPart = JSONObject().apply {
                put("text", promptText)
            }

            val content = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(textPart)
                    put(audioPart)
                })
            }

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply { put(content) })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 200)
                })
            }

            val model = DEFAULT_MODEL
            val url = "$BASE_URL/$model:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Log.d(TAG, "Gemini STT: sending ${audioFile.length()} bytes to $model")

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Gemini STT error: ${response.code} $errBody")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val result = JSONObject(body)

            // Extract text from Gemini response
            val candidates = result.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val contentObj = firstCandidate.optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "").trim()
                    Log.d(TAG, "Gemini STT result: $text")
                    return@withContext text.ifEmpty { null }
                }
            }

            Log.w(TAG, "Gemini STT: no text in response")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Gemini STT failed", e)
            null
        }
    }
}
