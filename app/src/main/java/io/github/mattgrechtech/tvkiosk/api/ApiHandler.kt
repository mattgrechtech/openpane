package io.github.mattgrechtech.tvkiosk.api

import io.github.mattgrechtech.tvkiosk.api.HttpServer.Request
import io.github.mattgrechtech.tvkiosk.api.HttpServer.Response
import io.github.mattgrechtech.tvkiosk.control.CommandResult
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Routes and protects the REST API (`/api/v1/...`). Every request needs:
 * - a `Host` header naming this TV (blocks DNS-rebinding from web pages), and
 * - `Authorization: Bearer <token>`.
 * Repeated bad tokens from one address are refused for a minute.
 *
 * Dependencies are passed in as functions so this class has no Android dependencies.
 */
class ApiHandler(
    private val token: () -> String,
    private val allowedHosts: () -> Set<String>,
    private val execute: (String, JSONObject) -> CommandResult,
    private val exportSettings: () -> JSONObject,
    private val importSettings: (JSONObject) -> JSONObject,
    private val diagnostics: () -> JSONObject = { JSONObject() },
    private val settingsSchema: () -> org.json.JSONArray = { org.json.JSONArray() },
    /** The web admin page's files by path ("/", "/admin.js"), or null. They hold no secrets. */
    private val staticFile: (String) -> Response? = { null },
    /** The family planner's data for a number of days. Blocking (Home Assistant calls). */
    private val plannerData: (Int) -> JSONObject = { JSONObject().put("ok", false).put("error", "no planner") },
    /** Marks a planner to-do item done or not: entity, uid, done. */
    private val plannerTodo: (String, String, Boolean) -> JSONObject = { _, _, _ -> JSONObject().put("ok", false) },
    /** Posts a note to the family board. */
    private val plannerNote: (String) -> JSONObject = { JSONObject().put("ok", false) },
    /** A JPEG of the dashboard, or null and why. Blocking. */
    private val screenshot: () -> Pair<ByteArray?, String?> = { null to "not available" },
    private val clock: () -> Long = System::currentTimeMillis,
) : (Request) -> Response {

    private class Failures(var count: Int, var windowStart: Long, var blockedUntil: Long)

    private val failures = mutableMapOf<String, Failures>()

    override fun invoke(request: Request): Response {
        hostProblem(request, allowedHosts())?.let { return it }

        // The admin page itself is public (it asks for the token); everything it does goes through the API.
        if (!request.path.startsWith(PREFIX)) {
            val file = staticFile(request.path) ?: return error(404, "not found")
            return if (request.method == "GET") withPageHeaders(file) else error(405, "use GET")
        }

        val now = clock()
        synchronized(failures) {
            failures[request.remoteAddress]?.let { if (it.blockedUntil > now) return error(429, "too many bad tokens, wait a minute") }
        }
        if (!authorised(request)) {
            recordFailure(request.remoteAddress, now)
            return error(401, "missing or wrong token")
        }
        synchronized(failures) { failures.remove(request.remoteAddress) }

        val path = request.path.trimEnd('/')
        if (!path.startsWith(PREFIX)) return error(404, "not found")
        val route = path.removePrefix(PREFIX)

        if (route == "/state") {
            // POST too, so a Home Assistant rest_command (always POST) can read it.
            return if (request.method == "GET" || request.method == "POST") result(execute("state", JSONObject()))
            else error(405, "use GET")
        }
        if (route == "/diagnostics" || route == "/logs") {
            if (request.method != "GET") return error(405, "use GET")
            val all = diagnostics()
            if (route == "/diagnostics") return json(200, all.put("ok", true))
            val wanted = request.query["lines"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
            val log = all.optJSONArray("log") ?: org.json.JSONArray()
            val text = (maxOf(0, log.length() - wanted) until log.length()).joinToString("\n") { log.optString(it) }
            return HttpServer.Response(200, text, "text/plain")
        }
        if (route == "/screenshot") {
            if (request.method != "GET") return error(405, "use GET")
            val (picture, problem) = screenshot()
            return if (picture != null) Response(200, picture, "image/jpeg") else error(409, problem ?: "no picture")
        }
        if (route == "/planner") {
            if (request.method != "GET") return error(405, "use GET")
            val days = request.query["days"]?.toIntOrNull() ?: 7
            val data = plannerData(days)
            return if (data.optBoolean("ok")) json(200, data) else json(503, data)
        }
        if (route == "/planner/note") {
            if (request.method != "POST") return error(405, "use POST")
            val args = body(request) ?: return error(400, "body must be a JSON object")
            val answer = plannerNote(args.optString("text"))
            return json(if (answer.optBoolean("ok")) 200 else 400, answer)
        }
        if (route == "/planner/todo") {
            if (request.method != "POST") return error(405, "use POST")
            val args = body(request) ?: return error(400, "body must be a JSON object")
            val answer = plannerTodo(args.optString("entity_id"), args.optString("uid"), args.optBoolean("done", true))
            return json(if (answer.optBoolean("ok")) 200 else 400, answer)
        }
        if (route == "/settings/schema") {
            return if (request.method == "GET") json(200, JSONObject().put("ok", true).put("settings", settingsSchema()))
            else error(405, "use GET")
        }
        if (route == "/settings") {
            return when (request.method) {
                "GET" -> json(200, JSONObject().put("ok", true).put("settings", exportSettings()))
                "POST" -> body(request)?.let { json(200, importSettings(it).put("ok", true)) } ?: error(400, "body must be a JSON object")
                else -> error(405, "use GET or POST")
            }
        }

        val command = COMMANDS[route] ?: return error(404, "not found")
        if (request.method != "POST") return error(405, "use POST")
        val args = body(request) ?: return error(400, "body must be a JSON object")
        return result(execute(command, args))
    }

    private fun withPageHeaders(response: Response) = Response(
        response.status, response.body, response.contentType,
        response.headers + PAGE_HEADERS,
    )

    private fun authorised(request: Request): Boolean {
        val expected = token()
        if (expected.isEmpty()) return false
        val header = request.header("authorization") ?: return false
        if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
        val given = header.substring(7).trim()
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun recordFailure(address: String, now: Long): Unit = synchronized(failures) {
        val entry = failures.getOrPut(address) { Failures(0, now, 0) }
        if (now - entry.windowStart > WINDOW_MILLIS) {
            entry.count = 0
            entry.windowStart = now
        }
        entry.count++
        if (entry.count >= MAX_FAILURES) {
            entry.blockedUntil = now + WINDOW_MILLIS
            entry.count = 0
            entry.windowStart = now
        }
        // Don't let a scan grow this without bound.
        if (failures.size > 256) failures.entries.removeAll { it.value.blockedUntil < now && now - it.value.windowStart > WINDOW_MILLIS }
    }

    companion object {
        const val PREFIX = "/api/v1"

        /** The admin page loads only its own files, can't be framed, and sends no referrer. */
        private val PAGE_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
                "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )
        private const val MAX_FAILURES = 10
        private const val WINDOW_MILLIS = 60_000L

        /** REST route -> controller command. */
        val COMMANDS = mapOf(
            "/url" to "url",
            "/navigate" to "navigate",
            "/start_page" to "start_page",
            "/reload" to "reload",
            "/screen" to "screen",
            "/wake" to "wake",
            "/motion" to "motion",
            "/now_playing" to "now_playing",
            "/now_playing/stop" to "now_playing_stop",
            "/brightness" to "brightness",
            "/do_not_disturb" to "do_not_disturb",
            "/pointer" to "pointer",
            "/remote" to "remote",
            "/notify" to "notify",
            "/notify/clear" to "notify_clear",
            "/launch" to "launch",
            "/volume" to "volume",
            "/tts" to "tts",
            "/sound" to "sound",
            "/sound/stop" to "stop_sound",
        )

        /** Returns an error response unless the Host header names one of [allowed] (any port). */
        fun hostProblem(request: Request, allowed: Set<String>): Response? {
            val host = request.header("host") ?: return error(400, "Host header required")
            val name = hostName(host)
            return if (name in allowed) null else error(421, "unknown host")
        }

        /** "192.168.1.60:2424" -> "192.168.1.60", "[fe80::1]:80" -> "fe80::1", lower case. */
        fun hostName(hostHeader: String): String {
            val value = hostHeader.trim().lowercase(Locale.ROOT)
            if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
            return if (value.count { it == ':' } == 1) value.substringBefore(':') else value
        }

        fun body(request: Request): JSONObject? {
            val text = request.bodyText().trim()
            if (text.isEmpty()) return JSONObject()
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                null
            }
        }

        fun result(result: CommandResult) = json(result.status, result.body)

        fun json(status: Int, body: JSONObject) = Response(status, body.toString())

        fun error(status: Int, message: String) = json(status, JSONObject().put("ok", false).put("error", message))
    }
}

/**
 * TvOverlay-compatible pop-ups: `POST /notify` with TvOverlay's fields, on its own port, with no
 * token (TvOverlay has none), so automations written for TvOverlay keep working. It can only show
 * pop-ups. Off by default.
 *
 * The body must be sent as `application/json`: a web page can't send that to another address
 * without the browser asking first (CORS), so a page someone opens can't post pop-ups here.
 */
class TvOverlayCompatHandler(
    private val allowedHosts: () -> Set<String>,
    private val notify: (JSONObject) -> CommandResult,
) : (Request) -> Response {

    override fun invoke(request: Request): Response {
        ApiHandler.hostProblem(request, allowedHosts())?.let { return it }
        return when (request.path.trimEnd('/')) {
            "/notify" -> {
                if (request.method != "POST") return ApiHandler.error(405, "use POST")
                val type = request.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
                if (type != "application/json") return ApiHandler.error(415, "send the body as application/json")
                val args = ApiHandler.body(request) ?: return ApiHandler.error(400, "body must be a JSON object")
                // No token here: only TvOverlay's own fields and the look, nothing that speaks,
                // plays, streams, acts with the TV's sign-in, overrides do-not-disturb or stays up.
                ApiHandler.result(notify(tvOverlayOnly(args)))
            }
            "/notify_fixed", "/set/overlay", "/set/notifications", "/set/settings", "/set/mqtt" ->
                ApiHandler.error(501, "not supported by this app")
            else -> ApiHandler.error(404, "not found")
        }
    }

    companion object {
        private val ALLOWED = setOf(
            "title", "message", "source", "image", "largeIcon", "smallIcon", "smallIconColor", "icon", "color",
            "corner", "duration", "id", "size", "opacity", "text_size",
        )

        /** TvOverlay's fields and the look; a duration of at least a second. */
        fun tvOverlayOnly(args: JSONObject): JSONObject {
            val kept = JSONObject()
            for (key in args.keys()) if (key in ALLOWED) kept.put(key, args.get(key))
            if (kept.has("duration") && kept.optInt("duration", 1) < 1) kept.remove("duration")
            return kept
        }
    }
}
