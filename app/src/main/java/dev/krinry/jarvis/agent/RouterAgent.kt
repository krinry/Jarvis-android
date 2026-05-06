package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AgentType {
    UI_AGENT,
    CODER_AGENT,
    GENERAL_CHAT
}

object RouterAgent {
    private const val TAG = "RouterAgent"

    private const val ROUTER_SYSTEM_PROMPT = """You are the Router Agent for Jarvis Android AI. Analyze the user's command and route it to the correct specialist.
Options:
1. 'UI_AGENT': For controlling phone screen, clicking, opening apps, WhatsApp, settings.
2. 'CODER_AGENT': For writing code, managing files, creating websites, terminal commands, or questions about your tools and capabilities.
3. 'GENERAL_CHAT': For casual chat or answering questions.
Output ONLY valid JSON: {"agent": "CODER_AGENT"}"""

    suspend fun determineRoute(userMessage: String, context: Context): AgentType = withContext(Dispatchers.IO) {
        try {
            val messages = listOf(
                mapOf("role" to "system", "content" to ROUTER_SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userMessage)
            )

            val response = GroqApiClient.agentChatDirect(context, messages)

            if (response != null) {
                val cleanJson = response.trim()
                    .removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()
                val start = cleanJson.indexOf('{')
                val end = cleanJson.lastIndexOf('}')

                if (start >= 0 && end > start) {
                    val json = JSONObject(cleanJson.substring(start, end + 1))
                    val agentStr = json.optString("agent")
                    return@withContext try {
                        AgentType.valueOf(agentStr)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid agent type returned: $agentStr, falling back to UI_AGENT")
                        AgentType.UI_AGENT
                    }
                }
            }
            Log.e(TAG, "Empty or invalid response from LLM, falling back to UI_AGENT")
            AgentType.UI_AGENT
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed, falling back to UI_AGENT", e)
            AgentType.UI_AGENT
        }
    }
}
