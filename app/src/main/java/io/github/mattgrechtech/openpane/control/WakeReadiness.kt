package io.github.mattgrechtech.openpane.control

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings as AndroidSettings
import io.github.mattgrechtech.openpane.diag.AppLog
import org.json.JSONObject

/** Turning the screen on, and what the TV allows for it. */
object WakeReadiness {

    private const val TAG = "Wake"
    private const val OP_TURN_SCREEN_ON = "android:turn_screen_on"
    /** TCL's own auto-start control (not in stock Android): without it, updates and boot can't restart the app. */
    private const val OP_AUTO_START = "android:auto_start"
    private const val WAKE_LOCK_MILLIS = 3_000L

    /**
     * Turns the screen on if Android has it off, and ends a screensaver. Returns true if the screen
     * was off. The wake lock is held briefly: the dashboard's keep-screen-on flag takes over.
     */
    @Suppress("DEPRECATION")
    fun turnScreenOn(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val wasOff = !power.isInteractive
        try {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "openpane:wake",
            ).acquire(WAKE_LOCK_MILLIS)
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't turn the screen on", e)
        }
        return wasOff
    }

    /**
     * "allowed", "default" or "denied" for Android 14's turn-screen-on permission, or "not needed"
     * on older Android.
     */
    fun turnScreenOnPermission(context: Context): String {
        if (Build.VERSION.SDK_INT < 34) return "not needed"
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            when (ops.unsafeCheckOpNoThrow(OP_TURN_SCREEN_ON, Process.myUid(), context.packageName)) {
                AppOpsManager.MODE_ALLOWED -> "allowed"
                AppOpsManager.MODE_DEFAULT -> "default"
                else -> "denied"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** "allowed", "denied", or "not needed" on devices without an auto-start control. */
    fun autoStart(context: Context): String = if (Build.VERSION.SDK_INT < 29) "not needed" else try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        when (ops.unsafeCheckOpNoThrow(OP_AUTO_START, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT -> "allowed"
            else -> "denied"
        }
    } catch (e: Exception) {
        "not needed" // Unknown op: stock Android has no such control.
    }

    fun toJson(context: Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val json = JSONObject()
            .put("screen_on", power.isInteractive)
            .put("overlay_permission", AndroidSettings.canDrawOverlays(context))
            .put("turn_screen_on", turnScreenOnPermission(context))
            .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("auto_start", autoStart(context))
            .put("service_protected", io.github.mattgrechtech.openpane.service.KioskService.protectedNow)
        if (Build.VERSION.SDK_INT >= 33) json.put("low_power_standby", power.isLowPowerStandbyEnabled)
        if (Build.VERSION.SDK_INT >= 34) json.put("low_power_standby_exempt", power.isExemptFromLowPowerStandby)
        return json
    }
}
