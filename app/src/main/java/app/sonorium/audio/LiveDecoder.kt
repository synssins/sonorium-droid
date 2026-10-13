package app.sonorium.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A theme's audio read live while the engine mixes (no ready-to-play copy): the platform's decoder
 * (MediaCodec), streaming, as 16-bit 44100 Hz PCM with 1 or 2 channels (a mono file centred in a stereo
 * mix, a stereo one folded for a mono mix; more channels: the first two; other rates resampled linearly).
 * The engine's Python decoder (phone.py LiveDecoder) calls [open] and pulls [Stream.read] chunks.
 *
 * A source is a file path, or a stand-in file whose text is [SOURCE_PREFIX] + a document URI (the file in
 * the user's themes folder; see [StandIns]).
 */
object LiveDecoder {
    const val RATE = 44100
    /** The text a stand-in starts with, then the URI of the file it stands for. */
    const val SOURCE_PREFIX = "sonorium-src:"
    private const val TIMEOUT_US = 5_000L
    /** About half a second of stereo audio per chunk handed to Python. */
    private const val CHUNK_BYTES = 88_200

    @Volatile private var appContext: Context? = null

    /** Streams open now, and the most open at once since [resetCounts] (measurements). */
    val openNow = java.util.concurrent.atomic.AtomicInteger()
    val openMax = java.util.concurrent.atomic.AtomicInteger()

    fun resetCounts() = openMax.set(openNow.get())

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** What [path] stands for: a document URI written in a stand-in, else the path itself. */
    @JvmStatic
    fun sourceOf(path: String): String {
        val f = File(path)
        if (f.isFile && f.length() in 1..4096) {
            val head = runCatching { f.readText() }.getOrNull()
            if (head != null && head.startsWith(SOURCE_PREFIX)) return head.removePrefix(SOURCE_PREFIX).trim()
        }
        return path
    }

    private fun extractor(source: String): MediaExtractor = MediaExtractor().apply {
        if (source.startsWith("content:") || source.startsWith("file:")) {
            setDataSource(appContext ?: throw IllegalStateException("LiveDecoder not initialised"), Uri.parse(source), null)
        } else {
            setDataSource(source)
        }
    }

    /** Opens [path] (a file or a stand-in) for a mix of [channels] (1 or 2). Throws when it has no audio. */
    @JvmStatic
    fun open(path: String, channels: Int): Stream = Stream(extractor(sourceOf(path)), path, if (channels >= 2) 2 else 1)

    /** The file's length in seconds from its header, or -1 when it doesn't say. */
    @JvmStatic
    fun durationSeconds(path: String): Double {
        val ex = runCatching { extractor(sourceOf(path)) }.getOrNull() ?: return -1.0
        return try {
            val track = audioTrack(ex) ?: return -1.0
            val f = ex.getTrackFormat(track)
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else -1.0
        } catch (e: Exception) {
            -1.0
        } finally {
            ex.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int? = (0 until ex.trackCount).firstOrNull {
        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    /** One file being read, start to end. Not thread-safe: one reader. */
    class Stream internal constructor(private val ex: MediaExtractor, name: String, private val outChannels: Int) {
        private val codec: MediaCodec
        private var rate: Int
        private var channels: Int
        private var float = false
        private var inputDone = false
        private var outputDone = false
        private var closed = false
        private val info = MediaCodec.BufferInfo()
        private var out = ByteArray(CHUNK_BYTES + 65_536)
        private var size = 0
        /** Frames in the header (at 44100 Hz), or -1. */
        val headerFrames: Long

        // Linear resampling, carried across buffers
        private var pos = 0.0
        private val prev = FloatArray(outChannels)
        private val cur = FloatArray(outChannels)
        private var havePrev = false

        init {
            val track = try {
                audioTrack(ex) ?: throw IllegalArgumentException("No audio in $name")
            } catch (e: Exception) {
                ex.release()
                throw e
            }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            headerFrames = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) * RATE / 1_000_000 else -1
            codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                ex.release()
                throw e
            }
            openMax.accumulateAndGet(openNow.incrementAndGet(), ::maxOf)
        }

        /** The next chunk of interleaved little-endian int16 frames, or null at the end. */
        fun read(): ByteArray? {
            if (closed) return null
            while (size < CHUNK_BYTES && !outputDone) step()
            if (outputDone && havePrev && pos < 1.0) {
                // The last frame
                for (c in 0 until outChannels) emit(prev[c])
                havePrev = false
            }
            if (size == 0) {
                close()
                return null
            }
            val bytes = out.copyOf(size)
            size = 0
            return bytes
        }

        private fun step() {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            when (val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                else -> if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    add(buf)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        private fun add(buf: ByteBuffer) {
            val step = rate.toDouble() / RATE
            val frameBytes = channels * if (float) 4 else 2
            val same = rate == RATE
            while (buf.remaining() >= frameBytes) {
                var sum = 0f
                for (c in 0 until channels) {
                    val v = if (float) buf.float * 32767f else buf.short.toFloat()
                    sum += v
                    if (outChannels == 2) {
                        if (c < 2) cur[c] = v
                        if (channels == 1) cur[1] = v
                    }
                }
                if (outChannels == 1) cur[0] = sum / channels
                if (same) {
                    // No resampling: each frame as it is
                    for (c in 0 until outChannels) emit(cur[c])
                    continue
                }
                if (!havePrev) {
                    cur.copyInto(prev)
                    havePrev = true
                    continue
                }
                while (pos < 1.0) {
                    for (c in 0 until outChannels) emit(prev[c] + (cur[c] - prev[c]) * pos.toFloat())
                    pos += step
                }
                pos -= 1.0
                cur.copyInto(prev)
            }
        }

        private fun emit(v: Float) {
            if (size + 2 > out.size) out = out.copyOf(out.size * 2)
            val s = v.coerceIn(-32768f, 32767f).toInt()
            out[size++] = s.toByte()
            out[size++] = (s shr 8).toByte()
        }

        fun close() {
            if (closed) return
            closed = true
            openNow.decrementAndGet()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { ex.release() }
        }
    }
}
