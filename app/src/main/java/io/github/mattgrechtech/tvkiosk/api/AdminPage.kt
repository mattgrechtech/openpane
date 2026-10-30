package io.github.mattgrechtech.tvkiosk.api

import android.content.Context

/**
 * The web admin page's files, from the app's assets (`assets/admin/`). Served by [ApiHandler]
 * without a token: they hold no secrets, and the page asks for the token before doing anything.
 */
class AdminPage(private val context: Context) {

    private val cache = mutableMapOf<String, HttpServer.Response>()

    fun file(path: String): HttpServer.Response? {
        val (asset, type) = FILES[path] ?: return null
        return synchronized(cache) {
            cache.getOrPut(path) {
                HttpServer.Response(200, context.assets.open("admin/$asset").use { it.readBytes() }, type)
            }
        }
    }

    private companion object {
        val FILES = mapOf(
            "/" to ("index.html" to "text/html"),
            "/index.html" to ("index.html" to "text/html"),
            "/admin.js" to ("admin.js" to "text/javascript"),
            "/admin.css" to ("admin.css" to "text/css"),
        )
    }
}
