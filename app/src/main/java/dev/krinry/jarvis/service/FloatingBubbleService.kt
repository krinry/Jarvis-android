package dev.krinry.jarvis.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.krinry.jarvis.MainActivity
import dev.krinry.jarvis.R
import dev.krinry.jarvis.agent.AgentLlmEngine
import kotlinx.coroutines.*

/**
 * FloatingBubbleService — Always-on-top overlay bubble for Jarvis AI.
 *
 * Features:
 * - Foreground service with notification
 * - Tap to START recording → tap again to STOP and process
 * - Long-press for menu (via BubbleMenuManager)
 * - Subtitle-style scrolling transcript
 * - Pulse/orbit animations
 * - Double-tap to repeat last command
 *
 * Audio recording delegated to WhisperRecorder.
 * Menu handling delegated to BubbleMenuManager.
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"
        const val ACTION_START = "dev.krinry.jarvis.START_BUBBLE"
        const val ACTION_STOP = "dev.krinry.jarvis.STOP_BUBBLE"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "jarvis_agent_channel"
        private const val MAX_SUBTITLE_LINES = 5

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
    private var subtitleView: View? = null
    private var planView: View? = null  // Plan progress overlay at top
    private var agentEngine: AgentLlmEngine? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Extracted components
    private var whisperRecorder: WhisperRecorder? = null
    private var menuManager: BubbleMenuManager? = null

    private var isProcessingCommand = false
    private val subtitleHistory = mutableListOf<String>()

    // Double-tap & last command
    private var lastTapTime = 0L
    private var lastCommand: String? = null

    // Thinking animation
    private var thinkingAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        whisperRecorder = WhisperRecorder(applicationContext)
        menuManager = BubbleMenuManager(applicationContext, windowManager)

        agentEngine = AgentLlmEngine(applicationContext)
        agentEngine?.onStatusUpdate = { status ->
            scope.launch(Dispatchers.Main) {
                addSubtitle(status)
                if (status.startsWith("✅")) {
                    isProcessingCommand = false
                    stopThinkingAnimation()
                    playCompletionSound()
                    vibratePattern(longArrayOf(0, 80, 60, 80))
                    // Plan stays visible — user can dismiss manually
                } else if (status.startsWith("❌") || status.startsWith("⚠️") || status.startsWith("⏹")) {
                    isProcessingCommand = false
                    stopThinkingAnimation()
                    vibrateShort()
                }
            }
        }
        // Plan progress callback — shows checklist at top of screen
        agentEngine?.onPlanUpdate = { steps, currentIdx, completedSet ->
            scope.launch(Dispatchers.Main) {
                updatePlanOverlay(steps, currentIdx, completedSet)
            }
        }
        isRunning = true
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        if (bubbleView == null) createBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        whisperRecorder?.cancel()
        stopThinkingAnimation()
        menuManager?.releaseWakeLock()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        subtitleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        planView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null; subtitleView = null; planView = null
        super.onDestroy()
    }

    // =========================================================================
    // === Foreground Notification ===
    // =========================================================================

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Jarvis AI Agent",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Jarvis background service"
                    setShowBadge(false)
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }

            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Jarvis Active")
                .setContentText("Tap bubble to give command")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service: ${e.message}")
        }
    }

    // =========================================================================
    // === Bubble Creation ===
    // =========================================================================

    private fun createBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val bubbleSize = dpToPx(54)
        val bubble = FrameLayout(this).apply {
            background = createBubbleDrawable()
            elevation = dpToPx(8).toFloat()
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            val padding = dpToPx(13)
            setPadding(padding, padding, padding, padding)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bubbleParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(10); y = dpToPx(400)
        }

        // Touch: drag + tap + double-tap + long press
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isMoved = false
        val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val longPressRunnable = Runnable { if (!isMoved) { vibrateShort(); showBubbleMenu() } }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x; initialY = bubbleParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isMoved = false
                    longPressHandler.postDelayed(longPressRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dpToPx(5) || Math.abs(dy) > dpToPx(5)) {
                        if (!isMoved) {
                            isMoved = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    bubbleParams.x = initialX + dx; bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubble, bubbleParams); true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!isMoved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 350 && lastCommand != null) {
                            onDoubleTap()
                        } else {
                            onBubbleTapped()
                        }
                        lastTapTime = now
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble

        // === Subtitle overlay ===
        val subtitleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10))
            background = createSubtitleBackground()
            visibility = View.GONE
        }

        val subtitleText = TextView(this).apply {
            id = View.generateViewId()
            tag = "subtitle_text"
            text = ""
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = MAX_SUBTITLE_LINES
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
            lineHeight = dpToPx(22)
        }
        subtitleContainer.addView(subtitleText)

        val subtitleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dpToPx(80) }

        windowManager.addView(subtitleContainer, subtitleParams)
        subtitleView = subtitleContainer

        // === Plan progress overlay (TOP of screen) ===
        val planContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0xE6101025.toInt())
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), 0x306C5CE7)
            }
            visibility = View.GONE
            tag = "plan_container"
        }

        // Plan title row with cancel button
        val planHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val planTitle = TextView(this).apply {
            tag = "plan_title"
            text = "📋 Plan"
            textSize = 13f
            setTextColor(0xFF8E7CF3.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        planHeader.addView(planTitle)

        // Cancel button ✕
        val cancelBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFFFF6B6B.toInt())
            setPadding(dpToPx(12), dpToPx(4), dpToPx(4), dpToPx(4))
            setOnClickListener {
                agentEngine?.cancelTask()
                isProcessingCommand = false
                stopThinkingAnimation()
                hidePlanOverlay()
                addSubtitle("⏹ Task cancelled")
                vibrateShort()
            }
        }
        planHeader.addView(cancelBtn)
        planContainer.addView(planHeader)

        val planStepsText = TextView(this).apply {
            tag = "plan_steps"
            text = ""
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            lineHeight = dpToPx(20)
        }
        planContainer.addView(planStepsText)

        val planParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(40)
        }

        windowManager.addView(planContainer, planParams)
        planView = planContainer
    }

    // =========================================================================
    // === Long-Press Menu (delegated to BubbleMenuManager) ===
    // =========================================================================

    private fun showBubbleMenu() {
        menuManager?.showMenu(
            onRepeatLast = { repeatLastCommand() },
            onClearSubtitles = { clearSubtitles() },
            onClose = { stopSelf() },
            scope = scope
        )
    }

    private fun repeatLastCommand() {
        val cmd = lastCommand
        if (cmd.isNullOrBlank()) {
            addSubtitle("❌ No previous command")
            return
        }
        isProcessingCommand = true
        subtitleHistory.clear()
        addSubtitle("🔄 Repeating: \"$cmd\"")
        startThinkingAnimation()
        agentEngine?.startTask(cmd, scope)
    }

    // =========================================================================
    // === Subtitle System ===
    // =========================================================================

    private fun addSubtitle(text: String) {
        subtitleHistory.add(text)
        while (subtitleHistory.size > MAX_SUBTITLE_LINES) subtitleHistory.removeAt(0)

        subtitleView?.let { view ->
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                val slideUp = TranslateAnimation(0f, 0f, dpToPx(30).toFloat(), 0f).apply { duration = 200 }
                val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 200 }
                val animSet = AnimationSet(true).apply { addAnimation(slideUp); addAnimation(fadeIn) }
                view.startAnimation(animSet)
            }
            val tv = view.findViewWithTag<TextView>("subtitle_text")
            tv?.text = subtitleHistory.joinToString("\n")
        }
    }

    private fun clearSubtitles() {
        subtitleHistory.clear()
        subtitleView?.let { view ->
            val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 200 }
            view.startAnimation(fadeOut)
            scope.launch { delay(200); view.visibility = View.GONE }
        }
    }

    // =========================================================================
    // === Plan Overlay (Top of screen — shows ✅/▶/○ checklist) ===
    // =========================================================================

    private fun updatePlanOverlay(steps: List<String>, currentIdx: Int, completedSet: Set<Int>) {
        planView?.let { view ->
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                val slideDown = TranslateAnimation(0f, 0f, -dpToPx(50).toFloat(), 0f).apply { duration = 250 }
                val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 250 }
                val animSet = AnimationSet(true).apply { addAnimation(slideDown); addAnimation(fadeIn) }
                view.startAnimation(animSet)
            }

            // Update title with progress
            val titleTv = view.findViewWithTag<TextView>("plan_title")
            titleTv?.text = "📋 Plan (${completedSet.size}/${steps.size})"

            // Build step list with icons
            val sb = StringBuilder()
            steps.forEachIndexed { i, step ->
                val icon = when {
                    i in completedSet -> "✅"
                    i == currentIdx -> "▶"
                    else -> "○"
                }
                sb.append("$icon ${i + 1}. ${step.take(35)}")
                if (i < steps.size - 1) sb.append("\n")
            }

            val stepsTv = view.findViewWithTag<TextView>("plan_steps")
            stepsTv?.text = sb.toString()
        }
    }

    private fun hidePlanOverlay() {
        planView?.let { view ->
            if (view.visibility == View.VISIBLE) {
                val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 300 }
                view.startAnimation(fadeOut)
                scope.launch { delay(300); view.visibility = View.GONE }
            }
        }
    }

    // =========================================================================
    // === Bubble Tap / Double-Tap ===
    // =========================================================================

    private fun onBubbleTapped() {
        if (isProcessingCommand) {
            agentEngine?.cancelTask()
            isProcessingCommand = false
            whisperRecorder?.cancel()
            stopThinkingAnimation()
            addSubtitle("⏹ Cancelled")
            vibrateShort()
            return
        }
        if (whisperRecorder?.isListening == true) {
            whisperRecorder?.stopListening()
            vibrateShort()
            addSubtitle("🔄 Processing...")
        } else {
            startWhisperRecording()
        }
    }

    private fun onDoubleTap() {
        vibratePattern(longArrayOf(0, 50, 40, 50))
        repeatLastCommand()
    }

    // =========================================================================
    // === Whisper STT Recording (delegated to WhisperRecorder) ===
    // =========================================================================

    private fun startWhisperRecording() {
        if (!AutoAgentService.isRunning()) {
            addSubtitle("❌ Enable Accessibility Service first!")
            return
        }

        vibrateShort()
        addSubtitle("🎤 Listening... tap to send")

        whisperRecorder?.startRecording(
            scope = scope,
            onStateChange = { listening -> animateBubble(listening) },
            onStatus = { status -> addSubtitle(status) },
            onTranscript = { transcript ->
                if (transcript.isNullOrBlank()) {
                    addSubtitle("❌ Couldn't understand, try again")
                } else {
                    val command = stripWakeWord(transcript)
                    lastCommand = command
                    isProcessingCommand = true
                    subtitleHistory.clear()
                    addSubtitle("🗣️ \"$command\"")
                    startThinkingAnimation()
                    agentEngine?.startTask(command, scope)
                }
            }
        )
    }

    // =========================================================================
    // === Vibration & Sound ===
    // =========================================================================

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(50)
                }
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun playCompletionSound() {
        try {
            val mp = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {}
    }

    // =========================================================================
    // === Thinking Animation ===
    // =========================================================================

    private fun startThinkingAnimation() {
        bubbleView?.let { view ->
            thinkingAnimator?.cancel()
            thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val angle = anim.animatedValue as Float
                    view.rotation = angle
                    val scale = 1f + 0.08f * Math.sin(Math.toRadians(angle.toDouble() * 2)).toFloat()
                    view.scaleX = scale
                    view.scaleY = scale
                }
            }
            thinkingAnimator?.start()
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        bubbleView?.let { view ->
            view.rotation = 0f
            view.scaleX = 1f
            view.scaleY = 1f
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

    private fun animateBubble(listening: Boolean) {
        bubbleView?.let { view ->
            ValueAnimator.ofFloat(view.scaleX, if (listening) 1.25f else 1.0f).apply {
                duration = 200; interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { view.scaleX = animatedValue as Float; view.scaleY = animatedValue as Float }
            }.start()

            if (listening) {
                view.animate().alpha(0.6f).setDuration(350).withEndAction {
                    if (whisperRecorder?.isListening == true) view.animate().alpha(1f).setDuration(350).withEndAction {
                        if (whisperRecorder?.isListening == true) animateBubble(true) }.start()
                }.start()
            } else { view.animate().cancel(); view.alpha = 1f }
        }
    }

    private fun createBubbleDrawable() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        colors = intArrayOf(0xFF6C5CE7.toInt(), 0xFF8E7CF3.toInt())
        cornerRadius = dpToPx(28).toFloat()
        setStroke(dpToPx(2), 0xFFFFFFFF.toInt())
    }

    private fun createSubtitleBackground() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(0xCC0A0A1A.toInt())
        cornerRadius = dpToPx(16).toFloat()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
