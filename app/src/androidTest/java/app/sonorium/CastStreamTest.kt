package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sonorium.cast.StreamServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The cast stream: a sine through the phone's AAC encoder, served over HTTP as ADTS frames. */
@RunWith(AndroidJUnit4::class)
class CastStreamTest {
    @Test
    fun servesAacFramesAtRealTime() {
        var phase = 0.0
        val server = StreamServer(render = { buf ->
            val s = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (s.hasRemaining()) {
                s.put((Math.sin(phase) * 12_000).toInt().toShort())
                phase += 2 * Math.PI * 440 / 44_100
            }
            buf.size
        }, bind = InetAddress.getByName("127.0.0.1"))
        server.use {
            val conn = URL("http://127.0.0.1:${server.port}${StreamServer.PATH}").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("audio/aac", conn.contentType)
            val input = conn.inputStream
            val start = System.currentTimeMillis()
            val data = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8_192)
            while (System.currentTimeMillis() - start < 2_000) {
                val n = input.read(buf)
                if (n < 0) break
                data.write(buf, 0, n)
            }
            conn.disconnect()
            val bytes = data.toByteArray()
            // About 3 s ahead plus 2 s of real time at 128 kbit/s: well over 30 KB, and not runaway
            assertTrue("Only ${bytes.size} bytes in 2 s", bytes.size > 30_000)
            assertTrue("Ran ahead: ${bytes.size} bytes in 2 s", bytes.size < 200_000)
            // Walk the ADTS frames: each starts with the sync word and its length leads to the next
            var pos = 0
            var frames = 0
            while (pos + 7 <= bytes.size) {
                assertEquals("No ADTS sync at $pos", 0xFFF, ((bytes[pos].toInt() and 0xFF) shl 4) or ((bytes[pos + 1].toInt() and 0xF0) shr 4))
                val len = ((bytes[pos + 3].toInt() and 3) shl 11) or ((bytes[pos + 4].toInt() and 0xFF) shl 3) or ((bytes[pos + 5].toInt() and 0xFF) shr 5)
                assertTrue("Bad frame length $len", len > 7)
                pos += len
                frames++
            }
            assertTrue("Only $frames frames", frames > 100)
        }
    }
}
