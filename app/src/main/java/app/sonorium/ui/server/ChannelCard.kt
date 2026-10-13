package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.net.Session
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerState
import app.sonorium.ui.Palette
import app.sonorium.ui.coach.coachTarget
import kotlinx.coroutines.delay

/** Capabilities a Sonorium lists for making and editing channels, and for listening to them from this device. */
const val CAP_CHANNELS = "channels.create"
const val CAP_LISTEN = "listen"

/** Card callbacks; each maps to one row of native-api.md 1.1 "Card actions". */
class CardActions(
    val togglePlay: (Session) -> Unit,
    val setTheme: (Session, String) -> Unit,
    val setPreset: (Session, String) -> Unit,
    val setVolume: (Session, Int) -> Unit,
    val toggleMuted: (Session) -> Unit,
    val toggleListen: (Session) -> Unit,
    val setListenVolume: (Int) -> Unit,
    val next: (Session) -> Unit,
    val themeMode: (Session) -> Unit,
    val playlistMode: (Session) -> Unit,
    val pickPlaylist: (Session, String) -> Unit,
    val edit: (Session) -> Unit,
    val delete: (Session) -> Unit,
    val stopCasting: (Session, String) -> Unit,
    val leaveLink: (Session, String) -> Unit = { _, _ -> },
    val rejoinLink: (Session, String) -> Unit = { _, _ -> },
    /** Mix: the Theme Editor on the channel's theme (null: no Mix button). */
    val mix: ((Session) -> Unit)? = null,
    /** Sleep timer: minutes, or null to cancel (null: no Sleep timer item). */
    val sleep: ((Session, Double?) -> Unit)? = null,
)

/** The preset menu (1.2): Default settings, presets, a separator, then sequences. */
fun presetOptions(state: ServerState, themeId: String?): List<PickOption> {
    val presets = themeId?.let { state.presets[it] } ?: return emptyList()
    return listOf(PickOption("", "Default settings", intrusion = presets.defaultHasIntrusion)) +
        presets.presets.map { PickOption(it.id, it.name, isDefault = it.isDefault, intrusion = it.hasIntrusion) } +
        presets.sequences.mapIndexed { i, q ->
            PickOption("seq:${q.id}", q.name, intrusion = q.hasIntrusion, sequence = true, separatorBefore = i == 0)
        }
}

/** The current time, ticking every second (for countdowns). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun ChannelCard(
    session: Session,
    state: ServerState,
    listening: Boolean,
    listenVolume: Int,
    actions: CardActions,
    onCountdownZero: () -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    /** What plays where this channel's sound comes out (this device: getting ready, Cast), above Play. */
    extra: (@Composable () -> Unit)? = null,
) {
    val playing = session.isPlaying
    val playlistMode = session.playlistId != null || session.id in state.playlistMode
    val zones = state.zones
    val link = zones?.linkOf(session.id)
    var openPlaylistMenu by remember { mutableStateOf(false) }
    // A channel that left stays in `channels` and is listed in `left` too (R4)
    val linked = link != null && session.id in link.channels && session.id !in link.left
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }

    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, when { linked -> Palette.accentSecondary; playing -> Palette.accent; else -> Palette.border }),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Link strip (Zones): Linked · <name> + Leave, or Left the link + Rejoin (1.2)
            if (link != null) {
                Row(
                    Modifier.fillMaxWidth().background(Palette.accentSecondary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SIcons.Link, null, tint = Palette.accentSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                    Text(
                        if (linked) "Linked · ${link.name}" else "Left the link",
                        color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (zones.mayLeave(session.id)) {
                        TextButton(
                            onClick = { if (linked) actions.leaveLink(session, link.id) else actions.rejoinLink(session, link.id) },
                            modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                        ) {
                            Text(if (linked) "Leave" else "Rejoin", color = Palette.text, fontSize = 12.sp)
                        }
                    }
                }
            }
            // Head: icon, name + speakers, state chip, Edit, ⋯
            Row(verticalAlignment = Alignment.CenterVertically) {
                onCollapse?.let {
                    Box(
                        Modifier.size(app.sonorium.ui.UiSize.STANDARD.dp).semantics { contentDescription = "Collapse ${session.name}" }.clickable(onClick = it),
                        contentAlignment = Alignment.Center,
                    ) { Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH.dp)) }
                }
                Box(
                    Modifier.size(app.sonorium.ui.UiSize.SMALL.dp).background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(ChannelRules.themeIcon(session.themeId, state.theme(session.themeId)), fontSize = 22.sp) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(session.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val zone = zones?.zones?.get(session.id)
                    val sub = speakerSummary(session) + (zone?.ownerName?.let { " · 👤 $it" } ?: "")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sub, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        // Child Mode: the orange lock, no words (it's named for TalkBack)
                        if (zone?.kidMode == true) {
                            val label = androidx.compose.ui.res.stringResource(app.sonorium.R.string.child_mode)
                            Text("🔒", color = Palette.warning, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = label })
                        }
                    }
                }
                StateChip(playing, sleepLeft(session))
                // A channel's own settings (name, speakers) and Delete: where channels can be made
                if (state.offers(CAP_CHANNELS)) {
                Spacer(Modifier.width(8.dp))
                IconBox(SIcons.Edit, "Edit", { actions.edit(session) }, size = app.sonorium.ui.UiSize.SMALL)
                }
                if (state.offers(CAP_CHANNELS) || (actions.sleep != null && playing)) {
                Spacer(Modifier.width(6.dp))
                Box {
                    IconBox(SIcons.More, "More for ${session.name}", { menuOpen = true }, size = app.sonorium.ui.UiSize.SMALL)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Palette.card) {
                        // Sleep timer: only a playing channel takes one
                        if (actions.sleep != null && playing) DropdownMenuItem(
                            text = { Text("Sleep timer…", color = Palette.text) },
                            onClick = { menuOpen = false; sleepOpen = true },
                        )
                        if (state.offers(CAP_CHANNELS)) DropdownMenuItem(
                            text = { Text("Delete…", color = Palette.danger) },
                            leadingIcon = { Icon(SIcons.Trash, null, tint = Palette.danger, modifier = Modifier.size(16.dp)) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
                }
            }

            // Cast strips: one per output (1.2)
            session.outputs.forEach { output ->
                Row(
                    Modifier.fillMaxWidth().background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SIcons.Cast, null, tint = Palette.accent, modifier = Modifier.size(16.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(output.name) }
                            append(" · cast from ${output.byDeviceName ?: "the app"}")
                        },
                        color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    // Only once the permissions are known to include ch.play (not before they load)
                    if (state.me?.perms?.contains("ch.play") == true) {
                        TextButton(
                            onClick = { actions.stopCasting(session, output.outputId) },
                            modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                        ) { Text("Stop casting", color = Palette.danger, fontSize = 12.sp) }
                    }
                }
            }

            // Source fields + mode buttons
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlistMode) PlaylistFields(session, state, actions, openMenu = openPlaylistMenu) { openPlaylistMenu = false } else ThemeFields(session, state, actions)
                    // Mix: the Theme Editor on the theme playing now
                    actions.mix?.let { mix ->
                        if (session.themeId != null && state.theme(session.themeId) != null) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { mix(session) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Palette.border),
                                modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)
                                    .semantics { contentDescription = "Mix: edit ${state.theme(session.themeId)?.name.orEmpty()}" },
                            ) {
                                Icon(SIcons.Edit, null, tint = Palette.accent, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mix", color = Palette.text)
                            }
                        }
                    }
                }
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(SIcons.Palette, "Play a theme", on = !playlistMode) { actions.themeMode(session) }
                    // Switch to playlist mode and open the Playlist menu at once (1.2 Mode buttons, R9)
                    ModeButton(SIcons.Playlist, "Play a playlist", on = playlistMode) {
                        actions.playlistMode(session)
                        openPlaylistMenu = true
                    }
                }
            }

            NowLine(session, actions, onCountdownZero)

            // Volume row (speakers)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val muted = session.speakersMuted
                IconBox(
                    if (muted) SIcons.Muted else SIcons.Volume,
                    // This device mutes its own mix (it has no speakers of its own to name)
                    if (state.offers("speakers")) (if (muted) "Unmute the speakers" else "Mute the speakers") else (if (muted) "Unmute" else "Mute"),
                    { actions.toggleMuted(session) },
                    tint = when { muted -> Palette.danger; playing -> Palette.accent; else -> Palette.textSecondary },
                    border = if (muted) Palette.danger else Palette.border,
                    size = app.sonorium.ui.UiSize.SMALL,
                )
                PercentSlider(session.volume, "Volume: ${session.name}", { actions.setVolume(session, it) }, Modifier.weight(1f).padding(start = 8.dp))
            }

            // Listen row (this device): where a channel can be listened to from another device
            if (state.offers(CAP_LISTEN)) Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(
                    SIcons.Headphones, "Listen on this device", { actions.toggleListen(session) },
                    tint = if (listening) Palette.bar else Palette.textSecondary,
                    background = if (listening) Palette.accent else Palette.backgroundSecondary,
                    border = if (listening) Palette.accent else Palette.border,
                    size = app.sonorium.ui.UiSize.SMALL,
                )
                PercentSlider(listenVolume, "Volume on this device: ${session.name}", actions.setListenVolume, Modifier.weight(1f).padding(start = 8.dp), onChange = actions.setListenVolume)
            }

            RecentChanges(session)

            extra?.invoke()

            Button(
                onClick = { actions.togglePlay(session) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (playing) Palette.danger.copy(alpha = 0.22f) else Palette.accent,
                    contentColor = if (playing) Palette.danger else Palette.bar,
                ),
                border = if (playing) BorderStroke(1.dp, Palette.danger.copy(alpha = 0.5f)) else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)
                    .then(Modifier.coachTarget(app.sonorium.ui.coach.Targets.PLAY)),
            ) {
                Icon(if (playing) SIcons.Stop else SIcons.Play, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "Stop" else "Play", fontWeight = FontWeight.Medium)
            }
        }
    }

    if (sleepOpen) SleepTimerDialog(running = session.sleepSecondsLeft != null, onPick = { actions.sleep?.invoke(session, it) }) { sleepOpen = false }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${session.name}”?",
            sub = "The channel stops and is removed for everyone.",
            action = "Delete",
            onConfirm = { actions.delete(session) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Sub line: the server's summary, else "No speakers selected" / "N speaker(s)" (1.2). */
private fun speakerSummary(session: Session): String = session.speakerSummary?.takeIf { it.isNotBlank() }
    ?: if (session.speakers.isEmpty()) "No speakers selected" else "${session.speakers.size} speaker${if (session.speakers.size != 1) "s" else ""}"

/** "Theme · Preset" (or the sequence), "Choose a theme" without one; playlists show their name. */
fun nowPlayingLine(session: Session, state: ServerState): String {
    if (session.playlistId != null) {
        val playlist = session.playlist?.name ?: state.playlist(session.playlistId)?.name ?: "Playlist"
        val theme = state.theme(session.playlist?.themeId ?: session.themeId)?.name
        return listOfNotNull(playlist, theme).joinToString(" · ")
    }
    val theme = state.theme(session.themeId)?.name ?: return "Choose a theme"
    val presets = state.presets[session.themeId]
    val choice = session.choice
    val what = when {
        choice.startsWith("seq:") -> presets?.sequences?.firstOrNull { it.id == choice.removePrefix("seq:") }?.name
        choice.isNotEmpty() -> presets?.presets?.firstOrNull { it.id == choice }?.name
        else -> "Default settings"
    }
    return listOfNotNull(theme, what).joinToString(" · ")
}

/**
 * A collapsed channel (approved design, to-app/2026-10-12-10): chevron, theme icon, name + state pill,
 * what's playing, and a small Play/Stop doing exactly what the card's big button does.
 */
@Composable
fun ChannelRow(session: Session, state: ServerState, onExpand: () -> Unit, onTogglePlay: () -> Unit, modifier: Modifier = Modifier) {
    val playing = session.isPlaying
    val zones = state.zones
    val linked = zones?.linkOf(session.id)?.let { session.id in it.channels && session.id !in it.left } == true
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, when { linked -> Palette.accentSecondary; playing -> Palette.accent; else -> Palette.border }),
        modifier = modifier.clickable(onClick = onExpand),
    ) {
        // 56 tall: 48 controls + 4 above and below (ui-standards, touch minimum 48)
        Row(Modifier.heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(app.sonorium.ui.UiSize.STANDARD.dp).semantics { contentDescription = "Open ${session.name}" }.clickable(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) { Icon(SIcons.ChevronRight, null, tint = Palette.textSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH.dp)) }
            Box(
                Modifier.size(app.sonorium.ui.UiSize.STANDARD.dp).background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(ChannelRules.themeIcon(session.themeId, state.theme(session.themeId)), fontSize = 22.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StateChip(playing)
                }
                val line = nowPlayingLine(session, state)
                Text(
                    line, color = if (session.themeId == null && session.playlistId == null) Palette.textMuted else Palette.textSecondary,
                    fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                color = if (playing) Palette.danger.copy(alpha = 0.22f) else Palette.accent,
                shape = RoundedCornerShape(8.dp),
                border = if (playing) BorderStroke(1.dp, Palette.danger.copy(alpha = 0.5f)) else null,
                modifier = Modifier.size(app.sonorium.ui.UiSize.STANDARD.dp)
                    .coachTarget(app.sonorium.ui.coach.Targets.PLAY)
                    .semantics { contentDescription = if (playing) "Stop ${session.name}" else "Play ${session.name}" }
                    .clickable(onClick = onTogglePlay),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (playing) SIcons.Stop else SIcons.Play, null, tint = if (playing) Palette.danger else Palette.bar, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH.dp))
                }
            }
        }
    }
}

@Composable
private fun StateChip(playing: Boolean, sleepLeft: Int? = null) {
    Box(
        Modifier
            .background(if (playing) Palette.accent.copy(alpha = 0.15f) else Palette.backgroundSecondary, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { if (playing && sleepLeft != null) contentDescription = "Sleep timer: ${ChannelRules.clock(sleepLeft.toLong())} left" },
    ) {
        // While a sleep timer runs: a moon and the time left instead of Playing (as the web UI's pill)
        Text(
            if (playing && sleepLeft != null) "☾ ${ChannelRules.clock(sleepLeft.toLong())}" else if (playing) "Playing" else "Stopped",
            color = if (playing) Palette.accent else Palette.textMuted, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** A sleep timer's seconds left now: counted down from what the server said when the channel was read. */
@Composable
private fun sleepLeft(session: Session): Int? {
    val given = session.sleepSecondsLeft ?: return null
    val readAt = remember(session.sleepSecondsLeft, session.sleepUntil) { android.os.SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(readAt) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return (given - ((now - readAt) / 1000).toInt()).coerceAtLeast(0)
}

/** Sleep timer… (the web UI's): 15 min to 4 h, Custom… (1–240 minutes), and Cancel timer while one runs. */
@Composable
private fun SleepTimerDialog(running: Boolean, onPick: (Double?) -> Unit, onClose: () -> Unit) {
    var custom by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.border)) {
            Column(Modifier.padding(16.dp).widthIn(min = 260.dp)) {
                Text("Sleep timer", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val c = custom
                if (c == null) {
                    listOf("15 min" to 15.0, "30 min" to 30.0, "45 min" to 45.0, "1 h" to 60.0, "2 h" to 120.0, "3 h" to 180.0, "4 h" to 240.0).forEach { (label, minutes) ->
                        TextButton(onClick = { onPick(minutes); onClose() }, modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.ROW.dp)) {
                            Text(label, color = Palette.text, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = { custom = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.ROW.dp)) {
                        Text("Custom…", color = Palette.text, modifier = Modifier.fillMaxWidth())
                    }
                    if (running) TextButton(onClick = { onPick(null); onClose() }, modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.ROW.dp)) {
                        Text("Cancel timer", color = Palette.danger, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val minutes = c.trim().toDoubleOrNull()
                    val ok = minutes != null && minutes >= 1 && minutes <= 240
                    SField(
                        value = c, onValueChange = { custom = it.take(5) }, singleLine = true,
                        placeholder = { Text("Minutes (1–240)", color = Palette.textMuted) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Minutes (1–240)" },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { custom = null }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Cancel", color = Palette.textSecondary) }
                        TextButton(onClick = { if (ok) { onPick(minutes); onClose() } }, enabled = ok, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Set", color = if (ok) Palette.accent else Palette.textMuted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    IconBox(
        icon, description, onClick,
        tint = if (on) Palette.accent else Palette.textSecondary,
        background = if (on) Palette.accent.copy(alpha = 0.12f) else Palette.backgroundSecondary,
        border = if (on) Palette.accent.copy(alpha = 0.6f) else Palette.border,
        size = app.sonorium.ui.UiSize.STANDARD,
    )
}

@Composable
private fun ThemeFields(session: Session, state: ServerState, actions: CardActions) {
    FieldLabel("Theme")
    PickerField(
        label = "Theme",
        options = state.themes.map { PickOption(it.id, it.name) },
        current = session.themeId,
        onPick = { actions.setTheme(session, it.value) },
        placeholder = "Choose a theme",
        enabled = state.themes.isNotEmpty(),
    )
    FieldLabel("Preset")
    val presets = state.presets[session.themeId]
    val hasPresets = (presets?.presets?.size ?: 0) > 0
    val options = presetOptions(state, session.themeId)
    // A current value not in the list shows Default settings (1.2)
    val current = session.choice.takeIf { c -> options.any { it.value == c } } ?: ""
    PickerField(
        label = "Preset",
        options = if (hasPresets) options else emptyList(),
        current = if (hasPresets) current else null,
        onPick = { actions.setPreset(session, it.value) },
        placeholder = "No presets available",
        enabled = hasPresets,
    )
}

@Composable
private fun PlaylistFields(session: Session, state: ServerState, actions: CardActions, openMenu: Boolean = false, onMenuOpened: () -> Unit = {}) {
    FieldLabel("Playlist")
    val options = state.playlists.map {
        PickOption(it.id, it.name, icon = SIcons.Playlist, note = if (it.entries.size == 1) "1 entry" else "${it.entries.size} entries")
    }
    val name = session.playlist?.name ?: state.playlist(session.playlistId)?.name
    PickerField(
        label = "Playlist",
        options = options,
        current = session.playlistId?.takeIf { name != null },
        onPick = { actions.pickPlaylist(session, it.value) },
        placeholder = if (state.playlists.isEmpty()) "No playlists yet" else "Choose a playlist",
        enabled = state.playlists.isNotEmpty(),
        openNow = openMenu,
        onOpened = onMenuOpened,
    )
    FieldLabel("Playing")
    // <theme name> · <what>: the current entry's sequence or preset, else the channel's own (1.2)
    val status = session.playlist
    val themeId = status?.themeId ?: session.themeId
    val playlist = state.playlist(session.playlistId)
    val entry = status?.entryIndex?.let { playlist?.entries?.getOrNull(it) }
    val presetId = entry?.presetId ?: if (entry == null) session.presetId else null
    val sequenceId = entry?.sequenceId ?: if (entry == null) session.sequenceId else null
    val presets = themeId?.let { state.presets[it] }
    val what = sequenceId?.let { id -> presets?.sequences?.firstOrNull { it.id == id }?.name ?: id }
        ?: presetId?.let { id -> presets?.presets?.firstOrNull { it.id == id }?.name ?: id }
        ?: "Default settings"
    Surface(
        color = Palette.backgroundSecondary.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Palette.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                "${state.theme(themeId)?.name ?: themeId.orEmpty()} · $what",
                color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The Now line: entry or step, the countdown, and Next (1.2). */
@Composable
private fun NowLine(session: Session, actions: CardActions, onCountdownZero: () -> Unit) {
    val playlist = if (session.playlistId != null) session.playlist else null
    val sequence = if (session.playlistId != null) null else session.sequence
    val now = rememberNow()
    fun countdown(running: Boolean, at: String?): String? {
        if (!running) return null
        val instant = ChannelRules.parseInstant(at) ?: return null
        val seconds = (instant.toEpochMilli() - now) / 1000
        return ChannelRules.clock(seconds)
    }
    val text = when {
        playlist != null -> buildAnnotatedString {
            append("Entry ${(playlist.position ?: 0) + 1} of ${playlist.entries ?: 0}")
            countdown(playlist.running, playlist.nextChangeAt)?.let { append(" · next in $it") }
        }
        sequence != null -> buildAnnotatedString {
            append("Step ${sequence.step ?: 1} of ${sequence.steps}: ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.text)) { append(sequence.currentPresetName ?: "Default settings") }
            countdown(sequence.running, sequence.nextChangeAt)?.let { append(" · next in $it") }
        }
        else -> null
    }
    // When a countdown reaches 0, refetch (at most every 5 s) until the server moved on (1.5.3)
    val at = (playlist?.takeIf { it.running }?.nextChangeAt ?: sequence?.takeIf { it.running }?.nextChangeAt)
        ?.let { ChannelRules.parseInstant(it)?.toEpochMilli() }
    var lastRefetch by remember { mutableLongStateOf(0L) }
    if (at != null && now >= at && now - lastRefetch >= 5_000) {
        lastRefetch = now
        LaunchedEffect(now) { onCountdownZero() }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
        if (text == null) {
            Text("No sequence or playlist", color = Palette.textMuted, fontSize = 13.sp)
            return@Row
        }
        Text(text, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        val enabled = session.isPlaying || session.listening
        TextButton(
            onClick = { actions.next(session) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
        ) {
            Icon(SIcons.Next, null, tint = if (enabled) Palette.text else Palette.textMuted, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
            Spacer(Modifier.width(4.dp))
            Text("Next", color = if (enabled) Palette.text else Palette.textMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecentChanges(session: Session) {
    Surface(color = Palette.backgroundSecondary, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).heightIn(min = 64.dp)) {
            FieldLabel("Recent changes")
            Spacer(Modifier.height(4.dp))
            session.recent.take(5).forEach { entry ->
                Row {
                    Text(ChannelRules.recentTime(entry.at), color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                    Text(entry.text, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
