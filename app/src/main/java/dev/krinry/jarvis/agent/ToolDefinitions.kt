package dev.krinry.jarvis.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolDefinitions — Declares all tools in OpenAI-compatible function-calling format.
 *
 * All 3 providers (Groq, OpenRouter/OpenAI, Gemini) accept this schema.
 * Each tool maps 1:1 to an existing executor in the codebase.
 *
 * Categories:
 *   📁 File System  → NativeFileExecutor
 *   🖥️ Terminal     → TermuxBridge
 *   🌐 Network      → WebApiExecutor, AgentWebViewManager
 *   📞 Device       → DirectIntentExecutor, ContactsLookup, NotificationReader
 *   🖱️ UI Control   → ActionExecutor (click, type, scroll…)
 *   🤖 Delegation   → AiAppDelegator
 */
object ToolDefinitions {

    // =========================================================================
    // Schema builder helpers
    // =========================================================================

    private fun param(type: String, description: String): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
    }

    private fun tool(
        name: String,
        description: String,
        requiredParams: Map<String, JSONObject>,
        optionalParams: Map<String, JSONObject> = emptyMap()
    ): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        requiredParams.forEach { (k, v) -> properties.put(k, v); required.put(k) }
        optionalParams.forEach { (k, v) -> properties.put(k, v) }

        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    if (required.length() > 0) {
                        put("required", required)
                    }
                })
            })
        }
    }

    // =========================================================================
    // Tool definitions — one per action the AI can invoke
    // =========================================================================

    fun getAllTools(): JSONArray {
        val tools = JSONArray()

        // ── 📁 File System ──────────────────────────────────────────
        tools.put(tool(
            "create_dir",
            "Create a directory (and parents). Use for new project folders.",
            mapOf("path" to param("string", "Absolute or relative path to create"))
        ))
        tools.put(tool(
            "write_file",
            "Write text content to a file. Creates parent dirs if needed.",
            mapOf(
                "path" to param("string", "File path to write"),
                "body" to param("string", "Full text content of the file")
            )
        ))
        tools.put(tool(
            "read_file",
            "Read text content of a file (max 100 KB).",
            mapOf("path" to param("string", "File path to read"))
        ))
        tools.put(tool(
            "list_files",
            "List files and subdirectories in a directory.",
            mapOf("path" to param("string", "Directory path to list"))
        ))
        tools.put(tool(
            "delete_path",
            "Delete a file or directory recursively.",
            mapOf("path" to param("string", "Path to delete"))
        ))
        tools.put(tool(
            "move_file",
            "Move or rename a file/directory.",
            mapOf(
                "path" to param("string", "Source path"),
                "new_path" to param("string", "Destination path")
            )
        ))

        // ── 🖥️ Terminal (Termux) ────────────────────────────────────
        tools.put(tool(
            "termux_run",
            "Run a shell command in Termux. Use for git, npm, python, etc.",
            mapOf("command" to param("string", "The shell command to execute"))
        ))
        tools.put(tool(
            "termux_write_file",
            "Write file content into Termux home directory via safe copy.",
            mapOf(
                "path" to param("string", "File path relative to Termux home or absolute"),
                "body" to param("string", "Full text content of the file")
            )
        ))
        tools.put(tool(
            "termux_read_file",
            "Read a file from Termux home directory.",
            mapOf("path" to param("string", "File path to read"))
        ))
        tools.put(tool(
            "termux_modify_file",
            "Modify an existing file: replace text, append, or prepend.",
            mapOf(
                "path" to param("string", "File path to modify"),
                "body" to param("string", "New content (replacement or content to add)")
            ),
            mapOf(
                "text" to param("string", "Old text to find (for replace mode)"),
                "mode" to param("string", "One of: replace, append, prepend. Default: replace")
            )
        ))

        // ── 🌐 Network / Browser ────────────────────────────────────
        tools.put(tool(
            "http_get",
            "Make an HTTP GET request and return the response body.",
            mapOf("url" to param("string", "The URL to fetch"))
        ))
        tools.put(tool(
            "http_post",
            "Make an HTTP POST request with a JSON body.",
            mapOf("url" to param("string", "The URL to post to")),
            mapOf("body" to param("string", "JSON body string"))
        ))
        tools.put(tool(
            "open_browser",
            "Open a URL in Jarvis's built-in WebView browser.",
            mapOf("url" to param("string", "URL to open"))
        ))
        tools.put(tool(
            "execute_javascript",
            "Execute JavaScript code in the currently open browser tab.",
            mapOf("script" to param("string", "JavaScript code to execute"))
        ))
        tools.put(tool(
            "close_browser",
            "Close the built-in WebView browser.",
            emptyMap()
        ))

        // ── 📞 Device Actions ───────────────────────────────────────
        tools.put(tool(
            "call",
            "Make a phone call.",
            mapOf("phone" to param("string", "Phone number to call"))
        ))
        tools.put(tool(
            "send_sms",
            "Send an SMS text message.",
            mapOf(
                "phone" to param("string", "Recipient phone number"),
                "text" to param("string", "Message body")
            )
        ))
        tools.put(tool(
            "set_alarm",
            "Set an alarm on the device. Time format: HH:MM or natural language.",
            mapOf("text" to param("string", "Alarm time, e.g. '07:30' or '7 baje subah'"))
        ))
        tools.put(tool(
            "set_timer",
            "Set a countdown timer.",
            mapOf("text" to param("string", "Duration, e.g. '5 minutes' or '10 min'"))
        ))
        tools.put(tool(
            "navigate",
            "Open Google Maps navigation to a destination.",
            mapOf("text" to param("string", "Destination address or place name"))
        ))
        tools.put(tool(
            "search_web",
            "Search the web using default browser.",
            mapOf("text" to param("string", "Search query"))
        ))
        tools.put(tool(
            "flashlight",
            "Turn flashlight on or off.",
            mapOf("text" to param("string", "on or off"))
        ))
        tools.put(tool(
            "set_volume",
            "Set device volume level or mode.",
            mapOf("text" to param("string", "Volume level 0-100, or 'silent', 'vibrate', 'full'"))
        ))
        tools.put(tool(
            "find_contact",
            "Look up a contact by name and return their phone number.",
            mapOf("text" to param("string", "Contact name to search"))
        ))
        tools.put(tool(
            "read_notifications",
            "Read all current notifications on the device.",
            emptyMap()
        ))
        tools.put(tool(
            "dismiss_notification",
            "Dismiss a specific notification.",
            mapOf("text" to param("string", "Notification text or app name to dismiss"))
        ))

        // ── 🖱️ UI Control ───────────────────────────────────────────
        tools.put(tool(
            "click",
            "Click a UI element by its node ID.",
            mapOf("node_id" to param("integer", "ID of the UI element to click")),
            mapOf("text" to param("string", "Fallback text label of the element"))
        ))
        tools.put(tool(
            "type",
            "Type text into a focused input field.",
            mapOf(
                "node_id" to param("integer", "ID of the text field"),
                "text" to param("string", "Text to type")
            )
        ))
        tools.put(tool(
            "tap_xy",
            "Tap at specific screen coordinates.",
            mapOf(
                "x" to param("integer", "X coordinate"),
                "y" to param("integer", "Y coordinate")
            )
        ))
        tools.put(tool(
            "long_press",
            "Long press at specific screen coordinates.",
            mapOf(
                "x" to param("integer", "X coordinate"),
                "y" to param("integer", "Y coordinate")
            )
        ))
        tools.put(tool(
            "swipe",
            "Swipe in a direction on the screen.",
            mapOf("direction" to param("string", "left, right, up, or down"))
        ))
        tools.put(tool(
            "scroll_down", "Scroll the current view downward.", emptyMap()
        ))
        tools.put(tool(
            "scroll_up", "Scroll the current view upward.", emptyMap()
        ))
        tools.put(tool(
            "back", "Press the system Back button.", emptyMap()
        ))
        tools.put(tool(
            "home", "Press the system Home button.", emptyMap()
        ))
        tools.put(tool(
            "recent", "Open the Recent Apps view.", emptyMap()
        ))
        tools.put(tool(
            "open_app",
            "Launch an installed app by name.",
            mapOf("app_name" to param("string", "Name of the app to open"))
        ))
        tools.put(tool(
            "open_url",
            "Open a URL in the default browser.",
            mapOf("url" to param("string", "URL to open"))
        ))
        tools.put(tool(
            "screenshot",
            "Take a screenshot of the current screen.",
            emptyMap()
        ))
        tools.put(tool(
            "analyze_screen",
            "Use vision AI to analyze what's visible on screen.",
            mapOf("question" to param("string", "What to look for on screen"))
        ))
        tools.put(tool(
            "copy", "Copy selected text to clipboard.", emptyMap()
        ))
        tools.put(tool(
            "paste",
            "Paste clipboard content into a field.",
            emptyMap(),
            mapOf("node_id" to param("integer", "Optional: ID of the target field"))
        ))
        tools.put(tool(
            "select_all", "Select all text in the focused field.", emptyMap()
        ))
        tools.put(tool(
            "open_notifications", "Pull down the notification shade.", emptyMap()
        ))
        tools.put(tool(
            "read_clipboard", "Read current clipboard contents.", emptyMap()
        ))

        // ── 🤖 AI Delegation ────────────────────────────────────────
        tools.put(tool(
            "delegate_ai",
            "Delegate a large task to another AI app (ChatGPT/Gemini/DeepSeek). Use for code gen >100 lines.",
            mapOf(
                "app_name" to param("string", "AI app: ChatGPT, Gemini, DeepSeek, or Kimi"),
                "prompt" to param("string", "The full prompt to send to the AI app")
            )
        ))

        // ── ❓ Control ──────────────────────────────────────────────
        tools.put(tool(
            "ask_user",
            "Ask the user a question and wait for their voice response. Use when confused or need a choice.",
            mapOf("question" to param("string", "Hindi question to ask the user"))
        ))
        tools.put(tool(
            "wait",
            "Pause execution for a specified number of seconds. Use when waiting for downloads or loading.",
            mapOf("seconds" to param("integer", "Seconds to wait (3-60)"))
        ))
        tools.put(tool(
            "task_complete",
            "Mark the current task as done and speak a final summary to the user.",
            mapOf("summary" to param("string", "Hindi summary of what was accomplished"))
        ))

        return tools
    }

    /**
     * Get tools for CODER_AGENT only (file + terminal + network — no UI tools).
     * This is a performance optimization: fewer tool definitions = faster LLM response.
     */
    fun getCoderTools(): JSONArray {
        val coderActions = setOf(
            "create_dir", "write_file", "read_file", "list_files", "delete_path", "move_file",
            "termux_run", "termux_write_file", "termux_read_file", "termux_modify_file",
            "http_get", "http_post", "open_browser", "execute_javascript", "close_browser",
            "delegate_ai", "ask_user", "wait", "task_complete"
        )
        val all = getAllTools()
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            val name = t.getJSONObject("function").getString("name")
            if (name in coderActions) filtered.put(t)
        }
        return filtered
    }

    /**
     * Get tools for GENERAL_CHAT — expanded set for common assistant tasks.
     */
    fun getGeneralTools(): JSONArray {
        val generalActions = setOf(
            "ask_user", "task_complete", "search_web", 
            "call", "send_sms", "open_app", "navigate", 
            "set_alarm", "set_timer", "flashlight", "set_volume"
        )
        val all = getAllTools()
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            val name = t.getJSONObject("function").getString("name")
            if (name in generalActions) filtered.put(t)
        }
        return filtered
    }
}
