package io.github.mattgrechtech.tvkiosk.control

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import io.github.mattgrechtech.tvkiosk.overlay.Popup
import io.github.mattgrechtech.tvkiosk.overlay.PopupManager
import io.github.mattgrechtech.tvkiosk.settings.Keys
import io.github.mattgrechtech.tvkiosk.settings.Settings
import io.github.mattgrechtech.tvkiosk.web.KioskActivity
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.mattgrechtech.tvkiosk.diag.AppLog

/** What the dashboard activity offers to the controller while it exists. */
interface DashboardSurface {
    fun loadUrl(url: String)
    fun reload(hard: Boolean, reason: String)
    fun applyScreen(mode: KioskState.Screen, level: Int)
    fun setPointer(enabled: Boolean)
    /** Navigates inside the HA frontend without a reload. Returns false if the bridge isn't up. */
    fun navigateHa(path: String): Boolean
    /** Keeps the night hours from darkening the screen for [millis]. */
    fun holdAwake(millis: Long)
    /** True during the night hours. */
    fun nightActive(): Boolean
    /** Logs and reports the time from [startedAt] (elapsed realtime) to the dashboard's next frame. */
    fun timeNextFrame(startedAt: Long)
    /** Window brightness, 0-100, or -1 for the device's own setting. */
    fun applyBrightness(level: Int)
    /** Resting starts ([style]: black, dim, clock or page) or ends (null). */
    fun restChanged(style: String?)
}

/**
 * Executes commands from the REST API, MQTT, the TvOverlay-compatible endpoint and adb.
 * Every command runs on the main thread; [executeBlocking] is for server threads.
 */
class KioskController(
    private val context: Context,
    private val settings: Settings,
    val state: KioskState,
) {
    private val main = Handler(Looper.getMainLooper())
    private var surface: DashboardSurface? = null
    /** A URL asked for while no dashboard existed; the activity picks it up when it starts. */
    private var pendingUrl: String? = null
    val popups = PopupManager(context).apply {
        maxPerCorner = { settings[Keys.POPUP_MAX_PER_CORNER] }
        takeRemote = { settings[Keys.POPUP_TAKE_REMOTE] }
        onAction = { popup, action -> runPopupAction(popup, action) }
    }
    /** Wake on motion: when the screen rests and what wakes it. */
    val presence = Presence()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null

    /** The pop-up settings, for anything a pop-up's payload leaves out. */
    fun popupDefaults() = Popup.Defaults(
        corner = Popup.Corner.parse(settings[Keys.POPUP_CORNER]) ?: Popup.Corner.TOP_END,
        durationSeconds = settings[Keys.POPUP_DURATION],
        size = Popup.Size.parse(settings[Keys.POPUP_SIZE]) ?: Popup.Size.MEDIUM,
        opacity = settings[Keys.POPUP_OPACITY],
        largeText = settings[Keys.POPUP_TEXT_SIZE] == "large",
    )

    /** Finds a live stream link for a camera entity (Home Assistant), or null. */
    var cameraStream: (String, (String?) -> Unit) -> Unit = { _, callback -> callback(null) }

    /**
     * Events for Home Assistant (type, data): pop-up buttons ([ACTION_EVENT]) and remote keys
     * ([KEY_EVENT]). Listeners send them on: Home Assistant's event bus, MQTT.
     */
    val eventListeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    private fun emit(type: String, data: JSONObject) {
        data.put("device_id", settings[Keys.DEVICE_ID]).put("device_name", settings[Keys.DEVICE_NAME])
        eventListeners.forEach { listener -> runCatching { listener(type, data) } }
    }

    /** A mapped remote key ([RemoteKeys]) was pressed while the dashboard was in front. */
    fun keyPressed(name: String, long: Boolean) {
        AppLog.i(TAG, "Remote key: $name" + if (long) " (long)" else "")
        emit(KEY_EVENT, JSONObject().put("key", name).put("long", long))
    }

    /**
     * A pop-up button: open a Home Assistant path or a web page on the dashboard (bringing it
     * forward), or tell Home Assistant which button was pressed.
     */
    private fun runPopupAction(popup: Popup, action: Popup.Action) {
        action.event?.let { event ->
            AppLog.i(TAG, "Pop-up button: $event")
            emit(
                ACTION_EVENT,
                JSONObject().put("action", event).put("label", action.label).put("popup_id", popup.id).put("title", popup.title),
            )
            return
        }
        val target = action.url ?: action.navigate?.let { path -> settings.haBaseUrl()?.let { it + path } } ?: return
        execute("url", JSONObject().put("url", target))
    }

    fun attach(surface: DashboardSurface) {
        this.surface = surface
    }

    fun detach(surface: DashboardSurface) {
        if (this.surface === surface) this.surface = null
    }

    fun takePendingUrl(): String? = pendingUrl.also { pendingUrl = null }

    fun executeBlocking(name: String, args: JSONObject, timeoutSeconds: Long = 10): CommandResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return execute(name, args)
        var result: CommandResult? = null
        val done = CountDownLatch(1)
        main.post {
            result = try {
                execute(name, args)
            } catch (e: Exception) {
                AppLog.e(TAG, "Command $name failed", e)
                CommandResult.error(500, e.message ?: e.javaClass.simpleName)
            }
            done.countDown()
        }
        return if (done.await(timeoutSeconds, TimeUnit.SECONDS)) result!!
        else CommandResult.error(504, "timed out")
    }

    fun execute(name: String, args: JSONObject): CommandResult {
        val result = dispatch(name, args)
        scheduleReturn(name, args, result)
        AppLog.i(TAG, "Command $name -> ${result.status}" + (result.body.optString("error").takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
        return result
    }

    /** Goes back to the start page after a page command with `return_after`. */
    private val returnToStart = Runnable {
        AppLog.i(TAG, "Back to the start page")
        execute("start_page", JSONObject())
    }

    /**
     * After [name] ran: a page command with `return_after` (seconds) goes back to the start page
     * that long after, unless another page command comes first.
     */
    private fun scheduleReturn(name: String, args: JSONObject, result: CommandResult) {
        if (name !in PAGE_COMMANDS || !result.ok) return
        main.removeCallbacks(returnToStart)
        val seconds = args.optInt("return_after", 0)
        if (seconds > 0) main.postDelayed(returnToStart, seconds.coerceIn(5, 86_400) * 1000L)
    }

    private fun dispatch(name: String, args: JSONObject): CommandResult = when (name) {
        "state" -> CommandResult.ok(
            state.toJson(context).put("device_id", settings[Keys.DEVICE_ID]).put("device_name", settings[Keys.DEVICE_NAME])
        )
        "url" -> loadUrl(args)
        "start_page" -> {
            val start = settings[Keys.START_URL]
            if (start.isEmpty()) CommandResult.error(409, "no start URL set") else loadUrl(JSONObject().put("url", start))
        }
        "navigate" -> navigate(args)
        "reload" -> reload(args)
        "screen" -> screen(args)
        "wake" -> wake(args)
        "brightness" -> brightness(args)
        "motion" -> motion(args)
        "pointer" -> pointer(args)
        "notify" -> notify(args)
        "notify_clear" -> {
            popups.clear(args.optString("id").ifEmpty { null })
            CommandResult.ok()
        }
        "launch" -> launch(args)
        "volume" -> volume(args)
        "tts" -> speak(args)
        "sound" -> sound(args)
        "stop_sound" -> {
            stopSound()
            tts?.stop()
            CommandResult.ok()
        }
        else -> CommandResult.error(404, "unknown command: $name")
    }

    private fun loadUrl(args: JSONObject): CommandResult {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return CommandResult.error(400, "url must start with http:// or https://")
        }
        val current = surface
        if (current != null) current.loadUrl(url) else {
            pendingUrl = url
            bringToFront()
        }
        return CommandResult.ok()
    }

    private fun navigate(args: JSONObject): CommandResult {
        val path = args.optString("path")
        if (!path.startsWith("/")) return CommandResult.error(400, "path must start with /")
        val current = surface ?: return CommandResult.error(409, "the dashboard isn't open")
        if (current.navigateHa(path)) return CommandResult.ok()
        // No bridge (e.g. auth mode "none"): fall back to a full load on the HA origin.
        val base = settings.haBaseUrl() ?: return CommandResult.error(409, "no Home Assistant URL set")
        current.loadUrl(base + path)
        return CommandResult.ok(JSONObject().put("note", "loaded as a new page"))
    }

    private fun reload(args: JSONObject): CommandResult {
        val current = surface ?: return CommandResult.error(409, "the dashboard isn't open")
        current.reload(args.optBoolean("hard", false), "command")
        return CommandResult.ok()
    }

    private fun screen(args: JSONObject): CommandResult {
        val mode = when (args.optString("mode").lowercase(Locale.ROOT)) {
            "on" -> KioskState.Screen.ON
            "dim" -> KioskState.Screen.DIM
            "blank", "off" -> KioskState.Screen.BLANK
            else -> return CommandResult.error(400, "mode must be on, dim or blank")
        }
        val level = when (mode) {
            KioskState.Screen.ON -> 0
            KioskState.Screen.BLANK -> 100
            KioskState.Screen.DIM -> args.optInt("level", 50).coerceIn(5, 95)
        }
        setScreen(mode, level)
        return CommandResult.ok()
    }

    fun setScreen(mode: KioskState.Screen, level: Int) {
        if (mode == KioskState.Screen.ON) presence.awake(SystemClock.elapsedRealtime())
        state.update { screen(mode, level) }
        surface?.applyScreen(mode, level)
        if (mode == KioskState.Screen.ON) endRest()
    }

    /** Screen brightness for the dashboard, 0-100, or -1 for the device's own setting. */
    private fun brightness(args: JSONObject): CommandResult {
        if (!args.has("level")) return CommandResult.error(400, "level is required: 0-100, or -1 for the device's setting")
        val level = args.optInt("level").coerceIn(-1, 100)
        state.update { brightness(level) }
        surface?.applyBrightness(level)
        return CommandResult.ok(JSONObject().put("brightness", level))
    }

    /** Wakes the screen and brings the dashboard to the front, even over another app. */
    private fun wake(args: JSONObject): CommandResult {
        val minutes = args.optInt("minutes", 5).coerceIn(1, 240)
        return CommandResult.ok(wakeScreen(SystemClock.elapsedRealtime(), front = true, holdMillis = minutes * 60_000L))
    }

    /**
     * Motion or presence from Home Assistant. Wakes a resting screen, restarts the rest timer, and
     * brings the dashboard forward as [Keys.MOTION_FRONT] allows. `present: true` holds the screen
     * awake until `present: false`.
     */
    private fun motion(args: JSONObject): CommandResult {
        val now = SystemClock.elapsedRealtime()
        val present = if (args.has("present")) args.optBoolean("present") else null
        val night = surface?.nightActive() == true
        val quiet = state.nightDark && state.screen != KioskState.Screen.ON && !settings[Keys.MOTION_AT_NIGHT]
        val result = presence.motion(now, present, quiet)
        state.update { if (present == false) presentOnly(false) else motion(presence.present) }
        val body = JSONObject().put("result", result.name.lowercase(Locale.ROOT)).put("present", presence.present)
        if (result == Presence.Result.WOKE || result == Presence.Result.AWAKE) {
            val front = when (settings[Keys.MOTION_FRONT]) {
                "always" -> true
                "never" -> false
                else -> !somethingPlaying()
            }
            wakeScreen(now, front, holdMillis = if (night) Schedule.NIGHT_WAKE_MILLIS else 0).let { woke ->
                woke.keys().forEach { key -> body.put(key, woke.get(key)) }
            }
        }
        return CommandResult.ok(body)
    }

    /**
     * Turns the screen on (ending a screensaver), lifts a dim or rest, and brings the dashboard
     * forward if [front]. [holdMillis] keeps the night hours from darkening it again for that long.
     */
    private fun wakeScreen(startedAt: Long, front: Boolean, holdMillis: Long): JSONObject {
        val body = JSONObject()
        body.put("screen_was_off", WakeReadiness.turnScreenOn(context))
        if (holdMillis > 0) surface?.holdAwake(holdMillis)
        val wasDark = state.screen != KioskState.Screen.ON || state.resting
        if (wasDark) setScreen(KioskState.Screen.ON, 0)
        body.put("was_dark", wasDark)
        when {
            state.dashboardVisible -> body.put("dashboard", "in front")
            !front -> body.put("dashboard", "left behind the app in front")
            bringToFront() -> body.put("dashboard", "brought to front")
            else -> body.put("dashboard", "behind other apps")
                .put("warning", "no permission to start from the background: grant 'display over other apps'")
        }
        surface?.timeNextFrame(startedAt)
        return body
    }

    /** Something is playing sound (a film, music): motion shouldn't take the screen from it. */
    private fun somethingPlaying(): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /** Called by the dashboard about every half minute while it's in front: rests the screen when due. */
    fun restTick(now: Long) {
        presence.restAfterMillis = settings[Keys.REST_AFTER_MINUTES] * 60_000L
        if (!presence.restDue(now) || state.screen != KioskState.Screen.ON) return
        if (surface?.nightActive() == true) return
        var style = settings[Keys.REST_STYLE]
        if (style == "page" && settings[Keys.REST_PAGE].isEmpty()) style = "black"
        presence.rest(now)
        AppLog.i(TAG, "Nobody around: screen resting ($style)")
        state.update { resting(true) }
        when (style) {
            "dim" -> applyDark(KioskState.Screen.DIM, REST_DIM_LEVEL)
            "page" -> Unit
            else -> applyDark(KioskState.Screen.BLANK, 100)
        }
        surface?.restChanged(style)
    }

    private fun applyDark(mode: KioskState.Screen, level: Int) {
        state.update { screen(mode, level) }
        surface?.applyScreen(mode, level)
    }

    /** Ends resting, whatever its style. */
    private fun endRest() {
        if (!state.resting) return
        state.update { resting(false) }
        surface?.restChanged(null)
    }

    /**
     * The remote was used: it counts as someone being around. Returns true if that ended a rest
     * (the key only wakes the screen).
     */
    fun interaction(now: Long): Boolean {
        val wasResting = presence.awake(now)
        if (!wasResting && !state.resting) return false
        setScreen(KioskState.Screen.ON, 0)
        return true
    }

    /**
     * Brings the dashboard to the front. Android 10+ only allows this from the background when the
     * app may draw over other apps (or already has a visible window), so report whether it can.
     */
    fun bringToFront(): Boolean {
        val intent = Intent(context, KioskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return try {
            context.startActivity(intent)
            state.dashboardVisible || AndroidSettings.canDrawOverlays(context)
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't start the dashboard", e)
            false
        }
    }

    private fun pointer(args: JSONObject): CommandResult {
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else !state.pointerEnabled
        val current = surface ?: return CommandResult.error(409, "the dashboard isn't open")
        current.setPointer(enabled)
        return CommandResult.ok(JSONObject().put("pointer", enabled))
    }

    private fun notify(args: JSONObject): CommandResult {
        val popup = Popup.fromJson(args, popupDefaults()) ?: return CommandResult.error(400, "a title or message is required")
        announce(args.optString("sound").trim(), args.optString("tts").trim(), args)
        val shown = popups.show(popup)
        if (shown != PopupManager.Shown.NOWHERE) startLive(popup)
        return when (shown) {
            PopupManager.Shown.OVER_EVERYTHING -> CommandResult.ok(JSONObject().put("id", popup.id))
            PopupManager.Shown.IN_DASHBOARD -> CommandResult.ok(
                JSONObject().put("id", popup.id)
                    .put("warning", "shown only while the dashboard is open: grant 'display over other apps'")
            )
            PopupManager.Shown.NOWHERE -> CommandResult.error(
                409, "can't show pop-ups over other apps: grant 'display over other apps' (see the install guide)"
            )
        }
    }

    private fun launch(args: JSONObject): CommandResult {
        val pkg = args.optString("package").trim()
        if (pkg.isEmpty()) return CommandResult.error(400, "package is required")
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return CommandResult.error(404, "no launchable app with package $pkg")
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandResult.ok()
        } catch (e: Exception) {
            CommandResult.error(500, "can't start $pkg: ${e.message}")
        }
    }

    private fun volume(args: JSONObject): CommandResult {
        if (!args.has("level")) return CommandResult.error(400, "level (0-100) is required")
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = args.optInt("level").coerceIn(0, 100)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level / 100f * max), 0)
        return CommandResult.ok()
    }

    private fun speak(args: JSONObject): CommandResult {
        val message = args.optString("message").trim()
        if (message.isEmpty()) return CommandResult.error(400, "message is required")
        val language = args.optString("language").ifEmpty { null }
        val engine = tts
        if (engine != null && ttsReady) {
            language?.let { engine.setLanguage(Locale.forLanguageTag(it)) }
            engine.speak(message, TextToSpeech.QUEUE_ADD, null, "say-${System.nanoTime()}")
            return CommandResult.ok()
        }
        if (engine == null) {
            // First use: start the engine and speak once it's ready.
            tts = TextToSpeech(context) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        language?.let { tts?.setLanguage(Locale.forLanguageTag(it)) }
                        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "say-first")
                    } else {
                        AppLog.w(TAG, "No text-to-speech engine available (status $status)")
                        state.update { error("No text-to-speech engine on this TV; send a sound URL instead") }
                        tts?.shutdown()
                        tts = null
                    }
                }
            }
            return CommandResult(202, JSONObject().put("ok", true).put("note", "starting the speech engine"))
        }
        return CommandResult.error(503, "the speech engine is still starting, try again")
    }

    private fun sound(args: JSONObject): CommandResult {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return CommandResult.error(400, "url must start with http:// or https://")
        }
        stopSound()
        if (args.has("volume")) volume(JSONObject().put("level", args.optInt("volume")))
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { if (player === it) stopSound() }
        mp.setOnErrorListener { p, what, extra ->
            state.update { error("Sound failed ($what/$extra): $url") }
            if (player === p) stopSound()
            true
        }
        return try {
            mp.setDataSource(url)
            mp.prepareAsync()
            CommandResult(202, JSONObject().put("ok", true))
        } catch (e: Exception) {
            stopSound()
            CommandResult.error(400, "can't play $url: ${e.message}")
        }
    }

    /** A pop-up's live picture: a stream URL straight away, or a camera's stream once Home Assistant gives its link. */
    private fun startLive(popup: Popup) {
        popup.stream?.let { return popups.playStream(popup.id, it) }
        val camera = popup.camera ?: return
        cameraStream(camera) { url -> if (url != null) main.post { popups.playStream(popup.id, url) } }
    }

    /**
     * A pop-up's `sound` (a built-in chime name or an http(s) URL) and `tts` (text to speak),
     * the speech after the sound.
     */
    private fun announce(sound: String, speech: String, args: JSONObject) {
        val speak = {
            if (speech.isNotEmpty()) {
                speak(JSONObject().put("message", speech).put("language", args.optString("language")))
            }
        }
        val chime = sound.lowercase(Locale.ROOT)
        when {
            chime in Chimes.NAMES -> Chimes.play(chime) { main.post(speak) }
            sound.startsWith("http://") || sound.startsWith("https://") -> {
                sound(JSONObject().put("url", sound))
                player?.setOnCompletionListener { if (player === it) stopSound(); speak() }
            }
            else -> speak()
        }
    }

    private fun stopSound() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    companion object {
        private const val TAG = "KioskController"
        private const val REST_DIM_LEVEL = 80
        const val ACTION_EVENT = "tv_kiosk_action"
        const val KEY_EVENT = "tv_kiosk_key"
        private val PAGE_COMMANDS = setOf("url", "navigate", "start_page")

        /** Whether the settings ask for a dashboard at all. */
        fun hasStartUrl(settings: Settings) = settings[Keys.START_URL].isNotEmpty()
    }
}
