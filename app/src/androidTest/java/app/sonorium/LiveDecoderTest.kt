package app.sonorium

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sonorium.audio.LiveDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Theme files read live while mixing: the device's decoder, through a stand-in, at 44100 Hz in the mix's channels. */
@RunWith(AndroidJUnit4::class)
class LiveDecoderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "live-test").apply { deleteRecursively(); mkdirs() }

    init {
        LiveDecoder.init(context)
    }

    private fun readAll(path: String, channels: Int): ShortArray {
        val stream = LiveDecoder.open(path, channels)
        val out = java.io.ByteArrayOutputStream()
        while (true) out.write(stream.read() ?: break)
        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun mp3ThroughAStandIn() {
        val mp3 = File(dir, "bench.mp3")
        instrumentation.context.assets.open("bench-60s-320k.mp3").use { input -> mp3.outputStream().use { input.copyTo(it) } }
        // The prepared copy's stand-in names the file; the decoder reads the file
        val standIn = File(dir, "Rain.mp3-standin").apply { writeText(LiveDecoder.SOURCE_PREFIX + android.net.Uri.fromFile(mp3)) }
        val frames = readAll(standIn.path, 2).size / 2
        assertTrue("about 60 s at 44.1 kHz, got $frames frames", abs(frames - 60 * 44_100) < 44_100 / 2)
        val seconds = LiveDecoder.durationSeconds(standIn.path)
        assertTrue("header length $seconds", abs(seconds - 60) < 1)
    }

    @Test
    fun mono48kResampledAndCentred() {
        val wav = File(dir, "tone.wav")
        writeWav(wav, rate = 48_000, channels = 1, seconds = 2.0, hz = 1_000.0)
        val s = readAll(wav.path, 2)
        val frames = s.size / 2
        assertTrue("about 2 s at 44.1 kHz, got $frames frames", abs(frames - 88_200) < 600)
        // Centred: both sides the same
        for (i in 0 until frames step 997) assertEquals(s[2 * i], s[2 * i + 1])
        // Still 1 kHz: rising zero crossings over the middle second
        var crossings = 0
        for (i in 22_051 until 66_150) if (s[2 * (i - 1)] < 0 && s[2 * i] >= 0) crossings++
        assertTrue("about 1000 Hz, got $crossings", abs(crossings - 1_000) <= 5)
        // A mono mix of it: one channel
        assertTrue(abs(readAll(wav.path, 1).size - 88_200) < 600)
    }

    private fun writeWav(file: File, rate: Int, channels: Int, seconds: Double, hz: Double) {
        val n = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(n * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (sin(2 * Math.PI * hz * i / rate) * 12_000).toInt().toShort()
            repeat(channels) { data.putShort(v) }
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        file.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }
}
