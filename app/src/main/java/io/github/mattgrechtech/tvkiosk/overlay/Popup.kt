package io.github.mattgrechtech.tvkiosk.overlay

import org.json.JSONObject
import java.util.UUID

/**
 * A pop-up notification. Field names follow TvOverlay's `/notify` API so the same payload works
 * for both apps (`title`, `message`, `source`, `image`, `largeIcon`, `smallIcon`, `smallIconColor`,
 * `corner`, `duration`, `id`), plus the shorter `icon` and `color` used by this app's own API, and
 * this app's `size`, `opacity`, `text_size` and `actions`. Anything not given comes from [Defaults]
 * (the pop-up settings).
 */
data class Popup(
    val id: String,
    val title: String,
    val message: String,
    val source: String,
    /** URL, base64 or a data: URI. */
    val image: String?,
    /** `mdi:name`, URL or base64. */
    val icon: String?,
    /** ARGB colour for the icon and the accent strip. */
    val color: Int?,
    val corner: Corner,
    /** Seconds on screen; 0 stays until it's closed (by id, a button or Back), at most [MAX_STICKY]. */
    val durationSeconds: Int,
    val size: Size,
    /** Background opacity, percent. */
    val opacity: Int,
    val largeText: Boolean,
    val actions: List<Action>,
    /** A live MJPEG stream URL shown where the picture goes (the picture shows until it starts). */
    val stream: String? = null,
    /** A Home Assistant camera entity to stream live, e.g. camera.front_door. */
    val camera: String? = null,
    /** Text shown as a QR code (a guest Wi-Fi login, a link), at most [MAX_QR] characters. */
    val qr: String? = null,
    /** Seconds to count down to zero, shown as m:ss. */
    val countdownSeconds: Int? = null,
    /** 0-100: a progress bar under the text. */
    val progress: Int? = null,
) {
    /** True when the pop-up has a picture area: a still, a stream or a camera. */
    val hasPicture get() = image != null || stream != null || camera != null

    enum class Size(val apiName: String, val widthDp: Int, val imageHeightDp: Int, val scale: Float) {
        SMALL("small", 320, 180, 0.9f), MEDIUM("medium", 400, 225, 1f), LARGE("large", 520, 293, 1.15f);

        companion object {
            fun parse(value: String?): Size? = entries.firstOrNull { it.apiName == value?.trim()?.lowercase() }
        }
    }

    /**
     * A button on the pop-up: open a Home Assistant path ([navigate]), open a web page ([url]),
     * or send [event] to Home Assistant (a `tv_kiosk_action` event, and the MQTT pop-up button
     * entity) for an automation to act on. Every button closes the pop-up.
     */
    data class Action(val label: String, val navigate: String?, val url: String?, val event: String? = null)

    /** The pop-up settings, used for anything the payload leaves out. */
    data class Defaults(
        val corner: Corner = Corner.TOP_END,
        val durationSeconds: Int = DEFAULT_DURATION,
        val size: Size = Size.MEDIUM,
        val opacity: Int = 93,
        val largeText: Boolean = false,
    )

    enum class Corner(val apiName: String) {
        TOP_START("top_start"), TOP_END("top_end"), BOTTOM_START("bottom_start"), BOTTOM_END("bottom_end");

        companion object {
            fun parse(value: String?): Corner? = entries.firstOrNull { it.apiName == value?.trim()?.lowercase() }
        }
    }

    companion object {
        const val DEFAULT_DURATION = 10
        const val MAX_DURATION = 600
        const val MAX_ACTIONS = 3
        const val MAX_STICKY = 3600
        const val MAX_QR = 500
        private val EVENT_NAME = Regex("[a-z0-9_]{1,50}")
        private val CAMERA_ENTITY = Regex("camera\\.[a-z0-9_]{1,100}")

        /** Returns null when there is nothing to show (no title and no message). */
        fun fromJson(json: JSONObject, defaults: Defaults = Defaults()): Popup? {
            val title = text(json, "title")
            val message = text(json, "message")
            if (title.isEmpty() && message.isEmpty()) return null

            val duration = if (json.has("duration")) json.optInt("duration", defaults.durationSeconds) else defaults.durationSeconds
            val opacity = if (json.has("opacity")) json.optInt("opacity", defaults.opacity) else defaults.opacity
            return Popup(
                id = text(json, "id").ifEmpty { UUID.randomUUID().toString() },
                title = title,
                message = message,
                source = text(json, "source"),
                image = firstNonBlank(json, "image", "largeIcon"),
                icon = firstNonBlank(json, "icon", "smallIcon"),
                color = parseColor(firstNonBlank(json, "color", "smallIconColor")),
                corner = Corner.parse(text(json, "corner")) ?: defaults.corner,
                durationSeconds = if (duration <= 0) 0 else duration.coerceIn(1, MAX_DURATION),
                size = Size.parse(text(json, "size")) ?: defaults.size,
                opacity = opacity.coerceIn(40, 100),
                largeText = when (text(json, "text_size").lowercase()) {
                    "large" -> true
                    "normal" -> false
                    else -> defaults.largeText
                },
                actions = actions(json),
                stream = text(json, "stream").takeIf { it.startsWith("http://") || it.startsWith("https://") },
                camera = text(json, "camera").lowercase().takeIf { CAMERA_ENTITY.matches(it) },
                qr = text(json, "qr").takeIf { it.isNotEmpty() && it.length <= MAX_QR },
                countdownSeconds = if (json.has("countdown")) json.optInt("countdown", -1).takeIf { it in 1..86_400 } else null,
                progress = if (json.has("progress")) json.optDouble("progress", -1.0).takeIf { it >= 0 }?.toInt()?.coerceAtMost(100) else null,
            )
        }

        /** Up to [MAX_ACTIONS] buttons; ones with no label or an unsafe target are left out. */
        private fun actions(json: JSONObject): List<Action> {
            val array = json.optJSONArray("actions") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val label = text(item, "label").take(30)
                val navigate = text(item, "navigate").ifEmpty { null }
                val url = text(item, "url").ifEmpty { null }
                val event = text(item, "event").lowercase().ifEmpty { null }
                when {
                    label.isEmpty() -> null
                    navigate != null && !navigate.startsWith("/") -> null
                    url != null && !url.startsWith("http://") && !url.startsWith("https://") -> null
                    event != null && !EVENT_NAME.matches(event) -> null
                    navigate != null -> Action(label, navigate, null)
                    url != null -> Action(label, null, url)
                    else -> Action(label, null, null, event)
                }
            }.take(MAX_ACTIONS)
        }

        /** A trimmed string field; "" when missing or JSON null (optString would give "null"). */
        private fun text(json: JSONObject, name: String): String =
            if (json.isNull(name)) "" else json.optString(name).trim()

        private fun firstNonBlank(json: JSONObject, vararg names: String): String? =
            names.map { text(json, it) }.firstOrNull { it.isNotEmpty() }

        /** "#RRGGBB", "RRGGBB", "#AARRGGBB" or "AARRGGBB"; null if absent or invalid. */
        fun parseColor(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 && hex.length != 8) return null
            val parsed = hex.toLongOrNull(16) ?: return null
            return (if (hex.length == 6) parsed or 0xFF000000L else parsed).toInt()
        }
    }
}
