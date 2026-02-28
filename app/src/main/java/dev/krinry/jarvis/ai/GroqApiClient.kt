package dev.krinry.jarvis.ai

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified LLM Client supporting Groq and OpenRouter.
 *
 * API Providers:
 * - Groq: api.groq.com — fast, limited models (Whisper STT + LLM)
 * - OpenRouter: openrouter.ai — 300+ models, many free, OpenAI-compatible
 *
 * Features:
 * - Dynamic model selection (user picks from settings)
 * - Configurable request delay
 * - Smart rate limiting with Retry-After header parsing
 * - Automatic fallback model on repeated rate limits
 * - Whisper STT (always via Groq)
 */
object GroqApiClient {

    private const val TAG = "GroqApiClient"
    private const val GROQ_API_URL = "https://api.groq.com/openai/v1"
    private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1"

    // Default models (user can override in settings)
    private const val WHISPER_MODEL = "whisper-large-v3-turbo"
    private const val DEFAULT_GROQ_MODEL = "moonshotai/kimi-k2-instruct-0905"
    private const val DEFAULT_GROQ_FALLBACK = "openai/gpt-oss-120b"
    private const val DEFAULT_OPENROUTER_MODEL = "google/gemini-2.0-flash-exp:free"
    private const val DEFAULT_OPENROUTER_FALLBACK = "meta-llama/llama-3.3-70b-instruct:free"

    // Rate limit tracking
    private val requestCountThisMinute = AtomicInteger(0)
    private val minuteWindowStart = AtomicLong(System.currentTimeMillis())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // === Model & Provider Helpers ===
    // =========================================================================

    private fun getApiUrl(context: Context): String {
        return when (SecureKeyStore.getApiProvider(context)) {
            "openrouter" -> OPENROUTER_API_URL
            else -> GROQ_API_URL
        }
    }

    private fun getApiKey(context: Context): String? {
        return when (SecureKeyStore.getApiProvider(context)) {
            "openrouter" -> SecureKeyStore.getOpenRouterApiKey(context)
            else -> SecureKeyStore.getGroqApiKey(context)
        }
    }

    private fun getPrimaryModel(context: Context): String {
        val saved = SecureKeyStore.getPrimaryModel(context)
        if (saved.isNotEmpty()) return saved
        return when (SecureKeyStore.getApiProvider(context)) {
            "openrouter" -> DEFAULT_OPENROUTER_MODEL
            else -> DEFAULT_GROQ_MODEL
        }
    }

    private fun getFallbackModel(context: Context): String {
        val saved = SecureKeyStore.getFallbackModel(context)
        if (saved.isNotEmpty()) return saved
        return when (SecureKeyStore.getApiProvider(context)) {
            "openrouter" -> DEFAULT_OPENROUTER_FALLBACK
            else -> DEFAULT_GROQ_FALLBACK
        }
    }

    // =========================================================================
    // === Rate Limit ===
    // =========================================================================

    private suspend fun checkAndThrottleRateLimit(context: Context) {
        val now = System.currentTimeMillis()
        val elapsed = now - minuteWindowStart.get()

        if (elapsed >= 60_000) {
            minuteWindowStart.set(now)
            requestCountThisMinute.set(0)
        }

        val count = requestCountThisMinute.incrementAndGet()
        val configuredDelay = SecureKeyStore.getRequestDelayMs(context)

        if (count >= 25) {
            val waitMs = 60_000 - (System.currentTimeMillis() - minuteWindowStart.get()) + 500
            if (waitMs > 0) {
                Log.w(TAG, "RPM limit approaching ($count/min), waiting ${waitMs}ms")
                delay(waitMs)
                minuteWindowStart.set(System.currentTimeMillis())
                requestCountThisMinute.set(0)
            }
        } else {
            delay(configuredDelay)
        }
    }

    // =========================================================================
    // === Fetch Models (for settings UI) ===
    // =========================================================================

    /**
     * Fetch available models from the selected API provider.
     * Returns list of model IDs. Filters to chat-capable models.
     */
    suspend fun fetchAvailableModels(context: Context): List<ModelInfo> = withContext(Dispatchers.IO) {
        return@withContext when (SecureKeyStore.getApiProvider(context)) {
            "openrouter" -> fetchOpenRouterModels(context)
            else -> fetchGroqModels(context)
        }
    }

    data class ModelInfo(
        val id: String,
        val name: String,
        val isFree: Boolean = false,
        val contextLength: Int = 0
    )

    private suspend fun fetchGroqModels(context: Context): List<ModelInfo> {
        val apiKey = SecureKeyStore.getGroqApiKey(context) ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$GROQ_API_URL/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i)
                val id = model.optString("id", "")
                if (id.isNotEmpty() && !id.contains("whisper") && !id.contains("guard")) {
                    models.add(ModelInfo(
                        id = id,
                        name = id,
                        isFree = true,
                        contextLength = model.optInt("context_window", 0)
                    ))
                }
            }
            models.sortedBy { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch Groq models failed", e)
            emptyList()
        }
    }

    private suspend fun fetchOpenRouterModels(context: Context): List<ModelInfo> {
        return try {
            val request = Request.Builder()
                .url("$OPENROUTER_API_URL/models")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i)
                val id = model.optString("id", "")
                val name = model.optString("name", id)
                val pricing = model.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
                val isFree = promptPrice == 0.0 || id.contains(":free")
                val contextLength = model.optInt("context_length", 0)

                if (id.isNotEmpty()) {
                    models.add(ModelInfo(
                        id = id,
                        name = name,
                        isFree = isFree,
                        contextLength = contextLength
                    ))
                }
            }
            // Sort: free first, then by name
            models.sortedWith(compareByDescending<ModelInfo> { it.isFree }.thenBy { it.name })
        } catch (e: Exception) {
            Log.e(TAG, "Fetch OpenRouter models failed", e)
            emptyList()
        }
    }

    // =========================================================================
    // === Speech-to-Text (Whisper) — always via Groq ===
    // =========================================================================

    suspend fun transcribeAudio(
        context: Context,
        audioFile: File,
        language: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            // STT always uses Groq (OpenRouter doesn't support audio)
            val apiKey = SecureKeyStore.getGroqApiKey(context)
            if (apiKey.isNullOrEmpty()) {
                Log.e(TAG, "Groq API key needed for STT")
                return@withContext null
            }
            transcribeDirectGroq(apiKey, audioFile, language)
        } catch (e: Exception) {
            Log.e(TAG, "STT failed", e)
            null
        }
    }

    private suspend fun transcribeDirectGroq(
        apiKey: String, audioFile: File, language: String?
    ): String? = withContext(Dispatchers.IO) {
        val mimeType = when (audioFile.extension.lowercase()) {
            "m4a" -> "audio/m4a"; "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
            "webm" -> "audio/webm"; else -> "audio/wav"
        }

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("response_format", "text")
        language?.let { builder.addFormDataPart("language", it) }

        val request = Request.Builder()
            .url("$GROQ_API_URL/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(builder.build()).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "Whisper error: ${response.code}")
            return@withContext null
        }
        response.body?.string()?.trim()
    }

    

    // =========================================================================
    // === LLM Chat ===
    // =========================================================================

    suspend fun chat(
        context: Context, messages: List<Map<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        try {
            
                val apiKey = getApiKey(context)
                if (apiKey.isNullOrEmpty()) return@withContext null
                chatDirect(getApiUrl(context), apiKey, messages, getPrimaryModel(context))
            
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e); null
        }
    }

    private suspend fun chatDirect(
        baseUrl: String, apiKey: String, messages: List<Map<String, String>>, model: String
    ): String? = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg["role"]); put("content", msg["content"])
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 300)
        }

        val requestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        // OpenRouter requires HTTP-Referer header
        if (baseUrl.contains("openrouter")) {
            requestBuilder.addHeader("HTTP-Referer", "https://wokitoki.app")
            requestBuilder.addHeader("X-Title", "WokiToki")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) return@withContext null

        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        json.optJSONArray("choices")?.let { choices ->
            if (choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            } else null
        }
    }

    

    // =========================================================================
    // === Agent Chat — with rate limiting, retry, fallback ===
    // =========================================================================

    suspend fun agentChat(
        context: Context,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        currentMessage: String
    ): String? = withContext(Dispatchers.IO) {
        val maxRetries = 4
        var useFallback = false

        for (attempt in 1..maxRetries) {
            try {
                checkAndThrottleRateLimit(context)

                val messages = mutableListOf<Map<String, String>>()
                messages.add(mapOf("role" to "system", "content" to systemPrompt))
                for ((role, content) in history) {
                    messages.add(mapOf("role" to role, "content" to content))
                }
                messages.add(mapOf("role" to "user", "content" to currentMessage))

                val result = run {
                    val apiKey = getApiKey(context)
                    if (apiKey.isNullOrEmpty()) return@withContext null
                    val model = if (useFallback) getFallbackModel(context) else getPrimaryModel(context)
                    agentChatDirect(getApiUrl(context), apiKey, messages, model)
                }

                if (result != null) return@withContext result

            } catch (e: RateLimitException) {
                Log.w(TAG, "Rate limit: waiting ${e.retryAfterMs}ms (attempt $attempt)")
                delay(e.retryAfterMs)
                if (attempt >= 2) useFallback = true

            } catch (e: Exception) {
                Log.w(TAG, "Agent chat attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) delay((2000L * attempt).coerceAtMost(10_000L))
            }
        }
        null
    }

    private suspend fun agentChatDirect(
        baseUrl: String, apiKey: String,
        messages: List<Map<String, String>>, model: String
    ): String? = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg["role"]); put("content", msg["content"])
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.2)
            put("max_tokens", 300)
        }

        Log.d(TAG, "Agent LLM: $model via $baseUrl")

        val requestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        if (baseUrl.contains("openrouter")) {
            requestBuilder.addHeader("HTTP-Referer", "https://wokitoki.app")
            requestBuilder.addHeader("X-Title", "WokiToki Agent")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val retryMs = if (retryAfter != null) retryAfter * 1000L else 15_000L
            response.body?.close()
            throw RateLimitException(retryMs)
        }

        if (!response.isSuccessful) {
            Log.e(TAG, "Agent error: ${response.code} ${response.body?.string()}")
            return@withContext null
        }

        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        json.optJSONArray("choices")?.let { choices ->
            if (choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            } else null
        }
    }

    private class RateLimitException(val retryAfterMs: Long) : Exception("Rate limited")

    
}
