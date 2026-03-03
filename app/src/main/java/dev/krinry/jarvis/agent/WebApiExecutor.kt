package dev.krinry.jarvis.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebApiExecutor — Make HTTP requests without opening browser.
 *
 * AI can fetch weather, crypto prices, sports scores, or call any API.
 * Actions: http_get, http_post
 * Response is truncated to 2000 chars to save tokens.
 */
object WebApiExecutor {

    private const val TAG = "WebApiExecutor"
    private const val MAX_RESPONSE_LENGTH = 2000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Execute HTTP action. Must be called from a coroutine.
     */
    suspend fun execute(action: ActionExecutor.AgentAction): String {
        return when (action.action) {
            "http_get" -> httpGet(action)
            "http_post" -> httpPost(action)
            else -> "❓ Unknown web action: ${action.action}"
        }
    }

    /**
     * HTTP GET request.
     * AI: {"action":"http_get","url":"https://api.weather.com/current?city=delhi"}
     */
    private suspend fun httpGet(action: ActionExecutor.AgentAction): String {
        val url = action.url ?: return "❌ URL nahi diya"

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Jarvis-AI/1.0")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()?.take(MAX_RESPONSE_LENGTH) ?: ""
                val code = response.code

                Log.d(TAG, "GET $url → $code (${body.length} chars)")

                if (response.isSuccessful) {
                    "✅ Response ($code):\n$body"
                } else {
                    "❌ HTTP $code: ${body.take(200)}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP GET failed: $url", e)
                "❌ HTTP error: ${e.message?.take(80)}"
            }
        }
    }

    /**
     * HTTP POST request.
     * AI: {"action":"http_post","url":"https://api.example.com/data","body":"{\"key\":\"value\"}"}
     */
    private suspend fun httpPost(action: ActionExecutor.AgentAction): String {
        val url = action.url ?: return "❌ URL nahi diya"
        val body = action.body ?: ""

        return withContext(Dispatchers.IO) {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = body.toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Jarvis-AI/1.0")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()?.take(MAX_RESPONSE_LENGTH) ?: ""
                val code = response.code

                Log.d(TAG, "POST $url → $code (${responseBody.length} chars)")

                if (response.isSuccessful) {
                    "✅ Response ($code):\n$responseBody"
                } else {
                    "❌ HTTP $code: ${responseBody.take(200)}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP POST failed: $url", e)
                "❌ HTTP error: ${e.message?.take(80)}"
            }
        }
    }
}
