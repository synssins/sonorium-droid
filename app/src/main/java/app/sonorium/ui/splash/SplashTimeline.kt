package app.sonorium.ui.splash

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The launch splash's timing (Chris's approved board, shared/boards/splash): what each part looks like at a
 * time t of the 1.0 s animation (t in 0..1, before the device's animation scale). Pure, so it is unit-tested;
 * [SplashAnimation] draws it.
 */
object SplashTimeline {
    /** The animation's length at an animator duration scale of 1. */
    const val DURATION_MS = 1000L
    const val GLIDE_END = 0.80f
    const val REVEAL_START = 0.06f
    const val REVEAL_END = 0.80f
    /** The dot flashes, the haptic tick fires and the ripple starts. */
    const val PULSE = 0.80f
    const val PILL_END = 0.95f
    const val RIPPLE_END = 1.00f

    /** The reveal's soft edge, as a part of the word's width (about one letter). */
    const val REVEAL_SOFT = 1f / 8f

    /** The logo's pressure bump at its peak (+3 %). */
    const val BUMP = 0.03f

    /** How long the animation runs with the device's animator duration [scale]; 0 means no animation. */
    fun durationMs(scale: Float): Long = if (scale <= 0f) 0L else (DURATION_MS * scale).toLong()

    data class Frame(
        /** The glide's progress: 0 = the system splash's mark, 1 = the final logo. */
        val glide: Float,
        /** The reveal's leading edge from the word's left end, in word widths (fully in at 1 + [REVEAL_SOFT]). */
        val revealEdge: Float,
        /** The logo's extra scale (0 = none). */
        val bump: Float,
        /** The gold dot's flash, 0..1. */
        val flash: Float,
        /** The PRO pill: opacity and scale (0 without Pro). */
        val pillAlpha: Float,
        val pillScale: Float,
        /** The ripple's progress 0..1 while it runs, else null. */
        val ripple: Float?,
    )

    /** The frame at [t]; with [reducedMotion] ("Remove animations") the final frame only. */
    fun frame(t: Float, pro: Boolean, reducedMotion: Boolean = false): Frame {
        val at = if (reducedMotion) 1f else t.coerceIn(0f, 1f)
        val pill = if (pro) pill(at) else 0f to 0f
        return Frame(
            glide = glide(at),
            revealEdge = revealEdge(at),
            bump = bump(at),
            flash = flash(at),
            pillAlpha = pill.first,
            pillScale = pill.second,
            ripple = ripple(at),
        )
    }

    fun glide(t: Float): Float = easeOutCubic(t / GLIDE_END)

    fun revealEdge(t: Float): Float {
        val u = ((t - REVEAL_START) / (REVEAL_END - REVEAL_START)).coerceIn(0f, 1f)
        return u * (1f + REVEAL_SOFT)
    }

    /** How much of the word shows at [x] (0 = its left end, 1 = the end of "m") for a reveal [edge]. */
    fun revealAt(edge: Float, x: Float): Float {
        val m = ((edge - x) / REVEAL_SOFT).coerceIn(0f, 1f)
        return m * m * (3 - 2 * m)
    }

    /** +3 % at about 0.86 s, a small give, settled at 1.00 s. */
    fun bump(t: Float): Float {
        if (t <= PULSE || t >= 1f) return 0f
        val u = (t - PULSE) / (1f - PULSE)
        return if (u < 0.6f) BUMP * sin(PI.toFloat() * u / 0.6f)
        else -BUMP * 0.15f * sin(PI.toFloat() * (u - 0.6f) / 0.4f)
    }

    /** One flash, at its brightest at 0.80 s. */
    fun flash(t: Float): Float = when {
        t < 0.76f || t > PILL_END -> 0f
        t <= PULSE -> ((t - 0.76f) / 0.04f).let { it * it }
        else -> exp(-(t - PULSE) / 0.035f)
    }

    /** The PRO pill's (opacity, scale): in quickly from 0.80 s, with a slight overshoot, full at 0.95 s. */
    fun pill(t: Float): Pair<Float, Float> {
        if (t <= PULSE) return 0f to 0f
        val u = ((t - PULSE) / (PILL_END - PULSE)).coerceIn(0f, 1f)
        return (u / 0.6f).coerceIn(0f, 1f) to 0.6f + 0.4f * easeOutBack(u)
    }

    fun ripple(t: Float): Float? = if (t <= PULSE || t >= RIPPLE_END) null else (t - PULSE) / (RIPPLE_END - PULSE)

    /** The ripple on a [w]×[h] screen from ([cx], [cy]) at progress [u]: one wave, past the farthest corner at 1. */
    data class Wave(val radius: Float, val sigma: Float, val amplitude: Float, val fade: Float)

    fun wave(u: Float, w: Float, h: Float, cx: Float, cy: Float): Wave {
        val short = min(w, h)
        val far = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
        val sigma = 0.035f * short * (1 + 0.8f * u)
        val radius = (far + 3.2f * sigma) * (1 - (1 - u) * (1 - u))
        return Wave(radius, sigma, 0.022f * short * (1 - 0.55f * u), 1 - u)
    }

    private fun easeOutCubic(u: Float): Float {
        val v = 1 - u.coerceIn(0f, 1f)
        return 1 - v * v * v
    }

    private fun easeOutBack(u: Float): Float {
        val k = 1.70158f
        val v = u.coerceIn(0f, 1f) - 1
        return 1 + (k + 1) * v * v * v + k * v * v
    }
}

/**
 * Where the logo is on screen. The art is the logo PNG (725×191) split into two layers in place (`splash_mark`,
 * `splash_word`); boxes are in its pixels.
 */
object SplashGeometry {
    const val ART_W = 725f
    const val ART_H = 191f
    // The ink (no glow) of the mark, the word and the whole logo; the gold dot
    const val MARK_L = 4f; const val MARK_T = 41f; const val MARK_R = 218f; const val MARK_B = 154f
    const val WORD_L = 245f; const val WORD_T = 52f; const val WORD_R = 692f; const val WORD_B = 132f
    const val DOT_X = 110.6f; const val DOT_Y = 96.25f; const val DOT_R = 12.0f
    const val LOGO_L = MARK_L; const val LOGO_R = WORD_R

    /** The mark is this part of the system splash icon's width (splash_icon.png). */
    const val ICON_MARK = 0.31f

    /** The final logo's width on screen, as a part of the shorter side. */
    const val LOGO_SHARE = 0.70f

    /** Where art point (x, y) is drawn: screen = origin + art × scale. */
    data class Placement(val originX: Float, val originY: Float, val scale: Float) {
        fun x(artX: Float) = originX + artX * scale
        fun y(artY: Float) = originY + artY * scale
    }

    /**
     * The art's placement on a [w]×[h] screen. The system splash showed the mark centred at ([iconX], [iconY]),
     * [iconW] wide (the whole icon); at [glide] 1 the logo is centred and [LOGO_SHARE] of the shorter side wide.
     * [bump] presses the whole logo about the screen's centre.
     */
    fun place(w: Float, h: Float, iconX: Float, iconY: Float, iconW: Float, glide: Float, bump: Float): Placement {
        val startScale = iconW * ICON_MARK / (MARK_R - MARK_L)
        val endScale = LOGO_SHARE * min(w, h) / (LOGO_R - LOGO_L)
        val scale = (startScale + (endScale - startScale) * glide) * (1 + bump)
        // The screen point the art's anchor sits on, and the anchor: the mark's centre → the logo's centre
        val cx = iconX + (w / 2 - iconX) * glide
        val cy = iconY + (h / 2 - iconY) * glide
        val ax = (MARK_L + MARK_R) / 2 + ((LOGO_L + LOGO_R) / 2 - (MARK_L + MARK_R) / 2) * glide
        val ay = (MARK_T + MARK_B) / 2
        return Placement(cx - ax * scale, cy - ay * scale, scale)
    }

    /** The PRO pill (Chris's mockup): under the word, right edge on the end of "m", just below the baseline. */
    data class Pill(val left: Float, val top: Float, val width: Float, val height: Float) {
        val radius get() = 0.28f * height
    }

    fun pill(): Pill {
        val wordH = WORD_B - WORD_T
        val h = 0.45f * wordH
        val w = 2.5f * h
        return Pill(WORD_R - w, WORD_B + 0.10f * wordH, w, h)
    }
}
