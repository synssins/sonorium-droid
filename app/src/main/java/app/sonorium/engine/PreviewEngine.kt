package app.sonorium.engine

import android.content.Context
import com.chaquo.python.PyObject
import okio.Buffer
import okio.Source
import okio.Timeout

/**
 * A theme played for a preview (the Themes page's ▶, the Theme Editor's Preview mix) on this device's
 * own entry: a second engine beside the channel's, its mix as an endless WAV stream. One at a time.
 */
object PreviewEngine {
    private val lock = Any()
    private var player: PyObject? = null
    private var generation = 0

    /** Opens a preview of a prepared theme (closing the one before); returns the stream to read. */
    fun open(context: Context, prepared: ThemePrep.Prepared): Source = synchronized(lock) {
        player?.let { old -> player = null; runCatching { old.callAttr("close") } }
        val p = PhoneEngine.module(context, "phone").callAttr("Player", prepared.themeDir.path, prepared.pcmDir.path, null, 1.0, System.nanoTime() and 0x7fffffff, null)
        player = p
        generation++
        stream(generation)
    }

    private fun stream(mine: Int): Source = object : Source {
        private var header: ByteArray? = PhoneEngine.wavHeader()
        private val chunk = ByteArray(8192)

        private val openedAt = android.os.SystemClock.elapsedRealtime()
        private var rendered = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            header?.let { h -> header = null; sink.write(h); return h.size.toLong() }
            // Not more than a few seconds ahead of real time (a preview that drains fast stays in step)
            val ahead = rendered * 1000 / (44_100L * 2) - (android.os.SystemClock.elapsedRealtime() - openedAt)
            if (ahead > 4_000) Thread.sleep(ahead - 4_000)
            val n = synchronized(lock) {
                val p = player?.takeIf { generation == mine } ?: return -1
                val samples = (minOf(byteCount, chunk.size.toLong()).toInt() / 2).coerceAtLeast(1)
                val bytes = p.callAttr("render", samples).toJava(ByteArray::class.java)
                System.arraycopy(bytes, 0, chunk, 0, minOf(bytes.size, chunk.size))
                minOf(bytes.size, chunk.size)
            }
            sink.write(chunk, 0, n)
            rendered += n
            return n.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = synchronized(lock) {
            if (generation == mine) {
                player?.let { runCatching { it.callAttr("close") } }
                player = null
            }
        }
    }
}
