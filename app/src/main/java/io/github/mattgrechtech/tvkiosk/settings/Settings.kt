package io.github.mattgrechtech.tvkiosk.settings

/**
 * Typed settings. Plain values live in one store, secrets (tokens, passwords, PIN) in another
 * that is encrypted at rest. Everything that reads or writes settings goes through [Settings],
 * and every key is declared once in [Keys], so import/export, provisioning and the settings
 * screen can't drift apart.
 */

/** A minimal key-value store, so settings logic can be unit-tested without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String?>)
}

class Key<T : Any>(
    val name: String,
    val default: T,
    val secret: Boolean = false,
    /** Returns an error message, or null when the value is acceptable. */
    val check: (T) -> String? = { null },
    /** The allowed values of a text setting with a fixed set (read late: the lists are declared below the keys). */
    val choices: (() -> List<String>)? = null,
)

object Keys {
    /** A text setting that must be one of [values]. */
    private fun oneOf(name: String, default: String, values: () -> List<String>) = Key(
        name, default,
        check = { if (it in values()) null else "must be one of ${values()}" },
        choices = values,
    )

    private fun port(value: Int) = if (value in 1..65535) null else "must be a port number (1-65535)"
    private fun url(value: String) =
        if (value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")) null
        else "must start with http:// or https://"

    // What the TV shows
    /** home_assistant: a Home Assistant dashboard (sign-in, picker, pop-up bridge); web: any web page. */
    val MODE = oneOf("mode", "home_assistant") { MODES }
    val START_URL = Key("start_url", "", check = ::url)
    /** Home Assistant base URL. Empty means "the origin of the start URL". */
    val HA_URL = Key("ha_url", "", check = ::url)
    /** login: sign in once in the app (default); token: long-lived token; none: the page handles login. */
    val AUTH_MODE = oneOf("auth_mode", "login") { AUTH_MODES }
    val HA_KIOSK_MODE = Key("ha_kiosk_mode", true)
    val KEEP_SCREEN_ON = Key("keep_screen_on", true)
    val BOOT_START = Key("boot_start", false)
    /** buttons: arrows jump between clickable items; pointer: an on-screen pointer; page: arrows go to the page. */
    val REMOTE_MODE = oneOf("remote_mode", "buttons") { REMOTE_MODES }

    // Rotation and schedule
    /** More pages to rotate through after the start page, one URL per line. */
    val PAGES = Key("pages", "", check = { value ->
        splitPages(value).firstNotNullOfOrNull { page -> url(page)?.let { "has a line that $it" } }
    })
    /** Seconds on each page before the next; 0 turns rotation off. */
    val ROTATE_SECONDS = Key("rotate_seconds", 0, check = {
        if (it == 0 || it in 10..86_400) null else "must be 0 (off) or 10-86400"
    })
    /** Hour the screen goes black each night; -1 is never. */
    val SCREEN_OFF_FROM = Key("screen_off_from", -1, check = {
        if (it in -1..23) null else "must be -1 (never) or an hour 0-23"
    })
    /** Hour the screen comes back. */
    val SCREEN_OFF_TO = Key("screen_off_to", 7, check = {
        if (it in 0..23) null else "must be an hour 0-23"
    })

    // Wake on motion
    /** Minutes without motion, presence or the remote before the screen rests; 0 is never. */
    val REST_AFTER_MINUTES = Key("rest_after_minutes", 0, check = {
        if (it in 0..240) null else "must be 0 (never) or 1-240"
    })
    /** black, or dim (the page stays faintly visible). */
    val REST_STYLE = oneOf("rest_style", "black") { REST_STYLES }
    /** Moves the page a few pixels every couple of minutes, against burn-in on OLED screens. */
    val BURN_IN_GUARD = Key("burn_in_guard", false)
    /** Whether motion wakes the screen during the night hours. */
    val MOTION_AT_NIGHT = Key("motion_at_night", false)
    /** When motion brings the dashboard in front of another app: unless_playing, always or never. */
    val MOTION_FRONT = oneOf("motion_front", "unless_playing") { MOTION_FRONTS }

    // Recovery
    val RELOAD_ON_NETWORK = Key("reload_on_network", true)
    val RELOAD_ON_DISCONNECT_SECONDS = Key("reload_on_disconnect_seconds", 60, check = {
        if (it == 0 || it in 10..3600) null else "must be 0 (off) or 10-3600"
    })
    val NIGHTLY_RELOAD_HOUR = Key("nightly_reload_hour", -1, check = {
        if (it in -1..23) null else "must be -1 (off) or an hour 0-23"
    })

    // Pop-up look and behaviour (defaults; each pop-up can override size, opacity, text, corner, duration)
    val POPUP_SIZE = oneOf("popup_size", "medium") { POPUP_SIZES }
    /** Background opacity in percent; images always stay solid. */
    val POPUP_OPACITY = Key("popup_opacity", 93, check = { if (it in 40..100) null else "must be 40-100" })
    val POPUP_TEXT_SIZE = oneOf("popup_text_size", "normal") { POPUP_TEXT_SIZES }
    val POPUP_CORNER = oneOf("popup_corner", "top_end") { POPUP_CORNERS }
    val POPUP_DURATION = Key("popup_duration", 10, check = { if (it in 1..600) null else "must be 1-600 seconds" })
    val POPUP_MAX_PER_CORNER = Key("popup_max_per_corner", 3, check = { if (it in 1..3) null else "must be 1-3" })
    /** Pop-ups with buttons take the remote over other apps too (Back closes, OK presses). */
    val POPUP_TAKE_REMOTE = Key("popup_take_remote", false)

    // Local API
    val API_ENABLED = Key("api_enabled", true)
    val API_PORT = Key("api_port", 2424, check = ::port)
    val TVOVERLAY_COMPAT = Key("tvoverlay_compat", false)
    val TVOVERLAY_PORT = Key("tvoverlay_port", 5001, check = ::port)

    // MQTT
    val MQTT_ENABLED = Key("mqtt_enabled", false)
    val MQTT_HOST = Key("mqtt_host", "")
    val MQTT_PORT = Key("mqtt_port", 1883, check = ::port)
    val MQTT_TLS = Key("mqtt_tls", false)
    val MQTT_USER = Key("mqtt_user", "")
    val MQTT_BASE_TOPIC = Key("mqtt_base_topic", "tvkiosk", check = { topicSegment(it) })
    val MQTT_DISCOVERY_PREFIX = Key("mqtt_discovery_prefix", "homeassistant", check = { topicSegment(it) })

    // Device
    val DEVICE_NAME = Key("device_name", "")
    val DEVICE_ID = Key("device_id", "")

    // Secrets
    val API_TOKEN = Key("api_token", "", secret = true)
    val MQTT_PASSWORD = Key("mqtt_password", "", secret = true)
    val HA_LONG_LIVED_TOKEN = Key("ha_long_lived_token", "", secret = true)
    val HA_REFRESH_TOKEN = Key("ha_refresh_token", "", secret = true)
    /** The Home Assistant address the refresh or long-lived token belongs to (internal). */
    val HA_CREDENTIAL_ORIGIN = Key("ha_credential_origin", "")
    val SETTINGS_PIN = Key("settings_pin", "", secret = true, check = {
        if (it.isEmpty() || (it.length in 4..8 && it.all(Char::isDigit))) null else "must be 4-8 digits"
    })

    val MODES = listOf("home_assistant", "web")
    val POPUP_SIZES = listOf("small", "medium", "large")
    val POPUP_TEXT_SIZES = listOf("normal", "large")
    val POPUP_CORNERS = listOf("top_start", "top_end", "bottom_start", "bottom_end")
    val AUTH_MODES = listOf("none", "login", "token")
    val REMOTE_MODES = listOf("buttons", "pointer", "page")
    val REST_STYLES = listOf("black", "dim")
    val MOTION_FRONTS = listOf("unless_playing", "always", "never")

    /** The non-blank lines of a [PAGES] value, trimmed. */
    fun splitPages(value: String): List<String> = value.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun topicSegment(value: String) =
        if (value.isNotEmpty() && value.none { it in "+#/ " }) null else "must be one topic level, no + # / or spaces"

    val ALL: List<Key<*>> = listOf(
        MODE, START_URL, HA_URL, AUTH_MODE, HA_KIOSK_MODE, KEEP_SCREEN_ON, BOOT_START, REMOTE_MODE,
        PAGES, ROTATE_SECONDS, SCREEN_OFF_FROM, SCREEN_OFF_TO,
        REST_AFTER_MINUTES, REST_STYLE, MOTION_AT_NIGHT, MOTION_FRONT, BURN_IN_GUARD,
        POPUP_SIZE, POPUP_OPACITY, POPUP_TEXT_SIZE, POPUP_CORNER, POPUP_DURATION, POPUP_MAX_PER_CORNER, POPUP_TAKE_REMOTE,
        RELOAD_ON_NETWORK, RELOAD_ON_DISCONNECT_SECONDS, NIGHTLY_RELOAD_HOUR,
        API_ENABLED, API_PORT, TVOVERLAY_COMPAT, TVOVERLAY_PORT,
        MQTT_ENABLED, MQTT_HOST, MQTT_PORT, MQTT_TLS, MQTT_USER, MQTT_BASE_TOPIC, MQTT_DISCOVERY_PREFIX,
        DEVICE_NAME, DEVICE_ID,
        API_TOKEN, MQTT_PASSWORD, HA_LONG_LIVED_TOKEN, HA_REFRESH_TOKEN, SETTINGS_PIN, HA_CREDENTIAL_ORIGIN,
    )

    /** Keys that are internal state, not user settings: never imported. */
    val NOT_IMPORTABLE: Set<Key<*>> = setOf(DEVICE_ID, HA_REFRESH_TOKEN, HA_CREDENTIAL_ORIGIN)

    fun byName(name: String): Key<*>? = ALL.firstOrNull { it.name == name }
}

class Settings(private val plain: KeyValueStore, private val secrets: KeyValueStore) {

    private val listeners = mutableListOf<(Set<String>) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: Key<T>): T {
        val raw = store(key).getString(key.name) ?: return key.default
        return (decode(key, raw) ?: key.default) as T
    }

    operator fun <T : Any> set(key: Key<T>, value: T) = edit { put(key, value) }

    /** Applies several changes and notifies listeners once. */
    fun edit(block: Editor.() -> Unit) {
        val editor = Editor()
        editor.block()
        if (editor.plainChanges.isNotEmpty()) plain.putAll(editor.plainChanges)
        if (editor.secretChanges.isNotEmpty()) secrets.putAll(editor.secretChanges)
        // A new HA token belongs to the HA address it was set for; see [haCredentialsMatch].
        if (editor.secretChanges.keys.any { it == Keys.HA_REFRESH_TOKEN.name || it == Keys.HA_LONG_LIVED_TOKEN.name }) {
            plain.putAll(mapOf(Keys.HA_CREDENTIAL_ORIGIN.name to haBaseUrl().orEmpty()))
        }
        val changed = editor.plainChanges.keys + editor.secretChanges.keys
        if (changed.isNotEmpty()) synchronized(listeners) { listeners.toList() }.forEach { it(changed) }
    }

    fun addListener(listener: (Set<String>) -> Unit) = synchronized(listeners) { listeners += listener }

    fun removeListener(listener: (Set<String>) -> Unit) = synchronized(listeners) { listeners -= listener }

    /** True when the TV shows any web page rather than Home Assistant. */
    fun isWebMode(): Boolean = get(Keys.MODE) == "web"

    /**
     * The Home Assistant origin the app trusts: the HA URL if set, else the start URL's origin.
     * Null in web mode, so no page gets Home Assistant's sign-in or the app bridge.
     */
    fun haBaseUrl(): String? {
        if (isWebMode()) return null
        val explicit = get(Keys.HA_URL).trimEnd('/')
        if (explicit.isNotEmpty()) return originOf(explicit)
        return originOf(get(Keys.START_URL))
    }

    /**
     * True when the stored Home Assistant tokens belong to the current HA address. When the
     * address has changed (by setup, by hand, or by someone with the API token), the tokens must
     * not be sent to the new address: that would hand the TV's HA sign-in to whoever runs it.
     * Tokens saved before this check existed are adopted by the current address.
     */
    fun haCredentialsMatch(): Boolean {
        val base = haBaseUrl() ?: return false
        val origin = get(Keys.HA_CREDENTIAL_ORIGIN)
        if (origin.isEmpty()) {
            plain.putAll(mapOf(Keys.HA_CREDENTIAL_ORIGIN.name to base))
            return true
        }
        return origin == base
    }

    /** Forgets the HA tokens (after the HA address changed). */
    fun clearHaCredentials() = edit {
        clear(Keys.HA_REFRESH_TOKEN)
        clear(Keys.HA_LONG_LIVED_TOKEN)
        clear(Keys.HA_CREDENTIAL_ORIGIN)
    }

    /** Every page the dashboard shows: the start page (HA's home page if unset), then [Keys.PAGES]. */
    fun pages(): List<String> {
        val start = get(Keys.START_URL).ifEmpty { haBaseUrl()?.let { "$it/" } ?: "" }
        return (listOf(start) + Keys.splitPages(get(Keys.PAGES))).filter { it.isNotEmpty() }
    }

    inner class Editor {
        internal val plainChanges = mutableMapOf<String, String?>()
        internal val secretChanges = mutableMapOf<String, String?>()

        /** Records [value] unless it equals the current one, so listeners only hear real changes. */
        fun <T : Any> put(key: Key<T>, value: T) {
            key.check(value)?.let { throw IllegalArgumentException("${key.name} $it") }
            if (this@Settings[key] == value) return
            (if (key.secret) secretChanges else plainChanges)[key.name] = value.toString()
        }

        fun clear(key: Key<*>) {
            (if (key.secret) secretChanges else plainChanges)[key.name] = null
        }
    }

    private fun store(key: Key<*>) = if (key.secret) secrets else plain

    companion object {
        /** Parses a stored or imported string for [key], or null if it isn't valid for the key's type. */
        fun decode(key: Key<*>, raw: String): Any? = when (key.default) {
            is String -> raw
            is Int -> raw.trim().toIntOrNull()
            is Boolean -> when (raw.trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
            else -> null
        }

        /** "http://host:8123/path?x" -> "http://host:8123", or null if it isn't an http(s) URL. */
        fun originOf(url: String): String? {
            val match = Regex("^(https?)://([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
            return "${match.groupValues[1].lowercase()}://${match.groupValues[2].lowercase()}"
        }
    }
}
