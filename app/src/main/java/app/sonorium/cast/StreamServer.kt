package app.sonorium.cast

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * The phone's stream for the one speaker it casts to: `GET /stream.aac` answers the engine's mix as
 * AAC, paced to real time (a few seconds ahead at most). One listener at a time, as Chris asked: a new
 * connection (the speaker reconnecting) replaces the old one. Not a stream host for many devices.
 */
class StreamServer(
    /** Fills the buffer with the engine's next PCM (44100 Hz mono int16); returns the bytes written. */
    private val render: (ByteArray) -> Int,
    private val bind: InetAddress? = null,
) : AutoCloseable {
    private val socket = ServerSocket(0, 4, bind)
    @Volatile private var client: Socket? = null
    @Volatile private var closed = false

    val port: Int get() = socket.localPort

    /** Bytes of AAC sent to the current listener (for the device test and the status line). */
    @Volatile var sent = 0L
        private set

    init {
        thread(name = "sonorium-cast-accept", isDaemon = true) {
            while (!closed) {
                val s = try { socket.accept() } catch (_: SocketException) { break }
                client?.let { runCatching { it.close() } }
                client = s
                thread(name = "sonorium-cast-stream", isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true
            val path = requestPath(s.getInputStream())
            val out = s.getOutputStream()
            if (path == null || !path.startsWith(PATH)) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\nCache-Control: no-cache, no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            stream(s, out)
        } catch (_: Exception) {
            // The speaker went away or a new one replaced it
        } finally {
            runCatching { s.close() }
            if (client === s) client = null
        }
    }

    private fun stream(s: Socket, out: OutputStream) {
        AacEncoder().use { encoder ->
            val pcm = ByteArray(BLOCK_SAMPLES * 2)
            val started = System.nanoTime()
            var samples = 0L
            sent = 0
            while (!closed && client === s) {
                val ahead = samples / AacEncoder.SAMPLE_RATE.toDouble() - (System.nanoTime() - started) / 1e9
                if (ahead > LEAD_SECONDS) {
                    Thread.sleep(20)
                    continue
                }
                val n = render(pcm)
                samples += n / 2
                val aac = encoder.encode(pcm, 0, n)
                if (aac.isNotEmpty()) {
                    out.write(aac)
                    out.flush()
                    sent += aac.size
                }
            }
        }
    }

    /** The request line's path ("/stream.aac"), after reading the headers. */
    private fun requestPath(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n") && head.length < 8_192) {
            val c = input.read()
            if (c < 0) return null
            head.append(c.toChar())
        }
        return head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        client?.let { runCatching { it.close() } }
        client = null
    }

    companion object {
        const val PATH = "/stream.aac"
        /** How far ahead of real time the stream may run (the speaker's buffer fills from it). */
        const val LEAD_SECONDS = 3.0
        private const val BLOCK_SAMPLES = 4_096
    }
}
