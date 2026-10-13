package app.sonorium.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sync after the server turned a track's file into MP3 (x.wav → x.mp3, same key). */
class ThemeSyncTest {
    @Test
    fun aFileReplacedByAnotherFormatGoes() {
        val phone = listOf("metadata.json", "presets.json", "Rain.wav", "Birds/robin.wav", "Birds/owl.mp3", "mine.ogg", "notes.txt")
        val pkg = listOf("metadata.json", "presets.json", "Rain.mp3", "Birds/robin.mp3", "Birds/owl.mp3")
        assertEquals(listOf("Rain.wav", "Birds/robin.wav"), ThemeSync.replacedFiles(phone, pkg))
    }

    @Test
    fun thePhonesOwnFilesStay() {
        // Uploaded on the phone, nothing like it in the package; and a file the package has as it is
        assertEquals(emptyList<String>(), ThemeSync.replacedFiles(listOf("Bells/upload.mp3", "Rain.mp3"), listOf("Rain.mp3")))
    }

    @Test
    fun theExtensionsCaseDoesntMatter() {
        assertEquals(listOf("Rain.WAV"), ThemeSync.replacedFiles(listOf("Rain.WAV"), listOf("Rain.mp3")))
    }
}
