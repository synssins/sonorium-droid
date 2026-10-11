package app.sonorium.web

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The web UI's only way to the server (APP_API.md 12.3): an HTTP server on
 * 127.0.0.1 that forwards to the linked server. API and page requests go over
 * the pinned HTTPS client with the phone's token; `/stream/...` goes to the
 * server's plain HTTP port. The WebView never talks to the server itself.
 *
 * Other apps on the phone can reach loopback too, so every request must carry
 * the per-launch [secret] in the `sonorium_app` cookie.
 */
class LoopbackProxy(
    private val api: OkHttpClient,
    private val apiBase: HttpUrl,
    private val streams: OkHttpClient,
    private val streamBase: HttpUrl,
    private val token: String,
    val secret: String,
    /** Called once for a 401 `{"pair": true}`: the token is no longer valid. */
    private val onUnlinked: () -> Unit = {},
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "loopback-proxy").apply { isDaemon = true }
    }

    val port: Int get() = server.localPort
    val origin: String get() = "http://127.0.0.1:$port"

    fun start() {
        workers.execute {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                workers.execute { serve(socket) }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        // One request per connection keeps this simple; WebView reconnects as needed.
        val request = try {
            readRequest(input)
        } catch (_: IOException) {
            return
        } ?: return
        try {
            handle(request, output)
        } catch (_: IOException) {
            // The page went away mid-response (e.g. a stream was stopped).
        }
    }

    private fun handle(request: IncomingRequest, output: OutputStream) {
        if (!hasSecret(request)) {
            respond(output, 403, "text/plain", "Forbidden".toByteArray())
            return
        }
        val isStream = request.path.startsWith("/stream/")
        val base = if (isStream) streamBase else apiBase
        val target = base.takeIf { request.path.startsWith("/") }?.resolve(request.path)
            ?: run {
                respond(output, 400, "text/plain", "Bad path".toByteArray())
                return
            }

        val headers = Headers.Builder()
        request.headers.forEach { (name, value) -> if (name.lowercase() in FORWARDED_REQUEST_HEADERS) headers.add(name, value) }
        if (!isStream) headers.set("Authorization", "Bearer $token")

        val body = request.body?.toRequestBody(request.header("Content-Type")?.toMediaTypeOrNull())
            ?: if (request.method in BODY_METHODS) ByteArray(0).toRequestBody(null) else null
        val upstream = Request.Builder().url(target).headers(headers.build()).method(request.method, body).build()

        val client = if (isStream) streams else api
        val response = try {
            client.newCall(upstream).execute()
        } catch (_: IOException) {
            respond(output, 502, "application/json", """{"detail":"Can't reach the server"}""".toByteArray())
            return
        }
        response.use {
            val responseBody = it.body
            if (it.code == 401 && !isStream) {
                // Check for the relink flag without consuming the stream for the page.
                val bytes = responseBody.bytes()
                if (PAIR_FLAG.containsMatchIn(String(bytes, Charsets.UTF_8))) onUnlinked()
                writeHead(output, it.code, it.message, it.headers, bytes.size.toLong())
                output.write(bytes)
                output.flush()
                return
            }
            val length = responseBody.contentLength()
            writeHead(output, it.code, it.message, it.headers, length.takeIf { len -> len >= 0 })
            if (request.method != "HEAD") {
                responseBody.byteStream().use { source -> copyStreaming(source, output) }
            }
        }
    }

    private fun hasSecret(request: IncomingRequest): Boolean {
        val cookie = request.header("Cookie") ?: return false
        val value = cookie.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=') ?: return false
        return MessageDigest.isEqual(value.toByteArray(), secret.toByteArray())
    }

    private fun writeHead(output: OutputStream, code: Int, message: String, headers: Headers, length: Long?) {
        val sb = StringBuilder("HTTP/1.1 $code ${message.ifEmpty { "Status" }}\r\n")
        for ((name, value) in headers) {
            if (name.lowercase() in DROPPED_RESPONSE_HEADERS) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code Status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    /** Copies and flushes as data arrives, so live streams and event streams aren't held back. */
    private fun copyStreaming(source: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    internal class IncomingRequest(
        val method: String,
        val path: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
    ) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    companion object {
        const val COOKIE = "sonorium_app"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private val PAIR_FLAG = Regex("\"pair\"\\s*:\\s*true")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /** What the page may send on; cookies, Origin and Referer stay on the phone. */
        private val FORWARDED_REQUEST_HEADERS = setOf(
            "accept", "accept-language", "content-type", "range", "if-none-match", "if-modified-since",
            "last-event-id", "cache-control",
        )

        /** The proxy sets its own framing; the server's cookies are for browsers. */
        private val DROPPED_RESPONSE_HEADERS = setOf(
            "content-length", "transfer-encoding", "connection", "keep-alive", "set-cookie",
            "content-encoding", "strict-transport-security",
        )

        internal fun readRequest(input: InputStream): IncomingRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) throw IOException("Bad request line")
            val headers = mutableListOf<Pair<String, String>>()
            var total = requestLine.length
            while (true) {
                val line = readLine(input) ?: throw IOException("Unexpected end of headers")
                if (line.isEmpty()) break
                total += line.length
                if (total > MAX_HEADER_BYTES) throw IOException("Headers too large")
                val colon = line.indexOf(':')
                if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            val request = IncomingRequest(parts[0].uppercase(), parts[1], headers, null)
            val body = when {
                request.header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                request.header("Content-Length") != null -> {
                    val length = request.header("Content-Length")!!.toInt()
                    readExactly(input, length)
                }
                else -> null
            }
            return IncomingRequest(request.method, request.path, headers, body)
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > MAX_HEADER_BYTES) throw IOException("Line too long")
            }
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw IOException("Body ended early")
                offset += read
            }
            return bytes
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toInt(16) ?: throw IOException("Bad chunk")
                if (size == 0) {
                    while (readLine(input)?.isNotEmpty() == true) Unit
                    return out.toByteArray()
                }
                out.write(readExactly(input, size))
                readLine(input)
            }
        }
    }
}
