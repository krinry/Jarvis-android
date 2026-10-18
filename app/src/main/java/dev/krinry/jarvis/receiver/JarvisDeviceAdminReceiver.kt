package dev.krinry.jarvis.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * JarvisDeviceAdminReceiver — Grants Jarvis Iron Man-level security authority.
 *
 * Enables:
 * - Instant voice screen locking ("Jarvis, lock the phone")
 * - Password policies & screen timeout management
 * - Enterprise / Admin security functions
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "JarvisDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, JarvisDeviceAdminReceiver::class.java)
        }

        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isAdminActive(getComponentName(context)) == true
        }

        fun lockDevice(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                if (dpm != null && dpm.isAdminActive(getComponentName(context))) {
                    dpm.lockNow()
                    Log.d(TAG, "Device successfully locked via DevicePolicyManager")
                    true
                } else {
                    Log.w(TAG, "Device administrator is not active")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to lock device", e)
                false
            }
        }

        fun createEnableAdminIntent(context: Context): Intent {
            return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Jarvis requires Device Administrator access to secure, lock, and manage system power and screen policies on your command."
                )
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "🛡️ Jarvis Device Administrator authority ENABLED")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "⚠️ Jarvis Device Administrator authority DISABLED")
    }
}
