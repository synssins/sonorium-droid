package app.sonorium

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: whether the phone can decode MP3 live while mixing (no ready-to-play WAV copy). Decodes a
 * generated 60 s 320 kbps 44.1 kHz stereo MP3 (androidTest asset, tones and noise) with MediaCodec, once
 * and 5, 10 and 20 at the same time, and logs wall time, process CPU time and CPU seconds per hour of
 * playback (tag Mp3Benchmark, and the instrumentation output). Whatever happens is reported; it never fails.
 */
@RunWith(AndroidJUnit4::class)
class Mp3Benchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val seconds = 60

    @Test
    fun decodeCost() {
        try {
            val file = File(instrumentation.targetContext.cacheDir, "bench.mp3")
            instrumentation.context.assets.open("bench-60s-320k.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
            // Once to warm the codec up (not counted)
            decode(file)
            for (streams in listOf(1, 5, 10, 20)) measure(file, streams)
            file.delete()
        } catch (e: Throwable) {
            report("benchmark failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** [streams] decodes of the file at the same time. */
    private fun measure(file: File, streams: Int) {
        val bytes = AtomicLong()
        val failures = AtomicLong()
        val cpu0 = Process.getElapsedCpuTime()
        val t0 = SystemClock.elapsedRealtime()
        val threads = (1..streams).map {
            Thread {
                val n = runCatching { decode(file) }.getOrElse { e ->
                    if (failures.incrementAndGet() == 1L) report("$streams streams: a decode failed: ${e.javaClass.simpleName}: ${e.message}")
                    0L
                }
                bytes.addAndGet(n)
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val wall = SystemClock.elapsedRealtime() - t0
        val cpu = Process.getElapsedCpuTime() - cpu0
        // CPU seconds for an hour of playback of this many streams: (cpu ms for 60 s of audio) × 60 / 1000
        val perHour = cpu * (3600.0 / seconds) / 1000
        report(
            "$streams stream(s) × 60 s 320 kbps stereo: wall $wall ms, process cpu $cpu ms; " +
                "cpu per playback hour ≈ ${"%.1f".format(perHour)} s (${"%.1f".format(perHour / streams)} s per stream); " +
                "${bytes.get() / 1024} KiB PCM out" + if (failures.get() > 0) ", ${failures.get()} failed" else "",
        )
    }

    /** Decodes the whole file; the PCM bytes out. */
    private fun decode(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var total = 0L
                var inDone = false
                while (true) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        total += info.size
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return total
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun report(text: String) {
        Log.w("Mp3Benchmark", text)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Mp3Benchmark: $text\n") })
    }
}
