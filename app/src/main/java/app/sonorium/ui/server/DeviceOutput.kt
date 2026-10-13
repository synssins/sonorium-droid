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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
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
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
import kotlinx.coroutines.delay

/**
 * Where this device's channel sounds: getting a theme ready (with its progress), what went wrong, and
 * Cast to one speaker. Shown in the channel card when this device's own entry is active.
 */
@Composable
fun DeviceOutput(model: ServerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val s by PhonePlayer.state.collectAsState()
    LaunchedEffect(Unit) { CastController.init(context) }
    s.preparing?.let { (done, total) ->
        Text(
            "Getting “${s.preparingName ?: s.theme?.name.orEmpty()}” ready: $done of $total files",
            color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            color = Palette.accent, trackColor = Palette.border, modifier = Modifier.fillMaxWidth(),
        )
    }
    s.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
    s.notice?.let { Text(it, color = Palette.textSecondary, fontSize = 13.sp) }
    ReadyingLine()
    // The free sample's time for today is used up: said here only (no notification, no sound)
    val pro by model.entitlement.isPro.collectAsState()
    if (s.sampleEnded && !pro) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sample ended for today. It plays again tomorrow, or unlock Pro to keep listening.", color = Palette.text, fontSize = 14.sp)
            Button(
                onClick = { model.proOrAsk() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
            ) { Text("Unlock Pro", fontWeight = FontWeight.SemiBold) }
        }
    }
    if (s.sequenceFinished) Text("Sequence finished. It stays on its last preset.", color = Palette.textSecondary, fontSize = 13.sp)
    CastRow(s.theme?.name.orEmpty(), allow = model::proOrAsk)
}

/** A theme that just arrived on this device being got ready in the background, with its progress. */
@Composable
fun ReadyingLine() {
    val s by PhonePlayer.state.collectAsState()
    val (name, done, total) = s.readying ?: return
    Text("Getting “$name” ready: $done of $total files", color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    LinearProgressIndicator(
        progress = { if (total == 0) 0f else done.toFloat() / total },
        color = Palette.accent, trackColor = Palette.border, modifier = Modifier.fillMaxWidth(),
    )
}


/** How long the speaker picker looks before saying none were found. */
private const val SPEAKER_SEARCH_MS = 10_000L

/**
 * Cast: what the phone plays goes to one speaker on the network, like casting to a TV. Picking
 * another speaker moves it there.
 */
@Composable
private fun CastRow(themeName: String, allow: () -> Boolean = { true }) {
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
        TextButton(onClick = { if (allow()) picking = true }, modifier = Modifier.heightIn(min = UiSize.SMALL.dp)) {
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
