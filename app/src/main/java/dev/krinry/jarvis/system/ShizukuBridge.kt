package dev.krinry.jarvis.system

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader

/**
 * ShizukuBridge — Grants Jarvis direct ADB Shell & Root-level OS control.
 *
 * Capabilities:
 * - Silent screen gestures: 'input tap', 'input swipe', 'input keyevent'
 * - Hardware toggles: 'svc wifi', 'svc data', 'svc bluetooth'
 * - System settings: 'settings put'
 * - Package management: 'pm install', 'pm uninstall', 'pm clear', 'am force-stop'
 * - Silent screen capture: 'screencap -p' without any user prompt
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    const val REQUEST_CODE_SHIZUKU = 9001

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isSuccess: Boolean = exitCode == 0
    )

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "⚡ Shizuku binder received. Service is active.")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "⚠️ Shizuku binder died.")
    }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
        }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Exception) {}
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Exception) {}
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU) {
        try {
            if (isShizukuAvailable() && !hasShizukuPermission()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    private val newProcessMethod by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Shizuku.newProcess via reflection", e)
            null
        }
    }

    private fun spawnProcess(cmd: Array<String>): Process? {
        return try {
            newProcessMethod?.invoke(null, cmd, null, null) as? Process
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn Shizuku process for ${cmd.joinToString(" ")}", e)
            null
        }
    }

    /**
     * Executes an arbitrary shell command using Shizuku's ADB/Root process.
     */
    suspend fun executeShell(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku permission not granted or service inactive"
            )
        }

        try {
            val process = spawnProcess(arrayOf("sh", "-c", command))
                ?: return@withContext ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Could not initialize Shizuku remote process"
                )

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()

            Log.d(TAG, "[$command] -> exitCode=$exitCode, out='${stdout.take(60)}'")
            ShellResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Error executing shell command: $command", e)
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Execution failed"
            )
        }
    }

    // =========================================================================
    // === High-Level Iron Man Commands ===
    // =========================================================================

    suspend fun tap(x: Int, y: Int): Boolean {
        val result = executeShell("input tap $x $y")
        return result.isSuccess
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val result = executeShell("input swipe $x1 $y1 $x2 $y2 $durationMs")
        return result.isSuccess
    }

    suspend fun keyEvent(keyCode: Int): Boolean {
        val result = executeShell("input keyevent $keyCode")
        return result.isSuccess
    }

    suspend fun typeText(text: String): Boolean {
        val escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
        val result = executeShell("input text \"$escaped\"")
        return result.isSuccess
    }

    suspend fun toggleWifi(enable: Boolean): Boolean {
        val arg = if (enable) "enable" else "disable"
        val result = executeShell("svc wifi $arg")
        return result.isSuccess
    }

    suspend fun toggleMobileData(enable: Boolean): Boolean {
        val arg = if (enable) "enable" else "disable"
        val result = executeShell("svc data $arg")
        return result.isSuccess
    }

    suspend fun toggleBluetooth(enable: Boolean): Boolean {
        val arg = if (enable) "enable" else "disable"
        val result = executeShell("svc bluetooth $arg")
        return result.isSuccess
    }

    suspend fun setBrightness(level: Int): Boolean {
        val clamped = level.coerceIn(0, 255)
        val result = executeShell("settings put system screen_brightness $clamped")
        return result.isSuccess
    }

    suspend fun forceStopApp(packageName: String): Boolean {
        val result = executeShell("am force-stop $packageName")
        return result.isSuccess
    }

    suspend fun silentScreenshot(): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext null
        try {
            val process = spawnProcess(arrayOf("screencap", "-p")) ?: return@withContext null
            val outputStream = ByteArrayOutputStream()
            process.inputStream.use { input ->
                input.copyTo(outputStream)
            }
            process.waitFor()
            val bytes = outputStream.toByteArray()
            if (bytes.isNotEmpty()) bytes else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture silent screenshot via Shizuku", e)
            null
        }
    }

    /**
     * Silent Screen Capture optimized for Multimodal Vision (Gemini Live / Vision Models).
     * Compresses the screen capture into high-efficiency JPEG format (~80-150KB).
     */
    suspend fun silentScreenshotJpeg(quality: Int = 75, maxDimension: Int = 1080): ByteArray? = withContext(Dispatchers.IO) {
        val pngBytes = silentScreenshot() ?: return@withContext null
        try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return@withContext null
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val scale = maxDimension.toFloat() / Math.max(width, height)
                val targetW = (width * scale).toInt()
                val targetH = (height * scale).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val jpegStream = ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), jpegStream)
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            val result = jpegStream.toByteArray()
            Log.d(TAG, "Silent screencap ready: ${result.size} bytes JPEG")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress silent screenshot to JPEG", e)
            null
        }
    }
}
