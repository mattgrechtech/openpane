package io.github.mattgrechtech.openpane.web

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.mattgrechtech.openpane.BuildConfig
import io.github.mattgrechtech.openpane.settings.Settings
import org.json.JSONObject
import java.util.concurrent.Executors
import io.github.mattgrechtech.openpane.diag.AppLog

/**
 * The bridge HA's frontend uses to talk to an app that embeds it: external auth (tokens) and the
 * external bus (capabilities, connection status, kiosk mode, navigation).
 * https://contributors.home-assistant.io/frontend/reference/external-auth/
 * https://contributors.home-assistant.io/frontend/reference/external-bus/
 *
 * Security: the bridge must only ever answer the configured HA origin. With WebMessageListener
 * (`externalAppV2`) the WebView enforces that; the fallback `externalApp` interface is visible to
 * every page, so each call checks the origin of the page that is loaded.
 */
class HaBridge(
    private val webView: WebView,
    private val haOrigin: String,
    private val auth: HaAuth,
    private val events: Events,
) {
    interface Events {
        fun onConnectionStatus(status: String)
        fun onFrontendLoaded()
        fun onShowSettings()
        fun onAuthFailed(failure: HaAuth.Failure)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var nextId = 1

    /** Origin of the page currently loaded; updated by the WebViewClient on every navigation. */
    @Volatile var pageOrigin: String? = null

    /** True once the frontend has asked for our config, i.e. its bus is up. */
    @Volatile var busReady = false
        private set

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "externalAppV2", setOf(haOrigin)) { _, message, origin, isMainFrame, _ ->
                if (!isMainFrame || Settings.originOf(origin.toString()) != haOrigin) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                onV2Message(data)
            }
        } else {
            webView.addJavascriptInterface(V1(), "externalApp")
        }
    }

    /** Called when the WebView starts a new page; the frontend will ask for config again. */
    fun onPageStarted(url: String) {
        pageOrigin = Settings.originOf(url)
        busReady = false
    }

    /**
     * HA's own kiosk mode hides the sidebar; the dashboard's header (title, edit and assist
     * buttons) stays, so a small watcher in the page hides that too, again after every redraw
     * or dashboard change, until turned off.
     */
    fun setKioskMode(enable: Boolean) {
        if (busReady) sendCommand("kiosk_mode/set", JSONObject().put("enable", enable))
        evaluate(HEADER_SCRIPT.replace("__ENABLE__", enable.toString()))
    }

    fun navigate(path: String): Boolean {
        if (!busReady) return false
        sendCommand("navigate", JSONObject().put("path", path))
        return true
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun onV2Message(data: String) {
        val message = try {
            JSONObject(data)
        } catch (e: Exception) {
            AppLog.w(TAG, "Unreadable bridge message")
            return
        }
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (message.optString("type")) {
            "getExternalAuth" -> getExternalAuth(payload)
            "revokeExternalAuth" -> revokeExternalAuth(payload)
            "externalBus" -> onBusMessage(payload)
        }
    }

    private inner class V1 {
        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            if (trusted()) parse(payload)?.let { main.post { this@HaBridge.getExternalAuth(it) } }
        }

        /**
         * Ignored on this old path: it can't tell the dashboard from a frame inside it, and a frame
         * shouldn't be able to sign the TV out. Signing out is in the app's settings.
         */
        @JavascriptInterface
        fun revokeExternalAuth(payload: String) {
            AppLog.w(TAG, "Sign-out request through the old bridge ignored")
        }

        @JavascriptInterface
        fun externalBus(payload: String) {
            if (trusted()) parse(payload)?.let { main.post { onBusMessage(it) } }
        }

        private fun trusted(): Boolean {
            val ok = pageOrigin == haOrigin
            if (!ok) AppLog.w(TAG, "Ignored a bridge call from a page that isn't Home Assistant")
            return ok
        }

        private fun parse(payload: String) = try {
            JSONObject(payload)
        } catch (e: Exception) {
            null
        }
    }

    private fun getExternalAuth(payload: JSONObject) {
        val callback = payload.optString("callback")
        if (callback != AUTH_CALLBACK) {
            AppLog.w(TAG, "Unexpected auth callback name, ignored")
            return
        }
        val force = payload.optBoolean("force", false)
        worker.execute {
            val js = try {
                val token = auth.accessToken(force)
                val body = JSONObject().put("access_token", token.accessToken).put("expires_in", token.expiresInSeconds)
                "window.$AUTH_CALLBACK(true, $body);"
            } catch (e: HaAuth.Failure) {
                main.post { events.onAuthFailed(e) }
                "window.$AUTH_CALLBACK(false);"
            }
            main.post { evaluate(js) }
        }
    }

    private fun revokeExternalAuth(payload: JSONObject) {
        val callback = payload.optString("callback")
        if (callback != REVOKE_CALLBACK) return
        worker.execute {
            auth.signOut()
            main.post {
                evaluate("window.$REVOKE_CALLBACK(true);")
                events.onAuthFailed(HaAuth.Failure.Invalid("Signed out from Home Assistant on this TV."))
            }
        }
    }

    private fun onBusMessage(message: JSONObject) {
        val id = message.optInt("id")
        when (message.optString("type")) {
            "config/get" -> {
                busReady = true
                reply(id, JSONObject()
                    .put("hasSettingsScreen", true)
                    .put("hasSidebar", false)
                    .put("canWriteTag", false)
                    .put("hasExoPlayer", false)
                    .put("canCommissionMatter", false)
                    .put("hasAssist", false)
                    .put("hasBarCodeScanner", 0)
                    .put("appVersion", BuildConfig.VERSION_NAME))
            }
            "entity/add_to/get_actions" -> reply(id, JSONObject().put("actions", org.json.JSONArray()))
            "connection-status" -> {
                val status = message.optJSONObject("payload")?.optString("event").orEmpty()
                if (status.isNotEmpty()) events.onConnectionStatus(status)
            }
            "frontend/loaded" -> events.onFrontendLoaded()
            "config_screen/show" -> events.onShowSettings()
            "result" -> {
                val sent = pending.remove(id)
                if (!message.optBoolean("success", true)) {
                    val code = message.optJSONObject("error")?.optString("code")
                    // "unknown_command" means the frontend's handler isn't registered yet: try again.
                    if (code == "unknown_command" && sent != null && sent.attempt < MAX_ATTEMPTS) {
                        main.postDelayed({ sendCommand(sent.command, sent.payload, sent.attempt + 1) }, RETRY_MILLIS)
                    } else {
                        AppLog.i(TAG, "Frontend refused ${sent?.command ?: "command $id"}: ${message.optJSONObject("error")}")
                    }
                }
            }
            // Everything else (haptics, barcode, Matter, more-info events...) doesn't apply to a TV.
        }
    }

    private fun reply(id: Int, result: JSONObject) {
        sendRaw(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result))
    }

    private class Sent(val command: String, val payload: JSONObject, val attempt: Int)

    /** Commands waiting for their result, by id, so refused ones can be retried. */
    private val pending = mutableMapOf<Int, Sent>()

    private fun sendCommand(command: String, payload: JSONObject, attempt: Int = 1) {
        val id = nextId++
        pending[id] = Sent(command, payload, attempt)
        if (pending.size > 50) pending.keys.sorted().take(pending.size - 50).forEach { pending.remove(it) }
        sendRaw(JSONObject().put("id", id).put("type", "command").put("command", command).put("payload", payload))
    }

    private fun sendRaw(message: JSONObject) {
        if (pageOrigin != haOrigin) return
        evaluate("window.externalBus && window.externalBus($message);")
    }

    private fun evaluate(js: String) {
        // Never hand a token to a page that isn't Home Assistant.
        if (pageOrigin != haOrigin) return
        webView.evaluateJavascript(js, null)
    }

    private companion object {
        const val TAG = "HaBridge"
        const val AUTH_CALLBACK = "externalAuthSetToken"
        const val REVOKE_CALLBACK = "externalAuthRevokeToken"
        const val MAX_ATTEMPTS = 10

        /** Hides (or shows again) every dashboard header, now and whenever one is redrawn. */
        const val HEADER_SCRIPT = """
(function (enable) {
  function each(root, fn) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) { fn(all[i]); if (all[i].shadowRoot) each(all[i].shadowRoot, fn); }
  }
  function apply() {
    each(document, function (el) {
      if (el.tagName !== 'HUI-ROOT' || !el.shadowRoot) return;
      var style = el.shadowRoot.getElementById('openpane-hide-header');
      if (enable && !style) {
        style = document.createElement('style');
        style.id = 'openpane-hide-header';
        style.textContent = '.header { display: none !important; } #view { padding-top: 0 !important; min-height: 100vh !important; }';
        el.shadowRoot.appendChild(style);
      } else if (!enable && style) {
        style.remove();
      }
    });
  }
  if (window.__openpaneHeader) { clearInterval(window.__openpaneHeader); window.__openpaneHeader = null; }
  apply();
  if (enable) window.__openpaneHeader = setInterval(apply, 2000);
  return true;
})(__ENABLE__)
"""
        const val RETRY_MILLIS = 700L
    }
}
