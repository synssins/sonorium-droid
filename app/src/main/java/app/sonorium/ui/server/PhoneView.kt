package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.cast.CastController
import app.sonorium.engine.PhonePlayer
import app.sonorium.local.LocalTheme
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
import kotlinx.coroutines.delay

/**
 * Playing a theme on this phone (no server needed): the personal channel view's controls, driving the
 * on-phone engine. The theme list is the themes on this phone (null while they're being read).
 */
@Composable
fun PhoneView(themes: List<LocalTheme>?) {
    val context = LocalContext.current
    val s by PhonePlayer.state.collectAsState()
    // A theme to start with: the one used last, else the first on the phone
    LaunchedEffect(themes) { themes?.let { PhonePlayer.sync(context, it) } }
    val list = themes.orEmpty()
    val busy = s.preparing != null
    LaunchedEffect(Unit) { CastController.init(context) }
    val cast by CastController.state.collectAsState()
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (s.playing) Palette.accent else Palette.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("Theme")
            PickerField(
                label = "Theme",
                options = list.map { PickOption(it.docId, it.name) },
                current = s.theme?.docId,
                onPick = { o -> list.firstOrNull { it.docId == o.value }?.let { PhonePlayer.choose(context, it) } },
                placeholder = if (list.isEmpty()) "No themes on this phone" else "Choose a theme",
                enabled = list.isNotEmpty() && !busy,
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
                // A theme with sequences but no presets can still run them
                enabled = (s.presets.isNotEmpty() || s.sequences.isNotEmpty()) && !busy,
            )
            // A running sequence: its step and countdown, with Next (as on a server channel)
            if (s.sequenceId != null && s.playing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.step?.let { st -> "Step ${st.step} of ${st.steps}: ${st.name}" + (st.secondsLeft?.let { " · next in ${countdown(it)}" } ?: "") } ?: "Starting…",
                        color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = PhonePlayer::nextStep, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) {
                        Icon(SIcons.Next, null, tint = Palette.accent, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Next", color = Palette.accent)
                    }
                }
            } else if (s.sequenceFinished) {
                Text("Sequence finished. It stays on its last preset.", color = Palette.textSecondary, fontSize = 13.sp)
            }
            FieldLabel("Volume")
            PercentSlider(
                s.volume,
                // Casting: the mix's volume, heard on the speaker
                if (cast.speaker != null) "Volume" else "Volume on this phone",
                onRelease = { PhonePlayer.setVolume(context, it) },
            )
            s.preparing?.let { (done, total) ->
                Text(
                    "Getting “${s.theme?.name.orEmpty()}” ready: $done of $total files",
                    color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    color = Palette.accent, trackColor = Palette.border, modifier = Modifier.fillMaxWidth(),
                )
            }
            s.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
            s.notice?.let { Text(it, color = Palette.textSecondary, fontSize = 13.sp) }
            val stopping = s.playing || busy
            Button(
                onClick = { if (stopping) PhonePlayer.stop(context) else PhonePlayer.play(context) },
                enabled = s.theme != null,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stopping) Palette.danger.copy(alpha = 0.22f) else Palette.accent,
                    contentColor = if (stopping) Palette.danger else Palette.bar,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = UiSize.STANDARD.dp),
            ) {
                Icon(if (stopping) SIcons.Stop else SIcons.Play, null, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (stopping) "Stop" else "Play", fontWeight = FontWeight.SemiBold)
            }
            CastRow(s.theme?.name.orEmpty())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Headphones, null, tint = Palette.textMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (cast.speaker != null) "Mixed on this phone, playing on ${cast.speaker?.name}." else "Plays on this phone. No server needed.",
                    color = Palette.textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
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

/** How long the speaker picker looks before saying none were found. */
private const val SPEAKER_SEARCH_MS = 10_000L

/**
 * Cast: what the phone plays goes to one speaker on the network, like casting to a TV. Picking
 * another speaker moves it there.
 */
@Composable
private fun CastRow(themeName: String) {
    val cast by CastController.state.collectAsState()
    if (!cast.available) return
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(SIcons.Cast, null, tint = if (cast.speaker != null) Palette.accent else Palette.textSecondary, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                cast.speaker == null -> "Not casting"
                cast.streaming -> "Casting to ${cast.speaker?.name}"
                else -> "Connecting to ${cast.speaker?.name}…"
            },
            color = if (cast.speaker != null) Palette.text else Palette.textSecondary, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (cast.speaker != null) {
            TextButton(onClick = { CastController.stop() }, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) { Text("Stop casting", color = Palette.danger) }
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) {
            Text(if (cast.speaker == null) "Cast…" else "Change…", color = Palette.accent)
        }
    }
    cast.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
    if (picking) SpeakerPicker(themeName, onClose = { picking = false })
}

@Composable
private fun SpeakerPicker(themeName: String, onClose: () -> Unit) {
    val cast by CastController.state.collectAsState()
    // Each search (and "Look again") looks for speakers for a while, then says when none were found
    var search by remember { mutableIntStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }
    DisposableEffect(search) {
        CastController.discover(true)
        onDispose { CastController.discover(false) }
    }
    LaunchedEffect(search) {
        timedOut = false
        delay(SPEAKER_SEARCH_MS)
        timedOut = true
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.border)) {
            Column(Modifier.padding(16.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cast to", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("One speaker at a time, on this phone's Wi-Fi.", color = Palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (cast.speakers.isEmpty()) {
                    if (!timedOut) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = UiSize.ROW.dp)) {
                            CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Looking for speakers…", color = Palette.textSecondary, fontSize = 14.sp)
                        }
                    } else {
                        Text(
                            "No speakers found. Make sure this phone and the speaker are on the same Wi-Fi.",
                            color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        TextButton(onClick = { search++ }, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) { Text("Look again", color = Palette.accent) }
                    }
                }
                cast.speakers.forEach { sp ->
                    val current = cast.speaker?.id == sp.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiSize.ROW.dp)
                            .semantics { selected = current }
                            .clickable(role = Role.Button, onClickLabel = "Cast to ${sp.name}") { onClose(); CastController.castTo(sp, themeName) },
                    ) {
                        Icon(SIcons.Speakers, null, tint = if (current) Palette.accent else Palette.textSecondary, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sp.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sp.description.isNotBlank()) Text(sp.description, color = Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (current) Icon(SIcons.Check, null, tint = Palette.accent, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) { Text("Close", color = Palette.textSecondary) }
                }
            }
        }
    }
}
