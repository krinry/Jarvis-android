package dev.krinry.jarvis.ai

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Groq Provider — Fast inference, free models, Whisper STT support.
 * API: api.groq.com
 */
class GroqProvider : LlmProvider {
    override val id = "groq"
    override val displayName = "Groq"
    override val baseUrl = "https://api.groq.com/openai/v1"
    override val defaultModel = "llama-3.3-70b-versatile"
    override val defaultFallbackModel = "mixtral-8x7b-32768"
    override val supportsSTT = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchModels(apiKey: String): List<ModelInfo> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get().build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i)
                val modelId = model.optString("id", "")
                if (modelId.isNotEmpty() && !modelId.contains("whisper") && !modelId.contains("guard")) {
                    models.add(ModelInfo(
                        id = modelId,
                        name = modelId,
                        isFree = true,
                        contextLength = model.optInt("context_window", 0)
                    ))
                }
            }
            models.sortedBy { it.id }
        } catch (e: Exception) {
            Log.e("GroqProvider", "Fetch models failed", e)
            emptyList()
        }
    }
}
