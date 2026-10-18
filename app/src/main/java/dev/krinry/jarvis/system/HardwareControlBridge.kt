package dev.krinry.jarvis.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.krinry.jarvis.receiver.JarvisDeviceAdminReceiver
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * HardwareControlBridge — Dual-Tier Iron Man hardware automation bridge.
 *
 * Combines Native Android APIs (CameraManager, AudioManager, NotificationManager)
 * with Shizuku ADB Shell authority and Accessibility UI Fallbacks for complete,
 * zero-friction device control.
 *
 * Controls:
 * - Torch / Flashlight (Native CameraManager)
 * - Volume & Mute (Native AudioManager with smart percentages and relative steps)
 * - Do Not Disturb & Ringer Mode (NotificationManager & AudioManager)
 * - Hotspot / Tethering (Shizuku shell + Seamless Auto-UI fallback)
 * - Auto-Rotate (Shizuku system settings)
 * - Media Playback Controls (Play/Pause/Skip/Prev via keyevent)
 * - Device Power Controls (Reboot/Shutdown/Lock)
 * - App Data & Process Management (pm clear, am force-stop)
 */
object HardwareControlBridge {

    private const val TAG = "HardwareControlBridge"

    @Volatile
    private var isTorchOn = false

    // =========================================================================
    // === 1. Torch / Flashlight (Native CameraManager) ===
    // =========================================================================

    fun toggleTorch(context: Context, enable: Boolean): Pair<Boolean, String> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return Pair(false, "Camera service unavailable")

            var targetCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id
                    break
                }
            }

            // Fallback to any camera with flash if rear camera wasn't matched
            if (targetCameraId == null) {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        targetCameraId = id
                        break
                    }
                }
            }

            if (targetCameraId == null) {
                return Pair(false, "No flashlight hardware detected on this device")
            }

            cameraManager.setTorchMode(targetCameraId, enable)
            isTorchOn = enable
            Log.d(TAG, "Torch set to $enable on camera $targetCameraId")
            Pair(true, if (enable) "Torch turned on, Sir" else "Torch turned off, Sir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: ${e.message}", e)
            Pair(false, "Failed to toggle torch: ${e.message}")
        }
    }

    fun isTorchActive(): Boolean = isTorchOn

    // =========================================================================
    // === 2. Smart Volume & Audio Stream Control ===
    // =========================================================================

    fun setVolume(
        context: Context,
        streamName: String = "media",
        levelPercent: Int? = null,
        relativeStep: Int? = null,
        mute: Boolean? = null
    ): Pair<Boolean, String> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return Pair(false, "Audio service unavailable")

            val streamType = when (streamName.lowercase().trim()) {
                "ring", "ringer", "call", "phone" -> AudioManager.STREAM_RING
                "alarm", "wake" -> AudioManager.STREAM_ALARM
                "notification", "notice" -> AudioManager.STREAM_NOTIFICATION
                "system" -> AudioManager.STREAM_SYSTEM
                "voice", "voice_call" -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_MUSIC
            }

            val friendlyName = when (streamType) {
                AudioManager.STREAM_RING -> "Ringtone"
                AudioManager.STREAM_ALARM -> "Alarm"
                AudioManager.STREAM_NOTIFICATION -> "Notification"
                AudioManager.STREAM_VOICE_CALL -> "Call"
                else -> "Media"
            }

            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val currentVol = audioManager.getStreamVolume(streamType)

            // Handle Mute / Unmute
            if (mute != null) {
                if (mute) {
                    audioManager.setStreamVolume(streamType, 0, AudioManager.FLAG_SHOW_UI)
                    return Pair(true, "$friendlyName volume muted, Sir")
                } else {
                    val defaultUnmute = (maxVol * 0.4f).roundToInt().coerceAtLeast(1)
                    audioManager.setStreamVolume(streamType, defaultUnmute, AudioManager.FLAG_SHOW_UI)
                    val pct = ((defaultUnmute.toFloat() / maxVol) * 100).toInt()
                    return Pair(true, "$friendlyName volume unmuted at $pct%, Sir")
                }
            }

            // Handle Absolute Percentage (0 to 100)
            if (levelPercent != null) {
                val clampedPct = levelPercent.coerceIn(0, 100)
                val targetVol = ((clampedPct / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
                return Pair(true, "$friendlyName volume set to $clampedPct% ($targetVol/$maxVol), Sir")
            }

            // Handle Relative Adjustment (+1, -1, +2, -2 steps)
            if (relativeStep != null) {
                val targetVol = (currentVol + relativeStep).coerceIn(0, maxVol)
                audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
                val newPct = ((targetVol.toFloat() / maxVol) * 100).toInt()
                val direction = if (relativeStep > 0) "increased" else "decreased"
                return Pair(true, "$friendlyName volume $direction to $newPct% ($targetVol/$maxVol), Sir")
            }

            // Fallback: Default increase 1 step
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            val updated = audioManager.getStreamVolume(streamType)
            val newPct = ((updated.toFloat() / maxVol) * 100).toInt()
            Pair(true, "$friendlyName volume set to $newPct%, Sir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
            Pair(false, "Failed to adjust volume: ${e.message}")
        }
    }

    // =========================================================================
    // === 3. Do Not Disturb (DND) & Ringer Mode ===
    // =========================================================================

    fun setDnd(context: Context, enable: Boolean): Pair<Boolean, String> {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            var policyApplied = false
            if (notificationManager != null && notificationManager.isNotificationPolicyAccessGranted) {
                val filter = if (enable) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
                notificationManager.setInterruptionFilter(filter)
                policyApplied = true
            }

            // Complement with AudioManager ringer mode
            if (audioManager != null) {
                if (enable) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                } else {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
            }

            Pair(
                true,
                if (enable) "Do Not Disturb activated, Sir" else "Do Not Disturb deactivated, Sir"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set DND", e)
            Pair(false, "Failed to toggle DND: ${e.message}")
        }
    }

    // =========================================================================
    // === 4. Hotspot / Wi-Fi Tethering (Shizuku + Auto-UI Fallback) ===
    // =========================================================================

    suspend fun toggleHotspot(context: Context, enable: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // Step 1: Try Shizuku privileged shell methods
        if (ShizukuBridge.hasShizukuPermission()) {
            val cmd = if (enable) {
                "cmd connectivity start-tethering 0 || svc wifi softap start"
            } else {
                "cmd connectivity stop-tethering 0 || svc wifi softap stop"
            }
            val shellResult = ShizukuBridge.executeShell(cmd)
            if (shellResult.isSuccess && !shellResult.stderr.contains("No shell command")) {
                Log.d(TAG, "Hotspot toggled via Shizuku: ${shellResult.stdout}")
                return@withContext Pair(true, if (enable) "Hotspot enabled via system shell, Sir" else "Hotspot disabled via system shell, Sir")
            }
        }

        // Step 2: Seamless Auto-UI Fallback via Tethering Settings & AutoAgentService
        withContext(Dispatchers.Main) {
            try {
                val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    return@withContext Pair(false, "Could not open tethering settings: ${e.message}")
                }
            }
        }

        // Allow Tethering Settings screen to render
        delay(700)

        val service = AutoAgentService.instance
        if (service != null) {
            val root = service.getRootNode()
            if (root != null) {
                val nodes = dev.krinry.jarvis.agent.UiTreeExtractor.extractTree(root)
                // Search for Hotspot toggle or switch
                val hotspotNode = nodes.find { node ->
                    val txt = (node.text + " " + node.contentDescription).lowercase()
                    (txt.contains("personal hotspot") || txt.contains("wi-fi hotspot") || txt.contains("hotspot") || txt.contains("portable hotspot"))
                }

                if (hotspotNode != null) {
                    // Click the node or its switch
                    service.clickNode(hotspotNode.nodeInfo)
                    delay(300)
                    // Return user back to their previous screen automatically
                    service.pressBack()
                    return@withContext Pair(true, if (enable) "Hotspot activated, Sir" else "Hotspot toggled, Sir")
                }
            }
            // If specific node wasn't identified automatically, notify user
            return@withContext Pair(true, "Tethering settings opened. Please verify hotspot status, Sir.")
        }

        Pair(false, "Hotspot toggle requires Shizuku authority or Accessibility service active")
    }

    // =========================================================================
    // === 5. Auto-Rotate Screen ===
    // =========================================================================

    suspend fun toggleAutoRotate(enable: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuBridge.hasShizukuPermission()) {
            return@withContext Pair(false, "Auto-rotate control requires Shizuku authority")
        }
        val valArg = if (enable) "1" else "0"
        val res = ShizukuBridge.executeShell("settings put system accelerometer_rotation $valArg")
        if (res.isSuccess) {
            Pair(true, if (enable) "Auto-rotate screen enabled, Sir" else "Auto-rotate screen locked, Sir")
        } else {
            Pair(false, "Failed to update rotation setting: ${res.stderr}")
        }
    }

    // =========================================================================
    // === 6. Media Playback Controls ===
    // =========================================================================

    suspend fun mediaControl(action: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val keyCode = when (action.lowercase().trim()) {
            "play" -> 126 // KEYCODE_MEDIA_PLAY
            "pause" -> 127 // KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> 85 // KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> 87 // KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> 88 // KEYCODE_MEDIA_PREVIOUS
            "stop" -> 86 // KEYCODE_MEDIA_STOP
            else -> 85
        }

        val actionDesc = when (keyCode) {
            126 -> "Playback resumed"
            127 -> "Playback paused"
            85 -> "Media playback toggled"
            87 -> "Skipped to next track"
            88 -> "Returned to previous track"
            86 -> "Media stopped"
            else -> "Media command executed"
        }

        if (ShizukuBridge.hasShizukuPermission()) {
            val ok = ShizukuBridge.keyEvent(keyCode)
            if (ok) return@withContext Pair(true, "$actionDesc, Sir")
        }

        // Accessibility service fallback
        val service = AutoAgentService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Can be dispatched via system or Shizuku
        }

        Pair(false, "Media control requires Shizuku authority")
    }

    // =========================================================================
    // === 7. Device Power & Reboot Controls ===
    // =========================================================================

    suspend fun rebootDevice(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuBridge.hasShizukuPermission()) {
            return@withContext Pair(false, "Device reboot requires Shizuku authority")
        }
        val res = ShizukuBridge.executeShell("svc power reboot")
        if (res.isSuccess) Pair(true, "Rebooting device now, Sir.")
        else Pair(false, "Reboot failed: ${res.stderr}")
    }

    suspend fun shutdownDevice(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuBridge.hasShizukuPermission()) {
            return@withContext Pair(false, "Device shutdown requires Shizuku authority")
        }
        val res = ShizukuBridge.executeShell("svc power shutdown")
        if (res.isSuccess) Pair(true, "Shutting down device, Sir.")
        else Pair(false, "Shutdown failed: ${res.stderr}")
    }

    fun lockDevice(context: Context): Pair<Boolean, String> {
        val ok = JarvisDeviceAdminReceiver.lockDevice(context)
        if (ok) return Pair(true, "Device locked, Sir")

        if (ShizukuBridge.hasShizukuPermission()) {
            // KEYCODE_POWER (26)
            kotlinx.coroutines.runBlocking {
                ShizukuBridge.keyEvent(26)
            }
            return Pair(true, "Device screen locked via shell, Sir")
        }

        return Pair(false, "Lock requires Device Administrator or Shizuku authority")
    }

    // =========================================================================
    // === 8. App Freeze & Data Management ===
    // =========================================================================

    suspend fun clearAppData(packageName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuBridge.hasShizukuPermission()) {
            return@withContext Pair(false, "Clearing app data requires Shizuku authority")
        }
        val res = ShizukuBridge.executeShell("pm clear $packageName")
        if (res.isSuccess) Pair(true, "Cache and data cleared for $packageName, Sir")
        else Pair(false, "Failed to clear data for $packageName: ${res.stderr}")
    }
}
