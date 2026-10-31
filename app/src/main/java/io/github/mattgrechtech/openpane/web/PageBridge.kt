package io.github.mattgrechtech.openpane.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.mattgrechtech.openpane.control.KioskController
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Settings
import org.json.JSONObject

/**
 * `window.openPane` for your own dashboard cards and pages: show a pop-up, wake the screen, report
 * presence, read the screen's state, start voice. Only on the Home Assistant address, only in the
 * page itself (not in embedded frames), and only when `page_bridge` is on: Android hands the
 * channel to that origin alone, so other sites can't reach it.
 *
 * ```js
 * await window.openPane.notify({ title: "Pizza", countdown: 600 });
 * const s = await window.openPane.state();   // { screen, resting, present, ... }
 * ```
 */
class PageBridge(private val webView: WebView, private val haOrigin: String, private val controller: KioskController) {

    /** False when the device's WebView is too old for an origin-limited channel; then there's no bridge. */
    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            AppLog.w(TAG, "This WebView can't limit the page bridge to Home Assistant: page bridge off")
            return false
        }
        WebViewCompat.addWebMessageListener(webView, NATIVE, setOf(haOrigin)) { _, message, origin, isMainFrame, reply ->
            if (!isMainFrame || Settings.originOf(origin.toString()) != haOrigin) return@addWebMessageListener
            val request = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            val id = request.opt("id")
            val answer = JSONObject().put("id", id)
            val command = request.optString("command")
            if (command !in COMMANDS) {
                reply.postMessage(answer.put("ok", false).put("error", "unknown command: $command").toString())
                return@addWebMessageListener
            }
            val result = controller.execute(command, request.optJSONObject("args") ?: JSONObject())
            if (command == "state") {
                // What a card needs, nothing about the network, tokens or the device.
                val full = result.body
                val state = JSONObject()
                for (key in STATE_FIELDS) if (full.has(key)) state.put(key, full.get(key))
                reply.postMessage(answer.put("ok", true).put("result", state).toString())
            } else if (result.ok) {
                reply.postMessage(answer.put("ok", true).put("result", result.body).toString())
            } else {
                reply.postMessage(answer.put("ok", false).put("error", result.body.optString("error")).toString())
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, SHIM, setOf(haOrigin))
        }
        return true
    }

    /** Defines `window.openPane` before the page's own scripts; older WebViews get it on load. */
    fun injectNow() = webView.evaluateJavascript(SHIM, null)

    companion object {
        private const val TAG = "PageBridge"
        private const val NATIVE = "openPaneNative"

        /** What pages may do: show and close pop-ups, wake, presence, screen, brightness, read state, voice. */
        val COMMANDS = setOf("notify", "notify_clear", "wake", "motion", "screen", "brightness", "state", "listen")
        private val STATE_FIELDS = listOf(
            "screen", "resting", "night", "present", "brightness", "scene", "dashboard_visible", "do_not_disturb", "device_name",
        )

        val SHIM = """
            (function () {
              if (!window.$NATIVE || window.openPane) return;
              var next = 0, waiting = {};
              $NATIVE.onmessage = function (event) {
                var answer = JSON.parse(event.data), w = waiting[answer.id];
                if (!w) return;
                delete waiting[answer.id];
                if (answer.ok) w[0](answer.result); else w[1](new Error(answer.error));
              };
              function call(command, args) {
                return new Promise(function (ok, fail) {
                  var id = ++next;
                  waiting[id] = [ok, fail];
                  $NATIVE.postMessage(JSON.stringify({ id: id, command: command, args: args || {} }));
                });
              }
              window.openPane = {
                version: 1,
                notify: function (popup) { return call("notify", popup); },
                clearPopup: function (id) { return call("notify_clear", id ? { id: id } : {}); },
                wake: function (minutes) { return call("wake", minutes ? { minutes: minutes } : {}); },
                motion: function (present) { return call("motion", present === undefined ? {} : { present: present }); },
                screen: function (mode, level) { return call("screen", { mode: mode, level: level }); },
                brightness: function (level) { return call("brightness", { level: level }); },
                state: function () { return call("state"); },
                listen: function () { return call("listen"); }
              };
              window.dispatchEvent(new Event("openpane-ready"));
            })();
        """.trimIndent()
    }
}
