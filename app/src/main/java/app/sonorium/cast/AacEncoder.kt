package app.sonorium.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream

/**
 * The engine's 44100 Hz mono 16-bit PCM to AAC-LC in ADTS frames (what a cast speaker plays as an
 * `audio/aac` stream). One encoder per stream; not thread-safe.
 */
class AacEncoder(bitrate: Int = BITRATE) : AutoCloseable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** Encodes [length] bytes of PCM from [pcm]; returns the ADTS frames ready so far. */
    fun encode(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val n = minOf(buffer.remaining(), end - pos) and 1.inv()
                buffer.put(pcm, pos, n)
                codec.queueInputBuffer(index, 0, n, presentationUs, 0)
                presentationUs += n / 2 * 1_000_000L / SAMPLE_RATE
                pos += n
            }
            drain(out)
        }
        drain(out)
        return out.toByteArray()
    }

    private fun drain(out: ByteArrayOutputStream) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val frame = ByteArray(info.size).also(buffer::get)
                out.write(adtsHeader(frame.size))
                out.write(frame)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 1
        const val BITRATE = 128_000

        /** The 7-byte ADTS header for one AAC-LC frame of [length] bytes (44100 Hz, mono). */
        fun adtsHeader(length: Int): ByteArray {
            val full = length + 7
            val profile = 2 // AAC LC
            val freqIndex = 4 // 44100 Hz
            val channels = CHANNELS
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, no CRC
                (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (full shr 11)).toByte(),
                ((full shr 3) and 0xFF).toByte(),
                (((full and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }
    }
}
