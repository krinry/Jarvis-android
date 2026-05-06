package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * AgentLlmEngine — The brain of Krinry AI (Jarvis).
 *
 * Plan-first loop:
 *   Step 1: CMD + UI → AI returns plan + first action
 *   Step 2+: TaskMemory.summary + UI (or diff) → AI returns next action
 *
 * Token optimizations:
 * - TaskMemory: compact summary (~50 tokens) replaces raw history (~2000+ tokens)
 * - ScreenDiffCache: only sends changed UI nodes (~50-70% savings)
 * - Only 3 messages per LLM call: [system, context, current]
 * - Smart wait: real delay (no API call wasted during loading)
 */
class AgentLlmEngine(private val context: Context) {

    private val ttsManager = AgentTtsManager(context)
    private val taskMemory = TaskMemory()
    private val screenCache = ScreenDiffCache()

    companion object {
        private const val TAG = "AgentLlmEngine"
        private const val MAX_ITERATIONS = 50            // More steps for complex tasks
        private const val SCREEN_SETTLE_DELAY = 1200L   // 1.2s between iterations
        private const val MIN_API_INTERVAL = 1800L      // Minimum 1.8s between API calls
        private const val MAX_CONSECUTIVE_ERRORS = 3    // Stop after 3 parse failures

        private const val SYSTEM_PROMPT = """You are Krinry, AI phone assistant. Control phone via AccessibilityService.
Respond ONLY in valid JSON. No markdown, no explanation, no extra text.

FIRST CALL: Return {"plan":["step1","step2",...],"action":"...","speech":"Hindi","reason":"...","status":"in_progress"}

JSON FORMAT — always include ALL fields:
{"action":"ACTION_NAME", "speech":"", "reason":"why", "status":"in_progress"}
+ extra fields based on action (see below)

UI ACTIONS (need screen):
- click: +node_id (integer) | type: +node_id,text | tap_xy: +x,y | long_press: +x,y
- scroll_down | scroll_up | swipe: +text(left/right/up/down)
- back | home | recent | open_app: +app_name | open_url: +url
- screenshot | analyze_screen: +text(question) | copy | paste: +node_id | select_all | open_notifications | read_clipboard

FAST ACTIONS (no UI needed — use these when possible!):
- call: +phone | send_sms: +phone,text | set_alarm: +text | set_timer: +text
- navigate: +text | search_web: +text | flashlight: +text(on/off) | set_volume: +text
- find_contact: +text(name) | read_notifications | dismiss_notification: +text
- list_files: +path | read_file: +path | write_file: +path,body | create_dir: +path | delete_path: +path | move_file: +path,command(new path)
- termux_run: +command | termux_write_file: +path,body | termux_read_file: +path
- termux_modify_file: +path,body(new content),text(old content to replace),command(append|prepend|replace)
- http_get: +url | http_post: +url,body
- open_browser: +url | execute_javascript: +text(JS code) | close_browser
- delegate_ai: +app_name(ChatGPT/Gemini/DeepSeek/Kimi),text(full prompt). USE THIS for large code gen (>100 lines) to save tokens. After delegating, wait 30s then read_clipboard to get result.

CONTROL:
- wait: +wait_seconds(5-60). Real pause, 0 cost. Use for downloads/loading.
- done: set status="done". Only when task TRULY complete.
- ask_user: +speech(Hindi question). PAUSE and ask user. Use when confused or need choice.

UI FORMAT: i=id,t=text,T=type,c=clickable. DIFF: +=new,-=removed,~=changed.

RULES:
1. DIALOGS: Any popup (OK/Cancel/Allow) = click it FIRST.
2. VERIFY: Install done ONLY when button shows "Open" not "Install".
3. NEVER DONE EARLY. Install=click button+click OK+wait 30s+verify. SMS=type+send+verify.
4. For calls by name: find_contact first, then call with returned phone number.
5. Speech: Hindi. First=task confirm, middle="", done=result.
6. Prefer FAST actions over UI actions when possible.
7. ON ERROR (Err: prefix in context): DO NOT repeat the same action. Try a different way or give up.
8. ASK USER when confused: If task is unclear, multiple options exist, or you need a choice — use ask_user action.
   Examples: "Kaun sa contact? Mom ya Dad?", "YouTube Studio ya YouTube app?", "WiFi ya Mobile data se download karu?"
   NEVER assume. ALWAYS ask when unsure."""

        private const val CODER_SYSTEM_PROMPT = """You are Krinry Coder Agent. Control the system via terminal and files.
Respond ONLY in valid JSON. No markdown, no explanation, no extra text.

FIRST CALL: Return {"plan":["step1","step2",...],"action":"...","speech":"Hindi","reason":"...","status":"in_progress"}

JSON FORMAT — always include ALL fields:
{"action":"ACTION_NAME", "speech":"", "reason":"why", "status":"in_progress"}
+ extra fields based on action (see below)

FAST ACTIONS:
- list_files: +path | read_file: +path | write_file: +path,body | create_dir: +path | delete_path: +path | move_file: +path,command(new path)
- termux_run: +command | termux_write_file: +path,body | termux_read_file: +path
- termux_modify_file: +path,body(new content),text(old content to replace),command(append|prepend|replace)
- http_get: +url | http_post: +url,body
- open_browser: +url | execute_javascript: +text(JS code) | close_browser
- delegate_ai: +app_name(ChatGPT/Gemini/DeepSeek/Kimi),text(full prompt).

CONTROL:
- wait: +wait_seconds(5-60). Real pause, 0 cost.
- done: set status="done". Only when task TRULY complete.
- ask_user: +speech(Hindi question). PAUSE and ask user.

RULES:
1. Speech: Hindi. First=task confirm, middle="", done=result.
2. ON ERROR (Err: prefix in context): DO NOT repeat the same action. Try a different way or give up.
3. ASK USER when confused or need a choice."""

        private const val GENERAL_SYSTEM_PROMPT = """You are Krinry, a friendly AI assistant with native tool-calling capabilities.
You can control the phone, write code, run terminal commands, and more using tools.
Respond ONLY in valid JSON. No markdown, no explanation, no extra text.

JSON FORMAT — always include ALL fields:
{"action":"done", "speech":"Hindi answer", "reason":"casual chat", "status":"done"}

RULES:
1. Always respond in Hindi via the speech field.
2. The action should usually be "done" unless you need to ask the user a follow-up question (action="ask_user")."""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    // Callback to show plan progress on overlay
    var onPlanUpdate: ((planSteps: List<String>, currentIndex: Int, completedIndices: Set<Int>) -> Unit)? = null
    // Callback to ask user a question — returns user's voice answer
    var onAskUser: (suspend (question: String) -> String?)? = null
    // 🔒 Callback for pending action approval - returns true if allowed, false if denied
    var onPendingAction: (suspend (actionType: String, target: String, details: String, fullJson: String) -> Boolean)? = null
    private var currentJob: Job? = null
    private var lastApiCallTime = 0L          // Rate limiting
    private var consecutiveErrors = 0          // Track parse failures
    private var consecutiveActionErrors = 0    // Track execution failures
    private var lastUserReply: String? = null  // User's answer to ask_user

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        currentJob?.cancel()
        ttsManager.stop()
        currentJob = scope.launch {
            runAgentLoop(command = voiceCommand, audioFile = null)
        }
    }

    fun startTaskWithAudio(audioFile: File, initialTranscript: String?, scope: CoroutineScope) {
        currentJob?.cancel()
        ttsManager.stop()
        currentJob = scope.launch {
            runAgentLoop(command = initialTranscript, audioFile = audioFile)
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String?, audioFile: File?) {
        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            ttsManager.speak("Accessibility Service chalu karo pehle.")
            return
        }

        val actualCommand = if (command.isNullOrBlank() && audioFile != null && audioFile.exists()) {
            onStatusUpdate?.invoke("🎤 Audio sun raha hoon...")
            GroqApiClient.transcribeAudio(context, audioFile) ?: "Audio Command"
        } else {
            command ?: "Audio Command"
        }
        
        onStatusUpdate?.invoke("🧠 Route check kar raha hoon...")
        val agentRoute = RouterAgent.determineRoute(actualCommand, context)
        Log.d(TAG, "Routed to: $agentRoute")
        
        val systemPrompt = when (agentRoute) {
            AgentType.CODER_AGENT -> CODER_SYSTEM_PROMPT
            AgentType.GENERAL_CHAT -> GENERAL_SYSTEM_PROMPT
            else -> SYSTEM_PROMPT
        }

        Log.d(TAG, "Using NATIVE TOOL CALLING mode for: $actualCommand")
        onStatusUpdate?.invoke("🔧 Tool Calling mode: \"${actualCommand}\"")

        val toolEngine = ToolCallingEngine(context)
        toolEngine.onStatusUpdate = this@AgentLlmEngine.onStatusUpdate
        toolEngine.onAskUser = this@AgentLlmEngine.onAskUser
        toolEngine.onPendingAction = this@AgentLlmEngine.onPendingAction

        val result = toolEngine.runToolLoop(actualCommand, agentRoute)
        if (!result.isNullOrBlank()) {
            ttsManager.speak(result)
        } else {
            ttsManager.speak("Koi response nahi mila.")
        }
        onStatusUpdate?.invoke("✅ Done")
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
