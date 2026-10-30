package io.github.mattgrechtech.openpane.web

import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Settings
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
        Thread({ post(base, "/api/events/$type", data, type) }, "ha-event").start()
    }

    private fun post(base: String, path: String, data: JSONObject, what: String) {
        val type = what
        val token = try {
            auth.accessToken(force = false).accessToken
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't send $type: not signed in to Home Assistant (${e.message})")
            return
        }
        val connection = URL(base + path).openConnection() as HttpURLConnection
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

    /**
     * Calls a Home Assistant action ([domain].[service]) with [data], off the main thread. Only
     * used for pop-up toggle buttons, whose entities [io.github.mattgrechtech.openpane.overlay.Popup] limits.
     */
    fun callService(domain: String, service: String, data: JSONObject) {
        if (settings.isWebMode()) return
        val base = settings.haBaseUrl() ?: return
        Thread({ post(base, "/api/services/$domain/$service", data, "$domain.$service") }, "ha-service").start()
    }

    /** GET [path] from Home Assistant's REST API as JSON (object or array), or null. Blocking. */
    fun getJson(path: String): Any? = request("GET", path, null)

    /** POST [body] to [path], answer as JSON (or an empty object), or null on failure. Blocking. */
    fun postJson(path: String, body: JSONObject): Any? = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): Any? {
        val base = settings.haBaseUrl()
        if (settings.isWebMode() || base == null) return null
        return try {
            val token = auth.accessToken(force = false).accessToken
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                if (status != 200 && status != 201) {
                    AppLog.w(TAG, "Home Assistant answered $status to $method ${path.substringBefore('?')}")
                    return null
                }
                val text = connection.inputStream.bufferedReader().readText().trim()
                when {
                    text.startsWith("[") -> org.json.JSONArray(text)
                    text.startsWith("{") -> JSONObject(text)
                    else -> JSONObject()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't $method ${path.substringBefore('?')}: ${e.javaClass.simpleName}")
            null
        }
    }

    /** [entity]'s state and attributes from Home Assistant, or null. Blocking: call off the main thread. */
    fun state(entity: String): JSONObject? {
        // Checked here too: the id goes into a URL path, where "../" would reach other API routes.
        if (!ENTITY_ID.matches(entity)) return null
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
            // Only the kind of error: a parser's message can quote Home Assistant's whole answer.
            AppLog.w(TAG, "Can't read $entity: ${e.javaClass.simpleName}")
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
                AppLog.w(TAG, "No live stream for $entity: ${e.javaClass.simpleName}")
                null
            }
            callback(url)
        }, "ha-camera").start()
    }

    companion object {
        private const val TAG = "HaApi"
        private val ENTITY_ID = Regex("[a-z_]+\\.[a-z0-9_]{1,100}")
        private const val TIMEOUT_MILLIS = 8000
    }
}
