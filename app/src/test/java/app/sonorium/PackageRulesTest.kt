package app.sonorium

import app.sonorium.local.LocalTheme
import app.sonorium.local.PackageRules
import app.sonorium.net.PackageFile
import app.sonorium.net.PackageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageRulesTest {
    // The server's Tavern package (handoff to-app/30), in the server's order shuffled
    private val tavern = listOf(
        PackageFile("presets.json", 1942, "fa29b7180811abca1017a5a333e82fbd8b2b4dddb77711920fa392180282fdfd"),
        PackageFile("ATTRIBUTION.md", 1619, "441f840fa0cca87759037157b831b01e6ec7542975becd5a2c34858d7cb1ba71"),
        PackageFile("Collecting_glassess_195.mp3", 280900, "a80e881fb6513cd8b29ea41e7c632fdaeda00821a942efd0b858fd067dbe70f8"),
        PackageFile("Cutlery_321.mp3", 237850, "c797d3e1880f396ea4facfd92dc2dcfc4291f6852af01570d47651363e2f9579"),
        PackageFile("Lute_-_Greensleeves_753.mp3", 1190535, "46f35b202a5e86855a952216261a81fc9b57044447b8d6e64ab3ad152e082997"),
        PackageFile("MANIFEST.json", 4320, "dc79902cbe4962ad39e8977cead13f1a3bcd41af54ca1e449e46e46d71c46c11"),
        PackageFile("Pouring_water_into_glass_199.mp3", 74010, "ba137b70f851993a3636b2f56ca55b4a4958fd34f5b6cbb64d049c3e7af7a0d0"),
        PackageFile("Quiet_British_Pub_768.mp3", 1873481, "0376484698cd57d6ec439f610c5f79145d7ef2043446a93a1cd56567b1faeaec"),
        PackageFile("Wooden_Door_Slamming_723.mp3", 10030, "ff7ade5e6abcc6dfdf27ac74c4072a3fe931834db8c1acecbc7cee1d42dce57b"),
        PackageFile("fireplace_663.mp3", 259951, "07820aba53a09bb4a541dd4b0ae7b9d73e50ff29a2f907e7b41b9eeed3a783e7"),
        PackageFile("glass_clink_653.mp3", 522430, "af11b80df962cf84b1bef36f07d85ffa8b5e4235e8a0c678a87fd28a272a7f74"),
        PackageFile("metadata.json", 6596, "718483a1d27d9609834f9ec4c88881a798dd2c2fc270d27458bc59eff6b10d6e"),
    )

    @Test fun contentHashMatchesTheServer() {
        assertEquals("sha256:436fcf22b1c30111cc71c47518aaabcaf96703074aa6cb8861ab92005149a160", PackageRules.contentHash(tavern))
    }

    @Test fun safePaths() {
        assertTrue(PackageRules.safePath("Low/Drone.wav"))
        assertFalse(PackageRules.safePath("../Sleigh Ride/MANIFEST.json"))
        assertFalse(PackageRules.safePath("/etc/passwd"))
        assertFalse(PackageRules.safePath(".sonorium-package.json"))
        assertFalse(PackageRules.safePath("a//b.mp3"))
    }

    @Test fun actions() {
        val pkg = PackageSummary("t1", "Tavern", "sha256:aa", redistributable = true)
        assertEquals("Save to phone", PackageRules.action(pkg, emptyList()))
        assertEquals("Save to phone", PackageRules.action(pkg.copy(redistributable = null), null))
        assertNull(PackageRules.action(pkg.copy(redistributable = false), emptyList()))
        assertNull(PackageRules.action(null, emptyList()))
        val saved = LocalTheme("d", "Tavern", "Tavern", themeId = "t1", contentHash = "sha256:aa")
        assertNull(PackageRules.action(pkg, listOf(saved)))
        assertEquals(PackageRules.OnPhone.SAVED, PackageRules.onPhone(pkg, listOf(saved)))
        assertEquals("Update on phone", PackageRules.action(pkg, listOf(saved.copy(contentHash = "sha256:bb"))))
    }

    @Test fun licenceRestrictedThemesSayWhy() {
        val pkg = PackageSummary("t1", "Tavern", "sha256:aa", redistributable = false)
        assertEquals(PackageRules.LICENCE_BLOCKED, PackageRules.blocked(pkg, emptyList()))
        assertNull(PackageRules.blocked(pkg.copy(redistributable = true), emptyList()))
        assertNull(PackageRules.blocked(pkg.copy(redistributable = null), emptyList()))
        assertNull(PackageRules.blocked(null, emptyList()))
        // Already on the phone (e.g. imported): nothing to explain
        assertNull(PackageRules.blocked(pkg, listOf(LocalTheme("d", "Tavern", "Tavern", metaId = "t1"))))
    }

    @Test fun anImportedExportCountsAsOnThePhone() {
        val pkg = PackageSummary("t1", "Tavern", "sha256:aa", redistributable = true)
        val imported = LocalTheme("d", "Tavern", "Tavern", metaId = "t1")
        assertEquals(PackageRules.OnPhone.SAVED, PackageRules.onPhone(pkg, listOf(imported)))
        assertNull(PackageRules.action(pkg, listOf(imported)))
        assertEquals(imported, PackageRules.localCopy("t1", listOf(imported)))
        // A copy saved from the server wins over an imported one
        val saved = LocalTheme("e", "Tavern (2)", "Tavern", themeId = "t1", contentHash = "sha256:aa")
        assertEquals(saved, PackageRules.localCopy("t1", listOf(imported, saved)))
        // Another theme's metadata id, or a saved theme's own metadata id, doesn't match
        assertNull(PackageRules.localCopy("t1", listOf(LocalTheme("f", "Rain", "Rain", metaId = "t2"))))
        assertNull(PackageRules.localCopy("t1", listOf(LocalTheme("g", "Rain", "Rain", themeId = "t9", metaId = "t1"))))
        assertNull(PackageRules.localCopy("", listOf(imported)))
    }

    @Test fun versionChangesWithTheFiles() {
        val t = LocalTheme("d", "Tavern", "Tavern", tracks = 3, bytes = 100, themeId = "t1", contentHash = "sha256:aa")
        assertEquals(t.version, t.copy(name = "Renamed").version)
        assertTrue(t.version != t.copy(contentHash = "sha256:bb").version)
        assertTrue(t.version != t.copy(docId = "e").version)
        assertTrue(t.version != t.copy(bytes = 101).version)
    }

    @Test fun folderNames() {
        assertEquals("Tavern", PackageRules.folderName("Tavern", setOf("rain")))
        assertEquals("Tavern (2)", PackageRules.folderName("Tavern", setOf("tavern")))
        assertEquals("Tavern (3)", PackageRules.folderName("Tavern", setOf("tavern", "tavern (2)")))
        assertEquals("a_b", PackageRules.folderName("a/b", emptySet()))
    }
}
