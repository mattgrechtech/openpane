package io.github.mattgrechtech.tvkiosk.web

import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.settings.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fires events on Home Assistant's event bus with the TV's own sign-in. */
class HaEvents(private val settings: Settings, private val auth: HaAuth) {

    /**
     * Fires [type] with [data], off the main thread. Does nothing in web mode or without a sign-in;
     * MQTT is the other route for those set-ups.
     */
    fun fire(type: String, data: JSONObject) {
        if (settings.isWebMode()) return
        val base = settings.haBaseUrl() ?: return
        Thread({ post(base, type, data) }, "ha-event").start()
    }

    private fun post(base: String, type: String, data: JSONObject) {
        val token = try {
            auth.accessToken(force = false).accessToken
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't send $type: not signed in to Home Assistant (${e.message})")
            return
        }
        val connection = URL("$base/api/events/$type").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(data.toString().toByteArray()) }
            val status = connection.responseCode
            if (status == 200) AppLog.i(TAG, "Sent $type to Home Assistant")
            else AppLog.w(TAG, "Home Assistant answered $status to $type")
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't send $type: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The event pop-up buttons fire; `event_data.action` names the button's event. */
        const val ACTION_EVENT = "tv_kiosk_action"
        private const val TAG = "HaEvents"
        private const val TIMEOUT_MILLIS = 8000
    }
}
