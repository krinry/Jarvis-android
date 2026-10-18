package dev.krinry.jarvis.agent

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.krinry.jarvis.ai.AudioTrackPlayer
import dev.krinry.jarvis.ai.GeminiLiveClient
import dev.krinry.jarvis.data.ConversationHistoryStore
import dev.krinry.jarvis.receiver.JarvisDeviceAdminReceiver
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import dev.krinry.jarvis.service.JarvisNotificationListenerService
import dev.krinry.jarvis.audio.SoundEffectsManager
import dev.krinry.jarvis.system.CameraVisionBridge
import dev.krinry.jarvis.system.HardwareControlBridge
import dev.krinry.jarvis.system.ShizukuBridge
import dev.krinry.jarvis.system.SystemTelemetry
import dev.krinry.jarvis.system.TelecomBridge
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeminiLiveEngine — Full multimodal bidirectional live agent engine.
 *
 * Coordinates:
 * - GeminiLiveClient (WebSocket, STT, TTS, Tool Calling)
 * - AudioRecord (continuous 16kHz PCM streaming from microphone)
 * - AudioTrackPlayer (native 24kHz PCM playback through loudspeaker)
 * - ConversationHistoryStore (offline SQLite conversation & action history)
 * - AutoAgentService & ActionExecutor (Android UI control & gesture execution)
 * - UiTreeExtractor (screen reading & feedback)
 */
class GeminiLiveEngine(private val context: Context) : GeminiLiveClient.LiveListener {

    companion object {
        private const val TAG = "GeminiLiveEngine"
        private const val MIC_SAMPLE_RATE = 16000
        private const val CHUNK_FRAMES = 1600 // 100ms chunk = 1600 samples (3200 bytes)
    }

    private var liveClient: GeminiLiveClient? = null
    private var audioPlayer: AudioTrackPlayer? = null
    private var audioRecord: AudioRecord? = null

    private val historyStore = ConversationHistoryStore.getInstance(context)
    private var currentSessionId = "sess_${System.currentTimeMillis()}"

    private val userTranscriptBuffer = StringBuilder()
    private val assistantTranscriptBuffer = StringBuilder()

    private val isSessionActive = AtomicBoolean(false)
    private val isRecordingMic = AtomicBoolean(false)
    private val isModelSpeaking = AtomicBoolean(false)

    // Security Gate: Confirmation state for high-risk actions (Anti-Prompt Injection)
    data class PendingConfirmation(
        val toolName: String,
        val args: JSONObject,
        val description: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    private var pendingConfirmation: PendingConfirmation? = null

    private fun isDangerousAction(name: String, args: JSONObject): Boolean {
        return when (name) {
            "execute_shell_command", "clear_app_data", "send_sms" -> true
            "device_power_control" -> {
                val action = args.optString("action", "lock").lowercase()
                action == "reboot" || action == "shutdown"
            }
            else -> false
        }
    }

    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var micJob: Job? = null

    var onStatusUpdate: ((String) -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null
    var onThinkingChanged: ((Boolean) -> Unit)? = null

    fun startSession() {
        if (isSessionActive.get()) return

        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            return
        }

        val apiKey = SecureKeyStore.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            onStatusUpdate?.invoke("❌ Gemini API key set nahi hai. Settings me daalein.")
            return
        }

        val model = SecureKeyStore.getGeminiLiveModel(context)
        val voice = SecureKeyStore.getGeminiVoice(context)

        currentSessionId = "sess_${System.currentTimeMillis()}"
        userTranscriptBuffer.setLength(0)
        assistantTranscriptBuffer.setLength(0)

        isSessionActive.set(true)
        isModelSpeaking.set(false)
        onStatusUpdate?.invoke("⚡ Gemini Live se connect ho raha hai...")
        onThinkingChanged?.invoke(true)

        audioPlayer = AudioTrackPlayer(context).apply {
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    isModelSpeaking.set(true)
                    onThinkingChanged?.invoke(true)
                } else {
                    engineScope.launch {
                        delay(300) // Allow room echo to clear before unmuting mic
                        isModelSpeaking.set(false)
                        withContext(Dispatchers.Main) {
                            if (isSessionActive.get()) {
                                onThinkingChanged?.invoke(false)
                            }
                        }
                    }
                }
            }
        }

        liveClient = GeminiLiveClient(apiKey, model, voice).apply {
            listener = this@GeminiLiveEngine
            connect()
        }
    }

    fun stopSession() {
        if (!isSessionActive.getAndSet(false)) return

        // Flush any lingering conversation transcripts to offline database
        flushTranscriptsToHistory()

        stopMicRecording()
        isModelSpeaking.set(false)

        audioPlayer?.stopAndClear()
        audioPlayer?.release()
        audioPlayer = null

        liveClient?.disconnect()
        liveClient = null

        onThinkingChanged?.invoke(false)
        onStatusUpdate?.invoke("⏹ Session samapt")
        onSessionEnded?.invoke()
    }

    fun isRunning(): Boolean = isSessionActive.get()

    private fun flushTranscriptsToHistory() {
        if (userTranscriptBuffer.isNotBlank()) {
            val userText = userTranscriptBuffer.toString().trim()
            historyStore.saveUserMessage(currentSessionId, userText)
            userTranscriptBuffer.setLength(0)
        }
        if (assistantTranscriptBuffer.isNotBlank()) {
            val assistantText = assistantTranscriptBuffer.toString().trim()
            historyStore.saveAssistantMessage(currentSessionId, assistantText)
            assistantTranscriptBuffer.setLength(0)
        }
    }

    // =========================================================================
    // === Microphone Audio Streaming ===
    // =========================================================================

    private fun startMicRecording() {
        if (isRecordingMic.getAndSet(true)) return

        micJob = engineScope.launch(Dispatchers.IO) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                // Try VOICE_RECOGNITION for hardware echo cancellation & noise suppression
                var record: AudioRecord? = null
                try {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MIC_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer * 4
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "VOICE_RECOGNITION audio source failed, falling back to MIC", e)
                }

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        MIC_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer * 4
                    )
                }

                audioRecord = record

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    withContext(Dispatchers.Main) {
                        onStatusUpdate?.invoke("❌ Mic shuru nahi ho paya")
                    }
                    isRecordingMic.set(false)
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d(TAG, "Mic recording started (16kHz PCM, source=${audioRecord?.audioSource})")
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke("🎤 Sun raha hoon... Boliye")
                }

                val buffer = ByteArray(CHUNK_FRAMES * 2) // 16-bit = 2 bytes per sample (3200 bytes per 100ms)
                var isUserSpeaking = false
                var silenceFramesCount = 0
                val silenceFramesThreshold = 8 // 8 * 100ms = 800ms silence

                while (isRecordingMic.get() && isSessionActive.get() && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                        // Calculate RMS energy for Hybrid VAD
                        var sumSquares = 0.0
                        val sampleCount = read / 2
                        for (i in 0 until sampleCount) {
                            val lo = chunk[i * 2].toInt() and 0xFF
                            val hi = chunk[i * 2 + 1].toInt() shl 8
                            val sample = (lo or hi).toShort()
                            sumSquares += sample * sample
                        }
                        val rms = if (sampleCount > 0) Math.sqrt(sumSquares / sampleCount) else 0.0

                        // ACOUSTIC ECHO & SELF-INTERRUPTION PROTECTION:
                        // When Gemini is speaking or AudioTrack is actively playing through the loudspeaker,
                        // completely mute mic input streaming to Gemini.
                        // This prevents loudspeaker audio from feeding back into the mic and falsely
                        // triggering server-side VAD barge-in ("interrupted: true").
                        if (isModelSpeaking.get() || audioPlayer?.isCurrentlyPlaying() == true) {
                            continue
                        }

                        liveClient?.sendRealtimeAudio(chunk)

                        if (rms > 600.0) {
                            if (!isUserSpeaking) {
                                isUserSpeaking = true
                                Log.d(TAG, "Speech detected (RMS=${rms.toInt()})")
                            }
                            silenceFramesCount = 0
                        } else if (isUserSpeaking) {
                            silenceFramesCount++
                            if (silenceFramesCount >= silenceFramesThreshold) {
                                Log.d(TAG, "Silence after speech (800ms) -> sending audioStreamEnd (Hybrid VAD)")
                                liveClient?.sendAudioStreamEnd()
                                isUserSpeaking = false
                                silenceFramesCount = 0
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in mic recording loop", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                isRecordingMic.set(false)
            }
        }
    }

    private fun stopMicRecording() {
        isRecordingMic.set(false)
        micJob?.cancel()
        micJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        liveClient?.sendAudioStreamEnd()
    }

    // =========================================================================
    // === GeminiLiveClient.LiveListener Implementation ===
    // =========================================================================

    override fun onConnected() {
        engineScope.launch {
            onStatusUpdate?.invoke("⚡ Setup bheja ja raha hai...")
        }
    }

    override fun onSetupComplete() {
        engineScope.launch {
            onStatusUpdate?.invoke("✅ Gemini Live taiyar hai! Boliye...")
            onThinkingChanged?.invoke(false)

            // Screen snapshot lo aur Gemini ko shuruati context + past conversation memory do
            sendInitialScreenState()

            // Mic recording shuru karo
            startMicRecording()
        }
    }

    private fun sendInitialScreenState() {
        try {
            val service = AutoAgentService.instance
            val root = service?.getRootNode()
            val nodes = UiTreeExtractor.extractTree(root)
            val compactJson = UiTreeExtractor.toJson(nodes)

            val pastHistory = historyStore.getRecentContextForLivePrompt(maxTurns = 8)
            val historyBlock = if (pastHistory.isNotBlank()) {
                "\n\nPAST CONVERSATION HISTORY (Previous interactions with user):\n$pastHistory\nRemember this context so the user doesn't have to repeat themselves.\n"
            } else ""

            val contextText = """CURRENT PHONE SCREEN STATE (Reference):
$compactJson$historyBlock
CRITICAL OPERATING GUIDELINES:
1. CASUAL CHAT & QUESTIONS: Speak directly and naturally in Hindi. DO NOT CALL ANY TOOLS.
2. AUTOMATION & TASKS: Only when the user asks you to perform an action on the phone, speak a 1-sentence Hindi announcement and call the required tools.
3. PERSISTENT ASSISTANT: Never close or end the session on task completion. After calling task_done, ask the user naturally in Hindi if they need anything else and keep listening."""

            liveClient?.sendClientContent(contextText, turnComplete = false)
            Log.d(TAG, "Sent initial screen state with ${nodes.size} nodes and past context (chars=${pastHistory.length})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture initial screen", e)
        }
    }

    override fun onAudioDataReceived(pcmData: ByteArray) {
        // Native Gemini 24kHz Live TTS audio playback via AudioTrack
        isModelSpeaking.set(true)
        audioPlayer?.enqueueChunk(pcmData)
    }

    override fun onInputTranscript(text: String) {
        Log.d(TAG, "User Input STT: $text")
        // Assistant transcript flush karo if previous turn ended
        if (assistantTranscriptBuffer.isNotBlank()) {
            historyStore.saveAssistantMessage(currentSessionId, assistantTranscriptBuffer.toString().trim())
            assistantTranscriptBuffer.setLength(0)
        }

        userTranscriptBuffer.append(text).append(" ")
        val fullUserText = userTranscriptBuffer.toString().trim()
        engineScope.launch {
            onStatusUpdate?.invoke("🗣️ \"$fullUserText\"")
        }
    }

    override fun onOutputTranscript(text: String) {
        Log.d(TAG, "Gemini Output TTS: $text")
        isModelSpeaking.set(true)

        // Save user speech to offline history on transition to assistant speech
        if (userTranscriptBuffer.isNotBlank()) {
            historyStore.saveUserMessage(currentSessionId, userTranscriptBuffer.toString().trim())
            userTranscriptBuffer.setLength(0)
        }

        assistantTranscriptBuffer.append(text).append(" ")
        val fullAssistantText = assistantTranscriptBuffer.toString().trim()
        engineScope.launch {
            onStatusUpdate?.invoke("⚡ $fullAssistantText")
        }
    }

    override fun onToolCall(call: GeminiLiveClient.LiveFunctionCall) {
        engineScope.launch {
            onThinkingChanged?.invoke(true)
            executeToolCall(call)
        }
    }

    private suspend fun executeToolCall(call: GeminiLiveClient.LiveFunctionCall) {
        // Save pending transcripts before executing tool
        flushTranscriptsToHistory()

        val service = AutoAgentService.instance
        val rootNode = service?.getRootNode()
        val currentNodes = if (rootNode != null) UiTreeExtractor.extractTree(rootNode) else emptyList()
        val args = call.args

        val actionSummary = getHindiActionName(call.name, args)

        // Play high-tech Stark tool execution chime
        if (call.name != "close_assistant") {
            SoundEffectsManager.playToolSound()
        }

        // Handle close_assistant tool (User explicitly asked to stop/exit)
        if (call.name == "close_assistant") {
            val reason = args.optString("reason", "User requested exit")
            historyStore.saveAction(currentSessionId, "close_assistant", reason)
            onStatusUpdate?.invoke("⏹ Session samapt")
            val responseObj = JSONObject().apply {
                put("status", "closing")
            }
            liveClient?.sendToolResponse(call.id, call.name, responseObj, scheduling = "WHEN_IDLE")
            delay(600)
            stopSession()
            return
        }

        // Handle task_done tool (Keep session open! Do NOT stop!)
        if (call.name == "task_done") {
            val summary = args.optString("summary", "Kaam poora ho gaya!")
            historyStore.saveAction(currentSessionId, "task_done", summary)
            onStatusUpdate?.invoke("✅ $summary")
            onThinkingChanged?.invoke(false)
            SoundEffectsManager.playCompleteSound()

            delay(300)
            val freshRoot = service?.getRootNode()
            val freshNodes = if (freshRoot != null) UiTreeExtractor.extractTree(freshRoot) else emptyList()
            val freshUiJson = UiTreeExtractor.toJson(freshNodes)

            val responseObj = JSONObject().apply {
                put("status", "success")
                put("result", "Task completed: $summary")
                put("instruction", "The task is successfully completed. Speak to the user naturally in their language to inform them it's done and ask if they need anything else. Keep listening for their next command or conversation. DO NOT call task_done again unless another task is executed.")
                put("currentScreenUi", freshUiJson)
            }
            liveClient?.sendToolResponse(call.id, call.name, responseObj, scheduling = "WHEN_IDLE")
            // DO NOT stopSession() here! Jarvis remains active and conversational!
            return
        }

        // Security Confirmation Gate (Anti-Prompt Injection Defense)
        if (isDangerousAction(call.name, args)) {
            val isExplicitlyConfirmed = args.optBoolean("user_confirmed", false)
            val isPendingAuthorized = pendingConfirmation?.let {
                it.toolName == call.name && (System.currentTimeMillis() - it.timestamp) < 60_000L
            } ?: false

            if (!isExplicitlyConfirmed && !isPendingAuthorized) {
                pendingConfirmation = PendingConfirmation(
                    toolName = call.name,
                    args = args,
                    description = actionSummary
                )
                SoundEffectsManager.playErrorSound()
                onStatusUpdate?.invoke("⚠️ SECURITY GATE: Sir's confirmation required for $actionSummary")

                val securityPrompt = JSONObject().apply {
                    put("status", "requires_confirmation")
                    put("security_gate", "HIGH_RISK_ACTION")
                    put("action", actionSummary)
                    put("instruction", "SECURITY DIRECTIVE: You MUST ask Sir respectfully for verbal confirmation before executing this sensitive operation. Say: 'Sir, do you confirm: $actionSummary?' Wait for Sir's explicit vocal authorization ('Yes', 'Confirm', 'Proceed', 'Haan karo'). Once Sir confirms, call ${call.name} again with user_confirmed=true.")
                }
                liveClient?.sendToolResponse(call.id, call.name, securityPrompt, scheduling = "INTERRUPT")
                return
            } else {
                // Action authorized by Sir
                pendingConfirmation = null
                Log.i(TAG, "Security Gate Passed: Authorized execution of ${call.name} (actionSummary=$actionSummary)")
            }
        }

        // Record action to offline history database
        historyStore.saveAction(currentSessionId, call.name, actionSummary)

        // Execute UI action cleanly in background without robotic TTS announcements
        val executionResult = when (call.name) {
            "open_app" -> {
                val appName = args.optString("appName")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "open_app",
                        nodeId = null,
                        text = null,
                        appName = appName,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = args.optString("reason", "Open $appName")
                    ),
                    currentNodes
                )
            }
            "click_element" -> {
                val nodeId = if (args.has("nodeId")) args.optInt("nodeId") else null
                val text = args.optString("text").takeIf { it.isNotEmpty() }
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "click",
                        nodeId = nodeId,
                        text = text,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = args.optString("reason", "Click element")
                    ),
                    currentNodes
                )
            }
            "type_text" -> {
                val nodeId = if (args.has("nodeId")) args.optInt("nodeId") else null
                val text = args.optString("text")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "type",
                        nodeId = nodeId,
                        text = text,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = args.optString("reason", "Type: $text")
                    ),
                    currentNodes
                )
            }
            "scroll" -> {
                val dir = args.optString("direction", "down")
                val isForward = dir.equals("down", ignoreCase = true)
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = if (isForward) "scroll_down" else "scroll_up",
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Scroll $dir"
                    ),
                    currentNodes
                )
            }
            "tap_coordinates" -> {
                val x = args.optInt("x")
                val y = args.optInt("y")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "tap_xy",
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = x,
                        y = y,
                        reason = args.optString("reason", "Tap coordinates")
                    ),
                    currentNodes
                )
            }
            "swipe" -> {
                val dir = args.optString("direction", "down")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "swipe",
                        nodeId = null,
                        text = dir,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Swipe $dir"
                    ),
                    currentNodes
                )
            }
            "global_action" -> {
                val actionName = args.optString("action", "back")
                val parsedAction = when (actionName.lowercase()) {
                    "home" -> "home"
                    "recent", "recents" -> "recent"
                    "notification", "notifications" -> "open_notifications"
                    else -> "back"
                }
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = parsedAction,
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Global action $actionName"
                    ),
                    currentNodes
                )
            }
            "open_url" -> {
                val url = args.optString("url")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "open_url",
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = url,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Open URL $url"
                    ),
                    currentNodes
                )
            }
            "long_press" -> {
                val x = args.optInt("x")
                val y = args.optInt("y")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "long_press",
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = x,
                        y = y,
                        reason = "Long press"
                    ),
                    currentNodes
                )
            }
            "take_screenshot" -> {
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = "screenshot",
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Screenshot"
                    ),
                    currentNodes
                )
            }
            "clipboard_action" -> {
                val action = args.optString("action", "copy")
                ActionExecutor.execute(
                    ActionExecutor.AgentAction(
                        action = action,
                        nodeId = null,
                        text = null,
                        appName = null,
                        url = null,
                        speech = null,
                        status = "in_progress",
                        x = null,
                        y = null,
                        reason = "Clipboard $action"
                    ),
                    currentNodes
                )
            }
            "read_screen" -> {
                "✅ Screen read ho gaya"
            }
            "lock_phone" -> {
                val ok = JarvisDeviceAdminReceiver.lockDevice(context)
                if (ok) "✅ Phone locked, Sir"
                else {
                    if (ShizukuBridge.hasShizukuPermission()) {
                        ShizukuBridge.keyEvent(26)
                        "✅ Phone screen locked via shell, Sir"
                    } else {
                        "❌ Lock requires Device Administrator or Shizuku authority"
                    }
                }
            }
            "toggle_wifi" -> {
                val enable = args.optBoolean("enable", true)
                if (ShizukuBridge.hasShizukuPermission()) {
                    val ok = ShizukuBridge.toggleWifi(enable)
                    if (ok) "✅ Wi-Fi ${if (enable) "enabled" else "disabled"}, Sir" else "❌ Failed to toggle Wi-Fi"
                } else {
                    "❌ Wi-Fi control requires Shizuku authority"
                }
            }
            "toggle_bluetooth" -> {
                val enable = args.optBoolean("enable", true)
                if (ShizukuBridge.hasShizukuPermission()) {
                    val ok = ShizukuBridge.toggleBluetooth(enable)
                    if (ok) "✅ Bluetooth ${if (enable) "enabled" else "disabled"}, Sir" else "❌ Failed to toggle Bluetooth"
                } else {
                    "❌ Bluetooth control requires Shizuku authority"
                }
            }
            "toggle_mobile_data" -> {
                val enable = args.optBoolean("enable", true)
                if (ShizukuBridge.hasShizukuPermission()) {
                    val ok = ShizukuBridge.toggleMobileData(enable)
                    if (ok) "✅ Mobile data ${if (enable) "enabled" else "disabled"}, Sir" else "❌ Failed to toggle mobile data"
                } else {
                    "❌ Mobile data control requires Shizuku authority"
                }
            }
            "set_brightness" -> {
                val level = args.optInt("level", 128)
                if (ShizukuBridge.hasShizukuPermission()) {
                    val ok = ShizukuBridge.setBrightness(level)
                    if (ok) "✅ Brightness set to $level, Sir" else "❌ Failed to set brightness"
                } else {
                    "❌ Brightness control requires Shizuku authority"
                }
            }
            "execute_shell_command" -> {
                val cmd = args.optString("command")
                if (ShizukuBridge.hasShizukuPermission()) {
                    val res = ShizukuBridge.executeShell(cmd)
                    if (res.isSuccess) "✅ Shell output: ${res.stdout.take(300)}"
                    else "❌ Shell error (${res.exitCode}): ${res.stderr.take(200)}"
                } else {
                    "❌ Shell commands require Shizuku authority"
                }
            }
            "get_system_diagnostics" -> {
                SystemTelemetry.getDiagnosticsSummary(context)
            }
            "get_recent_notifications" -> {
                if (!JarvisNotificationListenerService.isNotificationAccessGranted(context)) {
                    "❌ Notification access is not granted, Sir. Please enable Notification Listener in Jarvis settings."
                } else {
                    val filterApp = args.optString("filter_app").takeIf { it.isNotBlank() }
                    val count = args.optInt("count", 5).coerceIn(1, 20)
                    val notifications = JarvisNotificationListenerService.getRecent(filterApp, count)
                    if (notifications.isEmpty()) {
                        "Sir, no notifications found${if (filterApp != null) " for $filterApp" else ""}."
                    } else {
                        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                        val sb = StringBuilder("Recent notifications:\n")
                        notifications.forEachIndexed { idx, n ->
                            val time = sdf.format(Date(n.timestamp))
                            sb.append("${idx + 1}. [${n.appName}] ${n.title}: ${n.text} ($time)\n")
                        }
                        sb.toString().trim()
                    }
                }
            }
            "reply_notification" -> {
                val target = args.optString("target")
                val msg = args.optString("message")
                if (!JarvisNotificationListenerService.isNotificationAccessGranted(context)) {
                    "❌ Notification access required to send inline reply, Sir."
                } else {
                    val ok = JarvisNotificationListenerService.replyTo(target, msg, context)
                    if (ok) "✅ Reply transmitted to $target: \"$msg\", Sir"
                    else "❌ Could not send direct reply to $target. App may not support inline reply."
                }
            }
            "read_latest_otp" -> {
                val otp = JarvisNotificationListenerService.getLatestOtpCode()
                if (otp != null) {
                    "✅ Latest OTP: ${otp.code} from ${otp.sender}. Message: \"${otp.messageSnippet}\""
                } else {
                    "Sir, no active OTP was detected in notifications in the last 15 minutes."
                }
            }
            "make_phone_call" -> {
                val target = args.optString("contact_or_number")
                TelecomBridge.makePhoneCall(context, target)
            }
            "search_contacts" -> {
                val query = args.optString("query")
                val matches = TelecomBridge.searchContacts(context, query)
                if (matches.isEmpty()) {
                    "No contacts found matching '$query', Sir."
                } else {
                    val list = matches.joinToString(", ") { "${it.name}: ${it.phoneNumber}" }
                    "Found contacts: $list"
                }
            }
            "send_sms" -> {
                val target = args.optString("contact_or_number")
                val msg = args.optString("message")
                TelecomBridge.sendSms(context, target, msg)
            }
            "analyze_current_screen" -> {
                val question = args.optString("question", "What is displayed on the screen?")
                val jpegBytes = ShizukuBridge.silentScreenshotJpeg(quality = 75, maxDimension = 1080)
                if (jpegBytes != null) {
                    liveClient?.sendRealtimeVideoFrame(jpegBytes)
                    "✅ Visual screen frame captured and transmitted to your vision feed, Sir. Analysis focus: $question"
                } else {
                    val freshText = currentNodes.filter { it.text.isNotBlank() }.joinToString(" | ") { it.text }
                    "Visual screencap unavailable. Screen elements: ${freshText.take(300)}"
                }
            }
            "inspect_real_world" -> {
                val cameraLens = args.optString("camera", "back").lowercase()
                val question = args.optString("question", "What is in front of the camera?")
                val facing = if (cameraLens.contains("front") || cameraLens.contains("selfie")) {
                    android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
                } else {
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
                }
                val jpegBytes = CameraVisionBridge.captureSilentSnapshot(context, facing)
                if (jpegBytes != null) {
                    liveClient?.sendRealtimeVideoFrame(jpegBytes)
                    "✅ Real-world optic frame from $cameraLens camera captured and transmitted to your vision stream, Sir. Answer Sir's question: $question"
                } else {
                    "❌ Optic sensors could not access the $cameraLens camera. Camera may be in use by another app or permission is missing, Sir."
                }
            }
            "toggle_flashlight" -> {
                val enable = args.optBoolean("enable", true)
                val (ok, msg) = HardwareControlBridge.toggleTorch(context, enable)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "toggle_hotspot" -> {
                val enable = args.optBoolean("enable", true)
                val (ok, msg) = HardwareControlBridge.toggleHotspot(context, enable)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "set_volume" -> {
                val stream = args.optString("stream", "media")
                val level = if (args.has("level_percent")) args.optInt("level_percent") else null
                val relative = if (args.has("relative_step")) args.optInt("relative_step") else null
                val mute = if (args.has("mute")) args.optBoolean("mute") else null
                val (ok, msg) = HardwareControlBridge.setVolume(context, stream, level, relative, mute)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "toggle_dnd" -> {
                val enable = args.optBoolean("enable", true)
                val (ok, msg) = HardwareControlBridge.setDnd(context, enable)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "toggle_auto_rotate" -> {
                val enable = args.optBoolean("enable", true)
                val (ok, msg) = HardwareControlBridge.toggleAutoRotate(enable)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "media_control" -> {
                val action = args.optString("action", "play_pause")
                val (ok, msg) = HardwareControlBridge.mediaControl(action)
                if (ok) "✅ $msg" else "❌ $msg"
            }
            "device_power_control" -> {
                val action = args.optString("action", "lock").lowercase()
                when (action) {
                    "reboot" -> {
                        val (ok, msg) = HardwareControlBridge.rebootDevice()
                        if (ok) "✅ $msg" else "❌ $msg"
                    }
                    "shutdown" -> {
                        val (ok, msg) = HardwareControlBridge.shutdownDevice()
                        if (ok) "✅ $msg" else "❌ $msg"
                    }
                    else -> {
                        val (ok, msg) = HardwareControlBridge.lockDevice(context)
                        if (ok) "✅ $msg" else "❌ $msg"
                    }
                }
            }
            "clear_app_data" -> {
                val pkg = args.optString("package_name")
                if (pkg.isBlank()) {
                    "❌ Package name required"
                } else {
                    val (ok, msg) = HardwareControlBridge.clearAppData(pkg)
                    if (ok) "✅ $msg" else "❌ $msg"
                }
            }
            else -> {
                "❓ Unknown function: ${call.name}"
            }
        }

        if (executionResult.startsWith("❌")) {
            SoundEffectsManager.playErrorSound()
        }

        // Screen transition / settle hone do
        delay(400)

        // Nayi screen padho aur response bhejo
        val freshRoot = service?.getRootNode()
        val freshNodes = if (freshRoot != null) UiTreeExtractor.extractTree(freshRoot) else emptyList()
        val freshUiJson = UiTreeExtractor.toJson(freshNodes)

        val responseObj = JSONObject().apply {
            put("status", if (executionResult.startsWith("❌")) "error" else "success")
            put("result", executionResult)
            put("currentScreenUi", freshUiJson)
        }

        val scheduling = when {
            executionResult.startsWith("❌") -> "INTERRUPT" // Alert model immediately on failures
            call.name in setOf("analyze_current_screen", "inspect_real_world", "get_system_diagnostics", "read_latest_otp", "get_recent_notifications", "search_contacts") -> "INTERRUPT"
            else -> "WHEN_IDLE"
        }

        liveClient?.sendToolResponse(call.id, call.name, responseObj, scheduling = scheduling)
    }

    private fun getHindiActionName(name: String, args: JSONObject): String {
        return when (name) {
            "inspect_real_world" -> "Camera se samne dekh raha hoon, Sir"
            "open_app" -> "${args.optString("appName")} open kar raha hoon, Sir"
            "click_element" -> {
                val label = args.optString("text").takeIf { it.isNotBlank() } ?: "element"
                "$label par click kar raha hoon, Sir"
            }
            "type_text" -> "${args.optString("text")} type kar raha hoon, Sir"
            "scroll" -> "Screen ${if (args.optString("direction").equals("up", ignoreCase = true)) "upar" else "neeche"} scroll kar raha hoon, Sir"
            "tap_coordinates" -> "Screen par tap kar raha hoon, Sir"
            "swipe" -> "Swipe kar raha hoon, Sir"
            "global_action" -> "${args.optString("action")} button daba raha hoon, Sir"
            "open_url" -> "Link open kar raha hoon: ${args.optString("url")}"
            "take_screenshot" -> "Screenshot capture kar raha hoon, Sir"
            "clipboard_action" -> "Clipboard action kar raha hoon, Sir"
            "read_screen" -> "Screen inspect kar raha hoon, Sir"
            "lock_phone" -> "Phone lock kar raha hoon, Sir"
            "toggle_wifi" -> "Wi-Fi ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "toggle_bluetooth" -> "Bluetooth ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "toggle_mobile_data" -> "Mobile data ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "set_brightness" -> "Brightness adjust kar raha hoon, Sir"
            "execute_shell_command" -> "System command run kar raha hoon, Sir: ${args.optString("command")}"
            "get_system_diagnostics" -> "System telemetry and diagnostics inspect kar raha hoon, Sir"
            "get_recent_notifications" -> "Notifications check kar raha hoon, Sir"
            "reply_notification" -> "${args.optString("target")} ko reply bhej raha hoon, Sir"
            "read_latest_otp" -> "Latest OTP check kar raha hoon, Sir"
            "make_phone_call" -> "${args.optString("contact_or_number")} ko call mila raha hoon, Sir"
            "search_contacts" -> "Contacts search kar raha hoon, Sir"
            "send_sms" -> "${args.optString("contact_or_number")} ko SMS bhej raha hoon, Sir"
            "toggle_flashlight" -> "Torch ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "toggle_hotspot" -> "Hotspot ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "set_volume" -> "Volume adjust kar raha hoon, Sir"
            "toggle_dnd" -> "Do Not Disturb ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "toggle_auto_rotate" -> "Auto-rotate ${if (args.optBoolean("enable", true)) "on" else "off"} kar raha hoon, Sir"
            "media_control" -> "Media playback update kar raha hoon, Sir: ${args.optString("action")}"
            "device_power_control" -> "${args.optString("action")} command execute kar raha hoon, Sir"
            "clear_app_data" -> "${args.optString("package_name")} ka data clear kar raha hoon, Sir"
            "task_done" -> args.optString("summary", "Kaam poora ho gaya, Sir!")
            "close_assistant" -> "Assistant session band kar raha hoon, Sir"
            else -> name
        }
    }

    override fun onToolCancellation(ids: List<String>) {
        Log.w(TAG, "Tool calls cancelled by Gemini server: $ids")
    }

    override fun onInterrupted() {
        Log.d(TAG, "onInterrupted received from server")
        audioPlayer?.stopAndClear()
        isModelSpeaking.set(false)
        if (assistantTranscriptBuffer.isNotBlank()) {
            historyStore.saveAssistantMessage(currentSessionId, assistantTranscriptBuffer.toString().trim())
            assistantTranscriptBuffer.setLength(0)
        }
    }

    override fun onTurnComplete() {
        if (assistantTranscriptBuffer.isNotBlank()) {
            historyStore.saveAssistantMessage(currentSessionId, assistantTranscriptBuffer.toString().trim())
            assistantTranscriptBuffer.setLength(0)
        }
        engineScope.launch {
            onThinkingChanged?.invoke(false)
        }
    }

    override fun onInteractionStatus(status: String) {
        engineScope.launch {
            if (status == "IN_PROGRESS") {
                onThinkingChanged?.invoke(true)
            } else if (status == "IDLE") {
                onThinkingChanged?.invoke(false)
            }
        }
    }

    override fun onGoAway(timeLeft: String) {
        Log.w(TAG, "Gemini Live GoAway notice received (timeLeft=$timeLeft)")
        engineScope.launch {
            onStatusUpdate?.invoke("🔄 Session refresh in $timeLeft")
        }
    }

    override fun onError(error: String) {
        engineScope.launch {
            onStatusUpdate?.invoke("❌ $error")
            onThinkingChanged?.invoke(false)
        }
    }

    override fun onClosed(code: Int, reason: String) {
        engineScope.launch {
            if (isSessionActive.get()) {
                if (code == 1000) {
                    onStatusUpdate?.invoke("⏹ Session samapt")
                    stopSession()
                } else {
                    onStatusUpdate?.invoke("🔄 Reconnecting: $reason")
                }
            }
        }
    }
}
