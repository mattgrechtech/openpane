package io.github.mattgrechtech.openpane.web

import android.os.SystemClock
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import java.security.SecureRandom

/**
 * Signing the TV in to Home Assistant from a phone, so nobody types a password with a remote.
 *
 * The phone opens Home Assistant's login with the TV's own address as the client
 * (`http://<tv>:2424/`) and the TV's `/setup/callback` as where to come back to; Home Assistant
 * allows that because both are on the same address. The one-time code goes to the TV, never
 * through Home Assistant's own page (which would use it up), and the TV trades it for its
 * refresh token. The client id is kept, because Home Assistant only refreshes a token for the
 * client it was issued to.
 */
class PhoneSignIn(private val settings: Settings, private val auth: HaAuth) {

    private class Pending(val haBase: String, val clientId: String, val redirectUri: String, val state: String, val at: Long)

    @Volatile private var pending: Pending? = null
    private val random = SecureRandom()

    /** Starts a sign-in to [haUrl] for a phone that reached the TV at [tvOrigin]; returns HA's login address. */
    fun start(haUrl: String, tvOrigin: String): String {
        val base = Settings.originOf(haUrl.trim()) ?: throw IllegalArgumentException("the Home Assistant address must start with http:// or https://")
        val state = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val clientId = "$tvOrigin/"
        val redirectUri = "$tvOrigin$CALLBACK"
        pending = Pending(base, clientId, redirectUri, state, SystemClock.elapsedRealtime())
        return "$base/auth/authorize?response_type=code" +
            "&client_id=${HaUrls.enc(clientId)}&redirect_uri=${HaUrls.enc(redirectUri)}&state=${HaUrls.enc(state)}"
    }

    /**
     * Home Assistant sent the phone back with [code] and [state]. Signs the TV in; returns null on
     * success or what went wrong. Blocking (talks to Home Assistant).
     */
    fun finish(code: String?, state: String?): String? {
        val waiting = pending ?: return "No sign-in is waiting. Start again from the admin page."
        if (SystemClock.elapsedRealtime() - waiting.at > MAX_AGE_MILLIS) {
            pending = null
            return "That sign-in took too long. Start again from the admin page."
        }
        if (state == null || state != waiting.state || code.isNullOrEmpty()) return "That sign-in link doesn't match. Start again from the admin page."
        pending = null
        return try {
            // The address first: the token is tied to the address it was issued for.
            if (settings.haBaseUrl() != waiting.haBase) {
                settings.edit {
                    put(Keys.MODE, "home_assistant")
                    put(Keys.HA_URL, waiting.haBase)
                }
            }
            auth.completeLogin(waiting.haBase, code, waiting.clientId)
            AppLog.i(TAG, "Signed in to Home Assistant from a phone")
            null
        } catch (e: HaAuth.Failure) {
            AppLog.w(TAG, "Sign-in from a phone failed: ${e.javaClass.simpleName}")
            e.message ?: "Home Assistant didn't accept the sign-in."
        }
    }

    companion object {
        private const val TAG = "PhoneSignIn"
        const val CALLBACK = "/setup/callback"
        private const val MAX_AGE_MILLIS = 10 * 60_000L
    }
}
