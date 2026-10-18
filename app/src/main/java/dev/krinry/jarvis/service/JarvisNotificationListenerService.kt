package dev.krinry.jarvis.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * JarvisNotificationListenerService — Iron Man Notification Intelligence Subsystem.
 *
 * Capabilities:
 * - Real-time observation of all incoming notifications (WhatsApp, Telegram, SMS, Banking, etc.)
 * - Automatic regex detection and caching of OTPs / verification codes
 * - Inline direct replies via RemoteInput without opening the target app
 * - Notification dismissal / clearing
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotification"

        data class NotificationEntry(
            val key: String,
            val id: Int,
            val packageName: String,
            val appName: String,
            val title: String,
            val text: String,
            val timestamp: Long,
            val isClearable: Boolean,
            val replyAction: Notification.Action? = null
        )

        data class OtpInfo(
            val code: String,
            val sender: String,
            val messageSnippet: String,
            val timestamp: Long
        )

        @Volatile
        var isConnected = false
            private set

        private val recentNotifications = ConcurrentHashMap<String, NotificationEntry>()
        private val notificationOrder = mutableListOf<String>()
        private val lock = Any()

        @Volatile
        var latestOtp: OtpInfo? = null
            private set

        // Regex patterns for detecting OTPs / verification codes
        private val OTP_PATTERNS = listOf(
            Pattern.compile("(?i)(?:otp|code|verification|passwd|pin|security code)\\s*(?:is|:|-)?\\s*(\\d{4,8})"),
            Pattern.compile("(?i)(\\d{4,8})\\s*(?:is your|is the|as your)?\\s*(?:verification|otp|code|security)"),
            Pattern.compile("(?i)(?:use|enter)\\s*(\\d{4,8})\\s*to verify"),
            Pattern.compile("(?i)\\b(\\d{4,8})\\b\\s*(?:is valid for|valid for)")
        )

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(context.packageName)
        }

        fun createNotificationSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun getRecent(packageNameFilter: String? = null, limit: Int = 10): List<NotificationEntry> {
            synchronized(lock) {
                return notificationOrder.reversed()
                    .mapNotNull { recentNotifications[it] }
                    .filter { entry ->
                        packageNameFilter == null || entry.packageName.contains(packageNameFilter, ignoreCase = true)
                                || entry.appName.contains(packageNameFilter, ignoreCase = true)
                    }
                    .take(limit)
            }
        }

        fun getLatestOtpCode(): OtpInfo? {
            val otp = latestOtp ?: return null
            // Only return OTP if it was received within the last 15 minutes
            val ageMs = System.currentTimeMillis() - otp.timestamp
            return if (ageMs < 15 * 60 * 1000L) otp else null
        }

        fun replyTo(target: String, replyText: String, context: Context): Boolean {
            synchronized(lock) {
                val entry = notificationOrder.reversed()
                    .mapNotNull { recentNotifications[it] }
                    .firstOrNull {
                        it.key == target ||
                                it.appName.contains(target, ignoreCase = true) ||
                                it.title.contains(target, ignoreCase = true) ||
                                it.packageName.contains(target, ignoreCase = true)
                    } ?: return false

                val action = entry.replyAction ?: return false
                val remoteInputs = action.remoteInputs ?: return false

                return try {
                    val bundle = Bundle()
                    for (remoteInput in remoteInputs) {
                        bundle.putCharSequence(remoteInput.resultKey, replyText)
                    }

                    val replyIntent = Intent()
                    RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle)
                    action.actionIntent.send(context, 0, replyIntent)
                    Log.i(TAG, "Successfully sent direct reply to ${entry.title} (${entry.appName})")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send direct reply", e)
                    false
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "⚡ JarvisNotificationListenerService connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "⚠️ JarvisNotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        synchronized(lock) {
            recentNotifications.remove(sbn.key)
            notificationOrder.remove(sbn.key)
        }
    }

    private fun refreshActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh active notifications", e)
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        // Find direct reply action if available
        var replyAction: Notification.Action? = null
        n.actions?.forEach { action ->
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                replyAction = action
            }
        }

        val entry = NotificationEntry(
            key = sbn.key,
            id = sbn.id,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            isClearable = sbn.isClearable,
            replyAction = replyAction
        )

        synchronized(lock) {
            recentNotifications[sbn.key] = entry
            notificationOrder.remove(sbn.key)
            notificationOrder.add(sbn.key)
            if (notificationOrder.size > 50) {
                val oldestKey = notificationOrder.removeAt(0)
                recentNotifications.remove(oldestKey)
            }
        }

        // Check for OTP patterns in title or text
        val combinedText = "$title $text"
        for (pattern in OTP_PATTERNS) {
            val matcher = pattern.matcher(combinedText)
            if (matcher.find()) {
                val code = matcher.group(1) ?: matcher.group(2)
                if (code != null && code.length in 4..8) {
                    latestOtp = OtpInfo(
                        code = code,
                        sender = if (title.isNotBlank()) "$appName ($title)" else appName,
                        messageSnippet = text.take(120),
                        timestamp = System.currentTimeMillis()
                    )
                    Log.i(TAG, "🔑 Detected OTP: $code from ${latestOtp?.sender}")
                    break
                }
            }
        }
    }
}
