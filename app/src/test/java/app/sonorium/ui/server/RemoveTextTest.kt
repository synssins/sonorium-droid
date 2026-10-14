package app.sonorium.ui.server

import app.sonorium.local.LocalTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Remove from phone dialog's text (LocalThemesPage). */
class RemoveTextTest {
    private val downloaded = LocalTheme("d", "Tavern", "Tavern", themeId = "t1", contentHash = "sha256:aa")
    private val imported = LocalTheme("e", "Rain", "Rain")

    @Test fun serverCopyOnlyForDownloadedThemes() {
        assertEquals("The theme stays on the server.", removeText(downloaded, playing = false, hasServer = true))
        assertEquals("Its files are deleted from this device.", removeText(imported, playing = false, hasServer = true))
        assertEquals("Its files are deleted from this device.", removeText(downloaded, playing = false, hasServer = false))
    }

    @Test fun warnsWhenItsPlaying() {
        assertEquals("This theme is playing; removing it stops it. The theme stays on the server.", removeText(downloaded, playing = true, hasServer = true))
    }
}
