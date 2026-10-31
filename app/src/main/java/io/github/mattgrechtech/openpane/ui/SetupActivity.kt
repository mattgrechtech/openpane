package io.github.mattgrechtech.openpane.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.mattgrechtech.openpane.KioskApp
import io.github.mattgrechtech.openpane.R
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import io.github.mattgrechtech.openpane.web.HaDiscovery
import io.github.mattgrechtech.openpane.web.KioskActivity
import io.github.mattgrechtech.openpane.web.LoginActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * First-run setup, in the TV's own guided-step layout: what this step is about on the left, big
 * choices on the right. Home Assistant: find it on the network (or type its address), sign in,
 * choose a dashboard. A web page: type its address. Every choice is saved as it's made.
 */
class SetupActivity : Activity() {

    private enum class Step { WELCOME, FIND_HA, PICK }

    private class Option(val id: String, val title: String, val subtitle: String?, val run: () -> Unit)

    private lateinit var app: KioskApp
    private lateinit var icon: TextView
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var status: LinearLayout
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var options: LinearLayout
    private lateinit var panel: MessagePanel
    private lateinit var picker: DashboardPicker
    private val main = Handler(Looper.getMainLooper())

    private var step = Step.WELCOME
    private var discovery: HaDiscovery? = null
    private var servers = emptyList<HaDiscovery.Server>()
    private var searching = false

    private val searchTimeout = Runnable {
        searching = false
        if (step == Step.FIND_HA) render()
    }

    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(io.github.mattgrechtech.openpane.ui.TvUi.withTextSize(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as KioskApp

        val root = FrameLayout(this).apply { setBackgroundColor(TvUi.BACKGROUND) }
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        columns.addView(buildIntro(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))
        columns.addView(buildChoices(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))
        root.addView(columns)
        panel = MessagePanel(this)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        picker = DashboardPicker(this, panel, onSignIn = { signIn() }, onCancel = { focusFirst() })
        app.settings.addListener(signedInListener)
        go(Step.WELCOME)
    }

    /** Signed in from a phone while this screen is up: close the code and go on to choosing a dashboard. */
    private val signedInListener: (Set<String>) -> Unit = { changed ->
        if (Keys.HA_REFRESH_TOKEN.name in changed && app.settings[Keys.HA_REFRESH_TOKEN].isNotEmpty()) {
            main.post {
                phoneQr?.dismiss()
                phoneQr = null
                if (step != Step.PICK) go(Step.PICK)
            }
        }
    }
    private var phoneQr: android.app.Dialog? = null

    override fun onDestroy() {
        app.settings.removeListener(signedInListener)
        stopSearch()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.isShowing -> { panel.hide(); focusFirst() }
            step == Step.FIND_HA -> go(Step.WELCOME)
            step == Step.PICK -> finishSetup()
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SIGN_IN) return
        if (resultCode == RESULT_OK) go(Step.PICK) else focusFirst()
    }

    // ---- layout ------------------------------------------------------------------------------

    private fun buildIntro(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF1B1D22.toInt())
        setPadding(dp(64), dp(40), dp(48), dp(40))
        icon = TextView(this@SetupActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f) }
        title = TextView(this@SetupActivity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(12))
        }
        description = TextView(this@SetupActivity).apply {
            setTextColor(TvUi.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setLineSpacing(0f, 1.25f)
        }
        addView(icon)
        addView(title)
        addView(description)
    }

    private fun buildChoices(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(48), dp(40), dp(64), dp(40))
        statusSpinner = ProgressBar(this@SetupActivity).apply { isIndeterminate = true }
        statusText = TextView(this@SetupActivity).apply {
            setTextColor(TvUi.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(14), 0, 0, 0)
        }
        status = LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(12))
            addView(statusSpinner, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(statusText)
        }
        addView(status)
        options = LinearLayout(this@SetupActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(this@SetupActivity).apply {
            isVerticalScrollBarEnabled = false
            addView(options)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun optionView(option: Option): View = LinearLayout(this).apply {
        id = View.generateViewId()
        tag = option.id
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(16), dp(26), dp(16))
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(TvUi.ACCENT, Color.WHITE))
            addState(intArrayOf(), rounded(0xFF23262C.toInt(), null))
        }
        addView(TextView(this@SetupActivity).apply {
            text = option.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        })
        if (!option.subtitle.isNullOrEmpty()) addView(TextView(this@SetupActivity).apply {
            text = option.subtitle
            // Brighter on the focused (blue) card, where grey is hard to read.
            isDuplicateParentStateEnabled = true
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(0xFFE3F2FD.toInt(), TvUi.TEXT_SECONDARY),
            ))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        setOnClickListener { option.run() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun rounded(fill: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(12).toFloat()
        if (stroke != null) setStroke(dp(3), stroke)
    }

    // ---- steps -------------------------------------------------------------------------------

    private fun go(next: Step) {
        if (next != Step.FIND_HA) stopSearch()
        step = next
        if (next == Step.FIND_HA) startSearch()
        render(keepFocus = false)
        if (next == Step.PICK) chooseDashboard()
    }

    /** Draws the current step. A redraw keeps focus on the same choice, so new servers don't move it. */
    private fun render(keepFocus: Boolean = true) {
        val focusedId = if (keepFocus) (0 until options.childCount).map { options.getChildAt(it) }.firstOrNull { it.isFocused }?.tag else null
        val list = when (step) {
            Step.WELCOME -> welcome()
            Step.FIND_HA -> findHa()
            Step.PICK -> pick()
        }
        options.removeAllViews()
        val views = list.map { optionView(it) }
        views.forEach { options.addView(it) }
        views.firstOrNull()?.let { it.nextFocusUpId = it.id }
        views.lastOrNull()?.let { it.nextFocusDownId = it.id }
        (views.firstOrNull { it.tag == focusedId } ?: views.firstOrNull())?.requestFocus()
    }

    private fun focusFirst() {
        if (!panel.isShowing) options.getChildAt(0)?.requestFocus()
    }

    private fun intro(iconText: String, titleText: Int, descriptionText: String) {
        icon.text = iconText
        title.setText(titleText)
        description.text = descriptionText
    }

    private fun showStatus(text: String?, busy: Boolean) {
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        statusText.text = text ?: ""
        statusSpinner.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun welcome(): List<Option> {
        intro("📺", R.string.setup_welcome_title, getString(R.string.setup_welcome_message))
        showStatus(null, false)
        val list = mutableListOf(
            Option("phone", "📱 " + getString(R.string.setup_choice_phone), getString(R.string.setup_choice_phone_sub)) { showPhoneQr() },
            Option("ha", getString(R.string.setup_choice_ha), getString(R.string.setup_choice_ha_sub)) { go(Step.FIND_HA) },
            Option("web", getString(R.string.setup_choice_web), getString(R.string.setup_choice_web_sub)) { askForWebPage() },
        )
        if (app.settings.pages().isNotEmpty()) {
            list += Option("keep", getString(R.string.setup_keep), getString(R.string.setup_keep_sub)) { finish() }
        }
        return list
    }

    /** A QR code that opens the admin page on a phone, connected, to sign in and set up from there. */
    private fun showPhoneQr() {
        val app = application as io.github.mattgrechtech.openpane.KioskApp
        val ip = io.github.mattgrechtech.openpane.control.KioskState.localIpv4() ?: "<tv-ip>"
        val address = "http://$ip:${app.settings[io.github.mattgrechtech.openpane.settings.Keys.API_PORT]}"
        val token = app.settings[io.github.mattgrechtech.openpane.settings.Keys.API_TOKEN]
        phoneQr = Dialogs.qr(this, getString(R.string.setup_choice_phone), "$address/#token=$token", getString(R.string.setup_phone_caption, address))
    }

    private fun findHa(): List<Option> {
        intro("🏠", R.string.setup_find_title, getString(R.string.setup_find_message))
        showStatus(
            when {
                searching -> getString(R.string.setup_searching)
                servers.isEmpty() -> getString(R.string.setup_none_found)
                else -> getString(R.string.setup_found, servers.size)
            },
            busy = searching,
        )
        val list = servers.map { server ->
            val version = if (server.version.isEmpty()) "" else " · " + getString(R.string.setup_version, server.version)
            Option("server:${server.url}", server.name, server.url + version) { useHomeAssistant(server.url) }
        }.toMutableList()
        list += Option("type", getString(R.string.setup_type_address), getString(R.string.setup_type_address_sub)) {
            val current = app.settings[Keys.HA_URL].ifEmpty { "http://" }
            Dialogs.input(this, getString(R.string.setup_type_address), getString(R.string.edit_ha_url_first), initial = current) { value ->
                useHomeAssistant(value.trimEnd('/'))
            }
        }
        if (!searching) list += Option("search", getString(R.string.setup_search_again), null) { startSearch(); render() }
        return list
    }

    private fun pick(): List<Option> {
        intro("✅", R.string.setup_pick_title, getString(R.string.setup_pick_message))
        showStatus(null, false)
        return listOf(
            Option("pick", getString(R.string.row_pick_dashboard), getString(R.string.row_pick_dashboard_sub)) { chooseDashboard() },
            Option("default", getString(R.string.setup_default_dashboard), getString(R.string.setup_default_dashboard_sub)) {
                app.settings[Keys.START_URL] = ""
                finishSetup()
            },
        )
    }

    // ---- Home Assistant ----------------------------------------------------------------------

    private fun startSearch() {
        stopSearch()
        searching = true
        discovery = HaDiscovery(this) { found ->
            val first = servers.isEmpty()
            servers = found
            // The first server found takes focus: it's almost always the one wanted.
            if (step == Step.FIND_HA) render(keepFocus = !first)
        }.also { it.start() }
        main.postDelayed(searchTimeout, SEARCH_MILLIS)
    }

    /** Stops listening for new servers; the ones already found stay listed. */
    private fun stopSearch() {
        main.removeCallbacks(searchTimeout)
        discovery?.stop()
        discovery = null
        searching = false
    }

    /** Checks that [url] answers like Home Assistant, saves it, then signs in unless already signed in there. */
    private fun useHomeAssistant(url: String) {
        val origin = Settings.originOf(url)
        if (origin == null) {
            Dialogs.message(this, getString(R.string.setup_type_address), getString(R.string.setup_bad_address))
            return
        }
        panel.show(getString(R.string.setup_checking, origin.substringAfter("://")), busy = true)
        thread(name = "ha-check") {
            val problem = checkHomeAssistant(origin)
            main.post {
                if (isFinishing) return@post
                if (problem != null) {
                    panel.show(getString(R.string.setup_check_failed_title), problem, listOf(
                        MessagePanel.Action(getString(R.string.login_try_again), primary = true) { useHomeAssistant(url) },
                        MessagePanel.Action(getString(R.string.setup_back)) { panel.hide(); focusFirst() },
                    ), iconText = MessagePanel.ICON_WARNING)
                    return@post
                }
                panel.hide()
                saveHomeAssistant(origin)
            }
        }
    }

    private fun saveHomeAssistant(origin: String) {
        val settings = app.settings
        val sameServer = settings[Keys.MODE] == "home_assistant" && settings.haBaseUrl() == origin
        val signedIn = sameServer && settings[Keys.AUTH_MODE] == "login" && settings[Keys.HA_REFRESH_TOKEN].isNotEmpty()
        settings.edit {
            put(Keys.MODE, "home_assistant")
            put(Keys.HA_URL, origin)
            // A start page or rotation on another server (or a web page) no longer fits.
            if (Settings.originOf(settings[Keys.START_URL]) != origin) put(Keys.START_URL, "")
            if (!sameServer) put(Keys.PAGES, "")
            if (settings[Keys.AUTH_MODE] == "none" || !sameServer) put(Keys.AUTH_MODE, "login")
        }
        stopSearch()
        if (signedIn) go(Step.PICK) else signIn()
    }

    private fun signIn() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_FOR_RESULT, true), REQUEST_SIGN_IN)
    }

    private fun chooseDashboard() {
        picker.show(app.settings[Keys.START_URL]) { url ->
            app.settings[Keys.START_URL] = url
            finishSetup()
        }
    }

    /** Null when [origin] answers like Home Assistant, else what went wrong in plain words. */
    private fun checkHomeAssistant(origin: String): String? {
        val connection = try {
            URL("$origin/auth/providers").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return getString(R.string.setup_bad_address)
        }
        return try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader().use { it.readText().take(4096) } else ""
            // HA lists its login providers here, in an object (current) or an array (older).
            if (code == 200 && (body.contains("\"providers\"") || body.trimStart().startsWith("["))) null
            else getString(R.string.setup_not_ha, origin.substringAfter("://"))
        } catch (e: Exception) {
            getString(R.string.setup_unreachable, origin.substringAfter("://"), e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    // ---- web page ----------------------------------------------------------------------------

    private fun askForWebPage() {
        val current = app.settings[Keys.START_URL].takeIf { app.settings.isWebMode() }.orEmpty().ifEmpty { "https://" }
        Dialogs.input(this, getString(R.string.setup_web_title), getString(R.string.setup_web_message), initial = current) { value ->
            val url = if (value.contains("://")) value else "https://$value"
            if (Settings.originOf(url) == null || url.substringAfter("://").isEmpty()) {
                Dialogs.message(this, getString(R.string.setup_web_title), getString(R.string.setup_bad_address))
                return@input
            }
            val wasHomeAssistant = !app.settings.isWebMode()
            app.settings.edit {
                put(Keys.MODE, "web")
                put(Keys.START_URL, url)
                // Home Assistant dashboards in the rotation would now load without signing in.
                if (wasHomeAssistant) put(Keys.PAGES, "")
            }
            finishSetup()
        }
    }

    private fun finishSetup() {
        startActivity(Intent(this, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun dp(value: Int) = TvUi.dp(this, value)

    private companion object {
        const val REQUEST_SIGN_IN = 1
        const val SEARCH_MILLIS = 10_000L
    }
}
