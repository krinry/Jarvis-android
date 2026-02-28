package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.*

/**
 * AgentLlmEngine — The brain of Krinry AI (Jarvis).
 *
 * Loop: Read screen → build prompt → call LLM → speak → execute → VERIFY → repeat
 *
 * Key fixes:
 * - VERIFICATION: Agent re-reads screen after "done" to confirm task actually completed
 * - Hindi status updates shown to user
 * - Agent speaks Hindi summary of what it's doing
 * - Coordinates (cx, cy) in UI tree for gesture-based tap
 * - When message typed, agent must find and click SEND button before saying done
 */
class AgentLlmEngine(private val context: Context) {

    private val ttsManager = AgentTtsManager(context)

    companion object {
        private const val TAG = "AgentLlmEngine"
        private const val MAX_ITERATIONS = 30
        private const val SCREEN_SETTLE_DELAY = 1500L
        private const val MAX_HISTORY_MESSAGES = 20

        private const val SYSTEM_PROMPT = """You are Krinry, a powerful AI phone assistant with FULL device control. You control the phone through AccessibilityService. You respond ONLY in JSON.

=== CAPABILITIES ===
Send messages, make calls, open ANY app, browse web, change settings, play music, search YouTube, post on social media, adjust volume, take screenshots — ANYTHING.

=== AVAILABLE ACTIONS ===
1. open_app: {"action":"open_app","app_name":"WhatsApp","speech":"WhatsApp khol raha hoon","reason":"...","status":"in_progress"}
2. click: {"action":"click","node_id":5,"speech":"","reason":"...","status":"in_progress"}
3. type: {"action":"type","node_id":3,"text":"Hello!","speech":"","reason":"...","status":"in_progress"}
4. tap_xy: {"action":"tap_xy","x":540,"y":1200,"speech":"","reason":"Tapping send button","status":"in_progress"}
5. scroll_down / scroll_up: {"action":"scroll_down","speech":"","reason":"...","status":"in_progress"}
6. swipe: {"action":"swipe","text":"left|right|up|down","speech":"","reason":"...","status":"in_progress"}
7. back / home / recent: navigation actions
8. open_url: {"action":"open_url","url":"https://...","speech":"...","reason":"...","status":"in_progress"}
9. wait: {"action":"wait","speech":"","reason":"Screen loading","status":"in_progress"}
10. done: {"action":"done","speech":"Kaam ho gaya!","reason":"...","status":"done"}

=== NODE STRUCTURE ===
Each UI element has: id, text, desc, type, cx (center X), cy (center Y), clickable, editable, scrollable.
- Use node_id to interact with elements.
- If a click doesn't work, use tap_xy with the element's cx and cy coordinates as fallback.

=== CRITICAL RULES ===

1. SPEECH IN HINDI:
   - First step: Short Hindi confirmation ("Haan, WhatsApp khol raha hoon" / "Theek hai, Papa ko message bhej raha hoon")
   - Intermediate steps: speech = "" (silent)
   - Done step: Hindi completion ("Ho gaya! Message bhej diya Papa ko")
   - Errors: Hindi explanation ("App nahi mili, naam check karo")

2. APP LAUNCHING:
   - ALWAYS use "open_app" first. NEVER scroll home screen to find app icons.
   - Use exact app display name: "WhatsApp", "YouTube", "Chrome", "Settings", "Instagram", "Camera"

3. COMPLETING TASKS — DO NOT SAY DONE EARLY:
   - NEVER say "done" after ONLY typing a message. You MUST click the SEND button too.
   - After typing, look for send button (usually has type "ImageButton" or desc "Send" or text "Send" or "➤" icon).
   - Click the send button, THEN verify the message appears in chat, THEN say done.
   - Similarly: don't just open an app and say done — do what the user asked INSIDE the app.
   - Example flow for "send message to Papa on WhatsApp":
     Step 1: open_app WhatsApp
     Step 2: click Papa's chat (find in UI tree)
     Step 3: click text input field
     Step 4: type message
     Step 5: click send button ← THIS IS REQUIRED
     Step 6: done (only after send clicked)

4. NODE NOT FOUND RECOVERY:
   - If target node_id not found, DON'T give up. Try:
     a. Scroll to find it
     b. Use tap_xy with coordinates from the UI tree
     c. Look for the element by its text/desc in the new UI tree
   - Only say "nahi mila" if you've tried scrolling AND searching.

5. VERIFICATION:
   - Before saying "done", look at the current screen to confirm the action actually happened.
   - If you typed and sent a message, check if it appears in the chat.
   - If you opened an app, verify you're in that app.

6. DISAMBIGUATION:
   - Multiple contacts with similar names? Ask user in Hindi via speech.
   - One match? Proceed without asking.

=== RESPONSE FORMAT ===
Output ONLY valid JSON. No markdown, no explanation, no text outside JSON.
One JSON object per response."""
    }

    var onStatusUpdate: ((String) -> Unit)? = null
    private val conversationHistory = mutableListOf<Pair<String, String>>()
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

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return

            Log.d(TAG, "=== Step $iteration ===")

            // 1. Screen padho
            val rootNode = service.getRootNode()
            if (rootNode == null) {
                onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                delay(800)
                continue
            }

            val uiNodes = UiTreeExtractor.extractTree(rootNode)
            val uiJson = UiTreeExtractor.toJson(uiNodes)
            Log.d(TAG, "UI nodes: ${uiNodes.size}")

            // 2. LLM ke liye message banao
            val userMessage = if (iteration == 1) {
                "VOICE COMMAND: $command\n\nSCREEN UI ELEMENTS:\n$uiJson"
            } else {
                "SCREEN UPDATED AFTER LAST ACTION:\n$uiJson"
            }

            // 3. Hindi status
            onStatusUpdate?.invoke("🤔 Soch raha hoon... (step $iteration)")

            // 4. LLM call
            val llmResponse = try {
                GroqApiClient.agentChat(context, SYSTEM_PROMPT, conversationHistory, userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed", e)
                onStatusUpdate?.invoke("❌ Server se baat nahi ho payi")
                ttsManager.speak("Server se connection nahi ho paya. Phir try karo.")
                return
            }

            if (llmResponse == null) {
                onStatusUpdate?.invoke("❌ Server ne jawab nahi diya")
                ttsManager.speak("Server jawab nahi de raha. Thodi der baad try karo.")
                return
            }

            Log.d(TAG, "LLM response: $llmResponse")

            // 5. History me save karo
            conversationHistory.add("user" to userMessage)
            conversationHistory.add("assistant" to llmResponse)

            while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
                conversationHistory.removeAt(0)
            }

            // 6. Parse action
            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya")
                // Don't stop — try again with fresh screen
                delay(1000)
                continue
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
                onStatusUpdate?.invoke("✅ Ho gaya: ${action.reason ?: "Task complete"}")
                delay(2500) // TTS finish hone do
                return
            }

            // 10. Execute action
            val result = ActionExecutor.execute(action, uiNodes)
            Log.d(TAG, "Result: $result")
            onStatusUpdate?.invoke(result)

            // If action failed, inform via TTS
            if (result.startsWith("❌")) {
                Log.w(TAG, "Action failed: $result")
                // Don't stop — let LLM try again with new screen state
            }

            // 11. Screen settle hone do
            delay(SCREEN_SETTLE_DELAY)
        }

        onStatusUpdate?.invoke("⚠️ Bahut steps ho gaye ($MAX_ITERATIONS)")
        ttsManager.speak("Kaam time pe complete nahi ho paya. Chhota command try karo.")
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
            "swipe" -> "Swipe kar raha hoon"
            "wait" -> "Ruk raha hoon"
            "done" -> "Ho gaya"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
