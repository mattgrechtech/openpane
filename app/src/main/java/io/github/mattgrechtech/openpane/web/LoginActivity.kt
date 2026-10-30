package io.github.mattgrechtech.openpane.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.mattgrechtech.openpane.KioskApp
import io.github.mattgrechtech.openpane.R
import io.github.mattgrechtech.openpane.ui.MessagePanel
import io.github.mattgrechtech.openpane.ui.SettingsActivity
import io.github.mattgrechtech.openpane.ui.TvUi
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Signs the TV in to Home Assistant once, through HA's own login page, then goes straight to the
 * dashboard. The refresh token is kept encrypted on the TV; the dashboard gets short-lived access
 * tokens from it.
 *
 * With trusted_networks HA may sign in without showing anything, so every step is spelled out on
 * screen: loading, the login page, "Signed in", or what went wrong and what to do.
 */
class LoginActivity : Activity() {

    private lateinit var app: KioskApp
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var panel: MessagePanel
    private val main = Handler(Looper.getMainLooper())
    private var state = UUID.randomUUID().toString()
    private var base: String? = null
    private var completing = false
    private var done = false

    private val loadTimeout = Runnable {
        if (!completing && !done) showError(getString(R.string.login_timeout))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as KioskApp
        base = app.settings.haBaseUrl()

        val root = FrameLayout(this).apply { setBackgroundColor(TvUi.BACKGROUND) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header())
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        column.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TvUi.dp(this, 6)))
        webView = createWebView()
        column.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        panel = MessagePanel(this)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (base == null) {
            panel.show(
                getString(R.string.login_no_url_title), getString(R.string.login_no_url), listOf(
                    MessagePanel.Action(getString(R.string.panel_open_settings), primary = true) {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    },
                    MessagePanel.Action(getString(R.string.login_cancel)) { finish() },
                ),
                iconText = MessagePanel.ICON_WARNING,
            )
            return
        }
        start()
    }

    private fun header(): View {
        val title = TextView(this).apply {
            setText(R.string.login_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val where = TextView(this).apply {
            text = getString(R.string.login_where, base?.removePrefix("http://")?.removePrefix("https://") ?: "?")
            setTextColor(TvUi.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        val tip = TextView(this).apply {
            setText(R.string.login_tip)
            setTextColor(0xFF90A4AE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B1D22.toInt())
            val pad = TvUi.dp(this@LoginActivity, 20)
            setPadding(pad * 2, pad, pad * 2, pad)
            addView(title)
            addView(where)
            addView(tip)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(TvUi.BACKGROUND)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (intercept(url)) return true
                // The login page must stay in this screen; anything else isn't part of signing in.
                return request.url.scheme != "http" && request.url.scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (intercept(url)) {
                    view.stopLoading()
                    return
                }
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                if (!completing && !done && !panel.isShowing) {
                    main.removeCallbacks(loadTimeout)
                    view.requestFocus()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !completing && !done) {
                    showError(getString(R.string.login_unreachable, base, error.description))
                }
            }
        }
    }

    /** Opens HA's login page with a fresh state value. */
    private fun start() {
        val haBase = base ?: return
        state = UUID.randomUUID().toString()
        completing = false
        panel.hide()
        progressBar.visibility = View.VISIBLE
        main.removeCallbacks(loadTimeout)
        main.postDelayed(loadTimeout, LOAD_TIMEOUT_MILLIS)
        webView.loadUrl(HaUrls.authorizeUrl(haBase, state))
    }

    /** Catches HA's redirect back to us; returns true when the WebView shouldn't load it. */
    private fun intercept(url: String): Boolean {
        val haBase = base ?: return false
        if (!url.startsWith(HaUrls.redirectUri(haBase))) return false
        if (completing || done) return true
        main.removeCallbacks(loadTimeout)
        val code = HaUrls.authCodeFrom(url, haBase, state)
        if (code == null) {
            showError(getString(R.string.login_bad_redirect))
            return true
        }
        completing = true
        progressBar.visibility = View.GONE
        panel.show(getString(R.string.login_finishing), busy = true)
        thread(name = "ha-login") {
            val error = try {
                app.auth.completeLogin(haBase, code)
                null
            } catch (e: HaAuth.Failure) {
                e.message ?: getString(R.string.login_failed)
            }
            runOnUiThread { if (error == null) succeeded() else showError(error) }
        }
        return true
    }

    private fun succeeded() {
        done = true
        panel.show(getString(R.string.login_done), getString(R.string.login_done_message), iconText = MessagePanel.ICON_DONE)
        main.postDelayed({ openDashboard() }, SUCCESS_PAUSE_MILLIS)
    }

    private fun openDashboard() {
        if (isFinishing) return
        if (intent.getBooleanExtra(EXTRA_FOR_RESULT, false)) {
            // First-run setup carries on with choosing a dashboard.
            setResult(RESULT_OK)
            finish()
            return
        }
        // The dashboard reloads itself when the sign-in changes; this brings it forward and closes
        // settings if sign-in was started from there.
        startActivity(Intent(this, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        app.controller.execute("notify", JSONObject()
            .put("id", "signed-in")
            .put("title", getString(R.string.login_done))
            .put("message", getString(R.string.login_popup_message, base?.removePrefix("http://")?.removePrefix("https://")))
            .put("icon", "mdi:check")
            .put("color", "#43A047")
            .put("duration", 6))
        finish()
    }

    private fun showError(message: String) {
        completing = false
        progressBar.visibility = View.GONE
        main.removeCallbacks(loadTimeout)
        panel.show(
            getString(R.string.login_failed), message, listOf(
                MessagePanel.Action(getString(R.string.login_try_again), primary = true) { start() },
                MessagePanel.Action(getString(R.string.login_cancel)) { finish() },
            ),
            iconText = MessagePanel.ICON_WARNING,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            done -> Unit // about to open the dashboard
            !panel.isShowing && webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Return to the caller with RESULT_OK instead of opening the dashboard. */
        const val EXTRA_FOR_RESULT = "for_result"
        private const val LOAD_TIMEOUT_MILLIS = 20_000L
        private const val SUCCESS_PAUSE_MILLIS = 1_500L
    }
}