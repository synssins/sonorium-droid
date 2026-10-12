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

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        header = PhoneEngine.wavHeader()
        headerPos = 0
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
        // Whole samples, at most ~0.1 s per call so a preset or volume change is heard soon
        val n = PhoneEngine.render(buffer, offset, minOf(length, 8192) and 1.inv())
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
        /** Engine audio handed to the player so far (the device test checks the player takes it). */
        @Volatile
        var served = 0L
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = EngineDataSource()
    }
}
