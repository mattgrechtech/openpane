package io.github.mattgrechtech.tvkiosk.web

import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.settings.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Home Assistant's REST API with the TV's own sign-in: events, camera stream links. */
class HaApi(private val settings: Settings, private val auth: HaAuth) {

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

    /** [entity]'s state and attributes from Home Assistant, or null. Blocking: call off the main thread. */
    fun state(entity: String): JSONObject? {
        val base = settings.haBaseUrl()
        if (settings.isWebMode() || base == null) return null
        return try {
            val token = auth.accessToken(force = false).accessToken
            val connection = URL("$base/api/states/$entity").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (connection.responseCode != 200) null
                else JSONObject(connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't read $entity: ${e.message}")
            null
        }
    }

    /**
     * A live MJPEG link for [entity] (a camera), from its current access token, or null (off the
     * main thread; [callback] runs there too). The link works without a sign-in until the token
     * rotates, like a camera's entity_picture.
     */
    fun cameraStream(entity: String, callback: (String?) -> Unit) {
        val base = settings.haBaseUrl()
        if (settings.isWebMode() || base == null) return callback(null)
        Thread({
            val url = try {
                val token = auth.accessToken(force = false).accessToken
                val connection = URL("$base/api/states/$entity").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MILLIS
                    connection.readTimeout = TIMEOUT_MILLIS
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    if (connection.responseCode != 200) throw IllegalStateException("Home Assistant answered ${connection.responseCode}")
                    val state = JSONObject(connection.inputStream.bufferedReader().readText())
                    val access = state.optJSONObject("attributes")?.optString("access_token").orEmpty()
                    if (access.isEmpty()) throw IllegalStateException("$entity has no access token")
                    "$base/api/camera_proxy_stream/$entity?token=$access"
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "No live stream for $entity: ${e.message}")
                null
            }
            callback(url)
        }, "ha-camera").start()
    }

    companion object {
        private const val TAG = "HaApi"
        private const val TIMEOUT_MILLIS = 8000
    }
}
