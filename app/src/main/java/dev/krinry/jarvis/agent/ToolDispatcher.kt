package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ToolDispatcher — Receives a tool_call from the LLM, extracts arguments,
 * invokes the correct local executor, and returns the result string.
 *
 * This is the single "router" that maps function names → existing executors.
 * Every tool defined in ToolDefinitions has a corresponding branch here.
 */
object ToolDispatcher {

    private const val TAG = "ToolDispatcher"

    /**
     * Execute a tool call and return the result string.
     *
     * @param functionName  The tool name from LLM's tool_call (e.g. "write_file")
     * @param arguments     Raw JSON string of function arguments
     * @param context       Android context
     * @return Result string (✅ success or ❌ error)
     */
    suspend fun dispatch(
        functionName: String,
        arguments: String,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        try {
            val args = try {
                JSONObject(arguments)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse arguments for $functionName: $arguments", e)
                return@withContext "❌ Invalid arguments JSON: ${e.message}"
            }

            Log.d(TAG, "Dispatching: $functionName(${arguments.take(200)})")

            when (functionName) {
                // ── 📁 File System ──────────────────────────────────
                "create_dir" -> NativeFileExecutor.execute(
                    "create_dir", args.optString("path"), context = context
                )
                "write_file" -> NativeFileExecutor.execute(
                    "write_file", args.optString("path"), args.optString("body"), context = context
                )
                "read_file" -> NativeFileExecutor.execute(
                    "read_file", args.optString("path"), context = context
                )
                "list_files" -> NativeFileExecutor.execute(
                    "list_files", args.optString("path"), context = context
                )
                "delete_path" -> NativeFileExecutor.execute(
                    "delete_path", args.optString("path"), context = context
                )
                "move_file" -> NativeFileExecutor.execute(
                    "move_file", args.optString("path"), newPath = args.optString("new_path"), context = context
                )

                // ── 🖥️ Terminal (Termux) ────────────────────────────
                "termux_run", "termux_write_file", "termux_read_file", "termux_modify_file" -> {
                    val action = buildAgentAction(functionName, args)
                    TermuxBridge.execute(action, context)
                }

                // ── 🌐 Network / Browser ────────────────────────────
                "http_get" -> {
                    val action = buildAgentAction(functionName, args)
                    WebApiExecutor.execute(action)
                }
                "http_post" -> {
                    val action = buildAgentAction(functionName, args)
                    WebApiExecutor.execute(action)
                }
                "open_browser" -> {
                    val url = args.optString("url")
                    if (url.isBlank()) "❌ URL nahi diya" else {
                        AgentWebViewManager.instance?.openBrowser(url)
                        "✅ Browser open: $url"
                    }
                }
                "execute_javascript" -> {
                    val script = args.optString("script")
                    if (script.isBlank()) "❌ Script nahi diya" else {
                        AgentWebViewManager.instance?.executeJavascript(script)
                            ?: "❌ Browser open nahi hai"
                    }
                }
                "close_browser" -> {
                    AgentWebViewManager.instance?.closeBrowser()
                    "✅ Browser closed"
                }

                // ── 📞 Device Actions ───────────────────────────────
                "call", "send_sms", "set_alarm", "set_timer", "navigate",
                "search_web", "flashlight", "set_volume" -> {
                    val action = buildAgentAction(functionName, args)
                    DirectIntentExecutor.execute(action, context)
                }
                "find_contact" -> {
                    val action = buildAgentAction(functionName, args)
                    ContactsLookup.execute(action, context)
                }
                "read_notifications", "dismiss_notification" -> {
                    val action = buildAgentAction(functionName, args)
                    NotificationReader.execute(action, context)
                }

                // ── 🖱️ UI Control ───────────────────────────────────
                "click", "type", "tap_xy", "long_press", "swipe",
                "scroll_down", "scroll_up", "back", "home", "recent",
                "open_app", "open_url", "screenshot", "copy", "paste",
                "select_all", "open_notifications", "read_clipboard" -> {
                    val action = buildAgentAction(functionName, args)
                    val uiNodes = extractCurrentUiNodes()
                    ActionExecutor.execute(action, uiNodes)
                }
                "analyze_screen" -> {
                    val question = args.optString("question", "Check screen")
                    val service = AutoAgentService.instance
                    if (service != null) {
                        ScreenshotVision.analyzeScreen(question, service.applicationContext)
                    } else {
                        "❌ Accessibility service nahi chal rahi"
                    }
                }

                // ── 🤖 AI Delegation ────────────────────────────────
                "delegate_ai" -> {
                    val action = ActionExecutor.AgentAction(
                        action = "delegate_ai",
                        nodeId = null, text = args.optString("prompt"),
                        appName = args.optString("app_name"),
                        url = null, speech = null, status = "in_progress",
                        x = null, y = null, reason = null, waitSeconds = null,
                        phone = null, body = null, path = null, command = null
                    )
                    AiAppDelegator.execute(action, context)
                }

                // ── ❓ Control ──────────────────────────────────────
                "ask_user" -> {
                    // This is handled specially by ToolCallingEngine — return marker
                    "__ASK_USER__:${args.optString("question", "Kya karna hai?")}"
                }
                "wait" -> {
                    val seconds = args.optInt("seconds", 10).coerceIn(3, 60)
                    "__WAIT__:$seconds"
                }
                "task_complete" -> {
                    val summary = args.optString("summary", "Kaam ho gaya!")
                    "__DONE__:$summary"
                }

                else -> "❌ Unknown tool: $functionName"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool dispatch failed: $functionName", e)
            "❌ Tool execution error: ${e.message?.take(100)}"
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build an AgentAction from function name + parsed JSON args.
     * Maps tool-call argument names to AgentAction fields.
     */
    private fun buildAgentAction(functionName: String, args: JSONObject): ActionExecutor.AgentAction {
        return ActionExecutor.AgentAction(
            action = functionName,
            nodeId = if (args.has("node_id")) args.optInt("node_id", -1) else null,
            text = args.optString("text", "").takeIf { it.isNotEmpty() }
                ?: args.optString("direction", "").takeIf { it.isNotEmpty() }
                ?: args.optString("question", "").takeIf { it.isNotEmpty() }
                ?: args.optString("script", "").takeIf { it.isNotEmpty() },
            appName = args.optString("app_name", "").takeIf { it.isNotEmpty() },
            url = args.optString("url", "").takeIf { it.isNotEmpty() },
            speech = null,
            status = "in_progress",
            x = if (args.has("x")) args.optInt("x", -1) else null,
            y = if (args.has("y")) args.optInt("y", -1) else null,
            reason = null,
            waitSeconds = if (args.has("seconds")) args.optInt("seconds", 10) else null,
            phone = args.optString("phone", "").takeIf { it.isNotEmpty() },
            body = args.optString("body", "").takeIf { it.isNotEmpty() },
            path = args.optString("path", "").takeIf { it.isNotEmpty() },
            command = args.optString("command", "").takeIf { it.isNotEmpty() }
                ?: args.optString("mode", "").takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Extract current UI tree for UI-action execution.
     */
    private fun extractCurrentUiNodes(): List<UiTreeExtractor.UiNode> {
        return try {
            val root = AutoAgentService.instance?.getRootNode()
            if (root != null) UiTreeExtractor.extractTree(root) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract UI nodes", e)
            emptyList()
        }
    }
}
