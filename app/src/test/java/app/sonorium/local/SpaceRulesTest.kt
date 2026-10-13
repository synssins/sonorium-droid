package app.sonorium.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceRulesTest {
    @Test fun decodedSizes() {
        assertEquals(1_000L, SpaceRules.decodedBytes("Low/drone.WAV", 1_000))
        assertEquals(2_000L, SpaceRules.decodedBytes("a.flac", 1_000))
        assertEquals(12_000L, SpaceRules.decodedBytes("a.mp3", 1_000))
        assertEquals(12_000L, SpaceRules.decodedBytes("a.ogg", 1_000))
    }

    @Test fun enoughKeepsAMargin() {
        val gb = 1_000_000_000L
        assertTrue(SpaceRules.enough(gb, 2 * gb))
        assertFalse(SpaceRules.enough(gb, gb + SpaceRules.MARGIN - 1))
        assertTrue(SpaceRules.enough(gb, gb + SpaceRules.MARGIN))
        // Nothing to write: always fine, even on a full phone
        assertTrue(SpaceRules.enough(0, 0))
    }

    @Test fun messages() {
        assertEquals("1.5 GB", SpaceRules.sizeText(1_500_000_000))
        assertEquals("820 MB", SpaceRules.sizeText(820_000_000))
        assertEquals("5 KB", SpaceRules.sizeText(4_500))
        assertEquals(
            "Not enough free space on this phone to get “Rain” ready (about 820 MB needed). Free up some space and try again.",
            SpaceRules.notEnoughText("to get “Rain” ready", 820_000_000),
        )
    }
}
