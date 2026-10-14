package app.sonorium.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client id the app gives a demo Sonorium for its virtual speaker: 8-64 of A-Z a-z 0-9 . _ : - */
class VirtualSpeakerPlayerTest {
    private val allowed = Regex("^[A-Za-z0-9._:-]{8,64}$")

    @Test
    fun clientIdsAreValid() {
        assertEquals("app-7f3a9c2e", VirtualSpeakerPlayer.clientId("7f3a9c2e"))
        assertTrue(allowed.matches(VirtualSpeakerPlayer.clientId("a")))
        assertTrue(allowed.matches(VirtualSpeakerPlayer.clientId("x/y z?" + "q".repeat(100))))
    }
}
