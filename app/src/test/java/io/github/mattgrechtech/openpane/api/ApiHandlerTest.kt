package io.github.mattgrechtech.openpane.api

import io.github.mattgrechtech.openpane.control.CommandResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiHandlerTest {

    private var now = 1_000_000L
    private val executed = mutableListOf<Pair<String, String>>()
    private val handler = ApiHandler(
        token = { TOKEN },
        allowedHosts = { setOf("10.0.0.5", "localhost") },
        execute = { name, args ->
            executed += name to args.toString()
            CommandResult.ok(JSONObject().put("command", name))
        },
        exportSettings = { JSONObject().put("start_url", "http://ha.test") },
        importSettings = { JSONObject().put("applied", it.length()) },
        settingsSchema = { org.json.JSONArray().put(JSONObject().put("name", "mode")) },
        staticFile = { path -> if (path == "/") HttpServer.Response(200, "<html>", "text/html") else null },
        clock = { now },
    )

    @Test fun adminPageNeedsNoTokenButKeepsTheHostCheck() {
        val page = request("GET", "/", auth = null)
        assertEquals(200, page.status)
        assertEquals("text/html", page.contentType)
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertEquals(true, page.headers["Content-Security-Policy"]?.contains("frame-ancestors 'none'"))
        assertEquals(404, request("GET", "/missing.js", auth = null).status)
        assertEquals(405, request("POST", "/", auth = null).status)
        assertEquals(421, request("GET", "/", auth = null, host = "evil.example").status)
    }

    @Test fun settingsSchemaNeedsTheToken() {
        assertEquals(401, request("GET", "/api/v1/settings/schema", auth = null).status)
        val schema = request("GET", "/api/v1/settings/schema")
        assertEquals(200, schema.status)
        assertEquals("mode", JSONObject(String(schema.body)).getJSONArray("settings").getJSONObject(0).getString("name"))
    }

    private fun request(
        method: String,
        path: String,
        body: String = "",
        host: String? = "10.0.0.5:2424",
        auth: String? = "Bearer $TOKEN",
        from: String = "10.0.0.9",
    ): HttpServer.Response {
        val headers = mutableMapOf<String, String>()
        host?.let { headers["host"] = it }
        auth?.let { headers["authorization"] = it }
        return handler(HttpServer.Request(method, path, emptyMap(), headers, body.toByteArray(), from))
    }

    private fun HttpServer.Response.json() = JSONObject(String(body, Charsets.UTF_8))

    @Test fun runsACommand() {
        val response = request("POST", "/api/v1/notify", """{"title":"Doorbell"}""")
        assertEquals(200, response.status)
        assertEquals("notify", executed.single().first)
        assertEquals("Doorbell", JSONObject(executed.single().second).getString("title"))
    }

    @Test fun stateIsARead() {
        assertEquals(200, request("GET", "/api/v1/state").status)
        assertEquals(200, request("POST", "/api/v1/state").status)
        assertEquals(405, request("PUT", "/api/v1/state").status)
        assertEquals(405, request("GET", "/api/v1/reload").status)
    }

    @Test fun trailingSlashAndUnknownRoutes() {
        assertEquals(200, request("POST", "/api/v1/reload/").status)
        assertEquals(404, request("POST", "/api/v1/selfdestruct").status)
        assertEquals(404, request("POST", "/other").status)
    }

    @Test fun settingsRoundTrip() {
        assertEquals("http://ha.test", request("GET", "/api/v1/settings").json().getJSONObject("settings").getString("start_url"))
        assertEquals(2, request("POST", "/api/v1/settings", """{"a":1,"b":2}""").json().getInt("applied"))
    }

    @Test fun badJsonIs400() {
        assertEquals(400, request("POST", "/api/v1/notify", "not json").status)
    }

    @Test fun tokenIsRequired() {
        assertEquals(401, request("GET", "/api/v1/state", auth = null).status)
        assertEquals(401, request("GET", "/api/v1/state", auth = "Bearer wrong").status)
        assertEquals(401, request("GET", "/api/v1/state", auth = TOKEN).status)
        assertEquals(200, request("GET", "/api/v1/state", auth = "bearer $TOKEN").status)
    }

    @Test fun unknownHostsAreRefused() {
        assertEquals(421, request("GET", "/api/v1/state", host = "evil.example").status)
        assertEquals(400, request("GET", "/api/v1/state", host = null).status)
        assertEquals(200, request("GET", "/api/v1/state", host = "localhost").status)
    }

    @Test fun repeatedBadTokensAreBlockedForAMinute() {
        repeat(10) { assertEquals(401, request("GET", "/api/v1/state", auth = "Bearer nope").status) }
        assertEquals(429, request("GET", "/api/v1/state").status)
        // Another address is unaffected.
        assertEquals(200, request("GET", "/api/v1/state", from = "10.0.0.10").status)
        now += 61_000
        assertEquals(200, request("GET", "/api/v1/state").status)
    }

    @Test fun emptyTokenNeverAuthorises() {
        val open = ApiHandler({ "" }, { setOf("tv") }, { _, _ -> CommandResult.ok() }, { JSONObject() }, { JSONObject() })
        val response = open(HttpServer.Request("GET", "/api/v1/state", emptyMap(), mapOf("host" to "tv", "authorization" to "Bearer "), ByteArray(0), "x"))
        assertEquals(401, response.status)
    }

    @Test fun hostNames() {
        assertEquals("10.0.0.5", ApiHandler.hostName("10.0.0.5:2424"))
        assertEquals("tv.local", ApiHandler.hostName("TV.local"))
        assertEquals("fe80::1", ApiHandler.hostName("[fe80::1]:80"))
    }

    @Test fun tvOverlayCompatOnlyShowsPopups() {
        val shown = mutableListOf<JSONObject>()
        val compat = TvOverlayCompatHandler({ setOf("10.0.0.5") }) { shown += it; CommandResult.ok() }
        fun call(path: String, method: String = "POST", type: String? = "application/json; charset=utf-8") = compat(HttpServer.Request(
            method, path, emptyMap(), listOfNotNull("host" to "10.0.0.5:5001", type?.let { "content-type" to it }).toMap(),
            """{"title":"Bins","camera":"camera.front","tts":"hello","sound":"https://x.test/a.mp3","priority":"high","qr":"https://phish.test","stream":"http://x.test/s","duration":0,"pause_media":true,"actions":[{"label":"Open","url":"https://evil.test"}]}""".toByteArray(), "x",
        ))
        assertEquals(200, call("/notify").status)
        assertEquals("Bins", shown.single().getString("title"))
        assertEquals(false, shown.single().has("actions"))
        for (dropped in listOf("camera", "tts", "sound", "priority", "qr", "stream", "duration", "pause_media")) {
            assertEquals(dropped, false, shown.single().has(dropped))
        }
        assertEquals(501, call("/notify_fixed").status)
        assertEquals(404, call("/api/v1/reload").status)
        assertEquals(405, call("/notify", "GET").status)
    }

    @Test fun tvOverlayCompatRefusesWhatAWebPageCouldSend() {
        val compat = TvOverlayCompatHandler({ setOf("10.0.0.5") }) { CommandResult.ok() }
        for (type in listOf("text/plain;charset=UTF-8", "application/x-www-form-urlencoded", null)) {
            val headers = listOfNotNull("host" to "10.0.0.5:5001", type?.let { "content-type" to it }).toMap()
            val request = HttpServer.Request("POST", "/notify", emptyMap(), headers, """{"title":"Phish"}""".toByteArray(), "x")
            assertEquals(415, compat(request).status)
        }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef"
    }
}
