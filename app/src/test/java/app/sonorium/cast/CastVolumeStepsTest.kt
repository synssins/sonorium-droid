package app.sonorium.cast

import android.media.AudioManager
import app.sonorium.cast.CastVolumeSteps.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastVolumeStepsTest {
    private val eps = 1e-9

    private fun assertChange(expected: Change, actual: Change?) {
        requireNotNull(actual) { "Expected $expected, got no change" }
        assertEquals(expected.level, actual.level, eps)
        assertEquals(expected.muted, actual.muted)
    }

    @Test fun twentyStepsOfFivePercent() {
        assertEquals(20, CastVolumeSteps.STEPS)
        assertEquals(0.05, CastVolumeSteps.toLevel(1), eps)
        assertEquals(1.0, CastVolumeSteps.toLevel(20), eps)
    }

    @Test fun levelsRoundToTheNearestStepAndClamp() {
        assertEquals(10, CastVolumeSteps.toStep(0.5, false))
        assertEquals(9, CastVolumeSteps.toStep(0.43, false))
        assertEquals(8, CastVolumeSteps.toStep(0.42, false))
        assertEquals(0, CastVolumeSteps.toStep(-0.2, false))
        assertEquals(20, CastVolumeSteps.toStep(1.4, false))
        assertEquals(0, CastVolumeSteps.toStep(Double.NaN, false))
        assertEquals(0.0, CastVolumeSteps.toLevel(-3), eps)
        assertEquals(1.0, CastVolumeSteps.toLevel(25), eps)
    }

    @Test fun mutedReadsAsSilent() {
        assertEquals(0, CastVolumeSteps.toStep(0.7, true))
    }

    @Test fun aKeyPressMovesOneStep() {
        assertChange(Change(0.55, false), CastVolumeSteps.adjust(0.5, false, AudioManager.ADJUST_RAISE))
        assertChange(Change(0.45, false), CastVolumeSteps.adjust(0.5, false, AudioManager.ADJUST_LOWER))
        // A level between steps (set in the Google Home app) moves from the nearest one
        assertChange(Change(0.5, false), CastVolumeSteps.adjust(0.43, false, AudioManager.ADJUST_RAISE))
        assertChange(Change(0.4, false), CastVolumeSteps.adjust(0.43, false, AudioManager.ADJUST_LOWER))
    }

    @Test fun stopsAtTheEnds() {
        assertNull(CastVolumeSteps.adjust(1.0, false, AudioManager.ADJUST_RAISE))
        assertNull(CastVolumeSteps.adjust(0.0, false, AudioManager.ADJUST_LOWER))
        assertChange(Change(1.0, false), CastVolumeSteps.adjust(0.96, false, AudioManager.ADJUST_RAISE))
        assertChange(Change(0.0, false), CastVolumeSteps.adjust(0.05, false, AudioManager.ADJUST_LOWER))
        assertChange(Change(1.0, false), CastVolumeSteps.adjust(0.95, false, AudioManager.ADJUST_RAISE))
    }

    @Test fun upUnmutesWhereTheSoundWas() {
        assertChange(Change(0.6, false), CastVolumeSteps.adjust(0.6, true, AudioManager.ADJUST_RAISE))
        // Muted at silence: unmuting alone wouldn't be heard, so one step
        assertChange(Change(0.05, false), CastVolumeSteps.adjust(0.0, true, AudioManager.ADJUST_RAISE))
        assertNull(CastVolumeSteps.adjust(0.6, true, AudioManager.ADJUST_LOWER))
    }

    @Test fun muteKeepsTheLevel() {
        assertChange(Change(0.6, true), CastVolumeSteps.adjust(0.6, false, AudioManager.ADJUST_MUTE))
        assertNull(CastVolumeSteps.adjust(0.6, true, AudioManager.ADJUST_MUTE))
        assertChange(Change(0.6, false), CastVolumeSteps.adjust(0.6, true, AudioManager.ADJUST_UNMUTE))
        assertNull(CastVolumeSteps.adjust(0.6, false, AudioManager.ADJUST_UNMUTE))
        assertChange(Change(0.6, true), CastVolumeSteps.adjust(0.6, false, AudioManager.ADJUST_TOGGLE_MUTE))
        assertChange(Change(0.6, false), CastVolumeSteps.adjust(0.6, true, AudioManager.ADJUST_TOGGLE_MUTE))
        assertNull(CastVolumeSteps.adjust(0.6, false, AudioManager.ADJUST_SAME))
    }

    @Test fun thePanelSliderSetsAStepAndZeroMutes() {
        assertChange(Change(0.35, false), CastVolumeSteps.setTo(0.6, 7))
        assertChange(Change(1.0, false), CastVolumeSteps.setTo(0.6, 40))
        // The panel's mute icon sets 0: muted, the level kept for unmuting
        assertChange(Change(0.6, true), CastVolumeSteps.setTo(0.6, 0))
    }
}
