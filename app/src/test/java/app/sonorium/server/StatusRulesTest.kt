package app.sonorium.server

import app.sonorium.net.NowChannel
import app.sonorium.net.NowPlaying
import app.sonorium.net.NowSequence
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusRulesTest {
    @Test
    fun texts() {
        assertEquals("2:24", StatusRules.time(143.2))
        assertEquals("1:00:05", StatusRules.time(3604.1))
        assertEquals("0:00", StatusRules.time(-3.0))
        assertEquals("Next in 2:21", StatusRules.nowText("Next in {t}", 143.2, 2.5))
        assertEquals("Next in —", StatusRules.nowText("Next in {t}", null, 0.0))
        assertEquals("Tavern · Slow night", StatusRules.plays(NowChannel("a", themeName = "Tavern", presetName = "Slow night")))
        assertEquals("Tavern · Default settings", StatusRules.plays(NowChannel("a", themeName = "Tavern")))
        assertEquals(
            "Tavern · Gentle storm · Step 2 of 3: Slow night",
            StatusRules.plays(NowChannel("a", themeName = "Tavern", presetName = "Slow night", sequence = NowSequence("Gentle storm", 2, 3))),
        )
        assertEquals("41%", StatusRules.percent(0.412))
        assertEquals("—", StatusRules.percent(null))
    }

    @Test
    fun asHeard() {
        fun snap(t: Long, name: String) = StatusRules.Snap(t, NowPlaying(channels = listOf(NowChannel("c", name = name, bufferSeconds = 5.0))))
        val snaps = mutableListOf(snap(0, "a"), snap(4_000, "b"), snap(6_000, "c"), snap(9_000, "d"))
        // now 10 s, delay 5 s → target 5 s → the 4 s snapshot, 1 s past
        val heard = StatusRules.shown(snaps, 10_000, heard = true, localChannel = null, localBuffer = 0.0).single()
        assertEquals("b", heard.channel.name)
        assertEquals(1.0, heard.past, 0.001)
        val server = StatusRules.shown(snaps, 10_000, heard = false, localChannel = null, localBuffer = 0.0).single()
        assertEquals("d", server.channel.name)
        // The local player's longer buffer wins for the channel this device plays
        assertEquals("a", StatusRules.shown(snaps, 10_000, true, "c", 9.0).single().channel.name)
        StatusRules.trim(snaps, 25_000)
        assertEquals(listOf(4_000L, 6_000L, 9_000L), snaps.map { it.t })
    }
}
