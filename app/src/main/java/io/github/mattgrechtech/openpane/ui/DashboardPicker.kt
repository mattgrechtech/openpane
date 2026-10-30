package io.github.mattgrechtech.openpane.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mattgrechtech.openpane.KioskApp
import io.github.mattgrechtech.openpane.R
import io.github.mattgrechtech.openpane.web.HaAuth
import io.github.mattgrechtech.openpane.web.HaDashboards
import kotlin.concurrent.thread

/**
 * Lists Home Assistant's dashboards and panels so nobody has to type a URL with a remote. Used by
 * settings and by first-run setup. Shows progress and errors on [panel].
 */
class DashboardPicker(
    private val activity: Activity,
    private val panel: MessagePanel,
    /** Called when HA wants the TV to sign in (again) before it lists anything. */
    private val onSignIn: () -> Unit,
    /** Called when the user backs out of an error. */
    private val onCancel: () -> Unit = {},
) {
    private val app = activity.application as KioskApp
    private val main = Handler(Looper.getMainLooper())

    /**
     * Shows the list. [current] is the URL to tick; [onPicked] gets the chosen page's URL. Picking
     * the page already shown hands back [current] unchanged, so options like `?tv=1` stay.
     */
    fun show(current: String, onPicked: (String) -> Unit) {
        val base = app.settings.haBaseUrl() ?: return
        panel.show(activity.getString(R.string.pick_loading), busy = true)
        val started = SystemClock.elapsedRealtime()
        thread(name = "ha-dashboards") {
            val outcome = runCatching { HaDashboards.list(base, app.auth.accessToken(false).accessToken) }
            // Keep the spinner up long enough to be seen rather than flash.
            val wait = (400 - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)
            main.postDelayed({ showResult(base, current, outcome, onPicked) }, wait)
        }
    }

    private fun showResult(base: String, current: String, outcome: Result<List<HaDashboards.Dashboard>>, onPicked: (String) -> Unit) {
        if (activity.isFinishing) return
        val title = activity.getString(R.string.row_pick_dashboard)
        outcome.onSuccess { dashboards ->
            panel.hide()
            if (dashboards.isEmpty()) {
                Dialogs.message(activity, title, activity.getString(R.string.pick_none))
                onCancel()
                return
            }
            val currentPath = current.substringAfter(base, "").substringBefore('?').trim('/')
            Dialogs.list(activity, title, dashboards.map { d ->
                val mark = if (current.isNotEmpty() && d.path == currentPath) "  ✓" else ""
                val kind = if (d.builtIn) activity.getString(R.string.pick_built_in) else "/${d.path}"
                "${d.title}   ·   $kind$mark" to {
                    onPicked(if (current.isNotEmpty() && d.path == currentPath) current else "$base/${d.path}")
                }
            })
        }
        outcome.onFailure { error ->
            if (error is HaAuth.Failure.Invalid) {
                panel.show(
                    activity.getString(R.string.pick_sign_in_title), activity.getString(R.string.pick_sign_in_message), listOf(
                        MessagePanel.Action(activity.getString(R.string.panel_sign_in), primary = true) { panel.hide(); onSignIn() },
                        MessagePanel.Action(activity.getString(R.string.login_cancel)) { panel.hide(); onCancel() },
                    ),
                    iconText = MessagePanel.ICON_KEY,
                )
            } else {
                panel.show(
                    activity.getString(R.string.pick_failed), error.message ?: "", listOf(
                        MessagePanel.Action(activity.getString(R.string.login_try_again), primary = true) { panel.hide(); show(current, onPicked) },
                        MessagePanel.Action(activity.getString(R.string.login_cancel)) { panel.hide(); onCancel() },
                    ),
                    iconText = MessagePanel.ICON_WARNING,
                )
            }
        }
    }
}
