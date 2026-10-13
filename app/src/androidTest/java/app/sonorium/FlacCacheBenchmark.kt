package app.sonorium

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Not a check: measures whether a FLAC audio cache would pay on a phone (MediaCodec "audio/flac" encode
 * and decode of one minute of 44.1 kHz 16-bit stereo) against plain WAV (no work to read). The numbers go
 * to logcat (tag FlacBenchmark) and the instrumentation output.
 */
@RunWith(AndroidJUnit4::class)
class FlacCacheBenchmark {
    private val seconds = 60
    private val rate = 44_100
    private val channels = 2

    @Test
    fun flacVersusWav() {
        // A measurement only: whatever happens on a device is reported, never a failed run
        try {
            measure()
        } catch (e: Throwable) {
            report("benchmark failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun measure() {
        val hasEncoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_FLAC, true) } }
        if (!hasEncoder) {
            report("no FLAC encoder on this device")
            return
        }
        // One minute of a two-tone signal with a little noise (music-like for the compressor)
        val frames = seconds * rate
        val pcm = ByteArray(frames * channels * 2)
        val rnd = java.util.Random(1)
        for (i in 0 until frames) {
            val l = (8000 * sin(2 * Math.PI * 220 * i / rate) + 3000 * sin(2 * Math.PI * 1320 * i / rate) + rnd.nextGaussian() * 200).toInt()
            val r = (8000 * sin(2 * Math.PI * 330 * i / rate) + 3000 * sin(2 * Math.PI * 990 * i / rate) + rnd.nextGaussian() * 200).toInt()
            put(pcm, i * 4, l)
            put(pcm, i * 4 + 2, r)
        }

        val cpu0 = Debug.threadCpuTimeNanos()
        val t0 = SystemClock.elapsedRealtime()
        val flac = encode(pcm)
        val encodeMs = SystemClock.elapsedRealtime() - t0
        val encodeCpu = (Debug.threadCpuTimeNanos() - cpu0) / 1_000_000

        val cpu1 = Debug.threadCpuTimeNanos()
        val t1 = SystemClock.elapsedRealtime()
        val decodedBytes = decode(flac)
        val decodeMs = SystemClock.elapsedRealtime() - t1
        val decodeCpu = (Debug.threadCpuTimeNanos() - cpu1) / 1_000_000

        report(
            "60 s stereo: WAV ${pcm.size / 1024} KiB, FLAC ${flac.size / 1024} KiB (${100 * flac.size / pcm.size}%); " +
                "encode ${encodeMs} ms (cpu ${encodeCpu} ms); decode ${decodeMs} ms (cpu ${decodeCpu} ms), ${decodedBytes / 1024} KiB out; " +
                "decode cost per hour of playback ≈ ${decodeCpu * 60 / 1000} s cpu",
        )
    }

    private fun put(b: ByteArray, at: Int, v: Int) {
        val s = v.coerceIn(-32768, 32767)
        b[at] = s.toByte()
        b[at + 1] = (s shr 8).toByte()
    }

    private fun encode(pcm: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inDone = false
        var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = minOf(buf.remaining(), pcm.size - pos)
                    if (n <= 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        buf.put(pcm, pos, n)
                        codec.queueInputBuffer(i, 0, n, pos.toLong() * 1_000_000 / (rate * channels * 2), 0)
                        pos += n
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                val chunk = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(chunk)
                out.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop()
        codec.release()
        return out.toByteArray()
    }

    /** Decodes the encoder's raw FLAC frames back (the stream header from the encoder's first buffer). */
    private fun decode(flac: ByteArray): Long {
        val file = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bench.flac")
        file.writeBytes(flac)
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var total = 0L
            var inDone = false
            var outDone = false
            while (!outDone) {
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop()
            codec.release()
            total
        } catch (e: Exception) {
            report("FLAC decode failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        } finally {
            extractor.release()
            file.delete()
        }
    }

    private fun report(text: String) {
        Log.w("FlacBenchmark", text)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "FlacBenchmark: $text\n") })
    }
}
