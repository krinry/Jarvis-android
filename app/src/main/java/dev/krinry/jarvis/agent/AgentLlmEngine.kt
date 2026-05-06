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

        // ════════════════════════════════════════════════════════════════
        // MODE: Native Tool Calling (Function Calling)
        // Uses ToolCallingEngine with proper tool definitions
        // ════════════════════════════════════════════════════════════════
        if (dev.krinry.jarvis.security.SecureKeyStore.isToolCallingEnabled(context)) {
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
            onStatusUpdate?.invoke("✅ Done (Tool Calling)")
            return
        }

        // ════════════════════════════════════════════════════════════════
        // MODE: Legacy JSON Parsing
        // Falls through to the original for-loop below
        // ════════════════════════════════════════════════════════════════

        // Fresh start
        taskMemory.startNewTask(actualCommand)
        screenCache.clear()
        consecutiveErrors = 0
        consecutiveActionErrors = 0
        lastUserReply = null
        var hasPlayedNativeAudio = false

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"${actualCommand}\"")
        Log.d(TAG, "Starting task (legacy mode): ${actualCommand}")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return
            taskMemory.nextIteration()

            Log.d(TAG, "=== Step $iteration ===")

            // RATE LIMIT: ensure minimum gap between API calls
            val timeSinceLastCall = System.currentTimeMillis() - lastApiCallTime
            if (timeSinceLastCall < MIN_API_INTERVAL) {
                delay(MIN_API_INTERVAL - timeSinceLastCall)
            }

            // 1. Screen padho (only for UI_AGENT)
            var uiData = "{}"
            var uiNodes = emptyList<UiTreeExtractor.UiNode>()
            
            if (agentRoute == AgentType.UI_AGENT) {
                val rootNode = service.getRootNode()
                if (rootNode == null) {
                    onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                    delay(800)
                    continue
                }

                uiNodes = UiTreeExtractor.extractTree(rootNode)
                val uiFullJson = UiTreeExtractor.toJson(uiNodes)
                Log.d(TAG, "UI nodes: ${uiNodes.size}")

                // 2. Screen diff (saves ~50-70% on steps 2+)
                uiData = screenCache.getDiffOrFull(uiNodes, uiFullJson)
            }

            // 3. Build ONLY 3 messages (vs 10+ before)
            val messages = mutableListOf<Map<String, String>>()
            messages.add(mapOf("role" to "system", "content" to systemPrompt))

            if (iteration == 1) {
                // First call: command + full UI → AI returns plan + first action
                messages.add(mapOf("role" to "user", "content" to "CMD:${actualCommand}\nUI:$uiData"))
            } else {
                // Subsequent: compact memory + UI diff
                val memoryContext = taskMemory.toCompactContext()
                val userReplyPart = lastUserReply?.let { "|UserReply:$it" } ?: ""
                messages.add(mapOf("role" to "user", "content" to "MEM:$memoryContext$userReplyPart\nUI:$uiData"))
                lastUserReply = null // Clear after sending
            }

            // 4. LLM call — show plan progress
            val planInfo = if (taskMemory.hasPlan) {
                " (${taskMemory.completedSteps()}/${taskMemory.totalSteps()})"
            } else ""
            onStatusUpdate?.invoke("🤔 Step $iteration$planInfo")

            var llmResponse: String? = null
            try {
                lastApiCallTime = System.currentTimeMillis()
                if (iteration == 1 && audioFile != null && audioFile.exists()) {
                    onStatusUpdate?.invoke("🤔 Step $iteration (Native Audio Dialog)$planInfo")
                    val result = dev.krinry.jarvis.ai.GeminiNativeAudioClient.processAudioDialog(
                        context, audioFile, systemPrompt, uiData, ttsManager
                    )
                    
                    if (result.transcript != null) {
                        taskMemory.replaceCommand(result.transcript)
                        withContext(Dispatchers.Main) {
                            onStatusUpdate?.invoke("🗣️ \"${result.transcript}\"")
                        }
                    }
                    if (result.playedAudio) {
                        hasPlayedNativeAudio = true
                    }
                    llmResponse = result.jsonResponse
                } else {
                    llmResponse = GroqApiClient.agentChatDirect(context, messages)
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}")
                onStatusUpdate?.invoke("❌ ${e.message?.take(50) ?: "Server error"}")
                if (iteration < 3) {
                    delay(3000) // Wait and retry on early failures
                    continue
                }
                ttsManager.speak("Server se jawab nahi aaya.")
                return
            }

            if (llmResponse == null) {
                onStatusUpdate?.invoke("❌ Empty response from server")
                delay(2000)
                continue
            }

            Log.d(TAG, "LLM response: $llmResponse")

            // 5. Parse action — with consecutive error tracking
            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                consecutiveErrors++
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)")
                taskMemory.recordError("Invalid LLM response")
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    onStatusUpdate?.invoke("❌ Model $MAX_CONSECUTIVE_ERRORS baar galat response de raha hai. Ruk raha hoon.")
                    ttsManager.speak("Model samajh nahi pa raha. Doosra model try karo.")
                    return
                }
                delay(2000L + (consecutiveErrors * 1000L)) // Exponential backoff
                continue
            }
            consecutiveErrors = 0 // Reset on successful parse

            // 6. Extract plan from first response → show on overlay
            if (iteration == 1) {
                extractPlanFromResponse(llmResponse)
                // Show plan on screen overlay
                if (taskMemory.hasPlan) {
                    emitPlanUpdate()
                    // TTS: announce plan
                    val stepCount = taskMemory.totalSteps()
                    ttsManager.speak("Plan ready, $stepCount steps hain.")
                }
            }

            // 7. Hindi status update with reason
            val reasonText = action.reason ?: action.action
            onStatusUpdate?.invoke("⚡ ${getHindiAction(action.action)}: $reasonText")

            // 🔒 Check if action requires user permission
            val permissionRequiredActions = setOf(
                "write_file", "delete_path", "move_file", "create_dir",
                "termux_run", "termux_write_file", "termux_modify_file",
                "http_post"
            )
            if (action.action in permissionRequiredActions && onPendingAction != null) {
                // Get action details for display
                val target = action.path ?: action.text ?: action.command ?: action.body ?: "Unknown"
                val details = buildString {
                    if (action.action == "write_file" || action.action == "termux_write_file") {
                        append("Writing ${action.body?.length ?: 0} characters")
                    } else if (action.action == "termux_run") {
                        append("Command: ${action.command}")
                    } else if (action.action == "delete_path") {
                        append("Will delete: $target")
                    }
                }

                // Build full JSON for execution after approval
                val fullJson = buildString {
                    append("{")
                    append("\"action\":\"${action.action}\"")
                    if (action.path != null) append(",\"path\":\"${action.path}\"")
                    if (action.text != null) append(",\"text\":\"${action.text}\"")
                    if (action.body != null) append(",\"body\":\"${action.body}\"")
                    if (action.command != null) append(",\"command\":\"${action.command}\"")
                    if (action.reason != null) append(",\"reason\":\"${action.reason}\"")
                    append("}")
                }

                onStatusUpdate?.invoke("🔒 Permission required, waiting for user approval...")
                val pendingCallback = onPendingAction
                if (pendingCallback != null) {
                    val approve = pendingCallback(action.action, target, details, fullJson)
                    if (!approve) {
                        onStatusUpdate?.invoke("❌ User denied permission for: ${action.action}")
                        taskMemory.recordError("Permission denied by user")
                        return
                    }
                } else {
                    onStatusUpdate?.invoke("⚠️ No permission callback, executing anyway...")
                }
            }

            // 8. TTS speak (only on first, done, ask_user)
            if (iteration == 1 || action.action == "done" || action.status == "done" || action.action == "ask_user") {
                action.speech?.takeIf { it.isNotBlank() }?.let { speechText ->
                    if (!hasPlayedNativeAudio) {
                        ttsManager.speak(speechText)
                    } else if (iteration > 1 || action.action == "ask_user") {
                        // After iteration 1 (or ask_user during iter 1), if we need to speak, we reset Native Audio
                        hasPlayedNativeAudio = false
                        ttsManager.speak(speechText)
                    }
                }
            }

            // 8.5. ASK USER — pause and wait for voice answer
            if (action.action == "ask_user") {
                val question = action.speech ?: action.text ?: "Kya karna hai?"
                onStatusUpdate?.invoke("🗣 Puch raha hoon: $question")
                Log.d(TAG, "Ask user: $question")
                
                // Wait for user's voice response
                val userAnswer = onAskUser?.invoke(question)
                if (userAnswer.isNullOrBlank()) {
                    onStatusUpdate?.invoke("❌ Koi jawab nahi mila. Continue kar raha hoon.")
                    taskMemory.recordError("User did not respond")
                } else {
                    lastUserReply = userAnswer
                    onStatusUpdate?.invoke("💬 Jawab: $userAnswer")
                    taskMemory.markCurrentStepDone("User said: ${userAnswer.take(40)}")
                    Log.d(TAG, "User replied: $userAnswer")
                }
                emitPlanUpdate()
                delay(500)
                continue // Next iteration with user's answer in memory
            }

            // 9. Check if done
            if (action.status == "done" || action.action == "done") {
                taskMemory.markCurrentStepDone("Task complete")
                emitPlanUpdate()
                onStatusUpdate?.invoke("✅ Ho gaya: ${action.reason ?: "Task complete"}")
                delay(2500)
                return
            }

            // 10. SMART WAIT — real delay, NO API call wasted
            if (action.action == "wait") {
                val waitSec = action.waitSeconds?.coerceIn(3, 60) ?: 10
                onStatusUpdate?.invoke("⏳ Wait kar raha hoon ${waitSec}s: ${action.reason ?: "loading..."}")
                Log.d(TAG, "Smart wait: ${waitSec}s (no API call)")
                taskMemory.markCurrentStepDone("Waited ${waitSec}s")
                emitPlanUpdate()
                delay(waitSec * 1000L) // REAL delay — no token cost!
                continue // Go back to top, re-read screen, THEN call API
            }

            // 11. Execute action (WebApi/Vision are suspend, others are not)
            val result = if (action.action in listOf("http_get", "http_post")) {
                WebApiExecutor.execute(action)
            } else if (action.action == "analyze_screen") {
                ScreenshotVision.analyzeScreen(action.text ?: "Check screen", service.applicationContext)
            } else if (action.action == "execute_javascript") {
                val script = action.text ?: action.body ?: "❌ Script nahi mili"
                if (script.startsWith("❌")) script else AgentWebViewManager.instance?.executeJavascript(script) ?: "❌ Browser open nahi hai"
            } else if (action.action == "open_browser") {
                val url = action.url ?: action.text ?: "❌ URL nahi mili"
                if (url.startsWith("❌")) url else {
                    AgentWebViewManager.instance?.openBrowser(url)
                    "✅ Browser open: $url"
                }
            } else if (action.action == "close_browser") {
                AgentWebViewManager.instance?.closeBrowser()
                "✅ Browser closed"
            } else {
                ActionExecutor.execute(action, uiNodes)
            }
            Log.d(TAG, "Result: $result")
            onStatusUpdate?.invoke(result)

            // 12. Update memory + plan display
            if (result.startsWith("❌")) {
                consecutiveActionErrors++
                taskMemory.recordError(result)
                Log.w(TAG, "Action failed ($consecutiveActionErrors): $result")
                
                if (consecutiveActionErrors >= 3) {
                    onStatusUpdate?.invoke("❌ Action baar-baar fail ho raha hai. Ruk raha hoon.")
                    ttsManager.speak("Main yeh nahi kar pa raha hoon. Koi aur tarika try karo.")
                    return
                }
                delay(2000) // Small pause on error so AI realizes it failed
            } else {
                consecutiveActionErrors = 0 // Success resets error counter
                taskMemory.markCurrentStepDone(result.take(50))
            }
            emitPlanUpdate()

            // 13. Screen settle hone do
            delay(SCREEN_SETTLE_DELAY)
        }

        onStatusUpdate?.invoke("⚠️ Bahut steps ho gaye ($MAX_ITERATIONS)")
        ttsManager.speak("Kaam time pe complete nahi ho paya. Chhota command try karo.")
    }

    /**
     * Extract plan steps from AI's first response JSON.
     */
    private fun extractPlanFromResponse(response: String) {
        try {
            val cleanJson = response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val start = cleanJson.indexOf('{')
            val end = cleanJson.lastIndexOf('}')
            if (start >= 0 && end > start) {
                val json = JSONObject(cleanJson.substring(start, end + 1))
                val planArray = json.optJSONArray("plan")
                if (planArray != null) {
                    val steps = mutableListOf<String>()
                    for (i in 0 until planArray.length()) {
                        steps.add(planArray.getString(i))
                    }
                    taskMemory.setPlan(steps)
                    Log.d(TAG, "Plan: ${steps.joinToString(" → ")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract plan: ${e.message}")
        }
    }

    /**
     * Emit plan progress to overlay callback.
     */
    private fun emitPlanUpdate() {
        if (taskMemory.hasPlan) {
            onPlanUpdate?.invoke(
                taskMemory.getPlanSteps(),
                taskMemory.currentStepIndex,
                taskMemory.getCompletedIndices()
            )
        }
    }

    /**
     * Hindi action name for status display.
     */
    private fun getHindiAction(action: String): String {
        return when (action) {
            "click" -> "Click kar raha hoon"
            "type" -> "Type kar raha hoon"
            "scroll_down" -> "Neeche scroll"
            "scroll_up" -> "Upar scroll"
            "back" -> "Back"
            "home" -> "Home"
            "recent" -> "Recent apps"
            "open_app" -> "App khol raha hoon"
            "open_url" -> "URL khol raha hoon"
            "tap_xy" -> "Tap"
            "long_press" -> "Long press"
            "swipe" -> "Swipe"
            "screenshot" -> "Screenshot"
            "copy" -> "Copy"
            "paste" -> "Paste"
            "select_all" -> "Select all"
            "open_notifications" -> "Notifications"
            "wait" -> "Wait"
            "done" -> "Ho gaya"
            // Phase 1 actions
            "call" -> "📞 Call kar raha hoon"
            "send_sms" -> "💬 SMS bhej raha hoon"
            "set_alarm" -> "⏰ Alarm set"
            "set_timer" -> "⏱ Timer set"
            "create_event" -> "📅 Calendar event"
            "navigate" -> "🗺 Navigation"
            "search_web" -> "🔍 Web search"
            "send_email" -> "✉ Email"
            "flashlight" -> "🔦 Flashlight"
            "set_volume" -> "🔊 Volume"
            "open_settings" -> "⚙ Settings"
            "find_contact" -> "👤 Contact dhoondh raha hoon"
            "read_notifications" -> "🔔 Notifications padh raha hoon"
            "dismiss_notification" -> "🔕 Notification dismiss"
            "list_files" -> "📁 Files dekh raha hoon"
            "read_file" -> "📄 File padh raha hoon"
            "write_file" -> "✏ File likh raha hoon"
            "delete_file" -> "🗑 File delete"
            "share_file" -> "📤 File share"
            "termux_run" -> "🖥 Termux command"
            "termux_write_file" -> "✏ Termux file likh raha hoon"
            "termux_read_file" -> "📄 Termux file padh raha hoon"
            "termux_modify_file" -> "✏️ Termux file modify kar raha hoon"
            "http_get" -> "🌐 Web data fetch"
            "http_post" -> "🌐 Web data send"
            "open_browser" -> "🌐 Browser khol raha hoon"
            "execute_javascript" -> "💻 Script chala raha hoon"
            "close_browser" -> "🌐 Browser band"
            "delegate_ai" -> "🤖 ChatGPT/Gemini se help le raha hoon"
            "ask_user" -> "🗣 User se puch raha hoon"
            "analyze_screen" -> "👁 Screen dekh raha hoon"
            "read_clipboard" -> "📋 Clipboard padh raha hoon"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
