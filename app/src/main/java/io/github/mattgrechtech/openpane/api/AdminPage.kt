package io.github.mattgrechtech.openpane.api

import android.content.Context

/**
 * The web admin page's and the family planner's files, from the app's assets (`assets/admin/`,
 * `assets/planner/`). Served by [ApiHandler] without a token: they hold no secrets, and the pages
 * need the token before they show anything.
 */
class AdminPage(private val context: Context) {

    private val cache = mutableMapOf<String, HttpServer.Response>()

    fun file(path: String): HttpServer.Response? {
        val (asset, type) = FILES[path] ?: return null
        return synchronized(cache) {
            cache.getOrPut(path) {
                HttpServer.Response(200, context.assets.open(asset).use { it.readBytes() }, type)
            }
        }
    }

    private companion object {
        val FILES = mapOf(
            "/" to ("admin/index.html" to "text/html"),
            "/index.html" to ("admin/index.html" to "text/html"),
            "/admin.js" to ("admin/admin.js" to "text/javascript"),
            "/admin.css" to ("admin/admin.css" to "text/css"),
            "/planner/" to ("planner/index.html" to "text/html"),
            "/planner/planner.js" to ("planner/planner.js" to "text/javascript"),
            "/planner/planner.css" to ("planner/planner.css" to "text/css"),
        )
    }
}
