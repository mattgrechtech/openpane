package io.github.mattgrechtech.tvkiosk.control

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.webkit.WebView
import io.github.mattgrechtech.tvkiosk.BuildConfig
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the app reports about itself: over the API, over MQTT and on the settings screen.
 * Writers call [update]; listeners hear about every change (MQTT debounces them).
 */
class KioskState {

    enum class Screen { ON, DIM, BLANK }

    @Volatile var currentUrl: String = ""
        private set
    @Volatile var pageTitle: String = ""
        private set
    @Volatile var screen: Screen = Screen.ON
        private set
    @Volatile var dimLevel: Int = 0
        private set
    @Volatile var dashboardVisible: Boolean = false
        private set
    @Volatile var pointerEnabled: Boolean = false
        private set
    /** "connected", "disconnected", "auth-invalid", or "unknown" (not an HA page, or no bridge). */
    @Volatile var haConnection: String = "unknown"
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var lastReloadReason: String = ""
        private set
    @Volatile var lastReloadAtMillis: Long = 0
        private set
    @Volatile var reloadCount: Int = 0
        private set
    /** The screen is dark because nobody has been around (wake on motion). */
    @Volatile var resting: Boolean = false
        private set
    /** The night hours have the screen dark. */
    @Volatile var nightDark: Boolean = false
        private set
    /** Home Assistant says someone is present. */
    @Volatile var present: Boolean = false
        private set
    @Volatile var lastMotionAtMillis: Long = 0
        private set
    /** How long the last wake took, from the command arriving to the dashboard's next frame. */
    @Volatile var lastWakeMillis: Long = -1
        private set
    /** The newest release on GitHub when the update check is on, else null. */
    @Volatile var latestVersion: String? = null
        private set
    @Volatile var latestUrl: String? = null
        private set
    /** Dashboard brightness 0-100, or -1 for the device's own setting. */
    @Volatile var brightness: Int = -1
        private set

    private val startedElapsed = SystemClock.elapsedRealtime()
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    fun update(block: Editor.() -> Unit) {
        Editor().block()
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    inner class Editor {
        fun page(url: String, title: String) { currentUrl = url; pageTitle = title }
        fun screen(mode: Screen, level: Int) { screen = mode; dimLevel = level }
        fun dashboardVisible(visible: Boolean) { dashboardVisible = visible }
        fun pointer(enabled: Boolean) { pointerEnabled = enabled }
        fun haConnection(value: String) { haConnection = value }
        fun error(message: String) { lastError = message }
        fun resting(value: Boolean) { resting = value }
        fun nightDark(value: Boolean) { nightDark = value }
        fun motion(present: Boolean) { this@KioskState.present = present; lastMotionAtMillis = System.currentTimeMillis() }
        fun presentOnly(present: Boolean) { this@KioskState.present = present }
        fun wakeTook(millis: Long) { lastWakeMillis = millis }
        fun brightness(level: Int) { brightness = level }
        fun latest(version: String?, url: String?) { latestVersion = version; latestUrl = url }
        fun reloaded(reason: String) {
            lastReloadReason = reason
            lastReloadAtMillis = System.currentTimeMillis()
            reloadCount++
        }
    }

    /** A full snapshot, including values read from the system at call time. */
    fun toJson(context: Context): JSONObject {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val appMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val webView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        } else null
        val now = SystemClock.elapsedRealtime()

        return JSONObject()
            .put("url", currentUrl)
            .put("title", pageTitle)
            .put("screen", screen.name.lowercase())
            .put("dim_level", dimLevel)
            .put("brightness", brightness)
            .put("update", latestVersion?.let { latest ->
                JSONObject().put("latest", latest).put("url", latestUrl)
                    .put("available", UpdateCheck.isNewer(latest, BuildConfig.VERSION_NAME))
            } ?: JSONObject.NULL)
            .put("resting", resting)
            .put("night", nightDark)
            .put("present", present)
            .put("last_motion_at", if (lastMotionAtMillis == 0L) JSONObject.NULL else lastMotionAtMillis / 1000)
            .put("last_motion_iso", if (lastMotionAtMillis == 0L) JSONObject.NULL else iso(lastMotionAtMillis))
            .put("last_wake_ms", if (lastWakeMillis < 0) JSONObject.NULL else lastWakeMillis)
            .put("wake", WakeReadiness.toJson(context))
            .put("dashboard_visible", dashboardVisible)
            .put("pointer", pointerEnabled)
            .put("ha_connection", haConnection)
            .put("last_error", lastError)
            .put("last_reload_reason", lastReloadReason)
            .put("last_reload_at", if (lastReloadAtMillis == 0L) JSONObject.NULL else lastReloadAtMillis / 1000)
            .put("reload_count", reloadCount)
            .put("app_uptime_s", (now - startedElapsed) / 1000)
            .put("device_uptime_s", now / 1000)
            .put("app_started_at", startedAtMillis(now) / 1000)
            .put("app_started_iso", iso(startedAtMillis(now)))
            .put("memory_available_mb", memory.availMem / MB)
            .put("memory_total_mb", memory.totalMem / MB)
            .put("memory_low", memory.lowMemory)
            .put("app_memory_mb", appMemory.totalPss / 1024)
            .put("overlay_permission", AndroidSettings.canDrawOverlays(context))
            .put("ip", localIpv4() ?: JSONObject.NULL)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("webview_version", webView?.versionName ?: JSONObject.NULL)
            .put("webview_package", webView?.packageName ?: JSONObject.NULL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
    }

    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun startedAtMillis(nowElapsed: Long) = System.currentTimeMillis() - (nowElapsed - startedElapsed)

    companion object {
        private const val MB = 1024L * 1024L

        /** The first site-local IPv4 address, preferring Ethernet and Wi-Fi interfaces. */
        fun localIpv4(): String? = localIpv4Addresses().firstOrNull()

        fun localIpv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
