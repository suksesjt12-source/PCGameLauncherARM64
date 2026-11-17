package com.suksesjt12.pcgamelauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.util.Locale

/**
 * Reads public Android battery, thermal and memory telemetry.
 * PSS reports this launcher process, not the child Box64/Wine process.
 */
object PerformanceMonitor {
    data class Snapshot(
        val batteryTemperatureC: Double?,
        val batteryPercent: Int?,
        val charging: Boolean?,
        val thermalState: String,
        val availableSystemRamMb: Long,
        val launcherPssMb: Long
    ) {
        fun toDisplayText(): String {
            val temperature = batteryTemperatureC?.let {
                String.format(Locale.US, "%.1f°C", it)
            } ?: "N/A"
            val battery = batteryPercent?.let { "$it%" } ?: "N/A"
            val power = when (charging) {
                true -> "Charging"
                false -> "Battery"
                null -> "Unknown power state"
            }
            return "BATTERY: $temperature • $battery • $power\n" +
                "ANDROID THERMAL STATUS: $thermalState\n" +
                "AVAILABLE SYSTEM RAM: $availableSystemRamMb MiB\n" +
                "LAUNCHER MEMORY (PSS): $launcherPssMb MiB\n" +
                "GAME FPS: shown by DXVK HUD inside the game when DXVK rendering works."
        }
    }

    fun snapshot(context: Context): Snapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val batteryTemperature = rawTemperature.takeIf { it > 0 }?.div(10.0)

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            ((level.toDouble() / scale.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else null

        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = if (plugged >= 0) plugged != 0 else null

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "None"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else {
            "Not exposed by this Android version"
        }

        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)

        return Snapshot(
            batteryTemperatureC = batteryTemperature,
            batteryPercent = batteryPercent,
            charging = charging,
            thermalState = thermalStatus,
            availableSystemRamMb = memoryInfo.availMem / (1024L * 1024L),
            launcherPssMb = debugMemoryInfo.totalPss.toLong() / 1024L
        )
    }
}
