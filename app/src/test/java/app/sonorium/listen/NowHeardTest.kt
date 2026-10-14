package app.sonorium.listen

import app.sonorium.net.PlaylistStatus
import app.sonorium.net.PresetInfo
import app.sonorium.net.SequenceStatus
import app.sonorium.net.Session
import app.sonorium.net.ThemeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowHeardTest {
    private val themes = listOf(
        ThemeSummary("rain", name = "Rain"),
        ThemeSummary("tavern", name = "Tavern"),
        ThemeSummary("empty", name = "Empty", hasAudio = false),
    )
    private val presets = listOf(PresetInfo("p1", "Storm"), PresetInfo("p2", "Drizzle"))

    @Test fun `title is the theme, the playlist entry's while a playlist plays, else the channel`() {
        assertEquals("Rain", NowHeard.title(Session("c", name = "Living Room", themeId = "rain"), themes, "Living Room"))
        val playlist = Session("c", themeId = "rain", playlist = PlaylistStatus(running = true, themeName = "Tavern"))
        assertEquals("Tavern", NowHeard.title(playlist, themes, "Living Room"))
        assertEquals("Living Room", NowHeard.title(Session("c"), themes, "Living Room"))
        assertEquals("Sonorium", NowHeard.title(null, themes, ""))
    }

    @Test fun `subtitle is the step or preset, the channel, and the server when it fits`() {
        assertEquals("Default settings · Living Room · Home", NowHeard.subtitle(Session("c", themeId = "rain"), presets, "Living Room", "Home"))
        assertEquals("Storm · Living Room", NowHeard.subtitle(Session("c", themeId = "rain", presetId = "p1"), presets, "Living Room", null))
        val sequence = Session("c", sequence = SequenceStatus(running = true, step = 2, steps = 5, currentPresetName = "Drizzle"))
        assertEquals("Step 2 of 5 · Drizzle · Bedroom", NowHeard.subtitle(sequence, presets, "Bedroom", null))
        val playlist = Session("c", playlist = PlaylistStatus(running = true, position = 0, entries = 3))
        assertEquals("Entry 1 of 3 · Bedroom", NowHeard.subtitle(playlist, presets, "Bedroom", null))
        // Too long with the server: left out
        val long = "A server with a rather long name indeed"
        assertEquals("Default settings · Living Room", NowHeard.subtitle(Session("c"), presets, "Living Room", long))
    }

    @Test fun `next theme skips themes without audio and wraps`() {
        assertEquals("tavern", NowHeard.nextTheme("rain", themes)?.id)
        assertEquals("rain", NowHeard.nextTheme("tavern", themes)?.id)
        assertEquals("rain", NowHeard.nextTheme(null, themes)?.id)
        assertNull(NowHeard.nextTheme("rain", themes.take(1)))
    }

    @Test fun `presets cycle through the default settings both ways`() {
        assertEquals("p1", NowHeard.nextPreset(null, presets, forward = true))
        assertEquals("p2", NowHeard.nextPreset("p1", presets, forward = true))
        assertEquals("", NowHeard.nextPreset("p2", presets, forward = true))
        assertEquals("p2", NowHeard.nextPreset("", presets, forward = false))
        assertNull(NowHeard.nextPreset(null, emptyList(), forward = true))
    }
}
