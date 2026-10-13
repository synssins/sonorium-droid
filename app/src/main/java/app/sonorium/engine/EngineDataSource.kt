package app.sonorium.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** The engine's output as an endless WAV "file" for ExoPlayer (uri `sonorium-engine:`). */
@UnstableApi
class EngineDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var headerPos = 0
    private var openedAt = 0L
    private var renderedHere = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        header = PhoneEngine.wavHeader()
        headerPos = 0
        openedAt = android.os.SystemClock.elapsedRealtime()
        renderedHere = 0L
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headerPos < header.size) {
            val n = minOf(length, header.size - headerPos)
            System.arraycopy(header, headerPos, buffer, offset, n)
            headerPos += n
            bytesTransferred(n)
            return n
        }
        // Never more than LEAD_MS ahead of real time: the engine's clock (sequence steps, playlist entries)
        // is the audio it mixed, so an output that drains faster than real time can't speed it up
        val ahead = renderedHere * 1000 / BYTES_PER_SECOND - (android.os.SystemClock.elapsedRealtime() - openedAt)
        if (ahead > LEAD_MS) Thread.sleep(ahead - LEAD_MS)
        // Whole samples, at most ~0.1 s per call so a preset or volume change is heard soon
        val n = PhoneEngine.render(buffer, offset, minOf(length, 8192) and 1.inv())
        renderedHere += n
        served += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) transferEnded()
        uri = null
    }

    companion object {
        /** 16-bit mono at 44100 Hz. */
        private const val BYTES_PER_SECOND = 44_100L * 2
        /** How far the mix may run ahead of real time (the player's buffer is 1.5–3 s). */
        private const val LEAD_MS = 4_000L

        /** Engine audio handed to the player so far (the device test checks the player takes it). */
        @Volatile
        var served = 0L
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = EngineDataSource()
    }
}
