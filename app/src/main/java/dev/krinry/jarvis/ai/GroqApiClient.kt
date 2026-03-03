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
 * GroqApiClient — Central LLM gateway that delegates to registered providers.
 *
 * Providers are modular (each in its own file):
 * - GroqProvider, OpenRouterProvider, GeminiProvider, etc.
 *
 * This class handles:
 * - Provider registry & resolution
 * - Rate limiting & throttling
 * - Whisper STT (via Groq, always)
 * - Chat completions routed to active provider
 */
object GroqApiClient {

    private const val TAG = "GroqApiClient"
    private const val WHISPER_MODEL = "whisper-large-v3-turbo"

    // === Provider Registry ===
    private val providers = listOf<LlmProvider>(
        GroqProvider(),
        OpenRouterProvider(),
        GeminiProvider()
    )

    /** Get all registered providers (for UI dropdown) */
    fun getProviders(): List<LlmProvider> = providers

    /** Get provider by ID */
    fun getProvider(id: String): LlmProvider? = providers.find { it.id == id }

    /** Get active provider based on user setting */
    fun getActiveProvider(context: Context): LlmProvider {
        val id = SecureKeyStore.getApiProvider(context)
        return getProvider(id) ?: providers.first()
    }

    // Rate limit tracking
    private val requestCountThisMinute = AtomicInteger(0)
    private val minuteWindowStart = AtomicLong(System.currentTimeMillis())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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

        if (count >= 28) {
            // Near RPM limit — wait for minute window to reset
            val waitMs = 60_000 - (System.currentTimeMillis() - minuteWindowStart.get()) + 200
            if (waitMs > 0) {
                Log.w(TAG, "RPM limit approaching ($count/min), waiting ${waitMs}ms")
                delay(waitMs)
                minuteWindowStart.set(System.currentTimeMillis())
                requestCountThisMinute.set(0)
            }
        } else if (configuredDelay > 0) {
            delay(configuredDelay.coerceAtMost(1000L)) // Cap at 1s for agent speed
        }
    }

    // =========================================================================
    // === Fetch Models (delegates to provider) ===
    // =========================================================================

    suspend fun fetchAvailableModels(context: Context): List<ModelInfo> = withContext(Dispatchers.IO) {
        val provider = getActiveProvider(context)
        val apiKey = provider.getApiKey(context) ?: return@withContext emptyList()
        provider.fetchModels(apiKey)
    }

    // =========================================================================
    // === Speech-to-Text (Whisper) — always via Groq ===
    // =========================================================================

    suspend fun transcribeAudio(
        context: Context, audioFile: File, language: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            // STT always uses Groq (only provider with Whisper)
            val groq = getProvider("groq") as? GroqProvider
            val apiKey = groq?.getApiKey(context)
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
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
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
            val provider = getActiveProvider(context)
            val apiKey = provider.getApiKey(context) ?: return@withContext null
            val model = SecureKeyStore.getPrimaryModel(context).ifEmpty { provider.defaultModel }
            chatDirect(provider, apiKey, messages, model)
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e); null
        }
    }

    private suspend fun chatDirect(
        provider: LlmProvider, apiKey: String,
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
            put("temperature", 0.7)
            put("max_tokens", 300)
        }

        val requestBuilder = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        provider.extraHeaders().forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) return@withContext null

        val body = response.body?.string() ?: return@withContext null
        JSONObject(body).optJSONArray("choices")?.let { choices ->
            if (choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            } else null
        }
    }

    /**
     * Agent chat — takes pre-built messages (no history assembly here).
     *
     * AgentLlmEngine now builds exactly 2-3 messages:
     * [system, context_summary, current_ui]
     *
     * This method handles: rate limiting, retry, fallback provider.
     */
    suspend fun agentChatDirect(
        context: Context,
        messages: List<Map<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val provider = getActiveProvider(context)
        val maxRetries = 2
        var useFallback = false

        for (attempt in 1..maxRetries) {
            try {
                checkAndThrottleRateLimit(context)

                val apiKey = provider.getApiKey(context)
                    ?: throw Exception("No API key for ${provider.displayName}")
                val model = if (useFallback)
                    SecureKeyStore.getFallbackModel(context).ifEmpty { provider.defaultFallbackModel }
                else
                    SecureKeyStore.getPrimaryModel(context).ifEmpty { provider.defaultModel }

                val result = agentChatDirect(provider, apiKey, messages, model)
                if (result != null) return@withContext result

            } catch (e: RateLimitException) {
                Log.w(TAG, "Rate limit: waiting ${e.retryAfterMs}ms (attempt $attempt)")
                delay(e.retryAfterMs.coerceAtMost(8000L))
                useFallback = true

            } catch (e: Exception) {
                Log.w(TAG, "Agent chat attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(1000L)
                } else {
                    throw e
                }
            }
        }
        null
    }

    private suspend fun agentChatDirect(
        provider: LlmProvider, apiKey: String,
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

        Log.d(TAG, "Agent LLM: $model via ${provider.displayName}")

        val requestBuilder = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        provider.extraHeaders().forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val retryMs = if (retryAfter != null) retryAfter * 1000L else 10_000L
            response.body?.close()
            throw RateLimitException(retryMs)
        }

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            Log.e(TAG, "Agent error: ${response.code} $errBody")
            throw Exception("Server error ${response.code}: ${errBody.take(100)}")
        }

        val body = response.body?.string() ?: return@withContext null
        JSONObject(body).optJSONArray("choices")?.let { choices ->
            if (choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            } else null
        }
    }

    private class RateLimitException(val retryAfterMs: Long) : Exception("Rate limited")
}
