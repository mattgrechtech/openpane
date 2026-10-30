package io.github.mattgrechtech.tvkiosk.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import io.github.mattgrechtech.tvkiosk.BuildConfig
import io.github.mattgrechtech.tvkiosk.KioskApp
import io.github.mattgrechtech.tvkiosk.R
import io.github.mattgrechtech.tvkiosk.control.DashboardSurface
import io.github.mattgrechtech.tvkiosk.control.KioskState
import io.github.mattgrechtech.tvkiosk.control.RemoteKeys
import io.github.mattgrechtech.tvkiosk.control.Schedule
import io.github.mattgrechtech.tvkiosk.service.KioskService
import io.github.mattgrechtech.tvkiosk.settings.Keys
import io.github.mattgrechtech.tvkiosk.settings.Settings
import io.github.mattgrechtech.tvkiosk.ui.Dialogs
import io.github.mattgrechtech.tvkiosk.ui.MessagePanel
import io.github.mattgrechtech.tvkiosk.ui.SettingsActivity
import io.github.mattgrechtech.tvkiosk.ui.SetupActivity
import java.util.Calendar
import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.diag.CrashGuard

/**
 * The dashboard: one full-screen WebView showing Home Assistant (or, in web mode, any page),
 * kept alive. With more than one page set, it rotates through them while nobody uses the remote,
 * and the screen can go black for set hours each night.
 *
 * Recovery: a failed load retries with back-off; a crashed or frozen renderer gets a new WebView;
 * the page reloads when the network comes back, when HA's connection stays down too long, and
 * optionally every night.
 *
 * Remote: Back goes back in the page, then to the TV home screen (the dashboard keeps running
 * behind it). Holding OK (or Menu) opens the
 * quick menu: reload, pointer, start page, settings.
 */
class KioskActivity : Activity(), DashboardSurface {

    private lateinit var app: KioskApp
    private lateinit var root: FrameLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var dim: View
    private lateinit var panel: MessagePanel
    private var panelKind = Panel.NONE
    private lateinit var pointer: PointerController
    private lateinit var navigator: TargetNavigator
    /** How the arrow keys work right now: "buttons", "pointer" or "page" (see Keys.REMOTE_MODE). */
    private var remoteMode = "buttons"

    private var webView: WebView? = null
    private var bridge: HaBridge? = null
    /** The URL last asked for, used for retries. */
    private var requestedUrl: String? = null

    private val main = Handler(Looper.getMainLooper())
    private var retryDelayIndex = 0
    private var mainFrameFailed = false
    private var networkLost = false
    /** The dashboard as it last looked while connected, shown dimmed while it can't load; and when. */
    private var lastGood: android.graphics.Bitmap? = null
    private var lastGoodAt = 0L
    private var heartbeatPending = 0
    private var lastNightlyReloadDay = -1
    private var resumed = false

    /** Which of [Settings.pages] is showing, when it changed, and when the remote was last used. */
    private var pageIndex = 0
    private var pageShownAt = 0L
    private var lastInteraction = 0L
    /** True while the night schedule has the screen black, so only the schedule turns it back on. */
    private var nightBlank = false
    private var lastMemoryReload = 0L
    /** The page to go back to when a "page" rest ends. */
    private var restReturnUrl: String? = null
    private lateinit var clock: RestClock
    /** A wake command or motion keeps the night hours from darkening the screen until then. */
    private var nightWakeUntil = 0L
    /** When a wake started, until the dashboard draws its next frame; 0 when none is pending. */
    private var wakeStartedAt = 0L
    private val wakeFrame: ViewTreeObserver.OnDrawListener = ViewTreeObserver.OnDrawListener {
        val started = wakeStartedAt
        if (started == 0L) return@OnDrawListener
        wakeStartedAt = 0L
        val took = SystemClock.elapsedRealtime() - started
        AppLog.i(TAG, "Woke: next frame $took ms after the command")
        app.state.update { wakeTook(took) }
        main.post { root.viewTreeObserver.removeOnDrawListener(wakeFrame) }
    }

    private var centreDownAt = 0L
    private var centreLongPressed = false
    private val longPress = Runnable {
        centreLongPressed = true
        showQuickMenu()
    }

    private val settingsListener: (Set<String>) -> Unit = { changed -> main.post { onSettingsChanged(changed) } }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as KioskApp

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webFrame = FrameLayout(this)
        dim = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isFocusable = false
        }
        panel = MessagePanel(this)
        pointer = PointerController(this) { webView }
        navigator = TargetNavigator(this) { webView }

        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(webFrame, match)
        root.addView(panel, FrameLayout.LayoutParams(match))
        root.addView(pointer.view, FrameLayout.LayoutParams(match))
        root.addView(navigator.view, FrameLayout.LayoutParams(match))
        root.addView(dim, FrameLayout.LayoutParams(match))
        clock = RestClock(this)
        root.addView(clock.view, FrameLayout.LayoutParams(match))
        setContentView(root)

        applyWindowFlags()
        applyRemoteMode(app.settings[Keys.REMOTE_MODE])

        app.settings.addListener(settingsListener)
        app.controller.attach(this)
        app.controller.popups.attachDashboard(root)
        registerNetworkCallback()

        applyScreen(app.state.screen, app.state.dimLevel)
        applyBrightness(app.state.brightness)
        applyOrientation()
        burnInGuard()
        startDashboard(launchSetup = true)
        if (CrashGuard.takeUnseen(this)) {
            CrashGuard.clearGaveUp(this)
            app.controller.execute("notify", org.json.JSONObject()
                .put("id", "restarted")
                .put("title", getString(R.string.crash_restarted_title))
                .put("message", getString(R.string.crash_restarted_message))
                .put("icon", "mdi:alert")
                .put("duration", 8))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.controller.takePendingUrl()?.let { loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        app.controller.popups.dashboardInFront = true
        webView?.onResume()
        // The service may have been refused while the app was in the background (Android 12+).
        KioskService.start(this)
        app.state.update { dashboardVisible(true) }
        app.controller.takePendingUrl()?.let { loadUrl(it) }
        scheduleTick()
        scheduleRotation()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        app.controller.popups.dashboardInFront = false
        app.state.update { dashboardVisible(false) }
        main.removeCallbacks(tick)
        main.removeCallbacks(rotation)
        // Another app is in front: stop spending CPU on a dashboard nobody sees.
        webView?.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        app.settings.removeListener(settingsListener)
        app.controller.detach(this)
        app.controller.popups.detachDashboard(root)
        destroyWebView()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- the WebView -----------------------------------------------------------------------

    /** Opens the start page. Nothing set yet: first-run setup ([launchSetup] opens it straight away). */
    private fun startDashboard(launchSetup: Boolean = false) {
        // With only a Home Assistant address set, show HA's home page until a dashboard is chosen.
        pageIndex = 0
        pageShownAt = SystemClock.elapsedRealtime()
        val start = app.controller.takePendingUrl() ?: app.settings.pages().firstOrNull().orEmpty()
        if (start.isEmpty()) {
            destroyWebView()
            showPanel(Panel.NO_URL, getString(R.string.panel_setup_title), getString(R.string.panel_setup_message), MessagePanel.ICON_TV,
                MessagePanel.Action(getString(R.string.panel_set_up), primary = true) { openSetup() },
                MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() })
            if (launchSetup) openSetup()
            return
        }
        createWebView()
        loadUrl(start)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        destroyWebView()
        val view = try {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(this)
        } catch (e: Exception) {
            // No WebView installed, disabled, or being updated right now. Explain, don't crash-loop.
            AppLog.e(TAG, "Can't create a WebView", e)
            app.state.update { error("No working WebView: ${e.javaClass.simpleName}") }
            showPanel(Panel.ERROR, getString(R.string.panel_no_webview_title), getString(R.string.panel_no_webview_message), MessagePanel.ICON_WARNING,
                MessagePanel.Action(getString(R.string.panel_try_now), primary = true) { hidePanel(); startDashboard() },
                MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() })
            return
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // HA on a LAN is usually plain http; cameras may mix.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString TvKiosk/${BuildConfig.VERSION_NAME}"
        }
        view.setBackgroundColor(Color.BLACK)
        // Keep the dashboard's renderer among the last things a low-memory device stops.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.webViewClient = Client()
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                app.state.update { page(view.url ?: "", title ?: "") }
            }
        }

        val haOrigin = app.settings.haBaseUrl()
        if (haOrigin != null) {
            bridge = HaBridge(view, haOrigin, app.auth, BridgeEvents()).also { it.install() }
        }

        webView = view
        webFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
        heartbeatPending = 0
    }

    private fun destroyWebView() {
        bridge?.shutdown()
        bridge = null
        webView?.let {
            webFrame.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
    }

    private fun recreate(reason: String) {
        AppLog.w(TAG, "Recreating the WebView: $reason")
        app.state.update { reloaded(reason) }
        createWebView()
        requestedUrl?.let { open(it) }
    }

    /** Loads [url], adding external auth when the app manages HA sign-in. */
    private fun open(url: String) {
        val view = webView ?: return
        requestedUrl = url
        val base = app.settings.haBaseUrl()
        val managed = app.settings[Keys.AUTH_MODE] != "none"
        view.loadUrl(if (managed && base != null) HaUrls.withExternalAuth(url, base) else url)
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            // Stay in the WebView for web pages; never hand off to other apps (intent:, market:, ...).
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameFailed = false
            navigator.reset()
            bridge?.onPageStarted(url)
            app.state.update {
                page(url, "")
                haConnection("unknown")
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            AppLog.i(TAG, "Page loaded" + if (mainFrameFailed) " (with an error)" else "")
            // Also when HA handles its own sign-in (no bus events): hide the header on load.
            if (!mainFrameFailed && app.settings[Keys.HA_KIOSK_MODE]) bridge?.setKioskMode(true)
            if (!mainFrameFailed) {
                retryDelayIndex = 0
                lastGood = null
                hidePanel(unless = Panel.SIGN_IN)
                app.state.update { page(url, view.title ?: "") }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                onMainFrameFailed("${error.description} (${request.url.host})")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) {
                onMainFrameFailed(getString(R.string.panel_server_answered, response.statusCode))
            }
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.O) // Only ever called on Android 8+.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.e(TAG, "WebView renderer gone (crashed: ${detail.didCrash()})")
            if (view !== webView) return true
            app.state.update { error(if (detail.didCrash()) "Renderer crashed" else "Renderer stopped by the system") }
            // The old WebView is unusable: replace it on the next loop.
            main.post { recreate("renderer gone") }
            return true
        }
    }

    private fun onMainFrameFailed(message: String) {
        mainFrameFailed = true
        app.state.update { error(message) }
        val delay = RETRY_DELAYS_SECONDS[retryDelayIndex.coerceAtMost(RETRY_DELAYS_SECONDS.lastIndex)]
        retryDelayIndex++
        val picture = lastGood
        if (picture != null) {
            // Calm, and still useful: the dashboard as it last was, dimmed, with the time.
            val since = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(lastGoodAt))
            panelKind = Panel.ERROR
            panel.show(
                getString(R.string.panel_offline_title),
                getString(R.string.panel_offline_message, since, delay),
                listOf(
                    MessagePanel.Action(getString(R.string.panel_try_now), primary = true) { retryNow() },
                    MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() },
                ),
                background = picture,
            )
        } else {
            showPanel(Panel.ERROR, unreachableTitle(), getString(R.string.panel_unreachable_message, message, delay), MessagePanel.ICON_WARNING,
                MessagePanel.Action(getString(R.string.panel_try_now), primary = true) { retryNow() },
                MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() })
        }
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay * 1000L)
    }

    /**
     * Keeps a small copy of the dashboard as it is now, for the offline screen. Only once per
     * outage (the first picture has the freshest values) and only from a page that loaded.
     */
    private fun rememberLastGood() {
        if (lastGood != null || mainFrameFailed) return
        val view = webView ?: return
        if (view.width == 0 || view.height == 0) return
        try {
            val scale = 0.5f
            val bitmap = android.graphics.Bitmap.createBitmap(
                (view.width * scale).toInt(), (view.height * scale).toInt(), android.graphics.Bitmap.Config.RGB_565,
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            lastGood = bitmap
            lastGoodAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            // Out of memory or a WebView that can't draw in software: just the plain message then.
            AppLog.w(TAG, "Can't keep the last dashboard picture: ${e.javaClass.simpleName}")
        }
    }

    private val retry = Runnable {
        AppLog.i(TAG, "Retrying the dashboard")
        app.state.update { reloaded("retry after error") }
        requestedUrl?.let { open(it) }
    }

    private fun retryNow() {
        main.removeCallbacks(retry)
        hidePanel()
        retry.run()
    }

    // ---- HA bridge events --------------------------------------------------------------------

    private inner class BridgeEvents : HaBridge.Events {
        override fun onConnectionStatus(status: String) {
            app.state.update { haConnection(status) }
            main.removeCallbacks(disconnectedTooLong)
            when (status) {
                "connected" -> {
                    lastGood = null
                    hidePanel()
                    if (app.settings[Keys.HA_KIOSK_MODE]) bridge?.setKioskMode(true)
                }
                "disconnected" -> {
                    rememberLastGood()
                    val seconds = app.settings[Keys.RELOAD_ON_DISCONNECT_SECONDS]
                    if (seconds > 0) main.postDelayed(disconnectedTooLong, seconds * 1000L)
                }
                "auth-invalid" -> {
                    app.auth.forgetCachedToken()
                    showSignIn(getString(R.string.panel_sign_in_rejected))
                }
            }
        }

        override fun onFrontendLoaded() {
            hidePanel(unless = Panel.SIGN_IN)
            if (app.settings[Keys.HA_KIOSK_MODE]) bridge?.setKioskMode(true)
        }

        override fun onShowSettings() = openSettings()

        override fun onAuthFailed(failure: HaAuth.Failure) {
            app.state.update { error(failure.message ?: "Sign-in failed") }
            when (failure) {
                is HaAuth.Failure.Invalid -> showSignIn(
                    if (app.settings[Keys.HA_REFRESH_TOKEN].isEmpty() && app.settings[Keys.AUTH_MODE] == "login")
                        getString(R.string.panel_sign_in_message) else failure.message ?: ""
                )
                is HaAuth.Failure.Unavailable -> showPanel(
                    Panel.ERROR, unreachableTitle(), failure.message ?: "", MessagePanel.ICON_WARNING,
                    MessagePanel.Action(getString(R.string.panel_try_now), primary = true) { hidePanel(); reload(false, "sign-in retry") },
                    MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() },
                )
            }
        }
    }

    private val disconnectedTooLong = Runnable {
        AppLog.w(TAG, "HA connection down too long; reloading")
        reload(false, "HA connection lost")
    }

    // ---- periodic checks ---------------------------------------------------------------------

    private val tick: Runnable = Runnable {
        heartbeat()
        nightlyReload()
        nightScreen()
        burnInGuard()
        relieveMemory()
        app.controller.restTick(SystemClock.elapsedRealtime())
        scheduleTick()
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (resumed) main.postDelayed(tick, TICK_MILLIS)
    }

    /** A renderer that stops answering simple scripts is frozen: replace it. */
    private fun heartbeat() {
        val view = webView ?: return
        if (mainFrameFailed) return
        if (heartbeatPending >= 2) {
            heartbeatPending = 0
            recreate("renderer not responding")
            return
        }
        heartbeatPending++
        view.evaluateJavascript("1") { heartbeatPending = 0 }
    }

    private fun nightlyReload() {
        val hour = app.settings[Keys.NIGHTLY_RELOAD_HOUR]
        if (hour < 0) return
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_YEAR)
        if (now.get(Calendar.HOUR_OF_DAY) == hour && day != lastNightlyReloadDay) {
            lastNightlyReloadDay = day
            reload(true, "nightly")
        }
    }

    /** Blacks the screen during the night hours; the remote wakes it for a few minutes at a time. */
    private fun nightScreen() {
        val now = SystemClock.elapsedRealtime()
        val night = nightActive()
        val someoneThere = app.settings[Keys.MOTION_AT_NIGHT] && app.controller.presence.present
        val idle = now - lastInteraction >= Schedule.NIGHT_WAKE_MILLIS && now >= nightWakeUntil && !someoneThere
        if (night && idle && app.state.screen == KioskState.Screen.ON) {
            AppLog.i(TAG, "Night hours: screen black")
            app.controller.setScreen(KioskState.Screen.BLANK, 100)
            nightBlank = true
        } else if (!night && nightBlank) {
            nightBlank = false
            if (app.state.screen == KioskState.Screen.BLANK) app.controller.setScreen(KioskState.Screen.ON, 0)
        }
        if (app.state.nightDark != nightBlank) app.state.update { nightDark(nightBlank) }
    }

    /**
     * When Android says memory is low and nobody is using the screen, reload the page: a dashboard
     * that has run for days gives back what it has piled up. At most every half hour.
     */
    private fun relieveMemory() {
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (!memory.lowMemory) return
        val now = SystemClock.elapsedRealtime()
        val idle = app.state.resting || app.state.screen != KioskState.Screen.ON || now - lastInteraction > IDLE_FOR_RELOAD_MILLIS
        if (!idle || now - lastMemoryReload < MEMORY_RELOAD_GAP_MILLIS) return
        lastMemoryReload = now
        AppLog.w(TAG, "Memory is low: reloading the page while nobody is looking")
        reload(false, "low memory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && level < TRIM_MEMORY_UI_HIDDEN) relieveMemory()
    }

    /** Nudges the page a few pixels now and then, so an OLED never shows the same pixels all day. */
    private fun applyOrientation() {
        requestedOrientation = when (app.settings[Keys.ORIENTATION]) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun burnInGuard() {
        val (x, y) = if (app.settings[Keys.BURN_IN_GUARD]) {
            Schedule.burnInOffset(SystemClock.elapsedRealtime(), (BURN_IN_DP * resources.displayMetrics.density).toInt())
        } else 0 to 0
        webFrame.translationX = x.toFloat()
        webFrame.translationY = y.toFloat()
    }

    override fun applyBrightness(level: Int) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (level < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else maxOf(0.01f, level / 100f)
        }
    }

    override fun restChanged(style: String?) {
        clock.show(style == "clock")
        if (style == "clock") window.attributes = window.attributes.apply { screenBrightness = CLOCK_BRIGHTNESS }
        if (style == "page") {
            restReturnUrl = requestedUrl
            open(app.settings[Keys.REST_PAGE])
        } else if (style == null) {
            restReturnUrl?.let { url -> restReturnUrl = null; open(url) }
        }
    }

    override fun nightActive(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return Schedule.inNightWindow(hour, app.settings[Keys.SCREEN_OFF_FROM], app.settings[Keys.SCREEN_OFF_TO])
    }

    override fun holdAwake(millis: Long) {
        val now = SystemClock.elapsedRealtime()
        lastInteraction = now
        nightWakeUntil = maxOf(nightWakeUntil, now + millis)
    }

    override fun timeNextFrame(startedAt: Long) {
        val pending = wakeStartedAt != 0L
        wakeStartedAt = startedAt
        if (!pending) root.viewTreeObserver.addOnDrawListener(wakeFrame)
        root.invalidate()
    }

    // ---- page rotation -----------------------------------------------------------------------

    private val rotation: Runnable = Runnable {
        val pages = app.settings.pages()
        val now = SystemClock.elapsedRealtime()
        val busy = panel.isShowing || mainFrameFailed || app.state.screen == KioskState.Screen.BLANK || app.state.resting
        if (pages.size > 1 && !busy && Schedule.rotationDue(now, pageShownAt, lastInteraction, app.settings[Keys.ROTATE_SECONDS])) {
            showPage(Schedule.nextIndex(pageIndex, pages.size), pages)
        }
        scheduleRotation()
    }

    private fun scheduleRotation() {
        main.removeCallbacks(rotation)
        if (resumed && app.settings[Keys.ROTATE_SECONDS] > 0) main.postDelayed(rotation, ROTATION_CHECK_MILLIS)
    }

    /**
     * Shows page [index] of [pages]. A Home Assistant page on the same server is opened inside the
     * running frontend, which is instant and keeps the connection; anything else loads normally.
     */
    private fun showPage(index: Int, pages: List<String> = app.settings.pages()) {
        val url = pages.getOrNull(index) ?: return
        pageIndex = index
        pageShownAt = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "Showing page ${index + 1} of ${pages.size}")
        val base = app.settings.haBaseUrl()
        val path = if (base != null && Settings.originOf(url) == base) url.substring(base.length).ifEmpty { "/" } else null
        if (path != null && navigateHa(path)) {
            requestedUrl = url
            return
        }
        if (webView == null) createWebView()
        hidePanel()
        main.removeCallbacks(retry)
        retryDelayIndex = 0
        open(url)
    }

    // ---- network -----------------------------------------------------------------------------

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                val wasDown = networkLost || mainFrameFailed
                networkLost = false
                if (wasDown && app.settings[Keys.RELOAD_ON_NETWORK]) {
                    // Give DHCP/DNS a moment.
                    main.postDelayed({ reload(false, "network back") }, 3000)
                }
            }
        }

        override fun onLost(network: Network) {
            main.post {
                networkLost = true
                rememberLastGood()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't watch the network", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Not registered.
        }
    }

    // ---- DashboardSurface --------------------------------------------------------------------

    override fun loadUrl(url: String) {
        // A page asked for by a command holds the rotation like a key press does.
        lastInteraction = SystemClock.elapsedRealtime()
        if (webView == null) createWebView()
        hidePanel()
        main.removeCallbacks(retry)
        retryDelayIndex = 0
        open(url)
    }

    override fun reload(hard: Boolean, reason: String) {
        app.state.update { reloaded(reason) }
        main.removeCallbacks(retry)
        val view = webView
        if (view == null) {
            startDashboard()
            return
        }
        if (hard) view.clearCache(true)
        val url = requestedUrl
        if (mainFrameFailed && url != null) open(url) else view.reload()
    }

    override fun applyScreen(mode: KioskState.Screen, level: Int) {
        when (mode) {
            KioskState.Screen.ON -> dim.visibility = View.GONE
            KioskState.Screen.DIM, KioskState.Screen.BLANK -> {
                dim.alpha = level / 100f
                dim.visibility = View.VISIBLE
            }
        }
        // Black is also the lowest backlight where the device allows it (most tablets; many TVs
        // ignore it), so an LCD doesn't glow. Back to the chosen brightness when it lights up.
        if (mode == KioskState.Screen.BLANK) {
            window.attributes = window.attributes.apply { screenBrightness = 0f }
        } else {
            applyBrightness(app.state.brightness)
        }
    }

    /** The pointer command (API, MQTT, quick menu): pointer on, or back to the configured mode. */
    override fun setPointer(enabled: Boolean) {
        val configured = app.settings[Keys.REMOTE_MODE]
        applyRemoteMode(
            when {
                enabled -> "pointer"
                configured == "pointer" -> "buttons"
                else -> configured
            }
        )
    }

    private fun applyRemoteMode(mode: String) {
        remoteMode = mode
        pointer.enabled = mode == "pointer"
        if (mode != "buttons") navigator.reset()
        app.state.update { pointer(mode == "pointer") }
    }

    override fun navigateHa(path: String): Boolean = bridge?.navigate(path) ?: false

    // ---- keys --------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInteraction = SystemClock.elapsedRealtime()
            // A key during a rest only wakes the screen.
            if (app.controller.interaction(lastInteraction)) return true
        }
        val isCentre = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER

        // Any key wakes a dimmed or blank screen.
        if (event.action == KeyEvent.ACTION_DOWN && app.state.screen != KioskState.Screen.ON) {
            app.controller.setScreen(KioskState.Screen.ON, 0)
            return true
        }

        // Colour keys and digits for Home Assistant: short or long press, decided on release.
        if (app.settings[Keys.KEY_EVENTS] && !panel.isShowing) {
            RemoteKeys.name(code)?.let { name ->
                if (event.action == KeyEvent.ACTION_UP) {
                    app.controller.keyPressed(name, event.eventTime - event.downTime >= RemoteKeys.LONG_PRESS_MILLIS)
                }
                return true
            }
        }

        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showQuickMenu()
            return true
        }

        if (isCentre) {
            // Hold OK for the quick menu; a short press is replayed as a normal press.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    centreDownAt = SystemClock.uptimeMillis()
                    centreLongPressed = false
                    main.postDelayed(longPress, LONG_PRESS_MILLIS)
                }
                KeyEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!centreLongPressed) {
                        val used = !panel.isShowing && when (remoteMode) {
                            "pointer" -> { pointer.click(); true }
                            "buttons" -> navigator.click()
                            else -> false
                        }
                        if (!used) {
                            super.dispatchKeyEvent(KeyEvent(event.downTime, event.downTime, KeyEvent.ACTION_DOWN, code, 0))
                            super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, KeyEvent.ACTION_UP, code, 0))
                        }
                    }
                }
            }
            return true
        }

        if (!panel.isShowing && remoteMode == "buttons" && code in ARROWS) {
            buttonsModeArrow(event)
            return true
        }
        if (!panel.isShowing && remoteMode == "pointer" && pointer.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * An arrow in "jump between buttons" mode. The page gets it instead when it asks (its own
     * remote navigation, or a focused slider or text field); otherwise the highlight moves.
     */
    private fun buttonsModeArrow(event: KeyEvent) {
        // Arrows given to the page are sent as a whole press, so the matching release is dropped.
        if (event.action != KeyEvent.ACTION_DOWN) {
            navigator.onKey(event)
            return
        }
        navigator.pageKeys { keys ->
            val sideways = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (keys == TargetNavigator.PageKeys.ALL || (keys == TargetNavigator.PageKeys.SIDEWAYS && sideways)) {
                navigator.reset()
                pressOnPage(event.keyCode)
            } else {
                navigator.onKey(event)
            }
        }
    }

    /** Sends a whole key press (down and up) straight to the page. */
    private fun pressOnPage(code: Int) {
        val view = webView ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    /**
     * Back goes back in the page; with nowhere left to go, the TV home screen comes up but the
     * dashboard keeps running behind it, so coming back is instant and stays signed in.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = webView
        if (view == null) {
            moveTaskToBack(true)
            return
        }
        // A pop-up closes first. A page running its own remote navigation gets Back as Escape.
        // Then an open Home Assistant dialog (more-info, a card's pop-up) closes.
        if (app.controller.popups.closeNewest()) return
        navigator.pageKeys { keys ->
            if (keys == TargetNavigator.PageKeys.ALL) {
                pressOnPage(KeyEvent.KEYCODE_ESCAPE)
                return@pageKeys
            }
            navigator.closeDialog { closed ->
                if (closed) return@closeDialog
                if (view.canGoBack()) view.goBack() else moveTaskToBack(true)
            }
        }
    }

    private fun showQuickMenu() {
        val pointerLabel = getString(if (remoteMode == "pointer") R.string.menu_pointer_off else R.string.menu_pointer_on)
        val pages = app.settings.pages()
        val items = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.menu_reload) to { reload(false, "menu") },
            pointerLabel to { setPointer(remoteMode != "pointer") },
        )
        if (pages.size > 1) items += getString(R.string.menu_next_page) to { showPage(Schedule.nextIndex(pageIndex, pages.size), pages) }
        items += getString(R.string.menu_start_page) to { if (pages.isNotEmpty()) showPage(0, pages) }
        items += getString(R.string.menu_settings) to { openSettings() }
        Dialogs.list(this, getString(R.string.app_name), items)
    }

    private fun openSetup() {
        Dialogs.requirePin(this, app.settings[Keys.SETTINGS_PIN]) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun openSettings() {
        Dialogs.requirePin(this, app.settings[Keys.SETTINGS_PIN]) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ---- settings and window -----------------------------------------------------------------

    private fun onSettingsChanged(changed: Set<String>) {
        if (changed.contains(Keys.KEEP_SCREEN_ON.name)) applyWindowFlags()
        if (changed.contains(Keys.BURN_IN_GUARD.name)) burnInGuard()
        if (changed.contains(Keys.ORIENTATION.name)) applyOrientation()
        if (changed.contains(Keys.REMOTE_MODE.name)) applyRemoteMode(app.settings[Keys.REMOTE_MODE])
        if (changed.any { it in ROTATION_KEYS }) {
            pageShownAt = SystemClock.elapsedRealtime()
            if (pageIndex >= app.settings.pages().size) pageIndex = 0
            scheduleRotation()
        }
        if (changed.contains(Keys.SCREEN_OFF_FROM.name) || changed.contains(Keys.SCREEN_OFF_TO.name)) nightScreen()
        val reloadKeys = setOf(
            Keys.MODE.name, Keys.START_URL.name, Keys.HA_URL.name, Keys.AUTH_MODE.name,
            Keys.HA_LONG_LIVED_TOKEN.name, Keys.HA_REFRESH_TOKEN.name,
        )
        if (changed.any { it in reloadKeys }) {
            app.auth.forgetCachedToken()
            hidePanel()
            startDashboard()
        } else if (changed.contains(Keys.HA_KIOSK_MODE.name)) {
            bridge?.setKioskMode(app.settings[Keys.HA_KIOSK_MODE])
        }
    }

    private fun applyWindowFlags() {
        if (app.settings[Keys.KEEP_SCREEN_ON]) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** What the full-screen panel is showing, so routine events don't hide a sign-in prompt. */
    private enum class Panel { NONE, NO_URL, ERROR, SIGN_IN }

    private fun showPanel(kind: Panel, title: String, message: String, icon: String?, vararg actions: MessagePanel.Action) {
        panelKind = kind
        panel.show(title, message, actions.toList(), iconText = icon)
    }

    private fun showSignIn(message: String) {
        showPanel(Panel.SIGN_IN, getString(R.string.panel_sign_in_title), message, MessagePanel.ICON_KEY,
            MessagePanel.Action(getString(R.string.panel_sign_in), primary = true) { openSignIn() },
            MessagePanel.Action(getString(R.string.menu_settings)) { openSettings() })
    }

    private fun hidePanel(unless: Panel? = null) {
        if (panelKind == Panel.NONE || panelKind == unless) return
        panelKind = Panel.NONE
        panel.hide()
        webView?.requestFocus()
    }

    private fun unreachableTitle() =
        getString(if (app.settings.isWebMode()) R.string.panel_unreachable_web_title else R.string.panel_unreachable_title)

    private fun openSignIn() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    private companion object {
        const val TAG = "KioskActivity"
        val RETRY_DELAYS_SECONDS = intArrayOf(5, 10, 20, 30, 60)
        const val TICK_MILLIS = 30_000L
        const val BURN_IN_DP = 4
        const val IDLE_FOR_RELOAD_MILLIS = 10 * 60_000L
        const val MEMORY_RELOAD_GAP_MILLIS = 30 * 60_000L
        /** The rest clock is dim, but must stay readable where black sets the backlight to its lowest. */
        const val CLOCK_BRIGHTNESS = 0.15f
        const val LONG_PRESS_MILLIS = 800L
        const val ROTATION_CHECK_MILLIS = 5_000L
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        val ROTATION_KEYS = setOf(Keys.PAGES.name, Keys.ROTATE_SECONDS.name)
    }
}
