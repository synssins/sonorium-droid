package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The three audio settings (crossfade s, default volume %, master gain %). */
/** [stereo]: null when this Sonorium has no Stereo setting (shown only where it has). */
data class AudioValues(val crossfade: Double, val volume: Int, val gain: Int, val stereo: Boolean? = null) {
    companion object {
        val DEFAULTS = AudioValues(3.0, 60, 60)
    }
}

private fun oneDecimal(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

/** Settings › Audio Settings (native-api.md Phase 3): a draft, saved with Save settings. */
@Composable
fun AudioPage(model: ServerViewModel) {
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    val saved = model.audioSaved
    val draft = model.audioDraft
    val settingsChanged by model.settingsChanged.collectAsState()
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(settingsChanged, attempt) {
        failed = false
        model.call { it.json("GET", "settings") }.onSuccess { o ->
            fun n(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val v = AudioValues(
                n("crossfade_duration") ?: 3.0, n("default_volume")?.roundToInt() ?: 60, n("master_gain")?.roundToInt() ?: 60,
                (o["stereo"] as? JsonPrimitive)?.booleanOrNull,
            )
            // a clean draft follows the server; an unsaved one stays
            if (model.audioDraft == null || model.audioDraft == model.audioSaved) model.audioDraft = v
            model.audioSaved = v
        }.onFailure { failed = true } // never show defaults as if they were the server's (QA 9)
    }
    val dirty = saved != null && draft != null && draft != saved

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            if (saved == null) {
                PageHeader("Audio Settings", null, help = "settings-audio") {}
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) {
                        Text("Couldn't load the audio settings", color = Palette.text, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { attempt++ }, border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) {
                            Text("Try again", color = Palette.text)
                        }
                    } else {
                        androidx.compose.material3.CircularProgressIndicator(color = Palette.accent)
                    }
                }
                return@Column
            }
            PageHeader("Audio Settings", null, help = "settings-audio") {
                MoreMenu("More", listOf(MenuAction("Reset to defaults…", SIcons.Refresh, enabled = draft != null && draft != AudioValues.DEFAULTS) {
                    ask = Ask("Reset to defaults?", "Crossfade 3.0 s · Volume 60% · Gain 60%", "Reset") {
                        model.audioDraft = AudioValues.DEFAULTS
                        model.toast("Defaults restored · not saved yet", error = false)
                    }
                }))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (dirty) {
                    Dot(Palette.warning)
                    Text("Unsaved changes", color = Palette.warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = { model.audioDraft = saved }, enabled = dirty,
                    shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                ) { Text("Cancel", color = if (dirty) Palette.text else Palette.textMuted) }
                Button(
                    onClick = {
                        val d = draft ?: return@Button
                        scope.launch {
                            model.call { it.json("PUT", "settings", buildJsonObject {
                                put("crossfade_duration", d.crossfade)
                                put("default_volume", d.volume)
                                put("master_gain", d.gain)
                                d.stereo?.let { put("stereo", it) }
                            }) }.onSuccess {
                                model.audioSaved = d
                                model.toast("Audio settings saved", error = false)
                            }.onFailure { model.toast(model.messageOf(it, "Failed to save settings"), error = true) }
                        }
                    },
                    enabled = dirty,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                ) { Text("Save settings", fontWeight = FontWeight.SemiBold) }
            }
            if (draft != null && saved != null) {
                Column(Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    AudioRow(
                        "Crossfade", "Fade when changing themes", if (narrow) "Fade between themes · default 3.0 s" else "Default 3.0 s",
                        draft.crossfade.toFloat(), 0f..10f, 19, "${oneDecimal(draft.crossfade)} s", draft.crossfade != saved.crossfade,
                    ) { model.audioDraft = draft.copy(crossfade = (it * 2).roundToInt() / 2.0) }
                    AudioRow(
                        "Default volume", "Starting volume for new channels", if (narrow) "New channels · default 60%" else "Default 60%",
                        draft.volume.toFloat(), 0f..100f, 19, "${draft.volume}%", draft.volume != saved.volume,
                    ) { model.audioDraft = draft.copy(volume = (it / 5).roundToInt() * 5) }
                    AudioRow(
                        "Master output gain", "Multiplier for every stream", if (narrow) "All streams · default 60%" else "Default 60%",
                        draft.gain.toFloat(), 0f..100f, 19, "${draft.gain}%", draft.gain != saved.gain,
                    ) { model.audioDraft = draft.copy(gain = (it / 5).roundToInt() * 5) }
                    // Stereo: where this Sonorium (or this device) has the setting
                    draft.stereo?.let { on ->
                        Row(Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.ROW.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.audio_stereo), color = if (on != saved.stereo) Palette.warning else Palette.text, fontSize = 14.sp)
                                Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.audio_stereo_tip), color = Palette.textSecondary, fontSize = 12.sp)
                            }
                            androidx.compose.material3.Switch(
                                checked = on, onCheckedChange = { model.audioDraft = draft.copy(stereo = it) },
                                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                            )
                        }
                    }
                }
            }
            // This phone's own setting (not the server's): the volume buttons and a server channel (item 15)
            val onDevice = model.state.collectAsState().value.onDevice
            if (!onDevice) {
                val keys by model.volumeKeys.collectAsState()
                Spacer(Modifier.height(16.dp))
                Text(
                    androidx.compose.ui.res.stringResource(app.sonorium.R.string.volume_keys_section).uppercase(), color = Palette.textMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                        .padding(12.dp).heightIn(min = app.sonorium.ui.UiSize.ROW.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.volume_keys_title), color = Palette.text, fontSize = 14.sp)
                        Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.volume_keys_tip), color = Palette.textSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Switch(
                        checked = keys, onCheckedChange = { model.setVolumeKeys(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                    )
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

@Composable
private fun AudioRow(label: String, tip: String, defaultText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, shown: String, changed: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.text, fontSize = 16.sp, modifier = Modifier.semantics { contentDescription = "$label. $tip" })
            if (changed) {
                Spacer(Modifier.width(6.dp))
                androidx.compose.foundation.layout.Box(Modifier.semantics { contentDescription = "Changed, not saved" }) { Dot(Palette.warning) }
            }
            Spacer(Modifier.weight(1f))
            Text(shown, color = Palette.text, fontSize = 14.sp, modifier = Modifier.widthIn(min = 56.dp))
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.border),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
        Text(defaultText, color = Palette.textMuted, fontSize = 12.sp)
    }
}
