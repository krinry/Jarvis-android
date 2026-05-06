package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.ToolCallingClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * ToolCallingEngine — Orchestrates the full tool-calling loop.
 *
 * This replaces the old "parse JSON → execute action → feed back" cycle
 * with proper native function calling:
 *
 *   1. User command → build messages + tool definitions
 *   2. Send to LLM with tools
 *   3. If LLM returns tool_calls → execute each via ToolDispatcher
 *   4. Feed tool results back → re-send to LLM
 *   5. Repeat until LLM returns a text response (done)
 *
 * Special tool markers:
 *   __ASK_USER__:question  → pause and ask user
 *   __WAIT__:seconds       → real delay (no API cost)
 *   __DONE__:summary       → task complete
 */
class ToolCallingEngine(private val context: Context) {

    companion object {
        private const val TAG = "ToolCallingEngine"
        private const val MAX_TOOL_ROUNDS = 30    // Max round-trips before giving up
        private const val MAX_CONSECUTIVE_ERRORS = 3

        private const val UI_SYSTEM_PROMPT = """You are Krinry, a specialized UI Agent.
You control this Android phone using Accessibility tools.

CORE RULES:
1. You MUST ALWAYS call a tool in every response. NO EXCEPTIONS.
2. If you are starting, call a tool to click, type, or open an app.
3. If you are finished, call `task_complete`.
4. NEVER say "I can't do that" or give text instructions. Use the tools.

EXAMPLES:
- User: "open settings" -> call `open_app(app_name="Settings")`
- User: "click login" -> call `click(node_id=123)`
"""

        private const val CODER_SYSTEM_PROMPT = """You are Krinry Coder Agent.
You manage files, run terminal commands, and write code.

CORE RULE: 
- You MUST ALWAYS call a tool. 
- NEVER reply with text only.
- For code generation, use `termux_write_file` or `delegate_ai`.
- ALWAYS conclude with `task_complete`."""

        private const val GENERAL_SYSTEM_PROMPT = """You are Krinry, a powerful AI assistant with NATIVE SYSTEM ACCESS to this Android phone. 
You are NOT just a chatbot; you are a system-level agent.

CORE RULES:
1. You MUST ALWAYS call at least one tool in every response.
2. If you are just answering a question, call `task_complete(summary="Your answer here")`.
3. If you are performing a task (open app, call, etc.), call the specific tool FIRST, then `task_complete`.
4. NEVER say "I can't do that". You HAVE tools for calls, SMS, apps, and more.

TOOL-SPECIFIC GUIDELINES:
- To launch any app (WhatsApp, YouTube, etc.), use `open_app(app_name: "Name")`.
- To call someone, use `call(phone_number: "number")`.
- To set a timer/alarm, use `set_timer` or `set_alarm`.
- To search the internet, use `search_web`.

EXAMPLE:
User: "open whatsapp"
AI Call: `open_app(app_name="WhatsApp")` -> `task_complete(summary="WhatsApp open kar diya hai.")`
"""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    var onAskUser: (suspend (question: String) -> String?)? = null
    var onPendingAction: (suspend (actionType: String, target: String, details: String, fullJson: String) -> Boolean)? = null

    /**
     * Callback for live tool call updates — used by Chat UI to show tool bubbles.
     * Parameters: (toolName, status, arguments, result)
     *   status: "R" = running, "S" = success, "F" = failed, "D" = denied
     */
    var onToolCallUpdate: ((toolName: String, status: String, arguments: String, result: String?) -> Unit)? = null

    private var consecutiveErrors = 0

    /**
     * Run the full tool-calling loop for a user command.
     *
     * @param command       The user's voice/text command
     * @param agentType     The route decision from RouterAgent
     * @return Final text response or null
     */
    suspend fun runToolLoop(command: String, agentType: AgentType): String? {
        consecutiveErrors = 0

        // 1. Pick system prompt and tool set based on agent type
        val systemPrompt = when (agentType) {
            AgentType.CODER_AGENT -> CODER_SYSTEM_PROMPT
            AgentType.GENERAL_CHAT -> GENERAL_SYSTEM_PROMPT
            AgentType.UI_AGENT -> UI_SYSTEM_PROMPT
        }
        val tools = when (agentType) {
            AgentType.CODER_AGENT -> ToolDefinitions.getCoderTools()
            AgentType.GENERAL_CHAT -> ToolDefinitions.getGeneralTools()
            AgentType.UI_AGENT -> ToolDefinitions.getAllTools()
        }

        // 2. Build initial message history
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // For UI_AGENT, include current screen context
        val userContent = if (agentType == AgentType.UI_AGENT) {
            val uiJson = extractCurrentScreenJson()
            "User command: $command\n\nCurrent screen UI:\n$uiJson"
        } else {
            command
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        onStatusUpdate?.invoke("🧠 Soch raha hoon...")
        Log.d(TAG, "Starting tool loop: route=$agentType, command=${command.take(80)}")

        // 3. The tool-calling loop
        for (round in 1..MAX_TOOL_ROUNDS) {
            if (!coroutineContext.isActive) return null

            onStatusUpdate?.invoke("🤔 Step $round...")

            val result = ToolCallingClient.chatWithTools(context, messages, tools)

            when (result) {
                is ToolCallingClient.LlmResult.TextResponse -> {
                    // LLM gave a final text answer (no more tools to call)
                    Log.d(TAG, "Final text response in round $round: ${result.content.take(100)}")
                    onStatusUpdate?.invoke("✅ Done")
                    return result.content
                }

                is ToolCallingClient.LlmResult.ToolCallsResponse -> {
                    consecutiveErrors = 0

                    // Append the assistant's message (with tool_calls) to history
                    messages.put(result.rawAssistantMessage)

                    // Execute each tool call
                    for (toolCall in result.toolCalls) {
                        if (!coroutineContext.isActive) return null

                        Log.d(TAG, "Round $round: executing ${toolCall.functionName}(${toolCall.arguments.take(100)})")
                        onStatusUpdate?.invoke("⚡ ${toolCall.functionName}...")

                        // 🔔 Notify UI: tool is RUNNING
                        onToolCallUpdate?.invoke(toolCall.functionName, "R", toolCall.arguments, null)

                        // ── Permission check for dangerous tools ──
                        val dangerousTools = setOf(
                            "write_file", "delete_path", "move_file", "create_dir",
                            "termux_run", "termux_write_file", "termux_modify_file",
                            "http_post"
                        )
                        if (toolCall.functionName in dangerousTools && onPendingAction != null) {
                            val args = try { JSONObject(toolCall.arguments) } catch (_: Exception) { JSONObject() }
                            val target = args.optString("path", "")
                                .ifEmpty { args.optString("command", "") }
                                .ifEmpty { args.optString("url", "unknown") }
                            val details = "${toolCall.functionName}: $target"

                            onStatusUpdate?.invoke("🔒 Permission required...")
                            val approved = onPendingAction?.invoke(
                                toolCall.functionName, target, details, toolCall.arguments
                            ) ?: true

                            if (!approved) {
                                val denyResult = "❌ User denied permission for ${toolCall.functionName}"
                                messages.put(ToolCallingClient.buildToolResultMessage(toolCall.id, denyResult))
                                onStatusUpdate?.invoke(denyResult)
                                // 🔔 Notify UI: tool was DENIED
                                onToolCallUpdate?.invoke(toolCall.functionName, "D", toolCall.arguments, denyResult)
                                continue
                            }
                        }

                        // ── Execute the tool ──
                        val toolResult = try {
                            ToolDispatcher.dispatch(
                                toolCall.functionName, toolCall.arguments, context
                            )
                        } catch (e: Exception) {
                            val errorMsg = "Error: ${e.message ?: "Unknown error"}"
                            // 🔔 Notify UI: tool FAILED
                            onToolCallUpdate?.invoke(toolCall.functionName, "F", toolCall.arguments, errorMsg)
                            messages.put(ToolCallingClient.buildToolResultMessage(toolCall.id, errorMsg))
                            continue
                        }

                        // ── Handle special control markers ──
                        when {
                            toolResult.startsWith("__ASK_USER__:") -> {
                                val question = toolResult.removePrefix("__ASK_USER__:")
                                onStatusUpdate?.invoke("🗣 $question")
                                val answer = onAskUser?.invoke(question)
                                val answerText = if (answer.isNullOrBlank()) {
                                    "User did not respond"
                                } else {
                                    "User answered: $answer"
                                }
                                messages.put(ToolCallingClient.buildToolResultMessage(toolCall.id, answerText))
                            }
                            toolResult.startsWith("__WAIT__:") -> {
                                val seconds = toolResult.removePrefix("__WAIT__:")
                                    .toIntOrNull()?.coerceIn(3, 60) ?: 10
                                onStatusUpdate?.invoke("⏳ ${seconds}s wait...")
                                delay(seconds * 1000L)
                                messages.put(ToolCallingClient.buildToolResultMessage(
                                    toolCall.id, "✅ Waited ${seconds} seconds"
                                ))
                            }
                            toolResult.startsWith("__DONE__:") -> {
                                val summary = toolResult.removePrefix("__DONE__:")
                                onStatusUpdate?.invoke("✅ $summary")
                                return summary
                            }
                            else -> {
                                // Normal tool result — feed it back
                                messages.put(ToolCallingClient.buildToolResultMessage(toolCall.id, toolResult))
                                onStatusUpdate?.invoke(toolResult.take(80))
                                // 🔔 Notify UI: tool SUCCESS
                                onToolCallUpdate?.invoke(toolCall.functionName, "S", toolCall.arguments, toolResult.take(300))
                            }
                        }

                        // Small delay between tool calls to avoid overwhelming
                        delay(300)
                    }

                    // After processing all tool calls, refresh UI context for UI_AGENT
                    if (agentType == AgentType.UI_AGENT) {
                        delay(1000) // Let the screen settle
                    }
                }

                is ToolCallingClient.LlmResult.Error -> {
                    consecutiveErrors++
                    Log.e(TAG, "Round $round error ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS): ${result.message}")
                    onStatusUpdate?.invoke("❌ ${result.message.take(60)}")

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        onStatusUpdate?.invoke("❌ Too many errors. Stopping.")
                        return null
                    }

                    delay(2000L * consecutiveErrors) // Exponential backoff
                }
            }
        }

        onStatusUpdate?.invoke("⚠️ Max rounds ($MAX_TOOL_ROUNDS) reached")
        return null
    }

    /**
     * Extract current screen as compact JSON for UI_AGENT context.
     */
    private fun extractCurrentScreenJson(): String {
        return try {
            val service = dev.krinry.jarvis.service.AutoAgentService.instance
            val root = service?.getRootNode()
            if (root != null) {
                val nodes = UiTreeExtractor.extractTree(root)
                UiTreeExtractor.toJson(nodes)
            } else {
                "{}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen extraction failed", e)
            "{}"
        }
    }
}
