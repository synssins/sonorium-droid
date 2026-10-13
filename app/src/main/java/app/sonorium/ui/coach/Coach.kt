package app.sonorium.ui.coach

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.R
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
import kotlin.math.roundToInt

/** One step of a guide: the control it points at ([target], null: none) and what it says. */
data class CoachStep(val target: String?, val title: String, val text: String)

/** A guide: its id (remembered once seen) and its steps. */
data class CoachGuide(val id: String, val steps: List<CoachStep>)

/**
 * Coach marks over the live app: controls mark themselves with [coachTarget]; [show] puts a guide over the
 * screen (dimmed, the step's control cut out, a text box with Skip · dots · Next/Done). While a guide shows,
 * nothing under it acts. A guide shows once by itself ([showOnce]); its page's help shows it again.
 */
@Stable
class CoachState(private val context: Context) {
    /** Each target's places on screen (several controls may share a name: the topmost is used). */
    internal val targets = mutableStateMapOf<String, Map<Any, Rect>>()
    var guide by mutableStateOf<CoachGuide?>(null)
        private set
    var index by mutableStateOf(0)
        private set

    private val prefs get() = context.applicationContext.getSharedPreferences("coach", Context.MODE_PRIVATE)

    fun seen(id: String): Boolean = prefs.getBoolean("seen:$id", false)

    /**
     * This install was updated since it was first installed (an upgrade from a build without guides, or any
     * later one): the first-run guide is for a real first run only (Chris).
     */
    val upgraded: Boolean by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)
    }

    /** Shows [guide] now (from help: whether seen or not); steps whose control isn't on screen are left out. */
    fun show(guide: CoachGuide) {
        val steps = guide.steps.filter { it.target == null || rectOf(it.target) != null }
        if (steps.isEmpty()) return
        index = 0
        this.guide = guide.copy(steps = steps)
    }

    /** Shows [guide] unless it was seen before or another guide is showing. */
    fun showOnce(guide: CoachGuide) {
        if (this.guide == null && !seen(guide.id) && ENABLED) show(guide)
    }

    fun next() {
        val g = guide ?: return
        if (index + 1 < g.steps.size) index++ else finish()
    }

    fun finish() {
        guide?.let { prefs.edit().putBoolean("seen:${it.id}", true).apply() }
        guide = null
        index = 0
    }

    internal fun place(key: String, owner: Any, rect: Rect?) {
        val now = targets[key].orEmpty()
        val next = if (rect == null) now - owner else now + (owner to rect)
        if (next != now) targets[key] = next
    }

    /** Where [key] is now (the topmost of its controls), or null. */
    internal fun rectOf(key: String?): Rect? = key?.let { targets[it]?.values?.filter { r -> r.width > 0 && r.height > 0 }?.minByOrNull { r -> r.top } }

    companion object {
        /** Off in device tests that drive the screens (a guide would cover them). */
        @Volatile var ENABLED = true
    }
}

/** The screen's coach marks (null where there are none: the control isn't marked). */
val LocalCoach = staticCompositionLocalOf<CoachState?> { null }

/** Marks this control as the target [key] of guide steps. */
fun Modifier.coachTarget(key: String): Modifier = composed {
    val coach = LocalCoach.current ?: return@composed this
    val owner = remember { Any() }
    DisposableEffect(key) { onDispose { coach.place(key, owner, null) } }
    onGloballyPositioned { coach.place(key, owner, it.boundsInRoot()) }
}

/** The guide over the whole screen, when one shows. Put it last, over everything, filling the window. */
@Composable
fun CoachOverlay(coach: CoachState) {
    val guide = coach.guide ?: return
    val step = guide.steps.getOrNull(coach.index) ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val target = coach.rectOf(step.target)?.translate(-origin)?.inflate(pad)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            // Nothing under the guide acts, the spotlit control included
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val accent = Palette.accent
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.66f))
            target?.let { r ->
                val radius = CornerRadius(12.dp.toPx())
                drawRoundRect(Color.Transparent, topLeft = r.topLeft, size = Size(r.width, r.height), cornerRadius = radius, blendMode = BlendMode.Clear)
                drawRoundRect(accent, topLeft = r.topLeft, size = Size(r.width, r.height), cornerRadius = radius, style = Stroke(2.dp.toPx()))
            }
        }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        // The text box below the control when it's in the upper half, else above it; centred without one
        var boxHeight by remember(step) { mutableStateOf(0) }
        val y = when {
            target == null -> ((height - boxHeight) / 2).roundToInt()
            target.center.y < height / 2 -> (target.bottom + margin).roundToInt()
            else -> (target.top - margin - boxHeight).roundToInt()
        }.coerceIn(margin.roundToInt(), (height - boxHeight - margin).roundToInt().coerceAtLeast(margin.roundToInt()))
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, y) }, contentAlignment = Alignment.TopCenter) {
            Surface(
                color = Palette.card, shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.border),
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 380.dp).fillMaxWidth()
                    .onGloballyPositioned { boxHeight = it.size.height },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(step.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Text(step.text, color = Palette.textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val last = coach.index == guide.steps.size - 1
                        if (!last) {
                            TextButton(onClick = { coach.finish() }, modifier = Modifier.heightIn(min = UiSize.STANDARD.dp)) {
                                Text(stringResource(R.string.coach_skip), color = Palette.textSecondary)
                            }
                        }
                        val stepText = stringResource(R.string.coach_step, coach.index + 1, guide.steps.size)
                        Row(
                            Modifier.weight(1f).semantics { contentDescription = stepText },
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            guide.steps.indices.forEach { i ->
                                Box(Modifier.size(8.dp).background(if (i == coach.index) Palette.accent else Palette.border, CircleShape))
                            }
                        }
                        Button(
                            onClick = { coach.next() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                            modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                        ) { Text(stringResource(if (last) R.string.coach_done else R.string.coach_next), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
