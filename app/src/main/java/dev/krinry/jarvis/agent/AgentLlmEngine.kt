package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.*
import org.json.JSONObject

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
        private const val MAX_ITERATIONS = 25
        private const val SCREEN_SETTLE_DELAY = 1500L   // 1.5s between iterations (was 600ms)
        private const val MIN_API_INTERVAL = 2000L      // Minimum 2s between API calls
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
- screenshot | copy | paste: +node_id | select_all | open_notifications

FAST ACTIONS (no UI needed — use these when possible!):
- call: +phone | send_sms: +phone,text | set_alarm: +text | set_timer: +text
- navigate: +text | search_web: +text | flashlight: +text(on/off) | set_volume: +text
- find_contact: +text(name) | read_notifications | dismiss_notification: +text
- list_files: +path | read_file: +path | write_file: +path,body
- termux_run: +command | termux_write_file: +path,body | termux_read_file: +path
- http_get: +url | http_post: +url,body

CONTROL:
- wait: +wait_seconds(5-60). Real pause, 0 cost. Use for downloads/loading.
- done: set status="done". Only when task TRULY complete.

UI FORMAT: i=id,t=text,T=type,c=clickable. DIFF: +=new,-=removed,~=changed.

RULES:
1. DIALOGS: Any popup (OK/Cancel/Allow) = click it FIRST.
2. VERIFY: Install done ONLY when button shows "Open" not "Install".
3. NEVER DONE EARLY. Install=click button+click OK+wait 30s+verify. SMS=type+send+verify.
4. For calls by name: find_contact first, then call with returned phone number.
5. Speech: Hindi. First=task confirm, middle="", done=result.
6. Prefer FAST actions over UI actions when possible."""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    // Callback to show plan progress on overlay
    var onPlanUpdate: ((planSteps: List<String>, currentIndex: Int, completedIndices: Set<Int>) -> Unit)? = null
    private var currentJob: Job? = null
    private var lastApiCallTime = 0L          // Rate limiting
    private var consecutiveErrors = 0          // Track parse failures

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        currentJob?.cancel()
        ttsManager.stop()
        currentJob = scope.launch {
            runAgentLoop(voiceCommand)
        }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String) {
        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            ttsManager.speak("Accessibility Service chalu karo pehle.")
            return
        }

        // Fresh start
        taskMemory.startNewTask(command)
        screenCache.clear()
        consecutiveErrors = 0

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return
            taskMemory.nextIteration()

            Log.d(TAG, "=== Step $iteration ===")

            // RATE LIMIT: ensure minimum gap between API calls
            val timeSinceLastCall = System.currentTimeMillis() - lastApiCallTime
            if (timeSinceLastCall < MIN_API_INTERVAL) {
                delay(MIN_API_INTERVAL - timeSinceLastCall)
            }

            // 1. Screen padho
            val rootNode = service.getRootNode()
            if (rootNode == null) {
                onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                delay(800)
                continue
            }

            val uiNodes = UiTreeExtractor.extractTree(rootNode)
            val uiFullJson = UiTreeExtractor.toJson(uiNodes)
            Log.d(TAG, "UI nodes: ${uiNodes.size}")

            // 2. Screen diff (saves ~50-70% on steps 2+)
            val uiData = screenCache.getDiffOrFull(uiNodes, uiFullJson)

            // 3. Build ONLY 3 messages (vs 10+ before)
            val messages = mutableListOf<Map<String, String>>()
            messages.add(mapOf("role" to "system", "content" to SYSTEM_PROMPT))

            if (iteration == 1) {
                // First call: command + full UI → AI returns plan + first action
                messages.add(mapOf("role" to "user", "content" to "CMD:$command\nUI:$uiData"))
            } else {
                // Subsequent: compact memory + UI diff
                val memoryContext = taskMemory.toCompactContext()
                messages.add(mapOf("role" to "user", "content" to "MEM:$memoryContext\nUI:$uiData"))
            }

            // 4. LLM call — show plan progress
            val planInfo = if (taskMemory.hasPlan) {
                " (${taskMemory.completedSteps()}/${taskMemory.totalSteps()})"
            } else ""
            onStatusUpdate?.invoke("🤔 Step $iteration$planInfo")

            val llmResponse = try {
                lastApiCallTime = System.currentTimeMillis()
                GroqApiClient.agentChatDirect(context, messages)
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

            // 8. TTS speak (only on first, done, or error)
            action.speech?.takeIf { it.isNotBlank() }?.let { speechText ->
                ttsManager.speak(speechText)
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

            // 11. Execute action (WebApi is suspend, others are not)
            val result = if (action.action in listOf("http_get", "http_post")) {
                WebApiExecutor.execute(action)
            } else {
                ActionExecutor.execute(action, uiNodes)
            }
            Log.d(TAG, "Result: $result")
            onStatusUpdate?.invoke(result)

            // 12. Update memory + plan display
            if (result.startsWith("❌")) {
                taskMemory.recordError(result)
                Log.w(TAG, "Action failed: $result")
            } else {
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
            "http_get" -> "🌐 Web data fetch"
            "http_post" -> "🌐 Web data send"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
