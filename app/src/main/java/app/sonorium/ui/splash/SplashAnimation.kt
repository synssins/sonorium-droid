package app.sonorium.ui.splash

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.sonorium.R

/** The splash's background (Chris's spec; the system splash uses the same, `splash_background`). */
val SPLASH_BACKGROUND = Color(0xFF191C33)
private val PRO_GOLD = Color(0xFFFFB830)
private val PRO_TEXT = Color(0xFF191C33)
/** The logo's colours, for the faint colour on the ripple: pink → purple → teal → green. */
private val WAVE_COLOURS = listOf(Color(0xFFFF66C4), Color(0xFFBA68FF), Color(0xFF28C8DC), Color(0xFF3CDC8C))

/** Where the system splash showed its icon (window pixels), so the first frame matches it. */
data class SplashStart(val centreX: Float, val centreY: Float, val iconWidth: Float)

/**
 * The launch splash (approved board, shared/boards/splash): from the system splash's mark, the logo builds in 1 s ×
 * the animator duration scale, then holds its last frame. [start] is null until the system splash has handed
 * over (the first frame waits for it); [onFinished] when the last frame shows.
 */
@Composable
fun SplashAnimation(pro: Boolean, start: SplashStart?, onFinished: () -> Unit) {
    val view = LocalView.current
    val mark = ImageBitmap.imageResource(R.drawable.splash_mark)
    val word = ImageBitmap.imageResource(R.drawable.splash_word)
    var t by remember { mutableFloatStateOf(0f) }
    var reduced by remember { mutableStateOf(false) }

    LaunchedEffect(start != null) {
        if (start == null) return@LaunchedEffect
        // Android's animator duration scale (developer options; 0 with "Remove animations")
        val scale = android.provider.Settings.Global.getFloat(
            view.context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
        val length = SplashTimeline.durationMs(scale)
        if (length == 0L || !ValueAnimator.areAnimatorsEnabled()) {
            // "Remove animations": the final frame only
            reduced = true
            t = 1f
        } else {
            var begin = -1L
            var ticked = false
            while (t < 1f) {
                withFrameNanos { now ->
                    if (begin < 0) begin = now
                    t = ((now - begin) / 1_000_000f / length).coerceAtMost(1f)
                }
                if (!ticked && t >= SplashTimeline.PULSE) {
                    ticked = true
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
        onFinished()
    }

    val frame = SplashTimeline.frame(t, pro, reduced)
    val proLabel = stringResource(R.string.menu_pro_badge)
    val measurer = rememberTextMeasurer()
    val description = stringResource(R.string.splash_description)
    val ripple = remember { if (Build.VERSION.SDK_INT >= 33) RippleShader() else null }

    Box(
        Modifier.fillMaxSize().semantics { contentDescription = description }.graphicsLayer {
            val u = frame.ripple
            renderEffect = if (u != null && ripple != null && start != null && Build.VERSION.SDK_INT >= 33) {
                val dot = dotOnScreen(size.width, size.height, start, frame)
                ripple.effect(SplashTimeline.wave(u, size.width, size.height, dot.x, dot.y), dot)
            } else null
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(SPLASH_BACKGROUND)
            // Until the system splash hands over, it covers this: the background only
            val s = start ?: return@Canvas
            val p = SplashGeometry.place(size.width, size.height, s.centreX, s.centreY, s.iconWidth, frame.glide, frame.bump)
            drawLogo(mark, word, p, frame)
            if (frame.pillAlpha > 0f) drawPill(p, frame, proLabel, measurer)
            // Below Android 13: no refraction, a soft ring in the logo's colours
            val u = frame.ripple
            if (u != null && ripple == null) {
                val dot = Offset(p.x(SplashGeometry.DOT_X), p.y(SplashGeometry.DOT_Y))
                val w = SplashTimeline.wave(u, size.width, size.height, dot.x, dot.y)
                drawCircle(
                    Brush.sweepGradient(WAVE_COLOURS + WAVE_COLOURS.first(), center = dot),
                    radius = w.radius, center = dot, alpha = 0.16f * w.fade, style = Stroke(width = 2 * w.sigma),
                )
                drawCircle(Color.White, radius = w.radius - w.sigma * 0.5f, center = dot, alpha = 0.05f * w.fade, style = Stroke(width = w.sigma))
            }
        }
    }
}

private fun dotOnScreen(w: Float, h: Float, s: SplashStart, f: SplashTimeline.Frame): Offset {
    val p = SplashGeometry.place(w, h, s.centreX, s.centreY, s.iconWidth, f.glide, f.bump)
    return Offset(p.x(SplashGeometry.DOT_X), p.y(SplashGeometry.DOT_Y))
}

private fun DrawScope.drawLogo(mark: ImageBitmap, word: ImageBitmap, p: SplashGeometry.Placement, f: SplashTimeline.Frame) {
    withTransform({
        translate(p.originX, p.originY)
        scale(p.scale, p.scale, pivot = Offset.Zero)
    }) {
        drawImage(mark)
        if (f.revealEdge > 0f) {
            // The word through a soft edge sweeping left → right (in word widths, as SplashTimeline.revealAt)
            val l = SplashGeometry.WORD_L
            val ww = SplashGeometry.WORD_R - l
            val edge = l + f.revealEdge * ww
            val soft = SplashTimeline.REVEAL_SOFT * ww
            // Opaque behind the soft edge, clear ahead of it (the smoothstep of SplashTimeline.revealAt)
            val stops = (0..8).map { i ->
                val m = 1f - i / 8f
                i / 8f to Color.White.copy(alpha = m * m * (3 - 2 * m))
            }.toTypedArray()
            drawIntoCanvas { c ->
                c.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, SplashGeometry.ART_W, SplashGeometry.ART_H), Paint())
                drawImage(word)
                drawRect(
                    Brush.horizontalGradient(*stops, startX = edge - soft, endX = edge),
                    size = Size(SplashGeometry.ART_W, SplashGeometry.ART_H), blendMode = BlendMode.DstIn,
                )
                c.restore()
            }
        }
    }
    if (f.flash > 0f) {
        val c = Offset(p.x(SplashGeometry.DOT_X), p.y(SplashGeometry.DOT_Y))
        val r = SplashGeometry.DOT_R * p.scale
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFF5D2), Color(0x00FFF5D2)), center = c, radius = 3.2f * r),
            radius = 3.2f * r, center = c, alpha = 0.9f * f.flash, blendMode = BlendMode.Plus,
        )
        drawCircle(
            Brush.radialGradient(listOf(PRO_GOLD.copy(alpha = 0.6f), PRO_GOLD.copy(alpha = 0f)), center = c, radius = 12f * r),
            radius = 12f * r, center = c, alpha = 0.6f * f.flash, blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawPill(p: SplashGeometry.Placement, f: SplashTimeline.Frame, label: String, measurer: androidx.compose.ui.text.TextMeasurer) {
    val pill = SplashGeometry.pill()
    val cx = p.x(pill.left + pill.width / 2)
    val cy = p.y(pill.top + pill.height / 2)
    val w = pill.width * p.scale * f.pillScale
    val h = pill.height * p.scale * f.pillScale
    drawRoundRect(
        PRO_GOLD, topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h),
        cornerRadius = CornerRadius(pill.radius * p.scale * f.pillScale), alpha = f.pillAlpha,
    )
    val text = measurer.measure(
        label,
        TextStyle(color = PRO_TEXT, fontSize = (h * 0.58f / density / fontScale).sp, fontWeight = FontWeight.ExtraBold),
    )
    drawText(text, topLeft = Offset(cx - text.size.width / 2f, cy - text.size.height / 2f), alpha = f.pillAlpha)
}

/** The ripple on Android 13+: one wave refracting what's behind it, catching light, faintly in the logo's colours. */
@RequiresApi(33)
private class RippleShader {
    private val shader = RuntimeShader(
        """
        uniform shader content;
        uniform float2 centre;
        uniform float radius;
        uniform float sigma;
        uniform float amp;
        uniform float fade;
        layout(color) uniform half4 c0;
        layout(color) uniform half4 c1;
        layout(color) uniform half4 c2;
        layout(color) uniform half4 c3;

        half4 pick(float i) {
            if (i < 0.5) return c0;
            if (i < 1.5) return c1;
            if (i < 2.5) return c2;
            return c3;
        }

        half4 main(float2 p) {
            float2 v = p - centre;
            float r = length(v) + 0.001;
            float z = (r - radius) / sigma;
            float g = exp(-0.5 * z * z);
            // One crest and one trough: a single wave
            float2 sp = p - v / r * (amp * -z * g);
            half4 c = content.eval(sp);
            c.rgb += half3(14.0 / 255.0 * fade * (1.0 - z * z) * g);
            float a = (atan(v.y, v.x) / 6.2831853 + 0.5) * 4.0;
            float i0 = mod(floor(a), 4.0);
            float i1 = mod(i0 + 1.0, 4.0);
            half4 tint = mix(pick(i0), pick(i1), half(fract(a)));
            c.rgb = mix(c.rgb, tint.rgb, half(0.10 * fade * g));
            return c;
        }
        """.trimIndent(),
    )

    init {
        val cs = WAVE_COLOURS.map { android.graphics.Color.valueOf(it.red, it.green, it.blue, 1f) }
        shader.setColorUniform("c0", cs[0].toArgb())
        shader.setColorUniform("c1", cs[1].toArgb())
        shader.setColorUniform("c2", cs[2].toArgb())
        shader.setColorUniform("c3", cs[3].toArgb())
    }

    fun effect(w: SplashTimeline.Wave, centre: Offset): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("centre", centre.x, centre.y)
        shader.setFloatUniform("radius", w.radius)
        shader.setFloatUniform("sigma", w.sigma)
        shader.setFloatUniform("amp", w.amplitude)
        shader.setFloatUniform("fade", w.fade)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
