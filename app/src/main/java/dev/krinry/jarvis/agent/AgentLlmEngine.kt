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
        private const val MAX_ITERATIONS = 30
        private const val SCREEN_SETTLE_DELAY = 600L

        private const val SYSTEM_PROMPT = """You are Krinry, AI phone assistant. Full device control via AccessibilityService. Respond ONLY in valid JSON, no markdown.

FIRST CALL: Include "plan" field = short step list. Example: {"plan":["open PlayStore","search game","click Install","click OK on dialog","wait download","verify installed"],"action":"open_app","app_name":"Google Play Store","speech":"PlayStore khol raha hoon","reason":"opening store","status":"in_progress"}

NEXT CALLS: You get compact memory (Task/Plan/Done/Step/Last) + current UI. Continue executing plan.

ACTIONS (JSON: {"action":"X","speech":"","reason":"","status":"in_progress|done"} + fields):
- open_app: +app_name | click: +node_id | type: +node_id,text | tap_xy: +x,y | long_press: +x,y
- scroll_down/scroll_up | swipe: +text(left|right|up|down) | back/home/recent
- open_url: +url | screenshot | copy | paste: +node_id | select_all | open_notifications
- wait: +wait_seconds(5-60, real delay, NO API CALL during wait) | done: status="done"

UI: i=id,t=text,d=desc,T=type(B=Button,E=EditText,IB=ImageButton,TV=TextView,IV=ImageView),c=clickable,e=editable,s=scrollable. x,y=coords(only some nodes). DIFF: +=new,-=removed,~=changed. UI_SAME=no change.

CRITICAL RULES:
1. DIALOGS FIRST: ANY popup/dialog/bottom-sheet (OK/Cancel/Accept/Allow/Confirm/Continue) → CLICK it before anything else. NEVER ignore. Examples: network choice dialog → click OK. Permission dialog → click Allow.
2. VERIFY BY UI TEXT, NOT SCREENSHOT: To confirm install/download/send:
   - PlayStore: "Install" button MUST change to "Open" or "Uninstall" in UI. If still "Install" → NOT done.
   - WhatsApp: message must appear in chat. If not visible → NOT done.
   - NEVER say done based on screenshot alone. ALWAYS check actual UI text/buttons.
3. SMART WAIT: For long operations use {"action":"wait","wait_seconds":30}. Real pause, 0 tokens. After wait, re-check UI.
4. NEVER DONE EARLY: Full sequence required:
   - Install: click Install → click OK on dialog → wait 30-60s → check UI shows "Open" not "Install" → done
   - Message: type text → click Send → check message visible in chat → done
5. Speech: Hindi. First=confirm task, middle="", done=result, error=explain
6. Apps: ALWAYS open_app first. Exact names: "WhatsApp","YouTube","Chrome","Google Play Store"
7. Node missing? scroll_down first → if still missing, try tap_xy → give up after 3 tries"""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    // Callback to show plan progress on overlay
    var onPlanUpdate: ((planSteps: List<String>, currentIndex: Int, completedIndices: Set<Int>) -> Unit)? = null
    private var currentJob: Job? = null

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

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return
            taskMemory.nextIteration()

            Log.d(TAG, "=== Step $iteration ===")

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
                GroqApiClient.agentChatDirect(context, messages)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}")
                onStatusUpdate?.invoke("❌ ${e.message?.take(50) ?: "Server error"}")
                ttsManager.speak("Server se jawab nahi aaya.")
                return
            }

            if (llmResponse == null) {
                onStatusUpdate?.invoke("❌ Empty response from server")
                ttsManager.speak("Server ne koi jawab nahi diya.")
                return
            }

            Log.d(TAG, "LLM response: $llmResponse")

            // 5. Parse action
            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya")
                taskMemory.recordError("Invalid LLM response")
                delay(1000)
                continue
            }

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

            // 11. Execute action
            val result = ActionExecutor.execute(action, uiNodes)
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
            "scroll_down" -> "Neeche scroll kar raha hoon"
            "scroll_up" -> "Upar scroll kar raha hoon"
            "back" -> "Back ja raha hoon"
            "home" -> "Home ja raha hoon"
            "recent" -> "Recent apps dekh raha hoon"
            "open_app" -> "App khol raha hoon"
            "open_url" -> "URL khol raha hoon"
            "tap_xy" -> "Tap kar raha hoon"
            "long_press" -> "Long press kar raha hoon"
            "swipe" -> "Swipe kar raha hoon"
            "screenshot" -> "Screenshot le raha hoon"
            "copy" -> "Copy kar raha hoon"
            "paste" -> "Paste kar raha hoon"
            "select_all" -> "Sab select kar raha hoon"
            "open_notifications" -> "Notifications dekh raha hoon"
            "wait" -> "Wait kar raha hoon"
            "done" -> "Ho gaya"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
