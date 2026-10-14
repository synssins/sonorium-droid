package app.sonorium.server

import app.sonorium.net.Playlist
import app.sonorium.net.PlaylistEntry
import app.sonorium.net.SequenceInfo
import app.sonorium.net.Session
import app.sonorium.net.ThemePresets
import app.sonorium.net.ThemeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRulesTest {
    @Test
    fun numbers() {
        assertEquals("30–60 min", PlaylistRules.playEach(Playlist("a", playMin = 1800.0, playMax = 3600.0)))
        assertEquals("0.5–1.5 min", PlaylistRules.playEach(Playlist("a", playMin = 30.0, playMax = 90.0)))
        assertEquals("45 min", PlaylistRules.duration(45.0))
        assertEquals("2 h", PlaylistRules.duration(120.0))
        assertEquals("1 h 30 min", PlaylistRules.duration(90.0))
        assertEquals("No entries", PlaylistRules.summary(0, 30.0, 60.0))
        assertEquals("1 entry · 30 min–1 h per pass", PlaylistRules.summary(1, 30.0, 60.0))
        assertEquals("3 entries", PlaylistRules.summary(3, 60.0, 30.0))
    }

    @Test
    fun names() {
        assertEquals("Evening (copy)", PlaylistRules.freeName("Evening", listOf("Evening")))
        assertEquals("Evening (copy 3)", PlaylistRules.freeName("Evening", listOf("evening (COPY)", "Evening (copy 2)")))
    }

    @Test
    fun deleteNote() {
        val a = Session(id = "1", name = "Kitchen", playlistId = "p")
        val b = Session(id = "2", name = "Den", playlistId = "p")
        assertNull(PlaylistRules.deleteNote("p", emptyList()))
        assertEquals("Kitchen stops playing.", PlaylistRules.deleteNote("p", listOf(a)))
        assertEquals("Kitchen, Den stop playing.", PlaylistRules.deleteNote("p", listOf(a, b)))
    }

    @Test
    fun entryNote() {
        val presets = ThemePresets(sequences = listOf(SequenceInfo("s", passMin = 600.0, passMax = 1500.0)))
        val e = PlaylistEntry("t", null, "s")
        assertEquals("Fits into 30–60 min", PlaylistRules.entryNote(e, presets, true, 30.0, 60.0))
        assertEquals("Plays the whole sequence, about 10–25 min", PlaylistRules.entryNote(e, presets, false, 30.0, 60.0))
        assertNull(PlaylistRules.entryNote(PlaylistEntry("t", "p", null), presets, true, 30.0, 60.0))
    }

    @Test
    fun validation() {
        val themes = listOf(ThemeSummary("t"))
        val lists = listOf(Playlist("x", "Evening"))
        val ok = listOf(PlaylistEntry("t"))
        fun v(id: String? = null, name: String = "New", min: String = "30", max: String = "60", entries: List<PlaylistEntry> = ok) =
            PlaylistRules.validate(id, name, min, max, entries, lists, themes)?.toast
        assertEquals("Enter a name", v(name = "  "))
        assertEquals("A playlist with that name exists", v(name = " evening "))
        assertNull(v(id = "x", name = "Evening"))
        assertEquals("Minimum is more than maximum", v(min = "60", max = "30"))
        assertEquals("Enter the play time", v(min = ""))
        assertEquals("Enter the play time", v(min = "0"))
        assertEquals("Add at least one entry", v(entries = emptyList()))
        assertEquals("Choose a theme for every entry", v(entries = listOf(PlaylistEntry(null))))
        assertNull(v())
    }
}
