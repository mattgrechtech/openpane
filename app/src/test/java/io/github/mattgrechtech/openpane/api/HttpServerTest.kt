package io.github.mattgrechtech.openpane.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class HttpServerTest {

    private fun parse(raw: String, maxBody: Int = 1024) =
        HttpServer.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), "10.0.0.2", maxBody)

    @Test fun parsesAPostWithABody() {
        val req = parse("POST /api/v1/notify?x=1&y=a%20b HTTP/1.1\r\nHost: 10.0.0.5:2424\r\nContent-Length: 15\r\nAuthorization: Bearer t\r\n\r\n{\"title\":\"hi\"}X")
        assertEquals("POST", req.method)
        assertEquals("/api/v1/notify", req.path)
        assertEquals(mapOf("x" to "1", "y" to "a b"), req.query)
        assertEquals("10.0.0.5:2424", req.header("Host"))
        assertEquals("Bearer t", req.header("authorization"))
        assertEquals("{\"title\":\"hi\"}X", req.bodyText())
        assertEquals("10.0.0.2", req.remoteAddress)
    }

    @Test fun getWithoutBody() {
        val req = parse("GET /api/v1/state HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(0, req.body.size)
    }

    @Test fun refusesChunkedBodies() = expectStatus(411) {
        parse("POST / HTTP/1.1\r\nHost: tv\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
    }

    @Test fun refusesLargeBodies() = expectStatus(413) {
        parse("POST / HTTP/1.1\r\nHost: tv\r\nContent-Length: 5000\r\n\r\n", maxBody = 1024)
    }

    @Test fun refusesGarbage() = expectStatus(400) {
        parse("HELLO\r\n\r\n")
    }

    @Test fun refusesHugeHeaders() = expectStatus(431) {
        parse("GET / HTTP/1.1\r\nX-Big: " + "a".repeat(20_000) + "\r\n\r\n")
    }

    @Test fun writesAResponse() {
        val out = ByteArrayOutputStream()
        HttpServer.write(out, HttpServer.Response(200, """{"ok":true}"""))
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 11\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"))
    }

    @Test fun servesOverARealSocket() {
        val server = HttpServer(0, { req -> HttpServer.Response(200, """{"path":"${req.path}"}""") }, InetAddress.getLoopbackAddress())
        server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.getOutputStream().write("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response, response.startsWith("HTTP/1.1 200"))
                assertTrue(response, response.endsWith("""{"path":"/hello"}"""))
            }
        } finally {
            server.stop()
        }
    }

    private fun expectStatus(status: Int, block: () -> Unit) {
        try {
            block()
            fail("expected HTTP $status")
        } catch (e: HttpServer.BadRequest) {
            assertEquals(status, e.status)
        }
    }
}
