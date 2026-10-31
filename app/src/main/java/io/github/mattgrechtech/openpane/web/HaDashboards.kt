package io.github.mattgrechtech.openpane.web

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * Lists the pages a TV can show (dashboards and custom panels such as the House twin) by asking
 * Home Assistant's WebSocket API for its panels, the same list the sidebar is built from.
 * Blocking: call from a background thread.
 */
object HaDashboards {

    /** [builtIn]: one of HA's own pages (energy, map...) rather than a dashboard or panel you made. */
    data class Dashboard(val title: String, val path: String, val builtIn: Boolean = false)

    /** Panel types worth showing on a TV. Settings, developer tools and the like are left out. */
    private val YOURS = setOf("lovelace", "custom", "iframe")
    private val BUILT_IN = mapOf(
        "energy" to "Energy", "map" to "Map", "calendar" to "Calendar", "todo" to "To-do lists",
        "history" to "History", "logbook" to "Logbook",
    )

    @Throws(IOException::class)
    fun list(base: String, accessToken: String): List<Dashboard> {
        MiniWebSocket.connect("$base/api/websocket").use { ws ->
            val hello = JSONObject(ws.receiveText())
            if (hello.optString("type") != "auth_required") throw IOException("Unexpected answer from Home Assistant")
            ws.sendText(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
            val auth = JSONObject(ws.receiveText())
            if (auth.optString("type") != "auth_ok") throw IOException("Home Assistant refused this TV's sign-in")
            ws.sendText(JSONObject().put("id", 1).put("type", "get_panels").toString())
            while (true) {
                val message = JSONObject(ws.receiveText())
                if (message.optInt("id") != 1 || message.optString("type") != "result") continue
                if (!message.optBoolean("success")) throw IOException("Home Assistant couldn't list the dashboards")
                return parsePanels(message.optJSONObject("result") ?: JSONObject())
            }
        }
    }

    /** Turns a `get_panels` result into a sorted list, the default dashboard first. */
    fun parsePanels(result: JSONObject): List<Dashboard> {
        val dashboards = mutableListOf<Dashboard>()
        for (key in result.keys()) {
            val panel = result.optJSONObject(key) ?: continue
            val component = panel.optString("component_name")
            val builtIn = component in BUILT_IN
            if (component !in YOURS && !builtIn) continue
            if (panel.optBoolean("require_admin", false)) continue
            val path = panel.optString("url_path").ifEmpty { key }
            val title = if (panel.isNull("title")) "" else panel.optString("title")
            dashboards += Dashboard(
                title = when {
                    path == "lovelace" && (title.isEmpty() || title == "lovelace") -> "Overview"
                    // HA's own pages carry a translation key ("energy") rather than a title.
                    builtIn && (title.isEmpty() || title == component) -> BUILT_IN.getValue(component)
                    title.isNotEmpty() -> title
                    else -> path.replace('-', ' ').replaceFirstChar { it.uppercase() }
                },
                path = path,
                builtIn = builtIn,
            )
        }
        // The default dashboard, then yours, then HA's built-in pages; each group alphabetical.
        return dashboards.sortedWith(compareBy({ it.path != "lovelace" }, { it.builtIn }, { it.title.lowercase() }))
    }
}

/**
 * Just enough of a WebSocket client (RFC 6455) for a few small JSON messages: text frames,
 * fragments, ping/pong and close. ws:// and wss:// (system trust store).
 */
class MiniWebSocket private constructor(private val socket: Socket) : AutoCloseable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    // Voice sends audio from one thread while another reads (and answers pings): one writer at a time.
    private val writeLock = Any()

    fun sendText(text: String) = write(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /** A binary message (Assist audio: the handler id byte, then the audio). */
    fun sendBinary(bytes: ByteArray) = write(OPCODE_BINARY, bytes)

    private fun write(opcode: Int, payload: ByteArray) = synchronized(writeLock) {
        output.write(encodeFrame(opcode, payload))
        output.flush()
    }

    /** How long a read may wait before failing, in milliseconds. */
    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    /** The next complete text message. Answers pings; throws when the server closes. */
    fun receiveText(): String {
        val message = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame(input)
            when (frame.opcode) {
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    message.write(frame.payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("Message too large")
                    if (frame.fin) return String(message.toByteArray(), Charsets.UTF_8)
                }
                OPCODE_PING -> write(OPCODE_PONG, frame.payload)
                OPCODE_CLOSE -> throw IOException("Home Assistant closed the connection")
                else -> Unit // pong, binary: not used here
            }
        }
    }

    override fun close() {
        runCatching { write(OPCODE_CLOSE, ByteArray(0)) }
        runCatching { socket.close() }
    }

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        private const val MAX_MESSAGE = 4 * 1024 * 1024
        private val random = SecureRandom()

        @Throws(IOException::class)
        fun connect(url: String, timeoutMillis: Int = 8000): MiniWebSocket {
            val match = Regex("^(https?)://([^/:]+)(?::(\\d+))?(/.*)$").find(url)
                ?: throw IOException("Bad WebSocket address: $url")
            val (scheme, host, portText, path) = match.destructured
            val secure = scheme == "https"
            val port = portText.toIntOrNull() ?: if (secure) 443 else 80

            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), timeoutMillis)
            val socket = if (secure) SSLSocketFactory.getDefault().let {
                (it as SSLSocketFactory).createSocket(plain, host, port, true)
            } else plain
            socket.soTimeout = timeoutMillis

            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
            val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()

            val head = readHead(socket.getInputStream())
            if (!head.startsWith("HTTP/1.1 101")) {
                socket.close()
                throw IOException("Home Assistant didn't open a WebSocket (${head.lineSequence().first()})")
            }
            return MiniWebSocket(socket)
        }

        private fun readHead(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            while (!buffer.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw IOException("Connection closed during the handshake")
                buffer.write(b)
                if (buffer.size() > 16 * 1024) throw IOException("Handshake answer too large")
            }
            return buffer.toString("ISO-8859-1")
        }

        /** A client frame: always masked, as the protocol requires. */
        fun encodeFrame(opcode: Int, payload: ByteArray, mask: ByteArray = ByteArray(4).also { random.nextBytes(it) }): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            val length = payload.size
            when {
                length < 126 -> out.write(0x80 or length)
                length <= 0xFFFF -> {
                    out.write(0x80 or 126)
                    out.write(length ushr 8)
                    out.write(length and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) out.write(((length.toLong() ushr shift) and 0xFF).toInt())
                }
            }
            out.write(mask)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            return out.toByteArray()
        }

        /** Reads one frame, unmasking it if needed. */
        fun readFrame(input: InputStream): Frame {
            val b0 = readByte(input)
            val b1 = readByte(input)
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte(input).toLong() }
            }
            if (length > MAX_MESSAGE) throw IOException("Frame too large")
            val mask = if (masked) ByteArray(4) { readByte(input).toByte() } else null
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) throw IOException("Connection closed mid-frame")
                read += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return Frame(fin, opcode, payload)
        }

        private fun readByte(input: InputStream): Int {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed")
            return b
        }
    }
}
