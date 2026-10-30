package io.github.mattgrechtech.tvkiosk.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mattgrechtech.tvkiosk.BuildConfig
import io.github.mattgrechtech.tvkiosk.KioskApp
import io.github.mattgrechtech.tvkiosk.R
import io.github.mattgrechtech.tvkiosk.control.WakeReadiness
import io.github.mattgrechtech.tvkiosk.control.KioskState
import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.diag.CrashGuard
import io.github.mattgrechtech.tvkiosk.diag.Diagnostics
import io.github.mattgrechtech.tvkiosk.settings.Key
import io.github.mattgrechtech.tvkiosk.settings.Keys
import io.github.mattgrechtech.tvkiosk.settings.Settings
import io.github.mattgrechtech.tvkiosk.settings.SettingsJson
import io.github.mattgrechtech.tvkiosk.web.LoginActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Settings for a remote, laid out like the TV's own settings: categories in a left rail, the
 * focused category's settings on the right. Up and down move through a list, Right goes into the
 * settings, Left or Back comes back to the rail.
 *
 * Every change is saved the moment it is made: switches flip on OK, values open an editor and
 * save when it closes. No Save button, nothing to lose.
 */
class SettingsActivity : Activity() {

    private class Page(val icon: String, val title: Int, val rows: () -> List<Row>)

    private sealed class Row {
        class Header(val text: String) : Row()
        class Toggle(val title: String, val subtitle: String?, val key: Key<Boolean>) : Row()
        class Value(val title: String, val value: String, val onClick: () -> Unit) : Row()
        class Action(val title: String, val subtitle: String?, val onClick: () -> Unit) : Row()
        class Info(val title: String, val value: String, val colour: Int? = null) : Row()
    }

    private lateinit var app: KioskApp
    private lateinit var pages: List<Page>
    private lateinit var railItems: List<TextView>
    private lateinit var railStatus: TextView
    private lateinit var contentTitle: TextView
    private lateinit var contentList: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var panel: MessagePanel
    private val main = Handler(Looper.getMainLooper())
    private var selected = 0
    /** The mode the rail was built for; a change (setup backed out of half-way) rebuilds it. */
    private var builtForWeb = false
    private lateinit var picker: DashboardPicker

    private val stateListener: () -> Unit = { main.post { updateRailStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as KioskApp
        builtForWeb = app.settings.isWebMode()
        pages = buildPages()

        val root = FrameLayout(this).apply { setBackgroundColor(TvUi.BACKGROUND) }
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        columns.addView(buildRail(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.3f))
        columns.addView(buildContent(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.7f))
        root.addView(columns)
        panel = MessagePanel(this)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        picker = DashboardPicker(this, panel, onSignIn = { signIn() }, onCancel = { focusContent() })

        selected = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, pages.lastIndex)
        select(selected)
        railItems[selected].requestFocus()
        app.state.addListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        if (app.settings.isWebMode() != builtForWeb) {
            recreate()
            return
        }
        // Back from the sign-in screen or the overlay permission screen: show what changed.
        refresh()
        updateRailStatus()
    }

    override fun onDestroy() {
        app.state.removeListener(stateListener)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.isShowing) {
            panel.hide()
            railItems[selected].requestFocus()
            return
        }
        // From the settings list, Back returns to the rail; from the rail it closes settings.
        if (currentFocus?.parent === contentList) railItems[selected].requestFocus() else finish()
    }

    // ---- layout ------------------------------------------------------------------------------

    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B1D22.toInt())
            setPadding(dp(20), dp(24), dp(20), dp(14))
        }
        rail.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), 0, dp(14), dp(2))
        })
        railStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), 0, dp(14), dp(14))
        }
        rail.addView(railStatus)

        railItems = pages.mapIndexed { index, page ->
            TextView(this).apply {
                id = View.generateViewId()
                text = "${page.icon}   ${getString(page.title)}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                isFocusable = true
                background = railBackground()
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus && index != selected) select(index) }
                setOnClickListener { focusContent() }
                rail.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                })
            }
        }
        railItems.first().nextFocusUpId = railItems.first().id
        railItems.last().nextFocusDownId = railItems.last().id

        rail.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        rail.addView(TextView(this).apply {
            setText(R.string.settings_hint)
            setTextColor(0xFF757575.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), 0, dp(14), 0)
        })
        return rail
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(48), dp(20))
        }
        contentTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(16))
        }
        column.addView(contentTitle)
        contentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(contentList)
        }
        column.addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun select(index: Int) {
        selected = index
        railItems.forEachIndexed { i, item -> item.isSelected = i == index }
        render(focusIndex = null)
    }

    private fun focusContent() {
        firstFocusableRow()?.requestFocus()
    }

    private fun firstFocusableRow(): View? =
        (0 until contentList.childCount).map { contentList.getChildAt(it) }.firstOrNull { it.isFocusable }

    /** Redraws the current page after a change, keeping focus on the same row. */
    private fun refresh() {
        if (!::contentList.isInitialized) return
        val focusedIndex = (0 until contentList.childCount).firstOrNull { contentList.getChildAt(it).isFocused }
        render(focusIndex = focusedIndex)
    }

    private fun render(focusIndex: Int?) {
        val page = pages[selected]
        contentTitle.setText(page.title)
        contentList.removeAllViews()
        val views = page.rows().map { rowView(it) }
        views.forEach { contentList.addView(it) }

        val focusables = views.filter { it.isFocusable }
        focusables.forEach { it.nextFocusLeftId = railItems[selected].id }
        focusables.firstOrNull()?.let { it.nextFocusUpId = it.id }
        focusables.lastOrNull()?.let { it.nextFocusDownId = it.id }
        if (focusIndex != null) {
            (contentList.getChildAt(focusIndex)?.takeIf { it.isFocusable } ?: focusables.firstOrNull())?.requestFocus()
        }
    }

    private fun rowView(row: Row): View {
        if (row is Row.Header) {
            return TextView(this).apply {
                text = row.text
                setTextColor(0xFF4FC3F7.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(4), dp(18), 0, dp(6))
            }
        }
        val title: String
        val subtitle: String?
        var subtitleColour = TvUi.TEXT_SECONDARY
        var trailing: View? = null
        var onClick: (() -> Unit)? = null
        when (row) {
            is Row.Toggle -> {
                title = row.title
                subtitle = row.subtitle
                val on = app.settings[row.key]
                trailing = pill(if (on) getString(R.string.settings_on) else getString(R.string.settings_off), on)
                onClick = { save(row.key.name, !app.settings[row.key]) }
            }
            is Row.Value -> {
                title = row.title
                subtitle = row.value
                trailing = chevron()
                onClick = row.onClick
            }
            is Row.Action -> {
                title = row.title
                subtitle = row.subtitle
                trailing = chevron()
                onClick = row.onClick
            }
            is Row.Info -> {
                title = row.title
                subtitle = row.value
                row.colour?.let { subtitleColour = it }
            }
            is Row.Header -> error("handled above")
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            })
            if (!subtitle.isNullOrEmpty()) addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                setTextColor(subtitleColour)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = rowBackground()
            // Information rows can be focused too, so the remote can scroll through them.
            isFocusable = true
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailing?.let { addView(it) }
            onClick?.let { action -> setOnClickListener { action() } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
    }

    private fun pill(text: String, on: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minWidth = dp(64)
        setPadding(dp(14), dp(5), dp(14), dp(5))
        background = GradientDrawable().apply {
            setColor(if (on) 0xFF2E7D32.toInt() else 0xFF424242.toInt())
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun chevron() = TextView(this).apply {
        text = "›"
        setTextColor(TvUi.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setPadding(dp(16), 0, 0, 0)
    }

    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(TvUi.ACCENT, Color.WHITE))
        addState(intArrayOf(), rounded(0xFF23262C.toInt(), null))
    }

    private fun railBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(TvUi.ACCENT, Color.WHITE))
        addState(intArrayOf(android.R.attr.state_selected), rounded(0xFF26394A.toInt(), null))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, null))
    }

    private fun rounded(fill: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10).toFloat()
        if (stroke != null) setStroke(dp(3), stroke)
    }

    private fun updateRailStatus() {
        if (app.settings.isWebMode()) {
            val host = Settings.originOf(app.settings[Keys.START_URL])?.substringAfter("://")
            railStatus.text = if (host == null) getString(R.string.rail_no_page) else getString(R.string.rail_web, host)
            railStatus.setTextColor(if (host == null) TvUi.WARNING else TvUi.TEXT_SECONDARY)
            return
        }
        val mode = app.settings[Keys.AUTH_MODE]
        val signedIn = when (mode) {
            "login" -> app.settings[Keys.HA_REFRESH_TOKEN].isNotEmpty()
            "token" -> app.settings[Keys.HA_LONG_LIVED_TOKEN].isNotEmpty()
            else -> true
        }
        val connected = app.state.haConnection == "connected"
        val (text, colour) = when {
            !signedIn -> getString(R.string.rail_not_signed_in) to TvUi.WARNING
            connected -> getString(R.string.rail_connected) to 0xFF81C784.toInt()
            mode == "none" -> getString(R.string.rail_page_sign_in) to TvUi.TEXT_SECONDARY
            else -> getString(R.string.rail_not_connected) to TvUi.WARNING
        }
        railStatus.text = text
        railStatus.setTextColor(colour)
    }

    // ---- pages -------------------------------------------------------------------------------

    private fun buildPages() = listOfNotNull(
        if (builtForWeb) Page("🌐", R.string.page_web) { dashboardRows() } else Page("📺", R.string.page_dashboard) { dashboardRows() },
        if (builtForWeb) null else Page("🔑", R.string.page_sign_in) { signInRows() },
        Page("🎮", R.string.page_screen) { screenRows() },
        Page("🔄", R.string.page_recovery) { recoveryRows() },
        Page("💬", R.string.page_popups) { popupRows() },
        Page("📡", R.string.page_mqtt) { mqttRows() },
        Page("🔒", R.string.page_device) { deviceRows() },
        Page("ℹ️", R.string.page_status) { statusRows() },
    )

    private fun dashboardRows(): List<Row> {
        val web = app.settings.isWebMode()
        val start = app.settings[Keys.START_URL]
        val ha = app.settings[Keys.HA_URL]
        val rows = mutableListOf<Row>(
            Row.Value(getString(R.string.row_shows), getString(if (web) R.string.mode_web else R.string.mode_ha)) { changeMode() },
        )
        if (!web) rows += Row.Action(getString(R.string.row_pick_dashboard), getString(R.string.row_pick_dashboard_sub)) { pickDashboard() }
        rows += Row.Value(getString(R.string.label_start_url), start.ifEmpty { getString(if (web) R.string.value_not_set else R.string.value_ha_home) }) {
            editText(Keys.START_URL, getString(R.string.label_start_url), getString(if (web) R.string.edit_web_url_message else R.string.edit_start_url_message))
        }
        if (!web) {
            rows += Row.Value(getString(R.string.row_ha_address), ha.ifEmpty { getString(R.string.value_same_as_start) }) {
                editText(Keys.HA_URL, getString(R.string.row_ha_address), getString(R.string.edit_ha_url_message))
            }
            rows += Row.Toggle(getString(R.string.label_ha_kiosk_mode), getString(R.string.row_kiosk_mode_sub), Keys.HA_KIOSK_MODE)
        }
        rows += rotationRows()
        return rows
    }

    /** The extra pages to rotate through, how long each shows, and adding more. */
    private fun rotationRows(): List<Row> {
        val extra = Keys.splitPages(app.settings[Keys.PAGES])
        val seconds = app.settings[Keys.ROTATE_SECONDS]
        val rows = mutableListOf<Row>(Row.Header(getString(R.string.header_rotation)))
        rows += Row.Value(getString(R.string.row_rotate), if (seconds == 0) getString(R.string.value_rotate_off) else durationLabel(seconds)) {
            choose(getString(R.string.row_rotate), Keys.ROTATE_SECONDS,
                listOf(getString(R.string.value_rotate_off) to 0) + ROTATE_CHOICES.map { durationLabel(it) to it })
        }
        extra.forEachIndexed { index, url ->
            rows += Row.Action(getString(R.string.row_page_n, index + 2), url) { editPage(index) }
        }
        rows += Row.Action(getString(R.string.row_add_page), getString(
            if (seconds == 0) R.string.row_add_page_sub_off else R.string.row_add_page_sub,
        )) { addPage() }
        return rows
    }

    private fun savePages(list: List<String>) = save(Keys.PAGES.name, list.joinToString("\n"))

    private fun addPage() {
        val add: (String) -> Unit = { url ->
            if (savePages(Keys.splitPages(app.settings[Keys.PAGES]) + url) && app.settings[Keys.ROTATE_SECONDS] == 0) {
                // A second page only makes sense with rotation on: suggest a sensible interval.
                save(Keys.ROTATE_SECONDS.name, DEFAULT_ROTATE_SECONDS)
            }
        }
        val type = {
            Dialogs.input(this, getString(R.string.row_add_page), getString(R.string.edit_web_url_message), initial = "https://") { value ->
                if (value.isNotEmpty() && value != "https://") add(if (value.contains("://")) value else "https://$value")
            }
        }
        if (app.settings.isWebMode() || app.settings.haBaseUrl() == null) {
            type()
        } else {
            Dialogs.list(this, getString(R.string.row_add_page), listOf(
                getString(R.string.add_page_dashboard) to { picker.show("") { url -> add(url) } },
                getString(R.string.add_page_type) to { type() },
            ))
        }
    }

    private fun editPage(index: Int) {
        val list = Keys.splitPages(app.settings[Keys.PAGES]).toMutableList()
        val url = list.getOrNull(index) ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.page_change) to {
                Dialogs.input(this, getString(R.string.row_page_n, index + 2), getString(R.string.edit_web_url_message), initial = url) { value ->
                    if (value.isNotEmpty()) savePages(list.also { it[index] = value })
                }
            },
        )
        if (index > 0) actions += getString(R.string.page_move_up) to {
            savePages(list.also { it.add(index - 1, it.removeAt(index)) })
        }
        actions += getString(R.string.page_remove) to { savePages(list.also { it.removeAt(index) }) }
        Dialogs.list(this, url, actions)
    }

    /** Setup itself offers "Keep the current set-up", so no confirmation is needed here. */
    private fun changeMode() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun signInRows(): List<Row> {
        val mode = app.settings[Keys.AUTH_MODE]
        val signedIn = mode == "login" && app.settings[Keys.HA_REFRESH_TOKEN].isNotEmpty()
        val hasToken = app.settings[Keys.HA_LONG_LIVED_TOKEN].isNotEmpty()
        val (status, colour) = when {
            signedIn -> getString(R.string.auth_status_signed_in) to TvUi.SUCCESS
            mode == "login" -> getString(R.string.auth_status_not_signed_in) to TvUi.WARNING
            mode == "token" && hasToken -> getString(R.string.auth_status_token) to TvUi.SUCCESS
            mode == "token" -> getString(R.string.auth_status_token_missing) to TvUi.WARNING
            else -> getString(R.string.auth_status_page) to TvUi.TEXT_SECONDARY
        }
        val rows = mutableListOf<Row>(Row.Info(getString(R.string.row_status), status, colour))
        rows += Row.Action(
            getString(
                when {
                    signedIn -> R.string.settings_sign_in_again
                    mode == "login" -> R.string.settings_sign_in
                    else -> R.string.settings_sign_in_instead
                }
            ),
            getString(R.string.row_sign_in_sub),
        ) { signIn() }
        if (signedIn) rows += Row.Action(getString(R.string.settings_sign_out), getString(R.string.row_sign_out_sub)) { signOut() }
        rows += Row.Header(getString(R.string.header_other_options))
        rows += Row.Action(getString(R.string.auth_token), getString(R.string.row_token_sub)) { askForToken() }
        if (mode != "none") rows += Row.Action(getString(R.string.auth_none), getString(R.string.row_page_sign_in_sub)) {
            save(Keys.AUTH_MODE.name, "none")
        }
        return rows
    }

    private fun screenRows() = listOf<Row>(
        Row.Toggle(getString(R.string.label_keep_screen_on), getString(R.string.row_keep_screen_on_sub), Keys.KEEP_SCREEN_ON),
        Row.Toggle(getString(R.string.label_boot_start), getString(R.string.row_boot_start_sub), Keys.BOOT_START),
        Row.Toggle(getString(R.string.row_burn_in), getString(R.string.row_burn_in_sub), Keys.BURN_IN_GUARD),
        Row.Value(getString(R.string.row_orientation), orientationLabel(app.settings[Keys.ORIENTATION])) {
            choose(getString(R.string.row_orientation), Keys.ORIENTATION, Keys.ORIENTATIONS.map { orientationLabel(it) to it })
        },
        Row.Toggle(getString(R.string.row_home_app), getString(R.string.row_home_app_sub), Keys.HOME_APP),
        Row.Value(getString(R.string.row_remote_mode), remoteModeLabel(app.settings[Keys.REMOTE_MODE])) {
            choose(getString(R.string.row_remote_mode), Keys.REMOTE_MODE, Keys.REMOTE_MODES.map { remoteModeLabel(it) to it })
        },
        Row.Info(getString(R.string.row_remote_help), getString(R.string.row_remote_help_sub)),
    ) + nightRows() + motionRows()

    private fun nightRows(): List<Row> {
        val from = app.settings[Keys.SCREEN_OFF_FROM]
        val to = app.settings[Keys.SCREEN_OFF_TO]
        val rows = mutableListOf<Row>(
            Row.Header(getString(R.string.header_night)),
            Row.Value(getString(R.string.row_night_from), if (from < 0) getString(R.string.value_never) else hourLabel(from)) {
                choose(getString(R.string.row_night_from), Keys.SCREEN_OFF_FROM,
                    listOf(getString(R.string.value_never) to -1) + (0..23).map { hourLabel(it) to it })
            },
        )
        if (from >= 0) {
            rows += Row.Value(getString(R.string.row_night_to), hourLabel(to)) {
                choose(getString(R.string.row_night_to), Keys.SCREEN_OFF_TO, (0..23).map { hourLabel(it) to it })
            }
            rows += Row.Info(getString(R.string.row_night_help), getString(R.string.row_night_help_sub))
        }
        return rows
    }

    private fun motionRows(): List<Row> {
        val restAfter = app.settings[Keys.REST_AFTER_MINUTES]
        val rows = mutableListOf<Row>(
            Row.Header(getString(R.string.header_motion)),
            Row.Value(getString(R.string.row_rest_after), durationLabel(restAfter * 60)) {
                choose(getString(R.string.row_rest_after), Keys.REST_AFTER_MINUTES,
                    listOf(0, 1, 2, 5, 10, 15, 30, 60).map { durationLabel(it * 60) to it })
            },
        )
        if (restAfter > 0) {
            rows += Row.Value(getString(R.string.row_rest_style), restStyleLabel(app.settings[Keys.REST_STYLE])) {
                choose(getString(R.string.row_rest_style), Keys.REST_STYLE, Keys.REST_STYLES.map { restStyleLabel(it) to it })
            }
        }
        rows += Row.Value(getString(R.string.row_motion_front), motionFrontLabel(app.settings[Keys.MOTION_FRONT])) {
            choose(getString(R.string.row_motion_front), Keys.MOTION_FRONT, Keys.MOTION_FRONTS.map { motionFrontLabel(it) to it })
        }
        rows += Row.Toggle(getString(R.string.row_motion_at_night), getString(R.string.row_motion_at_night_sub), Keys.MOTION_AT_NIGHT)
        rows += wakeReadinessRow()
        rows += Row.Info(getString(R.string.row_motion_help), getString(R.string.row_motion_help_sub))
        return rows
    }

    private fun orientationLabel(value: String) = getString(
        when (value) {
            "landscape" -> R.string.value_landscape
            "portrait" -> R.string.value_portrait
            "reverse_landscape" -> R.string.value_reverse_landscape
            "reverse_portrait" -> R.string.value_reverse_portrait
            else -> R.string.value_orientation_auto
        }
    )

    private fun restStyleLabel(value: String) =
        getString(if (value == "dim") R.string.value_rest_dim else R.string.value_rest_black)

    private fun motionFrontLabel(value: String) = getString(
        when (value) {
            "always" -> R.string.value_front_always
            "never" -> R.string.value_front_never
            else -> R.string.value_front_unless_playing
        }
    )

    /** What the TV allows: a missing permission is named, with what it costs. */
    private fun wakeReadinessRow(): Row {
        val wake = WakeReadiness.toJson(this)
        val problems = listOfNotNull(
            if (!wake.optBoolean("overlay_permission")) getString(R.string.wake_missing_overlay) else null,
            if (wake.optString("turn_screen_on") == "denied") getString(R.string.wake_missing_turn_on, packageName) else null,
            if (wake.optBoolean("low_power_standby") && !wake.optBoolean("low_power_standby_exempt"))
                getString(R.string.wake_low_power_standby) else null,
        )
        return if (problems.isEmpty()) Row.Info(getString(R.string.row_wake_ready), getString(R.string.wake_ready), TvUi.SUCCESS)
        else Row.Info(getString(R.string.row_wake_ready), problems.joinToString("\n"), TvUi.WARNING)
    }

    private fun recoveryRows(): List<Row> {
        val disconnect = app.settings[Keys.RELOAD_ON_DISCONNECT_SECONDS]
        val nightly = app.settings[Keys.NIGHTLY_RELOAD_HOUR]
        return listOfNotNull(
            Row.Toggle(getString(R.string.label_reload_on_network), null, Keys.RELOAD_ON_NETWORK),
            if (app.settings.isWebMode()) null else Row.Value(getString(R.string.row_reload_disconnect), durationLabel(disconnect)) {
                choose(getString(R.string.row_reload_disconnect), Keys.RELOAD_ON_DISCONNECT_SECONDS,
                    listOf(0, 30, 60, 120, 300, 600).map { durationLabel(it) to it })
            },
            Row.Value(getString(R.string.row_nightly_reload), if (nightly < 0) getString(R.string.value_never) else hourLabel(nightly)) {
                choose(getString(R.string.row_nightly_reload), Keys.NIGHTLY_RELOAD_HOUR,
                    listOf(getString(R.string.value_never) to -1) + (0..23).map { hourLabel(it) to it })
            },
            Row.Info(getString(R.string.row_recovery_always), getString(R.string.row_recovery_always_sub)),
        )
    }

    private fun popupRows(): List<Row> {
        val canOverlay = AndroidSettings.canDrawOverlays(this)
        return listOf(
            if (canOverlay) Row.Info(getString(R.string.row_overlay), getString(R.string.row_overlay_allowed), TvUi.SUCCESS)
            else Row.Action(getString(R.string.row_overlay), getString(R.string.row_overlay_missing)) { openOverlayPermission() },
            Row.Action(getString(R.string.settings_test_popup), getString(R.string.row_test_popup_sub)) { testPopup() },
        ) + popupLookRows() + listOf(
            Row.Header(getString(R.string.section_api)),
            Row.Toggle(getString(R.string.label_api_enabled), getString(R.string.row_api_sub), Keys.API_ENABLED),
            Row.Value(getString(R.string.label_api_port), app.settings[Keys.API_PORT].toString()) {
                editNumber(Keys.API_PORT, getString(R.string.label_api_port))
            },
            Row.Action(getString(R.string.settings_show_token), apiAddress()) { showToken() },
            Row.Action(getString(R.string.settings_new_token), getString(R.string.row_new_token_sub)) { newToken() },
            Row.Header(getString(R.string.header_tvoverlay)),
            Row.Toggle(getString(R.string.row_tvoverlay), getString(R.string.row_tvoverlay_sub), Keys.TVOVERLAY_COMPAT),
            Row.Value(getString(R.string.label_tvoverlay_port), app.settings[Keys.TVOVERLAY_PORT].toString()) {
                editNumber(Keys.TVOVERLAY_PORT, getString(R.string.label_tvoverlay_port))
            },
        )
    }

    /** How pop-ups look and behave. Each change shows a sample straight away. */
    private fun popupLookRows(): List<Row> {
        val s = app.settings
        val size = s[Keys.POPUP_SIZE]
        val opacity = s[Keys.POPUP_OPACITY]
        val text = s[Keys.POPUP_TEXT_SIZE]
        val corner = s[Keys.POPUP_CORNER]
        val duration = s[Keys.POPUP_DURATION]
        val stack = s[Keys.POPUP_MAX_PER_CORNER]
        val sizeLabel = { v: String -> getString(when (v) { "small" -> R.string.popup_size_small; "large" -> R.string.popup_size_large; else -> R.string.popup_size_medium }) }
        val textLabel = { v: String -> getString(if (v == "large") R.string.popup_text_large else R.string.popup_text_normal) }
        val cornerLabel = { v: String ->
            getString(when (v) {
                "top_start" -> R.string.corner_top_start
                "bottom_start" -> R.string.corner_bottom_start
                "bottom_end" -> R.string.corner_bottom_end
                else -> R.string.corner_top_end
            })
        }
        return listOf(
            Row.Header(getString(R.string.header_popup_look)),
            Row.Value(getString(R.string.row_popup_size), sizeLabel(size)) {
                chooseAndPreview(getString(R.string.row_popup_size), Keys.POPUP_SIZE, Keys.POPUP_SIZES.map { sizeLabel(it) to it })
            },
            Row.Value(getString(R.string.row_popup_opacity), getString(R.string.value_percent, opacity)) {
                chooseAndPreview(getString(R.string.row_popup_opacity), Keys.POPUP_OPACITY,
                    listOf(100, 93, 85, 75, 60).map { getString(R.string.value_percent, it) to it })
            },
            Row.Value(getString(R.string.row_popup_text), textLabel(text)) {
                chooseAndPreview(getString(R.string.row_popup_text), Keys.POPUP_TEXT_SIZE, Keys.POPUP_TEXT_SIZES.map { textLabel(it) to it })
            },
            Row.Value(getString(R.string.row_popup_corner), cornerLabel(corner)) {
                chooseAndPreview(getString(R.string.row_popup_corner), Keys.POPUP_CORNER, Keys.POPUP_CORNERS.map { cornerLabel(it) to it })
            },
            Row.Value(getString(R.string.row_popup_duration), durationLabel(duration)) {
                chooseAndPreview(getString(R.string.row_popup_duration), Keys.POPUP_DURATION,
                    listOf(5, 10, 15, 20, 30, 60).map { durationLabel(it) to it })
            },
            Row.Value(getString(R.string.row_popup_stack), stack.toString()) {
                choose(getString(R.string.row_popup_stack), Keys.POPUP_MAX_PER_CORNER, (1..3).map { it.toString() to it })
            },
            Row.Toggle(getString(R.string.row_popup_take_remote), getString(R.string.row_popup_take_remote_sub), Keys.POPUP_TAKE_REMOTE),
            Row.Info(getString(R.string.row_popup_closing), getString(R.string.row_popup_closing_sub)),
        )
    }

    private fun <T : Any> chooseAndPreview(title: String, key: Key<T>, options: List<Pair<String, T>>) {
        Dialogs.list(this, title, options.map { (label, value) -> label to { if (save(key.name, value)) testPopup(); Unit } })
    }

    private fun mqttRows() = listOf<Row>(
        Row.Toggle(getString(R.string.label_mqtt_enabled), getString(R.string.row_mqtt_sub), Keys.MQTT_ENABLED),
        textRow(Keys.MQTT_HOST, R.string.label_mqtt_host, getString(R.string.edit_mqtt_host_message)),
        Row.Value(getString(R.string.label_mqtt_port), app.settings[Keys.MQTT_PORT].toString()) {
            editNumber(Keys.MQTT_PORT, getString(R.string.label_mqtt_port))
        },
        Row.Toggle(getString(R.string.label_mqtt_tls), null, Keys.MQTT_TLS),
        textRow(Keys.MQTT_USER, R.string.label_mqtt_user, ""),
        secretRow(Keys.MQTT_PASSWORD, R.string.label_mqtt_password),
        Row.Header(getString(R.string.header_advanced)),
        textRow(Keys.MQTT_BASE_TOPIC, R.string.label_mqtt_base_topic, ""),
        textRow(Keys.MQTT_DISCOVERY_PREFIX, R.string.label_mqtt_discovery_prefix, ""),
    )

    private fun deviceRows() = listOf<Row>(
        Row.Action(getString(R.string.row_run_setup), getString(R.string.row_run_setup_sub)) {
            startActivity(Intent(this, SetupActivity::class.java))
        },
        textRow(Keys.DEVICE_NAME, R.string.label_device_name, getString(R.string.edit_device_name_message)),
        secretRow(Keys.SETTINGS_PIN, R.string.label_settings_pin, numeric = true),
        Row.Header(getString(R.string.header_backup)),
        Row.Action(getString(R.string.settings_export), getString(R.string.row_export_sub)) { exportSettings() },
        Row.Action(getString(R.string.settings_import), getString(R.string.row_import_sub)) { importSettings() },
    )

    private fun statusRows(): List<Row> {
        val s = app.state.toJson(this)
        val uptimeMinutes = s.optLong("app_uptime_s") / 60
        return listOfNotNull(
            if (app.settings.isWebMode()) null else Row.Info(getString(R.string.status_connection), s.optString("ha_connection"),
                if (s.optString("ha_connection") == "connected") TvUi.SUCCESS else TvUi.WARNING),
            Row.Info(getString(R.string.status_page), s.optString("url").ifEmpty { "-" }),
            Row.Info(getString(R.string.status_last_error), s.optString("last_error").ifEmpty { getString(R.string.status_none) }),
            Row.Info(getString(R.string.status_reloads), getString(R.string.status_reloads_value,
                s.optInt("reload_count"), s.optString("last_reload_reason").ifEmpty { "-" })),
            Row.Info(getString(R.string.status_api), apiAddress()),
            Row.Info(getString(R.string.status_memory), getString(R.string.status_memory_value,
                s.optInt("app_memory_mb"), s.optInt("memory_available_mb"), s.optInt("memory_total_mb"))),
            Row.Info(getString(R.string.status_uptime), getString(R.string.status_uptime_value, uptimeMinutes / 60, uptimeMinutes % 60)),
            Row.Info(getString(R.string.status_versions), getString(R.string.status_versions_value,
                BuildConfig.VERSION_NAME, s.optString("webview_version"), s.optString("android_version"), s.optString("model"))),
            CrashGuard.lastCrash(this)?.let { crash ->
                Row.Info(getString(R.string.status_last_crash),
                    DateUtils.getRelativeTimeSpanString(crash.atMillis).toString() + " · " + crash.summary, TvUi.WARNING)
            } ?: Row.Info(getString(R.string.status_last_crash), getString(R.string.status_none)),
            Row.Header(getString(R.string.header_troubleshooting)),
            Row.Action(getString(R.string.row_show_log), getString(R.string.row_show_log_sub)) {
                Dialogs.message(this, getString(R.string.row_show_log), AppLog.recent(60).joinToString("\n"))
            },
            Row.Action(getString(R.string.row_save_diagnostics), getString(R.string.row_save_diagnostics_sub)) {
                val file = Diagnostics.export(this)
                Dialogs.message(this, getString(R.string.row_save_diagnostics), getString(R.string.diagnostics_saved, file.absolutePath))
            },
        )
    }

    private fun textRow(key: Key<String>, label: Int, message: String): Row =
        Row.Value(getString(label), app.settings[key].ifEmpty { getString(R.string.value_not_set) }) {
            editText(key, getString(label), message)
        }

    private fun secretRow(key: Key<String>, label: Int, numeric: Boolean = false): Row =
        Row.Value(getString(label), getString(if (app.settings[key].isEmpty()) R.string.value_not_set else R.string.value_set)) {
            Dialogs.input(this, getString(label), getString(R.string.edit_secret_message), password = true, numeric = numeric) { value ->
                if (value.isNotEmpty()) save(key.name, if (value == "-") "" else value)
            }
        }

    // ---- saving and editors ------------------------------------------------------------------

    /** Saves one setting straight away; explains and keeps the old value if it's rejected. */
    private fun save(name: String, value: Any): Boolean {
        val result = SettingsJson.import(app.settings, JSONObject().put(name, value))
        val problem = result.rejected[name]
        if (problem != null) {
            Dialogs.message(this, getString(R.string.settings_not_saved), problem)
            return false
        }
        refresh()
        updateRailStatus()
        return true
    }

    private fun editText(key: Key<String>, title: String, message: String) {
        Dialogs.input(this, title, message, initial = app.settings[key]) { value -> save(key.name, value) }
    }

    private fun editNumber(key: Key<Int>, title: String) {
        Dialogs.input(this, title, "", initial = app.settings[key].toString(), numeric = true) { value -> save(key.name, value) }
    }

    private fun <T : Any> choose(title: String, key: Key<T>, options: List<Pair<String, T>>) {
        Dialogs.list(this, title, options.map { (label, value) -> label to { save(key.name, value); Unit } })
    }

    private fun remoteModeLabel(mode: String) = getString(
        when (mode) {
            "pointer" -> R.string.remote_mode_pointer
            "page" -> R.string.remote_mode_page
            else -> R.string.remote_mode_buttons
        }
    )

    private fun hourLabel(hour: Int) = "%02d:00".format(hour)

    private fun durationLabel(seconds: Int) = when {
        seconds == 0 -> getString(R.string.value_never)
        seconds < 60 -> getString(R.string.value_seconds, seconds)
        seconds < 3600 || seconds % 3600 != 0 -> getString(R.string.value_minutes, seconds / 60)
        else -> getString(R.string.value_hours, seconds / 3600)
    }

    // ---- dashboard picker --------------------------------------------------------------------

    /** Asks for HA's address first if there isn't one, then lists its dashboards. */
    private fun pickDashboard() {
        if (app.settings.haBaseUrl() == null) {
            Dialogs.input(this, getString(R.string.row_ha_address), getString(R.string.edit_ha_url_first), initial = "http://") { value ->
                if (save(Keys.HA_URL.name, value.trimEnd('/'))) pickDashboard()
            }
            return
        }
        if (app.settings[Keys.AUTH_MODE] == "none") {
            Dialogs.message(this, getString(R.string.row_pick_dashboard), getString(R.string.pick_needs_sign_in_mode))
            return
        }
        picker.show(app.settings[Keys.START_URL]) { url -> save(Keys.START_URL.name, url) }
    }

    // ---- sign-in -----------------------------------------------------------------------------

    private fun signIn() {
        if (app.settings.haBaseUrl() == null) {
            Dialogs.message(this, getString(R.string.settings_sign_in), getString(R.string.login_no_url))
            return
        }
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun signOut() {
        Dialogs.confirm(this, getString(R.string.settings_sign_out), getString(R.string.sign_out_confirm)) {
            thread(name = "ha-sign-out") {
                app.auth.signOut()
                main.post { refresh(); updateRailStatus() }
            }
        }
    }

    private fun askForToken() {
        Dialogs.input(this, getString(R.string.auth_token), getString(R.string.token_dialog_message), password = true) { value ->
            if (value.isEmpty()) return@input
            app.settings.edit {
                put(Keys.HA_LONG_LIVED_TOKEN, value)
                put(Keys.AUTH_MODE, "token")
            }
            refresh()
            updateRailStatus()
        }
    }

    // ---- pop-ups, API, backup ----------------------------------------------------------------

    private fun apiAddress(): String {
        val ip = KioskState.localIpv4() ?: "<tv-ip>"
        return "http://$ip:${app.settings[Keys.API_PORT]}"
    }

    private fun showToken() {
        val token = app.settings[Keys.API_TOKEN]
        // Scanning it opens the web admin page on a phone, already connected (the token stays in the # part).
        val content = "${apiAddress()}/#token=$token"
        Dialogs.qr(this, getString(R.string.settings_show_token), content, getString(R.string.token_caption, apiAddress(), token))
    }

    private fun newToken() {
        Dialogs.confirm(this, getString(R.string.settings_new_token), getString(R.string.new_token_confirm)) {
            app.settings[Keys.API_TOKEN] = KioskApp.newToken()
            showToken()
        }
    }

    private fun openOverlayPermission() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // Many TVs have no screen for this permission.
            Dialogs.message(this, getString(R.string.row_overlay), getString(R.string.overlay_adb, packageName))
        }
    }

    /** A sample like a doorbell pop-up (a picture, an icon and a button), using the current settings. */
    private fun testPopup() {
        val result = app.controller.execute("notify", JSONObject()
            .put("id", "sample")
            .put("title", getString(R.string.app_name))
            .put("message", getString(R.string.test_popup_message))
            .put("icon", "mdi:television")
            .put("image", sampleImage())
            .put("actions", org.json.JSONArray().put(JSONObject().put("label", getString(R.string.sample_button)))))
        if (!result.ok) Dialogs.message(this, getString(R.string.settings_test_popup), result.body.optString("error"))
    }

    /** A stand-in for a camera snapshot, drawn here so the sample needs no network. */
    private fun sampleImage(): String {
        val bitmap = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 640f, 360f, 0xFF37474F.toInt(), 0xFF102027.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 360f, paint)
        paint.shader = null
        paint.color = 0xCCFFFFFF.toInt()
        paint.textSize = 34f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("📷  " + getString(R.string.sample_image_text), 320f, 192f, paint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun exportFile() = File(getExternalFilesDir(null), "settings.json")

    private fun exportSettings() {
        val file = exportFile()
        file.writeText(SettingsJson.export(app.settings, includeSecrets = false).toString(2))
        Dialogs.message(this, getString(R.string.settings_export), getString(R.string.export_done, file.absolutePath))
    }

    private fun importSettings() {
        val file = exportFile()
        if (!file.exists()) {
            Dialogs.message(this, getString(R.string.settings_import), getString(R.string.import_missing, file.absolutePath))
            return
        }
        val result = try {
            SettingsJson.import(app.settings, JSONObject(file.readText()))
        } catch (e: Exception) {
            Dialogs.message(this, getString(R.string.settings_import), e.message ?: "Unreadable file")
            return
        }
        val summary = getString(R.string.import_done, result.applied.size) +
            if (result.rejected.isEmpty()) "" else "\n\n" + result.rejected.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        Dialogs.message(this, getString(R.string.settings_import), summary)
        refresh()
    }

    private fun dp(value: Int) = TvUi.dp(this, value)

    companion object {
        /** Index of the page to open on. */
        const val EXTRA_PAGE = "page"
        private val ROTATE_CHOICES = listOf(15, 30, 60, 120, 300, 600, 1800, 3600)
        private const val DEFAULT_ROTATE_SECONDS = 60
    }
}
