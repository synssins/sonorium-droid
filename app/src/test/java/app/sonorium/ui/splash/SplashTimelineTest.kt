package app.sonorium.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimelineTest {
    private val eps = 1e-4f
    private val times = listOf(0f, 0.10f, 0.25f, 0.40f, 0.55f, 0.70f, 0.80f, 0.85f, 0.90f, 0.95f, 1f)

    @Test fun lastsOneSecondTimesTheAnimationScale() {
        assertEquals(1000L, SplashTimeline.durationMs(1f))
        assertEquals(500L, SplashTimeline.durationMs(0.5f))
        assertEquals(10_000L, SplashTimeline.durationMs(10f))
        assertEquals("Remove animations: none", 0L, SplashTimeline.durationMs(0f))
    }

    @Test fun theMarkGlidesFastThenSettles() {
        assertEquals(0f, SplashTimeline.glide(0f), eps)
        assertEquals(1f, SplashTimeline.glide(0.80f), eps)
        assertEquals(1f, SplashTimeline.glide(1f), eps)
        // Ease-out: more than half the way in the first third of the glide
        assertTrue(SplashTimeline.glide(0.80f / 3) > 0.6f)
        // Never goes back
        val g = (0..100).map { SplashTimeline.glide(it / 100f) }
        assertTrue(g.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun theWordShowsFromSAndIsFullyInAt080() {
        // Nothing of the word before the reveal starts
        assertEquals(0f, SplashTimeline.revealAt(SplashTimeline.revealEdge(0f), 0f), eps)
        // "S" (its first eighth) is coming in at 0.10 s
        assertTrue(SplashTimeline.revealAt(SplashTimeline.revealEdge(0.10f), 0.02f) > 0f)
        assertEquals(0f, SplashTimeline.revealAt(SplashTimeline.revealEdge(0.10f), 0.5f), eps)
        // "m" (the far end) fully in at 0.80 s and after
        for (t in listOf(0.80f, 0.90f, 1f)) assertEquals(1f, SplashTimeline.revealAt(SplashTimeline.revealEdge(t), 1f), eps)
        assertTrue(SplashTimeline.revealAt(SplashTimeline.revealEdge(0.70f), 1f) < 1f)
    }

    @Test fun onePulseAt080() {
        assertEquals(1f, SplashTimeline.flash(0.80f), eps)
        assertEquals(0f, SplashTimeline.flash(0.5f), eps)
        assertEquals(0f, SplashTimeline.flash(1f), eps)
        assertTrue(SplashTimeline.flash(0.85f) < 0.5f)
        assertNull(SplashTimeline.ripple(0.79f))
        assertEquals(0.5f, SplashTimeline.ripple(0.90f)!!, eps)
        assertNull("No ripple left at 1.00 s", SplashTimeline.ripple(1f))
    }

    @Test fun theBumpIsThreePercentAndSettles() {
        val peak = (800..1000).maxOf { SplashTimeline.bump(it / 1000f) }
        assertEquals(0.03f, peak, 0.0005f)
        assertEquals(0f, SplashTimeline.bump(0.80f), eps)
        assertEquals(0f, SplashTimeline.bump(1f), eps)
        assertEquals(0f, SplashTimeline.bump(0.5f), eps)
    }

    @Test fun thePillOnlyWithPro() {
        for (t in times) {
            val free = SplashTimeline.frame(t, pro = false)
            assertEquals("No pill without Pro at $t", 0f, free.pillAlpha, eps)
        }
        assertEquals(0f, SplashTimeline.frame(0.80f, pro = true).pillAlpha, eps)
        assertTrue(SplashTimeline.frame(0.85f, pro = true).pillAlpha > 0f)
        val end = SplashTimeline.frame(0.95f, pro = true)
        assertEquals(1f, end.pillAlpha, eps)
        assertEquals(1f, end.pillScale, eps)
        assertEquals(1f, SplashTimeline.frame(1f, pro = true).pillScale, eps)
    }

    @Test fun reducedMotionShowsTheFinalFrame() {
        for (pro in listOf(true, false)) {
            val r = SplashTimeline.frame(0f, pro, reducedMotion = true)
            assertEquals(SplashTimeline.frame(1f, pro), r)
            assertEquals(1f, r.glide, eps)
            assertNull(r.ripple)
            assertEquals(0f, r.flash, eps)
            assertEquals(if (pro) 1f else 0f, r.pillAlpha, eps)
        }
    }

    @Test fun theRippleIsPastEveryCornerAtTheEnd() {
        val w = 1080f; val h = 1920f; val cx = 278f; val cy = 960f
        val far = maxOf(Math.hypot(cx.toDouble(), cy.toDouble()), Math.hypot((w - cx).toDouble(), cy.toDouble())).toFloat()
        val end = SplashTimeline.wave(1f, w, h, cx, cy)
        assertTrue(end.radius - 3 * end.sigma > far)
        assertEquals(0f, end.fade, eps)
        assertEquals(0f, SplashTimeline.wave(0f, w, h, cx, cy).radius, eps)
    }

    // ---- where the logo is

    private fun place(t: Float, w: Float = 1080f, h: Float = 1920f, iconW: Float = 756f) =
        SplashGeometry.place(w, h, w / 2, h / 2, iconW, SplashTimeline.glide(t), SplashTimeline.bump(t))

    @Test fun startsOnTheSystemSplashsMark() {
        val iconW = 756f
        val p = place(0f, iconW = iconW)
        val g = SplashGeometry
        // The mark's centre on the icon's centre, and as wide as in the icon
        assertEquals(540f, (p.x(g.MARK_L) + p.x(g.MARK_R)) / 2, 0.01f)
        assertEquals(960f, (p.y(g.MARK_T) + p.y(g.MARK_B)) / 2, 0.01f)
        assertEquals(iconW * g.ICON_MARK, p.x(g.MARK_R) - p.x(g.MARK_L), 0.01f)
    }

    @Test fun endsCentredAtSeventyPercentOfTheShorterSide() {
        for ((w, h) in listOf(1080f to 1920f, 2560f to 1600f)) {
            for (t in listOf(0.80f, 1f)) {
                val p = place(t, w, h)
                val g = SplashGeometry
                assertEquals(0.70f * minOf(w, h), p.x(g.LOGO_R) - p.x(g.LOGO_L), 0.5f)
                assertEquals(w / 2, (p.x(g.LOGO_L) + p.x(g.LOGO_R)) / 2, 0.5f)
                assertEquals(h / 2, (p.y(g.MARK_T) + p.y(g.MARK_B)) / 2, 0.5f)
            }
        }
    }

    @Test fun noJumpsBetweenFrames() {
        // 60 fps: the mark's left edge never moves more than a sliver of the screen per frame, and only leftwards
        var last = place(0f).x(SplashGeometry.MARK_L)
        for (i in 1..48) {
            val x = place(i / 60f).x(SplashGeometry.MARK_L)
            assertTrue("Frame $i jumped ${last - x}", last - x in 0f..60f)
            last = x
        }
    }

    @Test fun thePillSitsUnderTheEndOfTheWord() {
        val p = SplashGeometry.pill()
        assertEquals(SplashGeometry.WORD_R, p.left + p.width, eps)
        assertTrue(p.top > SplashGeometry.WORD_B)
        assertEquals(0.28f * p.height, p.radius, eps)
    }
}
