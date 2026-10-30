package io.github.mattgrechtech.openpane.control

import io.github.mattgrechtech.openpane.diag.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The opt-in update check: once a day, asks GitHub for the latest release and reports it. It
 * never downloads or installs anything; updating stays the user's choice. Off by default.
 */
object UpdateCheck {

    const val INTERVAL_MILLIS = 24 * 60 * 60_000L
    private const val LATEST = "https://api.github.com/repos/mattgrechtech/openpane/releases/latest"
    private const val TAG = "UpdateCheck"

    class Release(val version: String, val url: String)

    /** The latest published release, or null if GitHub can't be reached. Call off the main thread. */
    fun latest(): Release? = try {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) null
            else JSONObject(connection.inputStream.bufferedReader().readText()).let {
                Release(it.optString("tag_name").removePrefix("v"), it.optString("html_url"))
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Can't check for updates: ${e.message}")
        null
    }

    /** True if [latest] ("1.2.3") is newer than [current] ("1.2.0", maybe "-debug"). Pure. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
