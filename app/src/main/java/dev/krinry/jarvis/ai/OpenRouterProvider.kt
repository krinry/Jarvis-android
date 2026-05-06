package dev.krinry.jarvis.ai

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenRouter Provider — 300+ models, many free, OpenAI-compatible.
 * API: openrouter.ai
 */
class OpenRouterProvider : LlmProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"
    override val baseUrl = "https://openrouter.ai/api/v1"
    override val defaultModel = "meta-llama/llama-3.1-70b-instruct"
    override val defaultFallbackModel = "google/gemini-2.0-flash-001"

    override fun extraHeaders(): Map<String, String> = mapOf(
        "HTTP-Referer" to "https://jarvis.krinry.dev",
        "X-Title" to "Jarvis AI Agent"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchModels(apiKey: String): List<ModelInfo> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .get().build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i)
                val modelId = model.optString("id", "")
                val name = model.optString("name", modelId)
                val pricing = model.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
                val isFree = promptPrice == 0.0 || modelId.contains(":free")
                val contextLength = model.optInt("context_length", 0)

                if (modelId.isNotEmpty()) {
                    models.add(ModelInfo(
                        id = modelId, name = name,
                        isFree = isFree, contextLength = contextLength
                    ))
                }
            }
            models.sortedWith(compareByDescending<ModelInfo> { it.isFree }.thenBy { it.name })
        } catch (e: Exception) {
            Log.e("OpenRouterProvider", "Fetch models failed", e)
            emptyList()
        }
    }
}
