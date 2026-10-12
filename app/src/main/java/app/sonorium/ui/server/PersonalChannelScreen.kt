package app.sonorium.ui.server

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.listen.ListenController
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's personal channel (native-api.md 1.10): local only, shared by this account's
 * devices, each with its own volume. Loaded with GET /api/me/channel, changed with PUT.
 */
@Composable
fun PersonalChannelScreen(
    model: ServerViewModel,
    state: ServerState,
    listenVolume: Int,
    listening: ListenController.Listening?,
) {
    LaunchedEffect(state.record?.id) { model.refreshPersonal() }
    val channel = state.personal
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("My channel", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            // Neither a server's personal channel nor a theme on this phone: say what's needed
            if (state.record == null || !state.personalChannel) {
                Text(
                    if (state.record == null) "Connect to your Sonorium server to use your account's channel, or put a theme on this phone (Pro) to play it here without a server."
                    else "This server has no personal channel. Put a theme on this phone (Pro) to play it here.",
                    color = Palette.textSecondary, fontSize = 14.sp,
                )
                return@Column
            }
            if (channel == null) {
                state.personalProblem?.let { Text(it, color = Palette.text) } ?: CircularProgressIndicator(color = Palette.accent)
                return@Column
            }
            val on = listening?.let { it.personal && it.playing && it.serverId == state.record?.id } == true
            Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (on) Palette.accent else Palette.border)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldLabel("Theme")
                    PickerField(
                        label = "Theme",
                        options = state.themes.map { PickOption(it.id, it.name) },
                        current = channel.themeId,
                        onPick = { if (it.value != channel.themeId) model.updatePersonal(buildJsonObject { put("theme_id", it.value) }, "Theme updated") },
                        placeholder = "Choose a theme",
                        enabled = state.themes.isNotEmpty(),
                    )
                    FieldLabel("Preset")
                    val options = presetOptions(state, channel.themeId)
                    val hasPresets = (state.presets[channel.themeId]?.presets?.size ?: 0) > 0
                    PickerField(
                        label = "Preset",
                        options = if (hasPresets) options else emptyList(),
                        current = if (hasPresets) channel.choice.takeIf { c -> options.any { it.value == c } } ?: "" else null,
                        onPick = { o ->
                            if (o.value != channel.choice) {
                                val success = when {
                                    o.value.startsWith("seq:") -> "Sequence started"
                                    o.value.isNotEmpty() -> "Preset applied"
                                    else -> "Using default settings"
                                }
                                model.updatePersonal(buildJsonObject { put("preset_id", o.value) }, success)
                            }
                        },
                        placeholder = "No presets available",
                        enabled = hasPresets,
                    )
                    // This device's volume only; each device keeps its own
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(SIcons.Headphones, null, tint = if (on) Palette.accent else Palette.textSecondary, modifier = Modifier.size(18.dp))
                        PercentSlider(listenVolume, "Volume on this device", model::setListenVolume, Modifier.weight(1f).padding(start = 12.dp), onChange = model::setListenVolume)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Announcements", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = channel.announcements,
                            onCheckedChange = { model.updatePersonal(buildJsonObject { put("announcements", it) }) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                        )
                    }
                    // A new personal channel has no theme yet: listening needs one
                    if (channel.themeId == null) Text("Choose a theme", color = Palette.textSecondary, fontSize = 13.sp)
                    Button(
                        onClick = { model.toggleListen(channel, personal = true) },
                        enabled = on || channel.themeId != null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (on) Palette.danger else Palette.accent,
                            contentColor = if (on) Palette.text else Palette.bar,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(if (on) SIcons.Stop else SIcons.Play, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (on) "Stop" else "Play here", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
