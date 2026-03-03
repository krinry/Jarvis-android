package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.service.JarvisNotificationService

/**
 * NotificationReader — Read, dismiss, and interact with notifications.
 *
 * Requires JarvisNotificationService to be enabled in device settings.
 * Actions: read_notifications, dismiss_notification
 */
object NotificationReader {

    private const val TAG = "NotificationReader"

    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        if (JarvisNotificationService.instance == null) {
            return "❌ Notification access nahi hai. Settings → Notification Access mein Jarvis enable karo."
        }

        return when (action.action) {
            "read_notifications" -> readNotifications()
            "dismiss_notification" -> dismissNotification(action)
            else -> "❓ Unknown notification action: ${action.action}"
        }
    }

    /**
     * Read all active notifications in compact format for AI.
     * AI: {"action":"read_notifications"}
     */
    private fun readNotifications(): String {
        val notifications = JarvisNotificationService.getActiveNotifications()

        if (notifications.isEmpty()) {
            return "✅ Koi notification nahi hai"
        }

        val sb = StringBuilder("✅ Notifications (${notifications.size}):\n")
        notifications.take(15).forEachIndexed { i, n ->
            sb.append("${i + 1}. [${n.appName}] ${n.title ?: ""}")
            n.text?.let { sb.append(": ${it.take(60)}") }
            sb.append("\n")
        }

        Log.d(TAG, "Read ${notifications.size} notifications")
        return sb.toString().trim()
    }

    /**
     * Dismiss notifications from a specific app.
     * AI: {"action":"dismiss_notification","text":"WhatsApp"}
     */
    private fun dismissNotification(action: ActionExecutor.AgentAction): String {
        val appName = action.text
        if (appName.isNullOrBlank()) return "❌ App name nahi diya dismiss ke liye"

        // Find matching package by app name
        val notifications = JarvisNotificationService.getActiveNotifications()
        val matching = notifications.filter {
            it.appName.contains(appName, ignoreCase = true) ||
            it.packageName.contains(appName, ignoreCase = true)
        }

        if (matching.isEmpty()) {
            return "❌ '$appName' ki koi notification nahi mili"
        }

        val pkg = matching.first().packageName
        JarvisNotificationService.dismissByPackage(pkg)
        Log.d(TAG, "Dismissed ${matching.size} notifications from $pkg")
        return "✅ ${matching.size} notification dismiss ki: $appName"
    }
}
