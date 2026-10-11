package app.sonorium.net

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityWordsTest {
    @Test
    fun matchesTheServerExample() {
        // APP_API.md 2.2: 6f310f64... -> forest candy award fancy
        assertEquals(listOf("forest", "candy", "award", "fancy"), SecurityWords.forFingerprint("6f310f64" + "0".repeat(56)))
    }

    @Test
    fun hasAllWords() {
        assertEquals(256, SecurityWords.WORDS.size)
        assertEquals(256, SecurityWords.WORDS.toSet().size)
        assertEquals("acorn", SecurityWords.WORDS.first())
        assertEquals("zebra", SecurityWords.WORDS.last())
    }
}
