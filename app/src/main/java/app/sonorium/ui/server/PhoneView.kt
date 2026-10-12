package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.engine.PhonePlayer
import app.sonorium.local.LocalTheme
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize

/**
 * Playing a theme on this phone (no server needed): the personal channel view's controls, driving the
 * on-phone engine. The theme list is the themes on this phone.
 */
@Composable
fun PhoneView(themes: List<LocalTheme>) {
    val context = LocalContext.current
    val s by PhonePlayer.state.collectAsState()
    // A theme to start with: the last one, else the first on the phone
    LaunchedEffect(themes) {
        val current = s.theme
        if (current == null || themes.none { it.docId == current.docId }) themes.firstOrNull()?.let { PhonePlayer.choose(context, it) }
    }
    val busy = s.preparing != null
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (s.playing) Palette.accent else Palette.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("Theme")
            PickerField(
                label = "Theme",
                options = themes.map { PickOption(it.docId, it.name) },
                current = s.theme?.docId,
                onPick = { o -> themes.firstOrNull { it.docId == o.value }?.let { PhonePlayer.choose(context, it) } },
                placeholder = "Choose a theme",
                enabled = themes.isNotEmpty() && !busy,
            )
            FieldLabel("Preset")
            PickerField(
                label = "Preset",
                options = listOf(PickOption("", "Default settings")) + s.presets.map { PickOption(it.id, it.name, isDefault = it.isDefault) } +
                    s.sequences.mapIndexed { i, q -> PickOption("seq:${q.id}", q.name, sequence = true, separatorBefore = i == 0) },
                current = s.sequenceId?.let { "seq:$it" } ?: s.presetId ?: "",
                onPick = { o ->
                    if (o.value.startsWith("seq:")) PhonePlayer.startSequence(o.value.removePrefix("seq:"))
                    else PhonePlayer.setPreset(o.value.ifEmpty { null })
                },
                placeholder = "Default settings",
                enabled = s.presets.isNotEmpty() && !busy,
            )
            // A running sequence: its step and countdown, with Next (as on a server channel)
            if (s.sequenceId != null && s.playing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.step?.let { st -> "Step ${st.step} of ${st.steps}: ${st.name}" + (st.secondsLeft?.let { " · next in ${countdown(it)}" } ?: "") } ?: "Starting…",
                        color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = PhonePlayer::nextStep, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) {
                        Icon(SIcons.Next, null, tint = Palette.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Next", color = Palette.accent)
                    }
                }
            }
            FieldLabel("Volume")
            PercentSlider(s.volume, "Volume on this phone", onRelease = PhonePlayer::setVolume)
            s.preparing?.let { (done, total) ->
                Text("Getting “${s.theme?.name.orEmpty()}” ready: $done of $total files", color = Palette.textSecondary, fontSize = 13.sp)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    color = Palette.accent, trackColor = Palette.border, modifier = Modifier.fillMaxWidth(),
                )
            }
            s.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
            Button(
                onClick = { if (s.playing || busy) PhonePlayer.stop(context) else PhonePlayer.play(context) },
                enabled = s.theme != null,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (s.playing || busy) Palette.danger.copy(alpha = 0.22f) else Palette.accent,
                    contentColor = if (s.playing || busy) Palette.danger else Palette.bar,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = UiSize.STANDARD.dp),
            ) {
                Icon(if (s.playing || busy) SIcons.Stop else SIcons.Play, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (s.playing || busy) "Stop" else "Play", fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Headphones, null, tint = Palette.textMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Plays on this phone. No server needed.", color = Palette.textMuted, fontSize = 12.sp)
            }
        }
    }
}

/** "4:05" / "1:02:03". */
private fun countdown(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val sec = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
