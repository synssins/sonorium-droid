package app.sonorium.ui.wizard

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize

/**
 * The first-run wizard: a framework only, switched off ([FirstRun.ENABLED] is false) until its steps
 * and texts are designed and approved. Steps are data: add, remove or reorder them in [FirstRun.steps];
 * each step's body is its own composable and may say whether Next is allowed yet.
 */
object FirstRun {
    /** Off until Chris approves the wizard's design. When off, the app never shows it. */
    const val ENABLED = false

    private const val PREFS = "sonorium"
    private const val KEY_DONE = "firstRunDone"

    /** One page of the wizard. [canSkip]: the step shows Skip; [body] draws it and reports via [StepScope]. */
    class Step(
        val id: String,
        val title: String,
        val canSkip: Boolean = true,
        val body: @Composable (StepScope) -> Unit,
    )

    /** What a step's body can do: allow Next, or move on itself (e.g. after linking a server). */
    class StepScope(val setReady: (Boolean) -> Unit, val next: () -> Unit)

    /**
     * The planned steps (placeholders: texts and screens to be designed). See the plan in the handoff repo.
     * 1 Welcome · 2 Connect a server (scan / type / skip) · 3 Notifications (playback controls) ·
     * 4 Play on this phone (Pro, import or download a theme; skip) · 5 Done.
     */
    val steps: List<Step> = listOf(
        Step("welcome", "Welcome to Sonorium", canSkip = false) { Placeholder("Ambient sound for every room, from your Sonorium server or right on this phone.") },
        Step("connect", "Connect your server") { Placeholder("Scan the Link App code from your server, or type its address.") },
        Step("notifications", "Playback controls") { Placeholder("Allow notifications so you can pause from the lock screen.") },
        Step("phone", "Play on this phone") { Placeholder("Download or import a theme to play it here, even away from home.") },
        Step("done", "You're set", canSkip = false) { Placeholder("") },
    )

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** Whether to show the wizard at start: only when enabled and never finished. */
    fun shouldShow(context: Context): Boolean = ENABLED && !isDone(context)
}

@Composable
private fun Placeholder(text: String) {
    if (text.isNotEmpty()) Text(text, color = Palette.textSecondary, fontSize = 14.sp)
}

/** The wizard host: the step's title and body, then Back / Skip / Next (Finish on the last step). */
@Composable
fun FirstRunWizard(onFinish: () -> Unit, steps: List<FirstRun.Step> = FirstRun.steps) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var ready by rememberSaveable(index) { androidx.compose.runtime.mutableStateOf(true) }
    val step = steps[index]
    val last = index == steps.lastIndex
    fun next() = if (last) onFinish() else index++
    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${index + 1} of ${steps.size}", color = Palette.textMuted, fontSize = 12.sp)
                Text(step.title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                step.body(FirstRun.StepScope(setReady = { ready = it }, next = ::next))
                Spacer(Modifier.heightIn(min = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    if (index > 0) OutlinedButton(
                        onClick = { index-- }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border),
                        modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                    ) { Text("Back", color = Palette.text) }
                    Spacer(Modifier.weight(1f))
                    if (step.canSkip && !last) OutlinedButton(
                        onClick = ::next, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border),
                        modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                    ) { Text("Skip", color = Palette.text) }
                    Button(
                        onClick = ::next, enabled = ready, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                    ) { Text(if (last) "Finish" else "Next") }
                }
            }
        }
    }
}
