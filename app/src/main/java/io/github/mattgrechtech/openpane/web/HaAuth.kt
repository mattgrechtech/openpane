package io.github.mattgrechtech.openpane.web

import android.os.SystemClock
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import io.github.mattgrechtech.openpane.diag.AppLog

/**
 * Supplies access tokens to HA's frontend through external auth.
 *
 * - `login`: the app keeps a refresh token from a one-time sign-in and trades it for short-lived
 *   access tokens, as HA's own apps do. Revocable in the TV user's HA profile.
 * - `token`: a long-lived access token, handed over as is.
 *
 * Network calls block: call from a background thread.
 */
class HaAuth(private val settings: Settings) {

    class Token(val accessToken: String, val expiresInSeconds: Int)

    sealed class Failure(message: String) : Exception(message) {
        /** HA rejected the credentials: the user must sign in again (or fix the token). */
        class Invalid(message: String) : Failure(message)
        /** HA couldn't be reached or answered strangely: worth retrying. */
        class Unavailable(message: String) : Failure(message)
    }

    private val lock = Any()
    private var cached: Token? = null
    private var cachedUntilElapsed = 0L

    @Throws(Failure::class)
    fun accessToken(force: Boolean): Token {
        synchronized(lock) {
            if (settings[Keys.AUTH_MODE] != "none" && !settings.haCredentialsMatch()) {
                val hadTokens = settings[Keys.HA_REFRESH_TOKEN].isNotEmpty() || settings[Keys.HA_LONG_LIVED_TOKEN].isNotEmpty()
                cached = null
                cachedUntilElapsed = 0
                if (hadTokens) {
                    AppLog.w(TAG, "Home Assistant's address changed: forgetting the old sign-in rather than sending it there")
                    settings.clearHaCredentials()
                    throw Failure.Invalid("Home Assistant's address changed: sign in again")
                }
            }
            when (settings[Keys.AUTH_MODE]) {
                "token" -> {
                    val token = settings[Keys.HA_LONG_LIVED_TOKEN]
                    if (token.isEmpty()) throw Failure.Invalid("No long-lived token set")
                    // The frontend asks again when this runs out; a day keeps that rare.
                    return Token(token, 86_400)
                }
                "login" -> {
                    val current = cached
                    if (!force && current != null && SystemClock.elapsedRealtime() < cachedUntilElapsed) {
                        val remaining = ((cachedUntilElapsed - SystemClock.elapsedRealtime()) / 1000).toInt()
                        return Token(current.accessToken, remaining)
                    }
                    return refresh()
                }
                else -> throw Failure.Invalid("Sign-in isn't managed by the app (auth mode is none)")
            }
        }
    }

    private fun refresh(): Token {
        val base = settings.haBaseUrl() ?: throw Failure.Invalid("No Home Assistant URL set")
        val refreshToken = settings[Keys.HA_REFRESH_TOKEN]
        if (refreshToken.isEmpty()) throw Failure.Invalid("Not signed in yet: open settings and sign in")
        val response = postToken(
            base,
            HaUrls.formBody(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to HaUrls.clientId(base),
            ),
        )
        val token = Token(response.getString("access_token"), response.optInt("expires_in", 1800))
        cached = token
        // Renew a minute early.
        cachedUntilElapsed = SystemClock.elapsedRealtime() + (token.expiresInSeconds - 60).coerceAtLeast(30) * 1000L
        return token
    }

    /** Finishes the sign-in: trades the code from the login redirect for a refresh token. */
    @Throws(Failure::class)
    fun completeLogin(base: String, code: String) = synchronized(lock) {
        val response = postToken(
            base,
            HaUrls.formBody(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to HaUrls.clientId(base),
            ),
        )
        val refreshToken = response.optString("refresh_token")
        if (refreshToken.isEmpty()) throw Failure.Unavailable("Home Assistant returned no refresh token")
        settings.edit {
            put(Keys.HA_REFRESH_TOKEN, refreshToken)
            put(Keys.AUTH_MODE, "login")
        }
        cached = Token(response.getString("access_token"), response.optInt("expires_in", 1800))
        cachedUntilElapsed = SystemClock.elapsedRealtime() + (cached!!.expiresInSeconds - 60).coerceAtLeast(30) * 1000L
    }

    /** Signs out: revokes the refresh token in HA (best effort) and forgets it. */
    fun signOut() = synchronized(lock) {
        val base = settings.haBaseUrl()
        val refreshToken = settings[Keys.HA_REFRESH_TOKEN]
        // Only the server that issued the token is told to revoke it.
        if (base != null && refreshToken.isNotEmpty() && settings.haCredentialsMatch()) {
            try {
                post("$base/auth/revoke", HaUrls.formBody("token" to refreshToken))
            } catch (e: IOException) {
                AppLog.w(TAG, "Revoking the refresh token failed; forgetting it anyway", e)
            }
        }
        settings.edit {
            clear(Keys.HA_REFRESH_TOKEN)
            clear(Keys.HA_CREDENTIAL_ORIGIN)
        }
        cached = null
        cachedUntilElapsed = 0
    }

    fun forgetCachedToken() = synchronized(lock) {
        cached = null
        cachedUntilElapsed = 0
    }

    private fun postToken(base: String, body: String): JSONObject {
        val (status, text) = try {
            post("$base/auth/token", body)
        } catch (e: IOException) {
            throw Failure.Unavailable("Can't reach Home Assistant: ${e.message}")
        }
        return when {
            status in 200..299 -> try {
                JSONObject(text)
            } catch (e: Exception) {
                throw Failure.Unavailable("Unexpected answer from Home Assistant")
            }
            status == 400 || status == 401 || status == 403 ->
                throw Failure.Invalid("Home Assistant refused the sign-in ($status): sign in again")
            else -> throw Failure.Unavailable("Home Assistant answered $status")
        }
    }

    private fun post(url: String, form: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "HaAuth"
    }
}
