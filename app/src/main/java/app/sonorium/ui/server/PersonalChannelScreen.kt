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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    LaunchedEffect(Unit) { model.refreshLocalThemes() }
    val localThemes by model.localThemes.collectAsState()
    val isPro by model.entitlement.isPro.collectAsState()
    var onPhone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(state.record == null || state.failure != null) }
    var unlock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val channel = state.personal
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("My channel", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            // On this phone (the engine, local themes) and/or On <server> (the account's channel)
            val canServer = state.record != null && state.personalChannel
            val canPhone = isPro && !localThemes.isNullOrEmpty()
            if (canServer && canPhone) {
                Segmented(
                    listOf("On this phone", "On ${state.record?.displayName.orEmpty()}"),
                    if (onPhone) 0 else 1,
                    { onPhone = it == 0 },
                )
            }
            if (canPhone && (onPhone || !canServer)) {
                PhoneView(localThemes.orEmpty())
                return@Column
            }
            // Neither: say what's needed
            if (!canServer) {
                Text(
                    when {
                        isPro -> "Import or download a theme (Themes › On this phone) to play it here, without a server."
                        state.failure != null && state.record != null -> "Can't reach ${state.record.displayName}, so its channel isn't available. " +
                            if (isPro) "" else "Unlock Pro to play themes on this phone without a server."
                        state.record == null -> "Connect to your Sonorium server to use your account's channel, or unlock Pro to play themes on this phone without a server."
                        else -> "This server has no personal channel. Unlock Pro to play themes on this phone."
                    },
                    color = Palette.textSecondary, fontSize = 14.sp,
                )
                if (!isPro) {
                    Button(
                        onClick = { unlock = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                    ) { Text("Unlock Pro") }
                }
                if (unlock) UnlockScreen(model, onClose = { unlock = false }, onUnlocked = { unlock = false })
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
