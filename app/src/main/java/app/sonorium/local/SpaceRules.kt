package app.sonorium.local

import android.os.StatFs
import java.io.File

/** Free space before decoding or saving themes (the arithmetic is pure and unit tested). */
object SpaceRules {
    /** Kept free for the rest of the phone. */
    const val MARGIN = 100L * 1000 * 1000

    /**
     * About how much one audio file takes once decoded (16-bit mono 44100 Hz WAV): a WAV about its own
     * size or less, FLAC about twice, compressed formats (MP3, Ogg, AAC…) about six times.
     */
    fun decodedBytes(path: String, bytes: Long): Long = when (path.substringAfterLast('.', "").lowercase()) {
        "wav" -> bytes
        "flac" -> bytes * 2
        else -> bytes * 6
    }

    /** Whether [needed] bytes fit in [available] with [MARGIN] left. */
    fun enough(needed: Long, available: Long): Boolean = needed <= 0 || available - needed >= MARGIN

    /** The plain message when they don't. */
    fun notEnoughText(what: String, needed: Long): String =
        "Not enough free space on this phone $what (about ${sizeText(needed)} needed). Free up some space and try again."

    /** "1.2 GB" / "820 MB" / "5 KB". */
    fun sizeText(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.ROOT, b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(java.util.Locale.ROOT, b / 1e6)
        else -> "${(b + 999) / 1000} KB"
    }

    /** Bytes free where [dir] is (app storage); [Long.MAX_VALUE] when it can't be read. */
    fun available(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
}
