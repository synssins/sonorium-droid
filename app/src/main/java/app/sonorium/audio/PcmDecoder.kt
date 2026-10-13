package app.sonorium.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Engine prototype (shared/engine.md 5a): decodes a theme's audio file with MediaCodec into what
 * `sonorium.engine.PcmFileDecoder` reads: a 16-bit PCM WAV at 44100 Hz, mono or stereo as the file is
 * (more channels: the first two). Other rates are resampled linearly.
 */
object PcmDecoder {
    const val RATE = 44100
    private const val TIMEOUT_US = 10_000L

    data class Result(val samples: Long, val sourceRate: Int, val sourceChannels: Int)

    /** Decodes [input] to [output] (overwritten). Throws if the file has no audio track. */
    fun decodeToWav(input: File, output: File): Result =
        decode(MediaExtractor().apply { setDataSource(input.path) }, input.name, output)

    /** The same, reading a document (e.g. a file in the user's themes folder) through [context]. */
    fun decodeToWav(context: android.content.Context, input: android.net.Uri, output: File): Result =
        decode(MediaExtractor().apply { setDataSource(context, input, null) }, input.lastPathSegment.orEmpty(), output)

    private fun decode(extractor: MediaExtractor, name: String, output: File): Result {
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio in $name")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            output.parentFile?.mkdirs()
            RandomAccessFile(output, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44)) // header, written at the end
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                // A stereo (or more) file stays stereo; the engine centres a mono one or folds stereo as asked
                val writer = FrameWriter(out, if (channels >= 2) 2 else 1)
                var float = false
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                try {
                    while (!outputDone) {
                        if (!inputDone) {
                            val i = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (i >= 0) {
                                val buf = codec.getInputBuffer(i)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = codec.outputFormat
                                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            }
                            else -> if (o >= 0) {
                                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                writer.add(buf, channels, rate, float)
                                codec.releaseOutputBuffer(o, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            }
                        }
                    }
                } finally {
                    codec.stop()
                    codec.release()
                }
                writer.flush()
                writeHeader(out, writer.written, writer.outChannels)
                return Result(writer.written, rate, channels)
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Writes 16-bit frames at 44100 Hz with [outChannels] channels (1, or 2: a stereo file keeps its image,
     * its first two channels), resampling linearly, streaming (position carried between buffers).
     */
    private class FrameWriter(private val out: RandomAccessFile, val outChannels: Int) {
        /** Frames written. */
        var written = 0L
        private var pos = 0.0 // position of the next output frame, in input frames since `prev`
        private val prev = FloatArray(outChannels)
        private val cur = FloatArray(outChannels)
        private var havePrev = false
        private val pending = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

        fun add(buf: ByteBuffer, channels: Int, rate: Int, float: Boolean) {
            val step = rate.toDouble() / RATE
            val frameBytes = channels * if (float) 4 else 2
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
                if (!havePrev) {
                    cur.copyInto(prev)
                    havePrev = true
                    continue
                }
                // Output every frame whose position falls between prev (0) and cur (1)
                while (pos < 1.0) {
                    for (c in 0 until outChannels) emit(prev[c] + (cur[c] - prev[c]) * pos.toFloat())
                    written++
                    pos += step
                }
                pos -= 1.0
                cur.copyInto(prev)
            }
        }

        private fun emit(v: Float) {
            if (pending.remaining() < 2) drain()
            pending.putShort(v.coerceIn(-32768f, 32767f).toInt().toShort())
        }

        private fun drain() {
            out.write(pending.array(), 0, pending.position())
            pending.clear()
        }

        fun flush() {
            if (havePrev && pos < 1.0) {
                for (c in 0 until outChannels) emit(prev[c])
                written++
            }
            drain()
        }
    }

    private fun writeHeader(out: RandomAccessFile, frames: Long, channels: Int) {
        val data = frames * 2 * channels
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(RATE).putInt(RATE * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        out.seek(0)
        out.write(h.array())
    }
}
