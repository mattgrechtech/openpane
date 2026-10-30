package io.github.mattgrechtech.tvkiosk.api

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * A deliberately small HTTP/1.1 server for a LAN control API: one request per connection,
 * Content-Length bodies only (no chunked uploads), bounded headers and bodies, a time limit for
 * the whole request (so a client trickling bytes can't hold a worker), at most three connections
 * per address, and a handful of worker threads. No Android dependencies, so it is unit-tested on
 * the JVM.
 */
class HttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
    private val bindAddress: InetAddress? = null,
    private val maxBodyBytes: Int = 4 * 1024 * 1024,
) {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        /** Header names in lower case. */
        val headers: Map<String, String>,
        val body: ByteArray,
        val remoteAddress: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    ) {
        constructor(status: Int, text: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()) :
            this(status, text.toByteArray(Charsets.UTF_8), contentType, headers)
    }

    class BadRequest(val status: Int, message: String) : IOException(message)

    @Volatile private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = ThreadPoolExecutor(
        1, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue(),
    )
    /** Open connections per client address. */
    private val perAddress = mutableMapOf<String, Int>()

    val isRunning get() = socket?.isClosed == false

    /** The port actually bound (useful when started with port 0). */
    val localPort get() = socket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port), 16)
        socket = server
        acceptThread = Thread({ acceptLoop(server) }, "http-$port").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // Closing anyway.
        }
        socket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            val address = client.inetAddress?.hostAddress ?: ""
            val admitted = synchronized(perAddress) {
                val open = perAddress[address] ?: 0
                if (open >= MAX_PER_ADDRESS) false else true.also { perAddress[address] = open + 1 }
            }
            if (!admitted) {
                refuse(client)
                continue
            }
            try {
                workers.execute {
                    try {
                        serve(client)
                    } finally {
                        release(address)
                    }
                }
            } catch (e: RejectedExecutionException) {
                release(address)
                // All workers busy: refuse rather than queue without limit.
                refuse(client)
            }
        }
    }

    private fun release(address: String) = synchronized(perAddress) {
        val open = (perAddress[address] ?: 1) - 1
        if (open <= 0) perAddress.remove(address) else perAddress[address] = open
    }

    private fun refuse(client: Socket) {
        runCatching {
            client.soTimeout = 2000
            write(client.getOutputStream(), Response(503, """{"ok":false,"error":"busy"}"""))
        }
        runCatching { client.close() }
    }

    private fun serve(client: Socket) {
        client.use {
            it.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = it.getOutputStream()
            val response = try {
                val input = DeadlineInputStream(BufferedInputStream(it.getInputStream()), it, System.currentTimeMillis() + REQUEST_DEADLINE_MILLIS)
                val request = read(input, it.inetAddress?.hostAddress ?: "", maxBodyBytes)
                try {
                    handler(request)
                } catch (e: Exception) {
                    Response(500, """{"ok":false,"error":"internal error"}""")
                }
            } catch (e: BadRequest) {
                Response(e.status, """{"ok":false,"error":"${e.message}"}""")
            } catch (e: IOException) {
                return // Timed out or the client went away.
            }
            runCatching { write(out, response) }
        }
    }

    companion object {
        private const val MAX_WORKERS = 4
        private const val MAX_PER_ADDRESS = 3
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        /** The whole request (headers and body) must arrive within this time. */
        private const val REQUEST_DEADLINE_MILLIS = 10_000L
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** Parses one request. Throws [BadRequest] for anything it won't handle. */
        @Throws(IOException::class)
        fun read(input: InputStream, remoteAddress: String, maxBodyBytes: Int = 4 * 1024 * 1024): Request {
            val head = readHead(input)
            val lines = head.split("\r\n")
            val requestLine = lines.first().split(" ")
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) throw BadRequest(400, "bad request line")
            val method = requestLine[0].uppercase(Locale.ROOT)
            val target = requestLine[1]

            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) throw BadRequest(400, "bad header")
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }

            if (headers["transfer-encoding"] != null) throw BadRequest(411, "send a Content-Length body")
            val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw BadRequest(400, "bad Content-Length") } ?: 0
            if (length < 0) throw BadRequest(400, "bad Content-Length")
            if (length > maxBodyBytes) throw BadRequest(413, "body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw IOException("body ended early")
                read += n
            }

            val path = decode(target.substringBefore('?'))
            val query = parseQuery(target.substringAfter('?', ""))
            return Request(method, path, query, headers, body, remoteAddress)
        }

        private fun readHead(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            var matched = 0 // progress through "\r\n\r\n"
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed")
                buffer.write(b)
                if (buffer.size() > MAX_HEADER_BYTES) throw BadRequest(431, "headers too large")
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            return String(buffer.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        }

        fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            return query.split('&').filter { it.isNotEmpty() }.associate {
                decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
            }
        }

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            throw BadRequest(400, "bad encoding")
        }

        fun write(out: OutputStream, response: Response) {
            val head = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                append("Content-Type: ").append(response.contentType).append("; charset=utf-8\r\n")
                append("Content-Length: ").append(response.body.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                for ((name, value) in response.headers) append(name).append(": ").append(value).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            421 -> "Misdirected Request"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status"
        }
    }
}

/** Fails reads once [deadline] (wall clock) has passed, and never waits for data beyond it. */
private class DeadlineInputStream(
    private val inner: InputStream,
    private val socket: Socket,
    private val deadline: Long,
) : InputStream() {
    private fun arm() {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) throw java.net.SocketTimeoutException("request took too long")
        socket.soTimeout = remaining.toInt().coerceAtLeast(1)
    }

    override fun read(): Int {
        arm()
        return inner.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        arm()
        return inner.read(b, off, len)
    }

    override fun available() = inner.available()
}
