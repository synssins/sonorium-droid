package app.sonorium.server

import app.sonorium.net.Area
import app.sonorium.net.Floor
import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerHierarchy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRulesTest {
    private val h = SpeakerHierarchy(
        floors = listOf(
            Floor("ground", "Ground floor", areas = listOf(
                Area("kitchen", "Kitchen", "ground", listOf(Speaker("k1", "Kitchen speaker"))),
                Area("lounge", "Lounge", "ground", listOf(Speaker("l1", "Lounge"))),
            )),
        ),
        unassignedAreas = listOf(Area("garage", "Garage", null, listOf(Speaker("g1", "Garage")))),
        unassignedSpeakers = listOf(Speaker("u1", "Loose")),
    )

    @Test
    fun members() {
        val sel = GroupRules.Selection(listOf("ground"), emptyList(), listOf("u1", "gone"), excludeSpeakers = listOf("k1"))
        val m = GroupRules.members(sel, h)
        assertEquals(listOf("k1", "l1", "u1"), m.map { it.placed.speaker.entityId })
        assertEquals("Ground floor · Kitchen · via floor", m[0].location)
        assertEquals("No area · added", m[2].location)
        assertEquals(true, m[0].excluded)
        assertEquals(2, GroupRules.count(sel, h))
        assertEquals(listOf("1 floor", "2 speakers"), GroupRules.parts(sel))
        // Stale ids are dropped from the chips
        assertEquals(listOf("Ground floor", "Loose", "Kitchen speaker"), GroupRules.chips(sel, h).map { it.label })
    }

    @Test
    fun checksAndLabels() {
        val none = GroupRules.Selection(emptyList(), emptyList(), emptyList())
        assertEquals("Enter a group name", GroupRules.validate(" ", none))
        assertEquals("Select at least one speaker", GroupRules.validate("Up", none))
        assertNull(GroupRules.validate("Up", none.copy(areas = listOf("garage"))))
        assertEquals("Denon / Marantz", GroupRules.typeLabel("denon"))
        assertNull(GroupRules.typeLabel(null))
    }
}
