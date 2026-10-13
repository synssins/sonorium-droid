package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.audio.PcmDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** MediaCodec turns a 48 kHz stereo file into the engine's 44.1 kHz stereo WAV (a stereo file stays stereo). */
@RunWith(AndroidJUnit4::class)
class PcmDecoderTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pcm-test").apply { mkdirs() }

    @Test
    fun resamplesAndDownmixes() {
        val input = File(dir, "tone.wav")
        writeWav(input, rate = 48_000, channels = 2, seconds = 2.0, hz = 1_000.0)
        val output = File(dir, "tone-out.wav")
        val r = PcmDecoder.decodeToWav(input, output)

        assertEquals(48_000, r.sourceRate)
        val bytes = output.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(2, b.getShort(22).toInt()) // stereo kept
        assertEquals(44_100, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        val n = b.getInt(40) / 4
        assertTrue("about 2 s at 44.1 kHz, got $n frames", abs(n - 88_200) < 600)

        // Still a 1 kHz tone on the left channel: count rising zero crossings over the middle second
        val s = ShortArray(n) { b.getShort(44 + it * 4) }
        var crossings = 0
        for (i in 22_050 + 1 until 66_150) if (s[i - 1] < 0 && s[i] >= 0) crossings++
        assertTrue("about 1000 Hz, got $crossings", abs(crossings - 1_000) <= 5)
    }

    private fun writeWav(f: File, rate: Int, channels: Int, seconds: Double, hz: Double) {
        val frames = (rate * seconds).toInt()
        val data = frames * channels * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * 12_000).toInt().toShort()
            repeat(channels) { b.putShort(v) }
        }
        f.writeBytes(b.array())
    }
}
