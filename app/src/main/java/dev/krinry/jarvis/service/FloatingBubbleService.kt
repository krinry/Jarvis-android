package dev.krinry.jarvis.service

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.krinry.jarvis.R
import dev.krinry.jarvis.agent.AgentLlmEngine
import dev.krinry.jarvis.ai.GroqApiClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatingBubbleService — Always-on-top overlay bubble for Jarvis AI.
 *
 * STT: Uses Groq Whisper (NOT Android SpeechRecognizer) for much better
 *      accuracy, especially for Hindi/English mixed commands.
 *
 * - Tap the bubble to START recording
 * - Tap again to STOP and process command
 * - Hold to manually stop without processing
 * - Shows listening animation & agent status
 * - Draggable
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"
        const val ACTION_START = "dev.krinry.jarvis.START_BUBBLE"
        const val ACTION_STOP = "dev.krinry.jarvis.STOP_BUBBLE"

        // Auto-stop recording after this many seconds of silence / max duration
        private const val MAX_RECORDING_SECONDS = 10
        private const val SAMPLE_RATE = 16000

        // Wake words stripped from commands
        private val WAKE_WORDS = listOf(
            "krinry", "cranary", "kri nri", "crinary", "cranery", "krinari", "jarvis",
            "crinri", "grini", "krinry,", "jarvis,"
        )

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var statusView: View? = null
    private var agentEngine: AgentLlmEngine? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isListening = false
    private var isProcessingCommand = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        agentEngine = AgentLlmEngine(applicationContext)
        agentEngine?.onStatusUpdate = { status ->
            scope.launch(Dispatchers.Main) {
                updateStatusText(status)
                if (status.startsWith("✅") || status.startsWith("❌") ||
                    status.startsWith("⚠️") || status.startsWith("⏹")) {
                    isProcessingCommand = false
                    delay(3000)
                    hideStatus()
                }
            }
        }
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (bubbleView == null) {
            createBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        stopRecording()
        bubbleView?.let { windowManager.removeView(it) }
        statusView?.let { windowManager.removeView(it) }
        bubbleView = null
        statusView = null
        super.onDestroy()
    }

    private fun createBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        // === Bubble button ===
        val bubbleSize = dpToPx(56)
        val bubble = FrameLayout(this).apply {
            background = createBubbleDrawable()
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            val padding = dpToPx(14)
            setPadding(padding, padding, padding, padding)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bubbleParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(400)
        }

        // Touch — drag + tap
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoved = true
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubble, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) onBubbleTapped()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble

        // === Status text overlay ===
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            background = createStatusDrawable()
            visibility = View.GONE
        }

        val statusText = TextView(this).apply {
            id = View.generateViewId()
            tag = "status_text"
            text = "🎤 Tap to speak..."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        }
        statusLayout.addView(statusText)

        val statusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(100)
        }

        windowManager.addView(statusLayout, statusParams)
        statusView = statusLayout
    }

    private fun onBubbleTapped() {
        if (isProcessingCommand) {
            // Cancel current task
            agentEngine?.cancelTask()
            isProcessingCommand = false
            stopRecording()
            hideStatus()
            return
        }
        if (isListening) {
            // Stop recording and process
            stopRecordingAndProcess()
        } else {
            startWhisperRecording()
        }
    }

    // =========================================================================
    // === Whisper STT Recording ===
    // =========================================================================

    /**
     * Start recording audio using AudioRecord (raw PCM).
     * User taps again to stop and send to Whisper.
     */
    private fun startWhisperRecording() {
        if (!AutoAgentService.isRunning()) {
            updateStatusText("❌ Enable Accessibility Service first!")
            return
        }

        isListening = true
        animateBubble(true)
        updateStatusText("🎤 Recording... tap to send")

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        updateStatusText("❌ Microphone unavailable")
                        isListening = false
                        animateBubble(false)
                    }
                    return@launch
                }

                val audioBuffer = mutableListOf<Short>()
                val buffer = ShortArray(bufferSize)

                audioRecord?.startRecording()
                Log.d(TAG, "Whisper recording started")

                val maxSamples = SAMPLE_RATE * MAX_RECORDING_SECONDS

                while (isListening && audioBuffer.size < maxSamples) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        for (i in 0 until read) {
                            audioBuffer.add(buffer[i])
                        }
                    }
                }

                // Auto-stop if max duration reached
                if (audioBuffer.size >= maxSamples) {
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Max recording duration reached, auto-processing")
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                if (audioBuffer.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isListening = false
                        animateBubble(false)
                        updateStatusText("❌ No audio captured")
                        delay(2000)
                        hideStatus()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    isListening = false
                    animateBubble(false)
                    updateStatusText("🔄 Transcribing with Whisper...")
                }

                // Save as WAV file
                val wavFile = saveAsWav(audioBuffer)
                if (wavFile == null) {
                    withContext(Dispatchers.Main) {
                        updateStatusText("❌ Failed to save audio")
                        delay(2000)
                        hideStatus()
                    }
                    return@launch
                }

                // Send to Groq Whisper
                val transcript = GroqApiClient.transcribeAudio(
                    applicationContext,
                    wavFile,
                    null // Auto-detect language (supports Hindi + English)
                )

                // Delete temp file
                wavFile.delete()

                withContext(Dispatchers.Main) {
                    if (transcript.isNullOrBlank()) {
                        updateStatusText("❌ Couldn't understand, try again")
                        delay(2000)
                        hideStatus()
                    } else {
                        Log.d(TAG, "Whisper transcript: $transcript")
                        val command = stripWakeWord(transcript)
                        isProcessingCommand = true
                        updateStatusText("🧠 \"$command\"")
                        agentEngine?.startTask(command, scope)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Whisper recording failed", e)
                withContext(Dispatchers.Main) {
                    isListening = false
                    animateBubble(false)
                    updateStatusText("❌ Recording error: ${e.message?.take(40)}")
                    delay(2000)
                    hideStatus()
                }
            }
        }
    }

    /**
     * Stop the recording loop and process immediately.
     * Called when user taps bubble again during recording.
     */
    private fun stopRecordingAndProcess() {
        Log.d(TAG, "User stopped recording manually")
        isListening = false
        // The coroutine loop checks isListening and will exit
        animateBubble(false)
        updateStatusText("🔄 Processing...")
    }

    private fun stopRecording() {
        isListening = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        animateBubble(false)
    }

    /**
     * Save raw PCM shorts as a WAV file.
     */
    private fun saveAsWav(audioData: List<Short>): File? {
        return try {
            val file = File(cacheDir, "whisper_input_${System.currentTimeMillis()}.wav")
            val totalPcmBytes = audioData.size * 2

            FileOutputStream(file).use { fos ->
                // WAV header
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(36 + totalPcmBytes)      // File size - 8
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)                       // Chunk size
                header.putShort(1)                      // PCM format
                header.putShort(1)                      // Mono
                header.putInt(SAMPLE_RATE)
                header.putInt(SAMPLE_RATE * 2)          // Byte rate (16bit mono)
                header.putShort(2)                      // Block align
                header.putShort(16)                     // Bits per sample
                header.put("data".toByteArray())
                header.putInt(totalPcmBytes)

                fos.write(header.array())

                // PCM data
                val pcmBytes = ByteBuffer.allocate(totalPcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioData) {
                    pcmBytes.putShort(sample)
                }
                fos.write(pcmBytes.array())
            }

            Log.d(TAG, "WAV saved: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV", e)
            null
        }
    }

    // =========================================================================
    // === Helpers ===
    // =========================================================================

    private fun stripWakeWord(command: String): String {
        val lower = command.trim().lowercase()
        for (wake in WAKE_WORDS) {
            if (lower.startsWith(wake)) {
                val stripped = command.substring(wake.length).trim().trimStart(',', ' ')
                if (stripped.isNotEmpty()) return stripped
            }
        }
        return command.trim()
    }

    private fun updateStatusText(text: String) {
        statusView?.let { view ->
            view.visibility = View.VISIBLE
            val tv = view.findViewWithTag<TextView>("status_text")
            tv?.text = text
        }
    }

    private fun hideStatus() {
        statusView?.visibility = View.GONE
    }

    private fun animateBubble(listening: Boolean) {
        bubbleView?.let { view ->
            val scale = if (listening) 1.3f else 1.0f
            val animator = ValueAnimator.ofFloat(view.scaleX, scale).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Float
                    view.scaleX = value
                    view.scaleY = value
                }
            }
            animator.start()

            if (listening) {
                view.animate()
                    .alpha(0.6f)
                    .setDuration(500)
                    .withEndAction {
                        if (isListening) {
                            view.animate().alpha(1f).setDuration(500).withEndAction {
                                if (isListening) animateBubble(true)
                            }.start()
                        }
                    }.start()
            } else {
                view.animate().cancel()
                view.alpha = 1f
            }
        }
    }

    private fun createBubbleDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(0xFF6C5CE7.toInt(), 0xFF8E7CF3.toInt())
            cornerRadius = dpToPx(28).toFloat()
            setStroke(dpToPx(2), 0xFFFFFFFF.toInt())
        }
    }

    private fun createStatusDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0xDD1A1A2E.toInt())
            cornerRadius = dpToPx(20).toFloat()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
