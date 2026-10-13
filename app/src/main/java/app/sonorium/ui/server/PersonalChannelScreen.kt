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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.engine.PhonePlayer
import app.sonorium.listen.ListenController
import app.sonorium.local.LocalTheme
import app.sonorium.local.ThemeStore
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
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
    /** Opens Themes › On this phone (null: not wired; the Import button here still works). */
    onPhoneThemes: (() -> Unit)? = null,
) {
    LaunchedEffect(state.record?.id) { model.refreshPersonal() }
    LaunchedEffect(Unit) { model.refreshLocalThemes() }
    val localThemes by model.localThemes.collectAsState()
    val folder by model.localFolder.collectAsState()
    val isPro by model.entitlement.isPro.collectAsState()
    val phone by PhonePlayer.state.collectAsState()
    var onPhone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(state.record == null || state.failure != null) }
    val channel = state.personal
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(UiSize.GLYPH.dp))
                Spacer(Modifier.width(10.dp))
                Text("My channel", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            // On this phone (the engine, local themes) and/or On <server> (the account's channel).
            // While the phone plays (or gets a theme ready), its controls always show, so it can be stopped.
            val canServer = state.record != null && state.personalChannel
            val phoneBusy = phone.playing || phone.preparing != null
            val canPhone = phoneBusy || (isPro && !localThemes.isNullOrEmpty())
            if (canServer && canPhone) {
                Segmented(
                    listOf("On this phone", "On ${state.record?.displayName.orEmpty()}"),
                    if (onPhone) 0 else 1,
                    { onPhone = it == 0 },
                )
            }
            if (canPhone && (onPhone || !canServer)) {
                PhoneView(localThemes)
                return@Column
            }
            // No server channel: the server's status, then what this phone can do on its own
            if (!canServer) {
                Text(
                    when {
                        state.record == null -> "No server connected. Connect to your Sonorium server to use your account's channel."
                        state.failure != null -> "Can't reach ${state.record?.displayName.orEmpty()}, so its channel isn't available right now."
                        else -> "${state.record?.displayName.orEmpty()} has no personal channel."
                    },
                    color = Palette.textSecondary, fontSize = 14.sp,
                )
                PhoneOffer(model, isPro, localThemes, folder, hasServer = state.record != null, onPhoneThemes = onPhoneThemes)
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
                        Icon(SIcons.Headphones, null, tint = if (on) Palette.accent else Palette.textSecondary, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
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
                    if (channel.themeId == null) Text("Pick a theme above to play your channel", color = Palette.textSecondary, fontSize = 13.sp)
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
            // With the paid features but nothing on the phone yet: where to get a theme to play without a server
            if (isPro && localThemes?.isEmpty() == true) {
                PhoneOffer(model, isPro, localThemes, folder, hasServer = true, onPhoneThemes = onPhoneThemes, compact = true)
            }
        }
    }
}

/**
 * What this phone can do without a server: the unlock (only here, where it's relevant), else how to get
 * a theme onto the phone (Import theme, or Themes › On this phone). [compact]: a short line under the
 * server's channel.
 */
@Composable
private fun PhoneOffer(
    model: ServerViewModel,
    isPro: Boolean,
    localThemes: List<LocalTheme>?,
    folder: ThemeStore.FolderState,
    hasServer: Boolean,
    onPhoneThemes: (() -> Unit)?,
    compact: Boolean = false,
) {
    var unlock by remember { mutableStateOf(false) }
    val folderPicker = rememberThemesFolderPicker(model)
    val startImport = rememberThemeImport(model, folderPicker)
    val importing by model.importing.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !isPro -> {
                Text("With Pro, this phone plays themes on its own, without a server.", color = Palette.textSecondary, fontSize = 14.sp)
                Button(
                    onClick = { unlock = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                ) { Text("Unlock Pro") }
            }
            folder == ThemeStore.FolderState.MISSING -> {
                Text("Your themes folder can't be found, so the themes on this phone can't play.", color = Palette.textSecondary, fontSize = 14.sp)
                Button(
                    onClick = { onPhoneThemes?.invoke() ?: folderPicker.ask(changing = false, then = null) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                ) { Text(if (onPhoneThemes != null) "Go to themes on this phone" else "Choose folder") }
            }
            localThemes == null -> {}
            localThemes.isEmpty() -> {
                Text(
                    when {
                        compact -> "To play without a server, put a theme on this phone: import a theme zip, or save one from Themes."
                        hasServer -> "No themes on this phone yet. Import a theme zip, or save one from your server's Themes (⋯ › Save to phone), to play it here without a server."
                        else -> "No themes on this phone yet. Import a theme zip to play it here, without a server."
                    },
                    color = Palette.textSecondary, fontSize = if (compact) 13.sp else 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = startImport, enabled = importing == null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                    ) {
                        Icon(SIcons.Upload, null, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (importing != null) "Importing…" else "Import theme", maxLines = 1)
                    }
                    if (onPhoneThemes != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onPhoneThemes, modifier = Modifier.heightIn(min = UiSize.STANDARD.dp)) {
                            Text("Go to themes on this phone", color = Palette.accent, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    if (unlock) UnlockScreen(model, onClose = { unlock = false }, onUnlocked = { unlock = false })
}
