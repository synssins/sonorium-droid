package app.sonorium.server

import app.sonorium.net.Session
import app.sonorium.net.ThemeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRulesTest {
    private val tavern = ThemeSummary("1", "Cozy_tavern", totalTracks = 8, isFavorite = true, categories = listOf("Fantasy"), description = "A night at an inn.")
    private val forest = ThemeSummary("2", "Forest", totalTracks = 8, categories = listOf("Nature"))
    private val attic = ThemeSummary("3", "attic", totalTracks = 2, hasAudio = false)

    @Test
    fun searchAndChips() {
        assertTrue(ThemeRules.matches(tavern, "cozy tavern"))
        assertTrue(ThemeRules.matches(tavern, "cozy_"))
        assertTrue(ThemeRules.matches(tavern, " INN "))
        assertFalse(ThemeRules.matches(forest, "inn"))
        assertTrue(ThemeRules.inChip(tavern, ThemeRules.FAVORITES))
        assertFalse(ThemeRules.inChip(forest, "Fantasy"))
        assertEquals(listOf("Weather", "Fantasy", "Nature"), ThemeRules.categoryChips(listOf("Weather", "Fantasy"), listOf(tavern, forest)))
    }

    @Test
    fun sortAndLabels() {
        assertEquals(listOf("attic", "Cozy_tavern", "Forest"), ThemeRules.sorted(listOf(forest, tavern, attic), false).map { it.name })
        assertEquals(listOf("Cozy_tavern", "Forest", "attic"), ThemeRules.sorted(listOf(forest, attic, tavern), true).map { it.name })
        assertEquals("1 theme", ThemeRules.count(1))
        assertEquals("No tracks", ThemeRules.tracks(0))
        val on = ThemeRules.onAir("1", listOf(Session("a", "Den", themeId = "1", isPlaying = true), Session("b", "Hall", themeId = "1", isPlaying = true), Session("c", "Off", themeId = "1")))
        assertEquals("Den +1", ThemeRules.onAirLabel(on))
        assertFalse(ThemeRules.canPreview(attic))
        assertFalse(ThemeRules.canAddCategory(" fantasy ", listOf("Fantasy")))
        assertEquals("Night sky", ThemeRules.collapseSpaces("  Night   sky "))
    }
}
