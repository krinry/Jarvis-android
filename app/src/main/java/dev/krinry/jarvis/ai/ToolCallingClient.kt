package dev.krinry.jarvis.ai

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * ToolCallingClient — Handles the OpenAI-compatible tool-calling (function-calling) API loop.
 *
 * Supports all 3 providers (Groq, OpenRouter, Gemini) since they all accept
 * the same `tools` + `tool_choice` format.
 *
 * Flow:
 *   1. Send messages + tool definitions → LLM
 *   2. If response has `tool_calls` → extract function name + args
 *   3. Return ToolCallResult so the engine can execute + feed back
 *   4. Engine appends tool result as `role: "tool"` message and re-sends
 *   5. Repeat until LLM returns a plain text response (no more tool_calls)
 */
object ToolCallingClient {

    private const val TAG = "ToolCallingClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // Data classes for tool call responses
    // =========================================================================

    /** Represents one tool call requested by the LLM */
    data class ToolCall(
        val id: String,
        val functionName: String,
        val arguments: String  // Raw JSON string of arguments
    )

    /** The result of a single LLM call — either text content or tool calls */
    sealed class LlmResult {
        /** LLM returned a plain text response (final answer) */
        data class TextResponse(val content: String) : LlmResult()

        /** LLM wants to call one or more tools */
        data class ToolCallsResponse(
            val toolCalls: List<ToolCall>,
            val rawAssistantMessage: JSONObject  // Full assistant message to append to history
        ) : LlmResult()

        /** Something went wrong */
        data class Error(val message: String) : LlmResult()
    }

    // =========================================================================
    // Main API call — sends messages + tools, parses response
    // =========================================================================

    /**
     * Send a chat completion request with tool definitions.
     *
     * @param context        Android context (for API keys)
     * @param messages       Full message history as JSONArray
     * @param tools          Tool definitions from ToolDefinitions
     * @param temperature    LLM temperature (default 0.15 for agent precision)
     * @param maxTokens      Max response tokens
     * @return LlmResult — either TextResponse, ToolCallsResponse, or Error
     */
    suspend fun chatWithTools(
        context: Context,
        messages: JSONArray,
        tools: JSONArray,
        temperature: Double = 0.15,
        maxTokens: Int = 2048
    ): LlmResult = withContext(Dispatchers.IO) {
        try {
            val provider = GroqApiClient.getActiveProvider(context)
            val apiKey = provider.getApiKey(context)
                ?: return@withContext LlmResult.Error("No API key for ${provider.displayName}")
            
            var model = SecureKeyStore.getPrimaryModel(context).ifEmpty { provider.defaultModel }
            
            // 🛡️ Groq specific: Some models (like llama3-8b) don't support tools.
            // If tools are provided, force a tool-capable model on Groq.
            if (provider.id == "groq" && tools.length() > 0) {
                val nonToolModels = listOf("llama3-8b-8192", "llama-3.1-8b-instant", "llama3-70b-8192")
                if (model in nonToolModels || model.contains("8b")) {
                    Log.w(TAG, "Model $model doesn't support tools on Groq. Falling back to llama-3.3-70b-versatile.")
                    model = "llama-3.3-70b-versatile"
                }
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                
                // ⚠️ Only include tools if they exist to avoid 400 errors on some models/providers
                if (tools.length() > 0) {
                    put("tools", tools)
                    // "auto" is standard, but we'll ensure parallel calls are disabled to keep it simple for Groq
                    put("tool_choice", "auto")
                    put("parallel_tool_calls", false) 
                }
                
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }

            val jsonPayload = payload.toString()
            Log.d(TAG, "Tool calling payload: $jsonPayload")

            val requestBuilder = Request.Builder()
                .url("${provider.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))

            provider.extraHeaders().forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 10
                response.body?.close()
                return@withContext LlmResult.Error("Rate limited. Retry after ${retryAfter}s")
            }

            val body = response.body?.string()
            if (!response.isSuccessful) {
                val errBody = body?.take(500) ?: ""
                Log.e(TAG, "API error ${response.code}: $errBody")
                return@withContext LlmResult.Error("Server error ${response.code}: $errBody")
            }

            if (body.isNullOrBlank()) {
                return@withContext LlmResult.Error("Empty response body")
            }

            parseResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Tool calling failed", e)
            LlmResult.Error("Request failed: ${e.message?.take(100)}")
        }
    }

    // =========================================================================
    // Response parser — detects tool_calls vs text
    // =========================================================================

    private fun parseResponse(responseBody: String): LlmResult {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return LlmResult.Error("No choices in response")
            }

            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")
            val finishReason = choice.optString("finish_reason", "")

            // Check if the LLM wants to call tools
            val toolCallsArray = message.optJSONArray("tool_calls")
            if (toolCallsArray != null && toolCallsArray.length() > 0) {
                val toolCalls = mutableListOf<ToolCall>()
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val function = tc.getJSONObject("function")
                    val argsObj = function.opt("arguments")
                    val argsString = when (argsObj) {
                        is JSONObject -> argsObj.toString()
                        is String -> argsObj
                        else -> "{}"
                    }

                    toolCalls.add(ToolCall(
                        id = tc.optString("id", "call_$i"),
                        functionName = function.getString("name"),
                        arguments = argsString
                    ))
                }

                Log.d(TAG, "LLM requested ${toolCalls.size} tool call(s): " +
                        toolCalls.joinToString { it.functionName })

                return LlmResult.ToolCallsResponse(
                    toolCalls = toolCalls,
                    rawAssistantMessage = message
                )
            }

            // No tool calls → plain text response
            val content = message.optString("content", "")
            if (content.isNotBlank()) {
                LlmResult.TextResponse(content)
            } else {
                // Edge case: stop with no content (happens rarely)
                LlmResult.TextResponse("(empty response)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool call response", e)
            LlmResult.Error("Parse failed: ${e.message}")
        }
    }

    // =========================================================================
    // Helper — build a tool result message for the feedback loop
    // =========================================================================

    /**
     * Build a `role: "tool"` message to feed the tool's result back to the LLM.
     * This is appended to message history before re-sending.
     */
    fun buildToolResultMessage(toolCallId: String, result: String): JSONObject {
        return JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", result.take(3000))  // Cap at 3000 chars to save tokens
        }
    }
}
