package dev.krinry.jarvis.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * JarvisNotificationService — Listens to all device notifications.
 *
 * Stores active notifications so AI can read, dismiss, or reply to them.
 * Must be enabled in Settings → Notification Access.
 */
class JarvisNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotificationSvc"

        @Volatile
        var instance: JarvisNotificationService? = null
            private set

        /**
         * Get all active notifications as compact data for AI.
         */
        fun getActiveNotifications(): List<NotificationData> {
            return instance?.getNotifications() ?: emptyList()
        }

        /**
         * Dismiss a notification by package name and tag.
         */
        fun dismissByPackage(packageName: String) {
            instance?.cancelByPackage(packageName)
        }
    }

    data class NotificationData(
        val id: Int,
        val packageName: String,
        val appName: String,
        val title: String?,
        val text: String?,
        val time: Long,
        val key: String
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification posted: ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification removed: ${sbn?.packageName}")
    }

    private fun getNotifications(): List<NotificationData> {
        return try {
            activeNotifications?.map { sbn ->
                val extras = sbn.notification.extras
                NotificationData(
                    id = sbn.id,
                    packageName = sbn.packageName,
                    appName = getAppName(sbn.packageName),
                    title = extras?.getCharSequence("android.title")?.toString(),
                    text = extras?.getCharSequence("android.text")?.toString(),
                    time = sbn.postTime,
                    key = sbn.key
                )
            }?.sortedByDescending { it.time } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Get notifications error", e)
            emptyList()
        }
    }

    private fun cancelByPackage(packageName: String) {
        try {
            activeNotifications?.filter { it.packageName == packageName }?.forEach {
                cancelNotification(it.key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cancel notification error", e)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }
}
