package dev.krinry.jarvis.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import dev.krinry.jarvis.service.FloatingBubbleService

/**
 * JarvisServiceLauncher — Unified lifecycle controller for Jarvis background services.
 */
object JarvisServiceLauncher {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    fun startBubbleService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not start Jarvis: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopBubbleService(context: Context) {
        try {
            context.startService(
                Intent(context, FloatingBubbleService::class.java).apply {
                    action = FloatingBubbleService.ACTION_STOP
                }
            )
        } catch (_: Exception) {}
    }

    fun isBubbleRunning(): Boolean = FloatingBubbleService.isRunning
}
