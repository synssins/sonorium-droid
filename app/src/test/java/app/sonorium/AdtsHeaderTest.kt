package app.sonorium

import app.sonorium.cast.AacEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class AdtsHeaderTest {
    @Test fun headerForAMonoLcFrame() {
        val h = AacEncoder.adtsHeader(371)
        assertEquals(7, h.size)
        assertEquals(0xFFF1, ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF))
        val b2 = h[2].toInt() and 0xFF
        assertEquals(1, b2 shr 6) // profile LC (2) - 1
        assertEquals(4, (b2 shr 2) and 0xF) // 44100 Hz
        val channels = ((b2 and 1) shl 2) or ((h[3].toInt() and 0xFF) shr 6)
        assertEquals(1, channels)
        val length = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        assertEquals(378, length)
    }
}
