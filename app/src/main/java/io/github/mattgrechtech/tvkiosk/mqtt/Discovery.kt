package io.github.mattgrechtech.tvkiosk.mqtt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Topics and Home Assistant MQTT discovery messages for one TV.
 * https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
 *
 * Commands: `<base>/<device_id>/cmd/<name>`; the payload is JSON (same fields as the REST API)
 * or, for the simple entities below, a plain value. State: one retained JSON document on
 * `<base>/<device_id>/state`. Availability (with a last will): `<base>/<device_id>/availability`.
 */
class Discovery(
    baseTopic: String,
    private val discoveryPrefix: String,
    private val deviceId: String,
    private val deviceName: String,
    private val model: String,
    private val manufacturer: String,
    private val appVersion: String,
) {
    val root = "$baseTopic/$deviceId"
    val stateTopic = "$root/state"
    val availabilityTopic = "$root/availability"
    val eventTopic = "$root/event"
    val commandFilter = "$root/cmd/+"

    fun commandTopic(name: String) = "$root/cmd/$name"

    /** The command name from a topic under [commandFilter], or null. */
    fun commandName(topic: String): String? =
        topic.removePrefix("$root/cmd/").takeIf { it != topic && it.isNotEmpty() && '/' !in it }

    private val nodeId = "tvkiosk_$deviceId"

    /** (topic, payload) for every entity; publish retained. */
    fun messages(): List<Pair<String, JSONObject>> = listOf(
        entity("button", "reload", "Reload") {
            put("command_topic", commandTopic("reload")).put("payload_press", "{}").put("icon", "mdi:reload")
        },
        entity("button", "hard_reload", "Reload and clear cache") {
            put("command_topic", commandTopic("reload")).put("payload_press", """{"hard":true}""")
                .put("icon", "mdi:broom").put("entity_category", "config")
        },
        entity("button", "wake", "Wake") {
            put("command_topic", commandTopic("wake")).put("payload_press", "{}").put("icon", "mdi:television")
        },
        entity("button", "motion", "Motion") {
            put("command_topic", commandTopic("motion")).put("payload_press", "{}").put("icon", "mdi:motion-sensor")
        },
        entity("switch", "present", "Someone present") {
            put("command_topic", commandTopic("present_switch"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ 'ON' if value_json.present else 'OFF' }}")
                .put("icon", "mdi:account")
        },
        entity("binary_sensor", "resting", "Screen resting") {
            put("state_topic", stateTopic)
                .put("value_template", "{{ 'ON' if value_json.resting else 'OFF' }}")
                .put("icon", "mdi:sleep")
        },
        sensor("last_motion", "Last motion", "{{ value_json.last_motion_iso }}", diagnostic = false) {
            put("device_class", "timestamp")
        },
        entity("button", "start_page", "Go to start page") {
            put("command_topic", commandTopic("start_page")).put("payload_press", "{}").put("icon", "mdi:home")
        },
        entity("select", "screen", "Screen") {
            put("command_topic", commandTopic("screen_mode"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.screen }}")
                .put("options", JSONArray(listOf("on", "dim", "blank")))
                .put("icon", "mdi:brightness-6")
        },
        entity("number", "dim_level", "Dim level") {
            put("command_topic", commandTopic("dim_level"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.dim_level }}")
                .put("min", 5).put("max", 95).put("step", 5).put("mode", "slider")
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:brightness-4")
        },
        entity("number", "brightness", "Brightness") {
            put("command_topic", commandTopic("brightness_level"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.brightness }}")
                .put("min", -1).put("max", 100).put("step", 1).put("mode", "slider")
                .put("icon", "mdi:brightness-7")
        },
        entity("switch", "pointer", "Pointer") {
            put("command_topic", commandTopic("pointer_switch"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ 'ON' if value_json.pointer else 'OFF' }}")
                .put("icon", "mdi:cursor-default")
        },
        entity("text", "url", "Page") {
            put("command_topic", commandTopic("url_text"))
                .put("state_topic", stateTopic)
                .put("value_template", "{{ value_json.url[:255] }}")
                .put("max", 255)
                .put("icon", "mdi:web")
        },
        entity("event", "popup_button", "Pop-up button") {
            put("state_topic", eventTopic)
                .put("event_types", JSONArray(listOf(EVENT_PRESSED)))
                .put("icon", "mdi:gesture-tap-button")
        },
        entity("notify", "popup", "Pop-up") {
            put("command_topic", commandTopic("notify")).put("icon", "mdi:message-badge")
        },
        sensor("ha_connection", "Home Assistant connection", "{{ value_json.ha_connection }}", diagnostic = true),
        sensor("last_error", "Last error", "{{ value_json.last_error[:255] }}", diagnostic = true),
        sensor("app_started", "App started", "{{ value_json.app_started_iso }}", diagnostic = true) {
            put("device_class", "timestamp")
        },
        sensor("reload_count", "Reloads", "{{ value_json.reload_count }}", diagnostic = true) {
            put("state_class", "total_increasing")
        },
        sensor("app_memory", "App memory", "{{ value_json.app_memory_mb }}", diagnostic = true) {
            put("unit_of_measurement", "MB").put("state_class", "measurement").put("icon", "mdi:memory")
        },
        sensor("memory_available", "Memory available", "{{ value_json.memory_available_mb }}", diagnostic = true) {
            put("unit_of_measurement", "MB").put("state_class", "measurement").put("icon", "mdi:memory")
        },
        sensor("last_wake", "Last wake time", "{{ value_json.last_wake_ms }}", diagnostic = true) {
            put("unit_of_measurement", "ms").put("icon", "mdi:timer-outline")
        },
        sensor("webview_version", "WebView version", "{{ value_json.webview_version }}", diagnostic = true),
        sensor("ip", "IP address", "{{ value_json.ip }}", diagnostic = true) { put("icon", "mdi:ip-network") },
        entity("binary_sensor", "dashboard_visible", "Dashboard visible") {
            put("state_topic", stateTopic)
                .put("value_template", "{{ 'ON' if value_json.dashboard_visible else 'OFF' }}")
                .put("icon", "mdi:monitor-dashboard")
        },
        entity("binary_sensor", "overlay_permission", "Can show pop-ups over apps") {
            put("state_topic", stateTopic)
                .put("value_template", "{{ 'ON' if value_json.overlay_permission else 'OFF' }}")
                .put("entity_category", "diagnostic")
        },
    )

    /** Empty retained payloads remove the entities from HA. */
    fun removalMessages(): List<String> = messages().map { it.first }

    private fun sensor(
        id: String,
        name: String,
        template: String,
        diagnostic: Boolean,
        extra: JSONObject.() -> Unit = {},
    ) = entity("sensor", id, name) {
        put("state_topic", stateTopic).put("value_template", template)
        if (diagnostic) put("entity_category", "diagnostic")
        extra()
    }

    private fun entity(component: String, id: String, name: String, fill: JSONObject.() -> Unit): Pair<String, JSONObject> {
        val config = JSONObject()
            .put("name", name)
            .put("unique_id", "${nodeId}_$id")
            .put("availability_topic", availabilityTopic)
            .put("payload_available", ONLINE)
            .put("payload_not_available", OFFLINE)
            .put("device", device())
        config.fill()
        return "$discoveryPrefix/$component/$nodeId/$id/config" to config
    }

    private fun device() = JSONObject()
        .put("identifiers", JSONArray(listOf(nodeId)))
        .put("name", deviceName)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("sw_version", appVersion)

    companion object {
        const val ONLINE = "online"
        const val EVENT_PRESSED = "pressed"
        const val OFFLINE = "offline"

        /**
         * Turns a command message into (controller command, JSON args). Plain payloads from the
         * simple entities are mapped; anything else must be a JSON object. Null means ignore.
         */
        fun toCommand(name: String, payload: String): Pair<String, JSONObject>? {
            val text = payload.trim()
            fun json(): JSONObject? = when {
                text.isEmpty() -> JSONObject()
                text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                else -> null
            }
            return when (name) {
                "screen_mode" -> "screen" to JSONObject().put("mode", text.lowercase())
                "dim_level" -> text.toDoubleOrNull()?.let {
                    "screen" to JSONObject().put("mode", "dim").put("level", it.toInt())
                }
                "brightness_level" -> text.toDoubleOrNull()?.let { "brightness" to JSONObject().put("level", it.toInt()) }
                "pointer_switch" -> "pointer" to JSONObject().put("enabled", text.equals("ON", ignoreCase = true))
                "present_switch" -> "motion" to JSONObject().put("present", text.equals("ON", ignoreCase = true))
                "url_text" -> "url" to JSONObject().put("url", text)
                // The MQTT notify entity sends plain text; JSON gets all pop-up fields.
                "notify" -> "notify" to (json() ?: JSONObject().put("message", text))
                "tts" -> "tts" to (json() ?: JSONObject().put("message", text))
                "url" -> "url" to (json() ?: JSONObject().put("url", text))
                "navigate" -> "navigate" to (json() ?: JSONObject().put("path", text))
                in PASS_THROUGH -> json()?.let { name to it }
                else -> null
            }
        }

        private val PASS_THROUGH = setOf(
            "reload", "wake", "motion", "brightness", "start_page", "screen", "pointer", "notify_clear", "launch", "volume", "sound", "stop_sound",
        )
    }
}
