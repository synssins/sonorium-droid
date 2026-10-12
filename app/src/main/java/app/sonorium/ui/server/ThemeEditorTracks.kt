package app.sonorium.ui.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.net.EditorTrack
import app.sonorium.net.TrackGroup
import app.sonorium.server.EditorUi
import app.sonorium.server.ThemeEditor
import app.sonorium.server.ThemeEditorRules
import app.sonorium.server.ThemeEditorRules.Section
import app.sonorium.ui.Palette
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A confirm the editor asks (`spConfirm`). */
data class Ask(val title: String, val sub: String?, val action: String, val danger: Boolean = true, val onOk: () -> Unit)

/** Shared by the rows and groups of the list. */
class ListHooks(
    val editor: ThemeEditor,
    val ask: (Ask) -> Unit,
    val upload: (String?) -> Unit,
    /** Group master values while a master slider is dragged: `"<group>|volume"` → 0..1. */
    val live: SnapshotStateMap<String, Double>,
    var deleting: String?,
    val setDeleting: (String?) -> Unit,
)

/** The four sections (`TE_SECTIONS`), each with its groups, then its ungrouped tracks. */
fun LazyListScope.editorSections(ui: EditorUi, hooks: ListHooks) {
    val bySection = ui.tracks.groupBy { ui.sectionOf(it) }
    Section.entries.forEach { section ->
        val tracks = bySection[section].orEmpty()
        val groups = if (section.groupMode == null) emptyList() else ui.listedGroups.filter { Section.ofGroupMode(ThemeEditorRules.groupMode(it)) == section }
        item(key = "section-${section.mode}") { SectionHeader(section, tracks.size, ui, hooks) }
        groups.forEach { g ->
            val members = tracks.filter { ThemeEditorRules.groupOf(it) == g.name }
            item(key = "group-${g.name}") { GroupBlock(g, section, members, ui, hooks) }
            if (g.name !in ui.collapsed) {
                if (members.isEmpty()) item(key = "group-empty-${g.name}") {
                    Text("Drag files here", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp, top = 6.dp, bottom = 10.dp))
                }
                members.forEach { t -> item(key = "track-${t.name}") { TrackRow(t, section, g, ui, hooks, inGroup = true) } }
            }
        }
        val loose = tracks.filter { t -> ui.listedGroupOf(t) == null }
        loose.forEach { t -> item(key = "track-${t.name}") { TrackRow(t, section, null, ui, hooks, inGroup = false) } }
        if (tracks.isEmpty() && groups.isEmpty()) item(key = "empty-${section.mode}") {
            Text(section.empty, color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun SectionHeader(section: Section, count: Int, ui: EditorUi, hooks: ListHooks) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        HorizontalDivider(color = Palette.border)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(section.label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            CountBadge(count)
            Spacer(Modifier.weight(1f))
            if (section.groupMode != null && ui.groupsOk) {
                OutlinedButton(
                    onClick = { hooks.editor.newGroup(section) },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.border),
                    modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = section.newGroupTip },
                ) {
                    Icon(SIcons.Folder, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New group", color = Palette.text, fontSize = 13.sp)
                }
            }
        }
        Text(section.description, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------- Track row (`teRenderRow`) ----------

@Composable
private fun TrackRow(t: EditorTrack, section: Section, group: TrackGroup?, ui: EditorUi, hooks: ListHooks, inGroup: Boolean) {
    val editor = hooks.editor
    val groupMuted = group?.let { ThemeEditorRules.bool(it.settings, "muted") } == true
    val previewing = ui.filePreview == t.name
    val enabled = !t.missing
    val volMaster = hooks.live["${group?.name}|volume"] ?: ThemeEditorRules.master(group, "volume")
    val presMaster = hooks.live["${group?.name}|presence"] ?: ThemeEditorRules.master(group, "presence")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inGroup) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            .background(if (previewing) Palette.accent.copy(alpha = 0.10f) else Palette.card, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                if (previewing) SIcons.Stop else SIcons.Play,
                if (t.missing) "File missing" else if (previewing) "Stop preview" else "Preview",
                { editor.toggleFilePreview(t.name) },
                enabled = enabled, size = 36,
                tint = if (previewing) Palette.accent else Palette.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).alpha(if (t.muted || groupMuted) 0.55f else 1f)) {
                Text(t.shortName, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.linked?.let { l ->
                    if (l.missing) {
                        Text("Missing", color = Palette.warning, fontSize = 12.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Linked from ${l.themeName}" }) {
                            Icon(SIcons.Link, null, tint = Palette.textMuted, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("from ${l.themeName}", color = Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (section == Section.Unassigned && !t.missing) {
                Text(ThemeEditorRules.formatLength(t.durationSeconds), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
            ModeCell(t, section, group, enabled, editor)
            if (section != Section.Unassigned) {
                IconBox(
                    if (t.muted) SIcons.Muted else SIcons.Volume, if (t.muted) "Unmute" else "Mute", { editor.toggleMute(t) },
                    enabled = enabled, size = 36, tint = if (t.muted) Palette.danger else Palette.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
            }
            TrackMenu(t, section, group, ui, hooks)
        }
        if (section != Section.Unassigned) {
            Column(Modifier.alpha(if (t.muted || groupMuted) 0.55f else 1f)) {
                if (section == Section.EbbFlow) {
                    DualVolume(t, volMaster, enabled, editor)
                } else {
                    LabelledSlider(
                        "Volume", (t.volume * 100).roundToInt(), "Volume: ${t.shortName}", enabled,
                        valueText = { "$it%" },
                        hint = { v -> if (volMaster < 0.999) "plays at ${ThemeEditorRules.playsAt(v / 100.0, volMaster)}%" else null },
                        onRelease = { editor.setVolume(t.name, it) },
                    )
                }
                section.second?.let { name ->
                    val ebb = section == Section.EbbFlow
                    LabelledSlider(
                        name, (t.presence * 100).roundToInt(), "$name: ${t.shortName}", enabled,
                        valueText = { if (ebb) null else "$it%" },
                        hint = { v -> if (!ebb && presMaster < 0.999) "plays at ${ThemeEditorRules.playsAt(v / 100.0, presMaster)}%" else null },
                        ends = if (ebb) "Shorter" to "Longer" else null,
                        onRelease = { editor.setPresence(t.name, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCell(t: EditorTrack, section: Section, group: TrackGroup?, enabled: Boolean, editor: ThemeEditor) {
    if (group != null) {
        Text(section.groupLabel, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Set by its group" })
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.heightIn(min = 36.dp).semantics { contentDescription = "Mode: ${section.label}. Moves the track to that section" }) {
            Text(section.label, color = Palette.text, fontSize = 12.sp, maxLines = 1)
            Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card) {
            Section.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, color = if (s == section) Palette.accent else Palette.text) },
                    onClick = {
                        open = false
                        if (s != section) editor.setMode(t, s)
                    },
                )
            }
        }
    }
}

/** Ebb & Flow Volume: low and high knobs (`Rises and falls between these two levels`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualVolume(t: EditorTrack, master: Double, enabled: Boolean, editor: ThemeEditor) {
    val low0 = (minOf(t.volume, t.volumeLow) * 100).roundToInt()
    val high0 = (t.volume * 100).roundToInt()
    var dragging by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(low0.toFloat()..high0.toFloat()) }
    if (!dragging) range = low0.toFloat()..high0.toFloat()
    val lo = range.start.roundToInt()
    val hi = range.endInclusive.roundToInt()
    Column(Modifier.semantics { contentDescription = "Rises and falls between these two levels" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            RangeSlider(
                value = range,
                onValueChange = { dragging = true; range = it },
                onValueChangeFinished = {
                    dragging = false
                    val l = range.start.roundToInt()
                    val h = range.endInclusive.roundToInt()
                    if (h != high0) editor.setVolume(t.name, h)
                    if (l != low0) editor.setVolumeLow(t.name, l)
                },
                valueRange = 0f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.border),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Lowest volume: ${t.shortName}, Highest volume: ${t.shortName}" },
            )
            Text("$lo–$hi%", color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp).padding(start = 6.dp))
        }
        if (master < 0.999) {
            Text(
                "plays at ${ThemeEditorRules.playsAt(lo / 100.0, master)}–${ThemeEditorRules.playsAt(hi / 100.0, master)}%",
                color = Palette.textMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 64.dp),
            )
        }
    }
}

/** A 0–100 slider saved on release, with its value, a `plays at` hint and optional end labels. */
@Composable
fun LabelledSlider(
    label: String,
    value: Int,
    description: String,
    enabled: Boolean,
    valueText: (Int) -> String?,
    hint: (Int) -> String? = { null },
    ends: Pair<String, String>? = null,
    onLive: (Int) -> Unit = {},
    onRelease: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    if (!dragging) local = value.toFloat()
    val v = local.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = local,
                onValueChange = { dragging = true; local = it; onLive(it.roundToInt()) },
                onValueChangeFinished = { dragging = false; if (local.roundToInt() != value) onRelease(local.roundToInt()) },
                valueRange = 0f..100f,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.border),
                modifier = Modifier.weight(1f).semantics { contentDescription = description },
            )
            Text(valueText(v) ?: "", color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp).padding(start = 6.dp))
        }
        if (ends != null) {
            Row(Modifier.padding(start = 64.dp, end = 70.dp)) {
                Text(ends.first, color = Palette.textMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(ends.second, color = Palette.textMuted, fontSize = 11.sp)
            }
        }
        hint(v)?.let { Text(it, color = Palette.textMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 64.dp)) }
    }
}

// ---------- A track's ⋯ menu ----------

@Composable
private fun TrackMenu(t: EditorTrack, section: Section, group: TrackGroup?, ui: EditorUi, hooks: ListHooks) {
    val editor = hooks.editor
    val link = t.linked != null
    val on = !t.missing
    var open by remember { mutableStateOf(false) }
    val dot = ThemeEditorRules.advancedDot(t, section, group != null)
    Box {
        Box {
            IconBox(SIcons.More, "More for ${t.shortName}", { open = true }, size = 36)
            if (dot) Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { Dot(Palette.accent, 6) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 260.dp)) {
            MenuNumberRow("Fade in", "Seconds it fades in each time the file starts", t.fadeIn, on) { editor.setFade(t, true, it) }
            MenuNumberRow("Fade out", "Seconds it fades out each time the file ends", t.fadeOut, on) { editor.setFade(t, false, it) }
            if (section == Section.EbbFlow) {
                HorizontalDivider(color = Palette.border)
                val groupRise = group?.let { ThemeEditorRules.num(it.settings, "rise") }
                MenuSlider(
                    "Rise", ((groupRise ?: t.rise) * 100).roundToInt(),
                    "How fast it rises: gradual, as smoothly as it falls, or sudden, jumping up and easing back down",
                    enabled = on && groupRise == null, note = if (groupRise != null) "set by group" else null,
                ) { editor.setRise(t, it) }
                HorizontalDivider(color = Palette.border)
                val groupPause = group?.let { ThemeEditorRules.bool(it.settings, "pause_between") } == true
                MenuSwitch(
                    "Pause between plays", if (groupPause) true else t.pauseBetween,
                    if (groupPause) "The group's Pause between plays is on" else "Plays to the end of the file, then pauses",
                    enabled = on && !groupPause, note = if (groupPause) "set by group" else null,
                ) { open = false; editor.togglePauseBetween(t) }
                PauseRow(t, on) { a, b -> editor.setPause(t, a, b) }
            }
            if ((group == null || link) && ThemeEditorRules.gaplessAllowed(t)) {
                HorizontalDivider(color = Palette.border)
                MenuSwitch("Gapless", t.seamlessLoop, "Loop without a crossfade", enabled = on) { open = false; editor.toggleGapless(t) }
            }
            if (t.exclusive) {
                MenuSwitch("One at a time (legacy)", true, "Older themes: only one of these tracks plays at a time. Use a group instead.", enabled = on) {
                    open = false
                    editor.exclusiveOff(t)
                }
            }
            if (ui.groupsOk) {
                HorizontalDivider(color = Palette.border)
                Text("Move to", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val here = ThemeEditorRules.groupOf(t)?.takeIf { group != null }
                MoveItem("Unassigned", SIcons.Folder, here == null, on) { open = false; editor.moveTrack(t, null) }
                ui.listedGroups.forEach { g ->
                    MoveItem(g.name, SIcons.Folder, here == g.name, on) { open = false; editor.moveTrack(t, g.name) }
                }
                if (!link && section.groupMode != null) {
                    MoveItem("New group", SIcons.Plus, false, on) { open = false; editor.newGroup(section, t) }
                }
            }
            HorizontalDivider(color = Palette.border)
            DropdownMenuItem(
                enabled = on,
                leadingIcon = { Icon(SIcons.Refresh, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text("Reset track", color = if (on) Palette.text else Palette.textMuted) },
                onClick = { open = false; editor.resetTrack(t) },
            )
            if (link) {
                HorizontalDivider(color = Palette.border)
                DropdownMenuItem(
                    leadingIcon = { Icon(SIcons.Link, null, tint = Palette.danger, modifier = Modifier.size(16.dp)) },
                    text = { Text("Remove link…", color = Palette.danger) },
                    onClick = {
                        open = false
                        hooks.ask(Ask("Remove link to ${t.shortName}?", null, "Remove") { editor.removeLink(t) })
                    },
                )
            }
        }
    }
}

@Composable
private fun MoveItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, here: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled && !here,
        leadingIcon = { Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
        trailingIcon = if (here) ({ Text("here", color = Palette.textMuted, fontSize = 12.sp) }) else null,
        text = { Text(label, color = if (enabled && !here) Palette.text else Palette.textMuted) },
        onClick = onClick,
    )
}

/** A number field in a menu (`Fade in`, `Off`, `s`): saved on Done or when it loses focus. */
@Composable
private fun MenuNumberRow(label: String, tip: String, seconds: Double, enabled: Boolean, onCommit: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = tip }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        NumberBox(if (seconds == 0.0) "" else fmt(seconds), "Off", enabled, "$label (seconds)", onCommit = onCommit)
        Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PauseRow(t: EditorTrack, enabled: Boolean, onCommit: (String, String) -> Unit) {
    var min by remember(t.pauseMin) { mutableStateOf(fmt(t.pauseMin)) }
    var max by remember(t.pauseMax) { mutableStateOf(fmt(t.pauseMax)) }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { contentDescription = "Stays quiet this long, in seconds, picked at random each time" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Pause", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        NumberBox(min, "30", enabled, "Shortest pause (seconds)") { min = it; onCommit(it, max) }
        Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        NumberBox(max, "300", enabled, "Longest pause (seconds)") { max = it; onCommit(min, it) }
        Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A small number input that reports its text on Done or on focus loss (only when changed). */
@Composable
fun NumberBox(initial: String, placeholder: String, enabled: Boolean, description: String, width: Int = 64, error: Boolean = false, onCommit: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    fun commit() { if (text != initial) onCommit(text) }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .width(width.dp)
            .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused }
            .semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(Palette.backgroundSecondary, RoundedCornerShape(6.dp))
                    .border(1.dp, if (error) Palette.danger else Palette.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (text.isEmpty()) Text(placeholder, color = Palette.textMuted, fontSize = 14.sp)
                inner()
            }
        },
    )
}

@Composable
private fun MenuSwitch(label: String, checked: Boolean, tip: String, enabled: Boolean, note: String? = null, onToggle: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp).semantics { contentDescription = tip },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Palette.text else Palette.textMuted, fontSize = 14.sp)
            note?.let { Text(it, color = Palette.textMuted, fontSize = 11.sp) }
        }
        Switch(
            checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
        )
    }
}

/** Rise: `Gradual` … `Sudden`, 0–100 ↔ 0..1. */
@Composable
private fun MenuSlider(label: String, value: Int, tip: String, enabled: Boolean, note: String? = null, onRelease: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(min = 240.dp)) {
        Row {
            Text(label, color = if (enabled) Palette.text else Palette.textMuted, fontSize = 14.sp)
            note?.let { Text("  $it", color = Palette.textMuted, fontSize = 11.sp) }
        }
        LabelledSlider("", value, tip, enabled, valueText = { null }, ends = "Gradual" to "Sudden", onRelease = onRelease)
    }
}

private fun fmt(v: Double): String = if (v == Math.floor(v)) "${v.toLong()}" else "$v"

// ---------- A group (`teRenderGroup`) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupBlock(g: TrackGroup, section: Section, members: List<EditorTrack>, ui: EditorUi, hooks: ListHooks) {
    val editor = hooks.editor
    val collapsed = g.name in ui.collapsed
    val muted = ThemeEditorRules.bool(g.settings, "muted") == true
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Palette.backgroundSecondary, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (hooks.deleting == g.name) {
            Text("Delete ${g.name}? Tracks move to the theme.", color = Palette.text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                TextButton(onClick = { hooks.setDeleting(null) }) { Text("Cancel", color = Palette.textSecondary) }
                TextButton(onClick = { hooks.setDeleting(null); editor.deleteGroup(g.name) }) { Text("Delete", color = Palette.danger) }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(if (collapsed) SIcons.ChevronRight else SIcons.ChevronDown, if (collapsed) "Expand ${g.name}" else "Collapse ${g.name}", { editor.toggleCollapsed(g.name) }, size = 36)
            Spacer(Modifier.width(6.dp))
            Icon(SIcons.Folder, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            if (ui.renaming == g.name) {
                RenameField(g.name, onDone = { editor.renameGroup(g.name, it) }, onCancel = { editor.startRename(null) }, modifier = Modifier.weight(1f))
            } else {
                Text(
                    g.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .combinedClickable(onClick = {}, onDoubleClick = { editor.startRename(g.name) }, onLongClick = { editor.startRename(g.name) })
                        .semantics { contentDescription = "${g.name}. Double-click to rename" },
                )
                Spacer(Modifier.width(6.dp))
                Text(if (members.size == 1) "1 track" else "${members.size} tracks", color = Palette.textMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
            }
            Text(section.groupLabel, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "Set by the section" })
            IconBox(
                if (muted) SIcons.Muted else SIcons.Volume, if (muted) "Unmute group" else "Mute group",
                { editor.setGroup(g.name, ThemeEditorRules.obj("muted" to JsonPrimitive(!muted))) },
                size = 36, tint = if (muted) Palette.danger else Palette.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            GroupMenu(g, section, hooks)
        }
        GroupTiming(g, section, editor)
        Column(Modifier.alpha(if (muted) 0.55f else 1f)) {
            LabelledSlider(
                "Volume", (ThemeEditorRules.master(g, "volume") * 100).roundToInt(), "Group volume: ${g.name}", true,
                valueText = { "$it%" },
                onLive = { hooks.live["${g.name}|volume"] = it / 100.0 },
                onRelease = { editor.setGroup(g.name, ThemeEditorRules.obj("volume" to JsonPrimitive(it / 100.0))); hooks.live.remove("${g.name}|volume") },
            )
            section.second?.let { name ->
                val ebb = section == Section.EbbFlow
                LabelledSlider(
                    name, (ThemeEditorRules.master(g, "presence") * 100).roundToInt(), "Group ${name.lowercase()}: ${g.name}", true,
                    valueText = { if (ebb) null else "$it%" },
                    ends = if (ebb) "Shorter" to "Longer" else null,
                    onLive = { hooks.live["${g.name}|presence"] = it / 100.0 },
                    onRelease = { editor.setGroup(g.name, ThemeEditorRules.obj("presence" to JsonPrimitive(it / 100.0))); hooks.live.remove("${g.name}|presence") },
                )
            }
        }
    }
}

/** Gap (Intermittent), Crossfade (Merry-go-round) or Pause between plays (Ebb & Flow), on its own line on a phone. */
@Composable
private fun GroupTiming(g: TrackGroup, section: Section, editor: ThemeEditor) {
    Row(Modifier.padding(start = 42.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        when (section) {
            Section.Intermittent -> {
                var min by remember(g.settings) { mutableStateOf(ThemeEditorRules.minutes(ThemeEditorRules.num(g.settings, "gap_min"))) }
                var max by remember(g.settings) { mutableStateOf(ThemeEditorRules.minutes(ThemeEditorRules.num(g.settings, "gap_max"))) }
                Text("Gap", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                NumberBox(min, "2", true, "Shortest gap (minutes): ${g.name}", width = 56) { min = it; editor.setGap(g, it, max) }
                Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                NumberBox(max, "2", true, "Longest gap (minutes): ${g.name}", width = 56) { max = it; editor.setGap(g, min, it) }
                Text("min", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Section.Background -> {
                Text("Crossfade", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                NumberBox(ThemeEditorRules.num(g.settings, "crossfade")?.let { fmt(it) } ?: "", "10", true, "Crossfade (seconds): ${g.name}", width = 56) { editor.setCrossfade(g, it) }
                Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Section.EbbFlow -> {
                val on = ThemeEditorRules.bool(g.settings, "pause_between") == true
                Text("Pause between plays", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = on,
                    onCheckedChange = { editor.setGroup(g.name, ThemeEditorRules.obj("pause_between" to JsonPrimitive(!on))) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                    modifier = Modifier.semantics { contentDescription = "Every file pauses between plays, at staggered times, so one is always playing" },
                )
            }
            Section.Unassigned -> Unit
        }
    }
}

@Composable
private fun GroupMenu(g: TrackGroup, section: Section, hooks: ListHooks) {
    val editor = hooks.editor
    var open by remember { mutableStateOf(false) }
    Box {
        IconBox(SIcons.More, "More for group ${g.name}", { open = true }, size = 36)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 240.dp)) {
            if (section == Section.EbbFlow) {
                val rise = ThemeEditorRules.num(g.settings, "rise")
                MenuSwitch("Same Rise for every file", rise != null, "Off: each file's own Rise", enabled = true) {
                    editor.setGroup(g.name, ThemeEditorRules.obj("rise" to if (rise == null) JsonPrimitive(0.5) else JsonNull))
                }
                if (rise != null) {
                    MenuSlider("Rise", (rise * 100).roundToInt(), "How fast every file rises", enabled = true) {
                        editor.setGroup(g.name, ThemeEditorRules.obj("rise" to JsonPrimitive(it / 100.0)))
                    }
                }
                HorizontalDivider(color = Palette.border)
            }
            DropdownMenuItem(
                leadingIcon = { Icon(SIcons.Edit, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text("Rename…", color = Palette.text) },
                onClick = { open = false; editor.startRename(g.name) },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(SIcons.Upload, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text("Upload into ${g.name}", color = Palette.text) },
                onClick = { open = false; hooks.upload(g.name) },
            )
            HorizontalDivider(color = Palette.border)
            DropdownMenuItem(
                leadingIcon = { Icon(SIcons.Trash, null, tint = Palette.danger, modifier = Modifier.size(16.dp)) },
                text = { Text("Delete group…", color = Palette.danger) },
                onClick = { open = false; hooks.setDeleting(g.name) },
            )
        }
    }
}

/** Inline rename (`Group name`, max 60): Done or focus loss commits, the × cancels. */
@Composable
private fun RenameField(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun done() { if (!finished) { finished = true; onDone(text.text) } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { if (it.text.length <= 60) text = it },
            singleLine = true,
            textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { if (focused && !it.isFocused) done(); focused = it.isFocused }
                .semantics { contentDescription = "Group name" }
                .background(Palette.background, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.accent, RoundedCornerShape(6.dp))
                .padding(8.dp),
        )
        IconBox(SIcons.Close, "Cancel rename", { finished = true; onCancel() }, size = 32)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
