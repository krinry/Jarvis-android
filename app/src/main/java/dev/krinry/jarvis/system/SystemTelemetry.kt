package dev.krinry.jarvis.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale

/**
 * SystemTelemetry — Iron Man diagnostic probes for battery, memory, storage, and system health.
 */
object SystemTelemetry {

    data class DiagnosticReport(
        val batteryLevel: Int,
        val batteryTemperatureCelsius: Float,
        val isCharging: Boolean,
        val chargingType: String,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val ramUsagePercent: Int,
        val freeStorageGb: Float,
        val totalStorageGb: Float,
        val uptimeFormatted: String
    )

    fun getDiagnostics(context: Context): DiagnosticReport {
        // Battery status
        var batteryLevel = -1
        var batteryTemp = 0.0f
        var isCharging = false
        var chargingType = "Unplugged"

        try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else -1

                val tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                batteryTemp = tempTenths / 10.0f

                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                chargingType = when (chargePlug) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC Power"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> if (isCharging) "Charging" else "Battery"
                }
            }
        } catch (_: Exception) {}

        // Memory (RAM)
        var availRam = 0L
        var totalRam = 0L
        var ramPercent = 0
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            availRam = memInfo.availMem / (1024 * 1024)
            totalRam = memInfo.totalMem / (1024 * 1024)
            if (totalRam > 0) {
                ramPercent = (((totalRam - availRam).toDouble() / totalRam.toDouble()) * 100).toInt()
            }
        } catch (_: Exception) {}

        // Storage
        var freeStorageGb = 0f
        var totalStorageGb = 0f
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val bytesTotal = stat.blockCountLong * stat.blockSizeLong
            freeStorageGb = bytesAvailable / (1024f * 1024f * 1024f)
            totalStorageGb = bytesTotal / (1024f * 1024f * 1024f)
        } catch (_: Exception) {}

        // Uptime
        val uptimeMillis = SystemClock.elapsedRealtime()
        val hours = (uptimeMillis / (1000 * 60 * 60)).toInt()
        val minutes = ((uptimeMillis / (1000 * 60)) % 60).toInt()
        val uptimeFormatted = "${hours}h ${minutes}m"

        return DiagnosticReport(
            batteryLevel = batteryLevel,
            batteryTemperatureCelsius = batteryTemp,
            isCharging = isCharging,
            chargingType = chargingType,
            availableRamMb = availRam,
            totalRamMb = totalRam,
            ramUsagePercent = ramPercent,
            freeStorageGb = freeStorageGb,
            totalStorageGb = totalStorageGb,
            uptimeFormatted = uptimeFormatted
        )
    }

    fun getDiagnosticsSummary(context: Context): String {
        val d = getDiagnostics(context)
        return String.format(
            Locale.US,
            "SYSTEM STATUS REPORT:\n- Battery: %d%% (%s, %.1f°C)\n- Memory: %dMB free / %dMB total (%d%% used)\n- Storage: %.1fGB free / %.1fGB total\n- System Uptime: %s",
            d.batteryLevel,
            d.chargingType,
            d.batteryTemperatureCelsius,
            d.availableRamMb,
            d.totalRamMb,
            d.ramUsagePercent,
            d.freeStorageGb,
            d.totalStorageGb,
            d.uptimeFormatted
        )
    }
}
