package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.net.ChannelLink
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerState
import app.sonorium.ui.Palette
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Zones (1.6): the Link channels dialog, for a new link or to change [edit]. Errors show under the form. */
@Composable
fun LinkChannelsDialog(
    edit: ChannelLink?,
    state: ServerState,
    onLoadPresets: (String) -> Unit,
    onSubmit: (linkId: String?, body: JsonObject, onError: (String) -> Unit, onDone: () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val zones = state.zones ?: return
    var name by remember { mutableStateOf(edit?.name ?: "") }
    val channels = remember { mutableStateListOf<String>().apply { addAll(edit?.channels.orEmpty()) } }
    var playlistMode by remember { mutableStateOf(edit?.playlistId != null) }
    var themeId by remember { mutableStateOf(edit?.themeId) }
    var presetId by remember { mutableStateOf(edit?.presetId ?: "") }
    var playlistId by remember { mutableStateOf(edit?.playlistId) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(themeId) { themeId?.takeIf { it !in state.presets }?.let(onLoadPresets) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Column(Modifier.safeDrawingPadding().imePadding().fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Link channels", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconBox(SIcons.Close, "Close", onClose, size = app.sonorium.ui.UiSize.STANDARD)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FieldLabel("Name")
                    OutlinedTextField(
                        value = name, onValueChange = { if (it.length <= 40) name = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                    )
                    FieldLabel("Channels")
                    state.sessions.forEach { s ->
                        val other = zones.linkOf(s.id)?.takeIf { it.id != edit?.id }
                        val zone = zones.zones[s.id]
                        val owner = zone?.owner?.let { id -> zones.users.firstOrNull { it.id == id } }
                        val sub = when {
                            other != null -> "Linked · ${other.name}"
                            zone?.ownerName != null || owner != null ->
                                "${owner?.username ?: zone?.ownerName} · ${if (owner?.canLeaveLink == true) "can leave the link" else "can’t leave the link"}"
                            else -> s.speakerSummary?.takeIf { it.isNotBlank() } ?: "No speakers selected"
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (other != null) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = s.id in channels, enabled = other == null,
                                onCheckedChange = { if (it) channels.add(s.id) else channels.remove(s.id) },
                                colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.bar),
                                modifier = Modifier.semantics { contentDescription = s.name },
                            )
                            Text(ChannelRules.themeIcon(s.themeId, state.theme(s.themeId)), fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sub, color = Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    FieldLabel("Plays")
                    Segmented(listOf("Theme", "Playlist"), if (playlistMode) 1 else 0, { playlistMode = it == 1 })
                    if (playlistMode) {
                        PickerField("Playlist", state.playlists.map { PickOption(it.id, it.name) }, playlistId, { playlistId = it.value }, placeholder = "Choose a playlist")
                    } else {
                        PickerField(
                            "Theme", state.themes.map { PickOption(it.id, "${ChannelRules.themeIcon(it.id, it)}  ${it.name}") }, themeId,
                            {
                                themeId = it.value
                                // Picking a theme preselects its default preset
                                presetId = state.presets[it.value]?.presets?.firstOrNull { p -> p.isDefault }?.id ?: ""
                            },
                            placeholder = "Choose a theme",
                        )
                        val presets = themeId?.let { state.presets[it] }
                        LaunchedEffect(presets) {
                            if (presets != null && presetId.isEmpty() && edit?.themeId != themeId) {
                                presetId = presets.presets.firstOrNull { it.isDefault }?.id ?: ""
                            }
                        }
                        PickerField(
                            "Preset",
                            listOf(PickOption("", "Default settings")) + presets?.presets.orEmpty().map { PickOption(it.id, it.name, isDefault = it.isDefault) },
                            presetId, { presetId = it.value }, enabled = themeId != null,
                        )
                    }
                    Text(
                        "Each channel's volume is independent. Unlinking puts each channel back to what it was playing.",
                        color = Palette.textSecondary, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                    )
                    error?.let { Text(it, color = Palette.danger, fontSize = 14.sp) }
                    Spacer(Modifier.heightIn(min = 16.dp))
                }
                HorizontalDivider(color = Palette.border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp)) { Text("Cancel", color = Palette.text) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            val body = buildJsonObject {
                                put("name", name.trim())
                                put("channels", buildJsonArray { channels.forEach { add(JsonPrimitive(it)) } })
                                if (playlistMode) {
                                    put("playlist_id", playlistId?.let { JsonPrimitive(it) } ?: JsonNull)
                                } else {
                                    put("theme_id", themeId?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("preset_id", presetId.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
                                }
                            }
                            onSubmit(edit?.id, body, { busy = false; error = it }, { busy = false; onClose() })
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    ) { Text("Link and play", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}
