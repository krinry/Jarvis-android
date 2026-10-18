package dev.krinry.jarvis.agent

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.krinry.jarvis.ai.AudioTrackPlayer
import dev.krinry.jarvis.ai.GeminiLiveClient
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeminiLiveEngine — Full multimodal bidirectional live agent engine.
 *
 * Coordinates:
 * - GeminiLiveClient (WebSocket, STT, TTS, Tool Calling)
 * - AudioRecord (continuous 16kHz PCM streaming from microphone)
 * - AudioTrackPlayer (native 24kHz PCM playback through loudspeaker)
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

    private val isSessionActive = AtomicBoolean(false)
    private val isRecordingMic = AtomicBoolean(false)

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

        isSessionActive.set(true)
        onStatusUpdate?.invoke("⚡ Gemini Live se connect ho raha hai...")
        onThinkingChanged?.invoke(true)

        audioPlayer = AudioTrackPlayer(context).apply {
            onPlaybackStateChanged = { isPlaying ->
                if (!isPlaying && !isRecordingMic.get()) {
                    onThinkingChanged?.invoke(false)
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

        stopMicRecording()
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

                        // Acoustic Echo Protection:
                        // While Gemini is speaking via the phone's loudspeaker, do NOT send
                        // the speaker output back into Gemini as user speech, which causes
                        // self-interruption (Gemini thinking user interrupted).
                        val isGeminiSpeaking = audioPlayer?.isCurrentlyPlaying() == true
                        if (isGeminiSpeaking) {
                            // User can barge in by speaking loudly over the assistant
                            if (rms > 2800.0) {
                                Log.d(TAG, "Intentional user barge-in detected (RMS=${rms.toInt()})")
                                audioPlayer?.stopAndClear()
                                liveClient?.sendRealtimeAudio(chunk)
                                isUserSpeaking = true
                                silenceFramesCount = 0
                            }
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

            // Screen snapshot lo aur Gemini ko shuruati context do
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
            val contextText = "DEVICE SCREEN UI STATE:\n$compactJson\nPerform the user's voice command using your tools. Speak concisely in Hindi."
            liveClient?.sendClientContent(contextText, turnComplete = false)
            Log.d(TAG, "Sent initial screen state with ${nodes.size} nodes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture initial screen", e)
        }
    }

    override fun onAudioDataReceived(pcmData: ByteArray) {
        // Native Gemini TTS audio play karo (loudspeaker via AudioTrack)
        Log.d(TAG, "Audio data chunk received from Gemini: ${pcmData.size} bytes")
        audioPlayer?.enqueueChunk(pcmData)
    }

    override fun onInputTranscript(text: String) {
        Log.d(TAG, "User Input STT: $text")
        engineScope.launch {
            onStatusUpdate?.invoke("🗣️ \"$text\"")
        }
    }

    override fun onOutputTranscript(text: String) {
        Log.d(TAG, "Gemini Output TTS: $text")
        engineScope.launch {
            onStatusUpdate?.invoke("⚡ $text")
        }
    }

    override fun onToolCall(call: GeminiLiveClient.LiveFunctionCall) {
        engineScope.launch {
            onThinkingChanged?.invoke(true)
            executeToolCall(call)
        }
    }

    private suspend fun executeToolCall(call: GeminiLiveClient.LiveFunctionCall) {
        val service = AutoAgentService.instance
        if (service == null) {
            val errObj = JSONObject().apply {
                put("status", "error")
                put("message", "AutoAgentService accessibility service not running")
            }
            liveClient?.sendToolResponse(call.id, call.name, errObj)
            return
        }

        val rootNode = service.getRootNode()
        val currentNodes = UiTreeExtractor.extractTree(rootNode)
        val args = call.args

        onStatusUpdate?.invoke("🔧 ${getHindiActionName(call.name, args)}")

        // Execute action based on function declaration
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
            "task_done" -> {
                val summary = args.optString("summary", "Task completed")
                onStatusUpdate?.invoke("✅ $summary")
                onThinkingChanged?.invoke(false)
                "✅ $summary"
            }
            else -> {
                "❓ Unknown function: ${call.name}"
            }
        }

        onStatusUpdate?.invoke(executionResult)

        // Screen transition / settle hone do
        delay(400)

        // Nayi screen padho aur response bhejo
        val freshRoot = service.getRootNode()
        val freshNodes = UiTreeExtractor.extractTree(freshRoot)
        val freshUiJson = UiTreeExtractor.toJson(freshNodes)

        val responseObj = JSONObject().apply {
            put("status", if (executionResult.startsWith("❌")) "error" else "success")
            put("result", executionResult)
            put("currentScreenUi", freshUiJson)
        }

        liveClient?.sendToolResponse(call.id, call.name, responseObj)

        if (call.name == "task_done") {
            delay(1500)
            stopSession()
        }
    }

    private fun getHindiActionName(name: String, args: JSONObject): String {
        return when (name) {
            "open_app" -> "App khol raha hoon: ${args.optString("appName")}"
            "click_element" -> "Click kar raha hoon"
            "type_text" -> "Type kar raha hoon: ${args.optString("text").take(15)}"
            "scroll" -> "Scroll kar raha hoon (${args.optString("direction")})"
            "tap_coordinates" -> "Tap kar raha hoon"
            "swipe" -> "Swipe kar raha hoon"
            "global_action" -> "${args.optString("action")} daba raha hoon"
            "open_url" -> "URL khol raha hoon"
            "take_screenshot" -> "Screenshot le raha hoon"
            "task_done" -> "Ho gaya!"
            else -> name
        }
    }

    override fun onToolCancellation(ids: List<String>) {
        Log.w(TAG, "Tool calls cancelled by Gemini server: $ids")
    }

    override fun onInterrupted() {
        // Barge-in: User started speaking while Gemini was speaking
        audioPlayer?.stopAndClear()
    }

    override fun onTurnComplete() {
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

    override fun onError(error: String) {
        engineScope.launch {
            onStatusUpdate?.invoke("❌ $error")
            onThinkingChanged?.invoke(false)
        }
    }

    override fun onClosed(code: Int, reason: String) {
        engineScope.launch {
            if (isSessionActive.get()) {
                onStatusUpdate?.invoke("⏹ Connection band hua")
                stopSession()
            }
        }
    }
}
