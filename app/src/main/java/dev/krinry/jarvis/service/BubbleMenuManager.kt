package dev.krinry.jarvis.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.PixelFormat
import android.view.Gravity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BubbleMenuManager — Handles the long-press popup menu and utility functions
 * for the floating bubble overlay.
 *
 * Extracted from FloatingBubbleService for cleaner separation.
 */
class BubbleMenuManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    // Keep screen on state
    private var wakeLock: PowerManager.WakeLock? = null
    var keepScreenOn = false
        private set

    /**
     * Show the long-press menu overlay.
     */
    fun showMenu(
        onRepeatLast: () -> Unit,
        onClearSubtitles: () -> Unit,
        onClose: () -> Unit,
        scope: CoroutineScope
    ) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val menuItems = mutableListOf<String>()
        menuItems.add("🔄 Repeat Last Command")
        menuItems.add("🗑️ Clear Subtitles")
        menuItems.add(if (keepScreenOn) "🌙 Screen: Auto-off" else "☀️ Screen: Always On")
        menuItems.add("❌ Close Jarvis")

        val menuLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0xF0141428.toInt())
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), 0x40FFFFFF)
            }
            elevation = dpToPx(16).toFloat()
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        menuItems.forEachIndexed { index, item ->
            val tv = TextView(context).apply {
                text = item
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(14))
                setOnClickListener {
                    try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
                    when (index) {
                        0 -> onRepeatLast()
                        1 -> onClearSubtitles()
                        2 -> toggleKeepScreenOn()
                        3 -> onClose()
                    }
                }
            }
            menuLayout.addView(tv)
        }

        menuLayout.setOnClickListener {
            try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
        }

        windowManager.addView(menuLayout, menuParams)

        // Auto-dismiss after 5 seconds
        scope.launch {
            delay(5000)
            try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
        }
    }

    fun toggleKeepScreenOn(): String {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) {
            acquireWakeLock()
            return "☀️ Screen will stay on"
        } else {
            releaseWakeLock()
            return "🌙 Screen auto-off restored"
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "jarvis:screenon"
            )
        }
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
}

