package io.github.mattgrechtech.openpane.web

import io.github.mattgrechtech.openpane.settings.Settings
import java.net.URLDecoder
import java.net.URLEncoder

/** URL helpers for Home Assistant's login flow and external auth. No Android dependencies. */
object HaUrls {

    /**
     * HA accepts a client_id that is a URL; a redirect_uri on the same host needs no further
     * checks. Using HA's own origin, exactly like HA's frontend does, avoids hosting a client page.
     */
    fun clientId(base: String) = "$base/"

    fun redirectUri(base: String) = "$base/?auth_callback=1"

    fun authorizeUrl(base: String, state: String): String =
        "$base/auth/authorize?response_type=code" +
            "&client_id=${enc(clientId(base))}" +
            "&redirect_uri=${enc(redirectUri(base))}" +
            "&state=${enc(state)}"

    /** The `code` from the login redirect, if [url] is that redirect and [state] matches. */
    fun authCodeFrom(url: String, base: String, state: String): String? {
        if (!url.startsWith(redirectUri(base))) return null
        val params = queryParams(url)
        if (params["state"] != state) return null
        return params["code"]?.takeIf { it.isNotEmpty() }
    }

    /** Adds `external_auth=1` to [url] when it is on the HA origin [base], keeping any fragment. */
    fun withExternalAuth(url: String, base: String): String {
        if (Settings.originOf(url) != base) return url
        val hashIndex = url.indexOf('#')
        val beforeHash = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val hash = if (hashIndex >= 0) url.substring(hashIndex) else ""
        if (queryParams(beforeHash).containsKey("external_auth")) return url
        val separator = when {
            !beforeHash.contains('?') -> "?"
            beforeHash.endsWith('?') || beforeHash.endsWith('&') -> ""
            else -> "&"
        }
        // "http://host:8123" has no path: HA needs the "/" before the query.
        val withPath = if (Regex("^https?://[^/?#]+$").matches(beforeHash)) "$beforeHash/" else beforeHash
        return "$withPath${separator}external_auth=1$hash"
    }

    fun formBody(vararg fields: Pair<String, String>) = fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
