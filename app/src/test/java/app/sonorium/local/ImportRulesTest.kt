package app.sonorium.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRulesTest {
    private fun ok(r: ImportRules.Result): ImportRules.Plan = (r as? ImportRules.Result.Ok)?.plan ?: throw AssertionError("Expected a plan, got $r")
    private fun problem(r: ImportRules.Result): String = (r as? ImportRules.Result.Problem)?.message ?: throw AssertionError("Expected a problem, got $r")

    @Test fun serverExportHasOneTopFolder() {
        val plan = ok(ImportRules.plan(listOf("Tavern/metadata.json", "Tavern/presets.json", "Tavern/fire.mp3", "Tavern/Birds/robin.ogg"), "tavern-export.zip", emptySet()))
        assertEquals("Tavern", plan.folderName)
        assertEquals(
            listOf("Tavern/metadata.json" to "metadata.json", "Tavern/presets.json" to "presets.json", "Tavern/fire.mp3" to "fire.mp3", "Tavern/Birds/robin.ogg" to "Birds/robin.ogg"),
            plan.files,
        )
        assertTrue(plan.skipped.isEmpty())
    }

    @Test fun filesAtTheTopAreOneThemeNamedAfterTheZip() {
        val plan = ok(ImportRules.plan(listOf("rain.wav", "Low/drone.flac"), "Rain.zip", emptySet()))
        assertEquals("Rain", plan.folderName)
        assertEquals(listOf("rain.wav" to "rain.wav", "Low/drone.flac" to "Low/drone.flac"), plan.files)
    }

    @Test fun metadataAloneIsATheme() {
        assertEquals("Quiet", ok(ImportRules.plan(listOf("Quiet/metadata.json"), "q.zip", emptySet())).folderName)
    }

    @Test fun noAudioAndNoMetadataIsNotATheme() {
        assertEquals(ImportRules.NO_THEME, problem(ImportRules.plan(listOf("docs/readme.txt", "notes.md"), "x.zip", emptySet())))
        assertEquals(ImportRules.NO_THEME, problem(ImportRules.plan(emptyList(), "x.zip", emptySet())))
        // Audio too deep inside a folder doesn't count (the engine reads a theme and its group folders)
        assertEquals(ImportRules.NO_THEME, problem(ImportRules.plan(listOf("A/b/c/deep.mp3"), "x.zip", emptySet())))
    }

    @Test fun severalThemesImportTheFirst() {
        val plan = ok(ImportRules.plan(listOf("Rain/a.mp3", "Wind/b.mp3", "Sea/c.mp3", "Extras/readme.txt"), "all.zip", emptySet()))
        assertEquals("Rain", plan.folderName)
        assertEquals(listOf("Wind", "Sea"), plan.skipped)
        assertEquals(listOf("Rain/a.mp3" to "a.mp3"), plan.files)
        assertTrue(ImportRules.importedText(plan).contains("2 other themes"))
    }

    @Test fun unsafeAndHiddenEntriesAreLeftOut() {
        assertNull(ImportRules.clean("../evil.mp3"))
        assertNull(ImportRules.clean("Theme/../../evil.mp3"))
        assertNull(ImportRules.clean("__MACOSX/Theme/._a.mp3"))
        assertNull(ImportRules.clean("Theme/.DS_Store"))
        assertNull(ImportRules.clean("Theme/"))
        assertEquals("Theme/a.mp3", ImportRules.clean("/Theme\\a.mp3"))
        val plan = ok(ImportRules.plan(listOf("Theme/../x.mp3", "Theme/a.mp3", "__MACOSX/Theme/._a.mp3"), "t.zip", emptySet()))
        assertEquals(listOf("Theme/a.mp3" to "a.mp3"), plan.files)
    }

    @Test fun aThemeAlreadyOnThePhoneIsRefusedPlainly() {
        val message = problem(ImportRules.plan(listOf("Tavern/fire.mp3"), "t.zip", setOf("tavern")))
        assertEquals("“Tavern” is already on this phone. Remove it first to import it again.", message)
    }

    @Test fun folderNamesAreMadeSafe() {
        assertEquals("a_b", ImportRules.safeName("a:b"))
        assertEquals("Theme", ImportRules.safeName("..."))
    }
}
