package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.net.Area
import app.sonorium.net.Session
import app.sonorium.net.Speaker
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerState
import app.sonorium.ui.Palette
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * New Channel / Edit Channel (native-api.md 1.4). [onSave] gets the PUT/POST body, the playlist to
 * set (playlist mode) and whether to clear the channel's playlist first (theme mode on a channel
 * that has one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelEditor(
    edit: Session?,
    state: ServerState,
    onLoadPresets: (String) -> Unit,
    onError: (String) -> Unit,
    onSave: (body: JsonObject, playlistId: String?, clearPlaylistFirst: Boolean, zoneChanges: JsonObject?) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(edit?.name ?: "") }
    var playlistMode by rememberSaveable { mutableStateOf(edit?.playlistId != null) }
    var playlistId by rememberSaveable { mutableStateOf(edit?.playlistId) }
    var themeId by rememberSaveable { mutableStateOf(edit?.themeId) }
    var themeSearch by rememberSaveable { mutableStateOf("") }
    // The preset: a channel's current choice; new or unset → the theme's default preset (★)
    fun defaultPreset(theme: String?) = theme?.let { state.presets[it] }?.presets?.firstOrNull { it.isDefault }?.id ?: ""
    var preset by rememberSaveable { mutableStateOf(edit?.choice?.takeIf { it.isNotEmpty() } ?: defaultPreset(edit?.themeId)) }
    var presetTouched by rememberSaveable { mutableStateOf(false) }
    var volume by rememberSaveable { mutableStateOf(edit?.volume ?: 60) }
    var announcements by rememberSaveable { mutableStateOf(edit?.announcements != false) }
    var groupId by rememberSaveable { mutableStateOf(edit?.speakerGroupId) }
    val picker = remember(state.hierarchy, state.enabledSpeakers) {
        state.hierarchy?.let { ChannelRules.Picker(it, state.enabledSpeakers) }
    }
    var selected by remember(picker) { mutableStateOf(picker?.effective(edit?.adhocSelection) ?: emptySet()) }
    // Zones (1.6): owner, Kid mode and hours; admins change them, others see them read-only
    val zones = state.zones
    val zone = edit?.let { zones?.zones?.get(it.id) }
    var owner by rememberSaveable { mutableStateOf(zone?.owner) }
    var kidMode by rememberSaveable { mutableStateOf(zone?.kidMode == true) }
    var hoursFrom by rememberSaveable { mutableStateOf(zone?.schedule?.start ?: "") }
    var hoursTo by rememberSaveable { mutableStateOf(zone?.schedule?.end ?: "") }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
            // imePadding: the footer (Create/Save, Cancel) stays above the keyboard
            BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
                val wide = maxWidth >= 720.dp
                Column(Modifier.fillMaxSize()) {
                    Text(
                        if (edit == null) "New Channel" else "Edit Channel",
                        color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 12.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            val left: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    FieldLabel("Channel Name")
                                    OutlinedTextField(
                                        value = name, onValueChange = { name = it }, singleLine = true,
                                        placeholder = { Text("Leave empty to auto-generate from selection", color = Palette.textMuted) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (zones != null && edit != null) {
                                        if (zones.admin) {
                                            FieldLabel("Owner")
                                            PickerField(
                                                label = "Owner",
                                                options = listOf(PickOption("", "No one")) + zones.users.sortedBy { it.username.lowercase() }.map { PickOption(it.id, it.username) },
                                                current = owner ?: "",
                                                onPick = { owner = it.value.ifEmpty { null } },
                                            )
                                        } else if (zone?.ownerName != null) {
                                            FieldLabel("Owner")
                                            PickerField("Owner", listOf(PickOption("o", zone.ownerName)), "o", {}, enabled = false)
                                        }
                                    }
                                    FieldLabel("Plays")
                                    Segmented(listOf("Theme", "Playlist"), if (playlistMode) 1 else 0) { playlistMode = it == 1 }
                                    if (playlistMode) {
                                        FieldLabel("Playlist")
                                        PickerField(
                                            label = "Playlist",
                                            options = state.playlists.map { PickOption(it.id, it.name, icon = SIcons.Playlist) },
                                            current = playlistId,
                                            onPick = { playlistId = it.value },
                                            placeholder = if (state.playlists.isEmpty()) "No playlists yet" else "Choose a playlist",
                                            enabled = state.playlists.isNotEmpty(),
                                        )
                                        state.playlist(playlistId)?.let { p ->
                                            Text("${if (p.entries.size == 1) "1 entry" else "${p.entries.size} entries"}", color = Palette.textSecondary, fontSize = 12.sp)
                                        }
                                    } else {
                                        FieldLabel("Theme · ${state.theme(themeId)?.name ?: "None"}")
                                        OutlinedTextField(
                                            value = themeSearch, onValueChange = { themeSearch = it }, singleLine = true,
                                            placeholder = { Text("Search themes", color = Palette.textMuted) },
                                            leadingIcon = { Icon(SIcons.Search, null, tint = Palette.textMuted, modifier = Modifier.size(16.dp)) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        val shown = state.themes.filter { themeSearch.isBlank() || it.name.contains(themeSearch.trim(), ignoreCase = true) }
                                        if (shown.isEmpty()) {
                                            Text(if (state.themes.isEmpty()) "No themes yet" else "No themes match", color = Palette.textMuted, fontSize = 13.sp)
                                        }
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            shown.forEach { theme ->
                                                val on = theme.id == themeId
                                                Chip(theme.name, on) {
                                                    if (theme.id != themeId) {
                                                        themeId = theme.id
                                                        if (theme.id !in state.presets) onLoadPresets(theme.id)
                                                        preset = defaultPreset(theme.id)
                                                        presetTouched = true
                                                    }
                                                }
                                            }
                                        }
                                        if (themeId != null) {
                                            FieldLabel("Theme Preset")
                                            val options = presetOptions(state, themeId)
                                            val hasPresets = (state.presets[themeId]?.presets?.size ?: 0) > 0
                                            PickerField(
                                                label = "Theme Preset",
                                                options = if (hasPresets) options else emptyList(),
                                                current = if (hasPresets) preset.takeIf { p -> options.any { it.value == p } } ?: "" else null,
                                                onPick = { preset = it.value; presetTouched = true },
                                                placeholder = "No presets yet",
                                                enabled = hasPresets,
                                            )
                                        }
                                    }
                                    FieldLabel("Volume")
                                    PercentSlider(volume, "Volume", { volume = it }, onChange = { volume = it })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Announcements", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = announcements, onCheckedChange = { announcements = it },
                                            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                                        )
                                    }
                                    if (zones != null && edit != null && (zones.admin || kidMode)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Kid mode", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                            Switch(
                                                checked = kidMode, onCheckedChange = { kidMode = it }, enabled = zones.admin,
                                                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                                            )
                                        }
                                        if (kidMode) {
                                            Text("Only an admin can change the speakers of ${edit.name}.", color = Palette.textSecondary, fontSize = 12.sp)
                                            FieldLabel("Hours")
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                OutlinedTextField(
                                                    hoursFrom, { hoursFrom = it.take(5) }, enabled = zones.admin, singleLine = true,
                                                    placeholder = { Text("07:00", color = Palette.textMuted) }, modifier = Modifier.weight(1f),
                                                )
                                                Text("  to  ", color = Palette.textSecondary)
                                                OutlinedTextField(
                                                    hoursTo, { hoursTo = it.take(5) }, enabled = zones.admin, singleLine = true,
                                                    placeholder = { Text("20:00", color = Palette.textMuted) }, modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val right: @Composable () -> Unit = {
                                SpeakersField(state, picker, groupId, { groupId = it }, selected, { selected = it })
                            }
                            if (wide) {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Box(Modifier.weight(1f)) { left() }
                                    Box(Modifier.weight(1f)) { right() }
                                }
                            } else {
                                left()
                                right()
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    // Footer: Primary · Cancel on the right (UI_STYLE.md)
                    Row(
                        Modifier.fillMaxWidth().background(Palette.bar).padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        Button(
                            onClick = {
                                if (playlistMode && playlistId == null) {
                                    onError("Choose a playlist")
                                    return@Button
                                }
                                val body = buildJsonObject {
                                    put("volume", volume)
                                    put("announcements", announcements)
                                    if (!playlistMode) {
                                        themeId?.let { put("theme_id", it) } ?: put("theme_id", JsonNull)
                                        // Editing without touching the preset sends none, so a running sequence isn't restarted
                                        if (edit == null || presetTouched) put("preset_id", preset)
                                    }
                                    val group = groupId
                                    if (group != null) {
                                        put("speaker_group_id", group)
                                        put("adhoc_selection", JsonNull)
                                    } else {
                                        put("speaker_group_id", JsonNull)
                                        val compact = picker?.compact(selected)
                                        putJsonObject("adhoc_selection") {
                                            put("include_floors", buildJsonArray { compact?.includeFloors?.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                            put("include_areas", buildJsonArray { compact?.includeAreas?.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                            put("include_speakers", buildJsonArray { (compact?.includeSpeakers ?: selected.toList()).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                            put("exclude_areas", buildJsonArray { })
                                            put("exclude_speakers", buildJsonArray { })
                                        }
                                    }
                                    name.trim().takeIf { it.isNotEmpty() }?.let { put("custom_name", it) }
                                }
                                // Zones (admin): only what changed; hours only when both are set, else none
                                val zoneChanges = if (zones?.admin == true && edit != null) buildJsonObject {
                                    if (owner != zone?.owner) owner?.let { put("owner", it) } ?: put("owner", JsonNull)
                                    if (kidMode != (zone?.kidMode == true)) put("kid_mode", kidMode)
                                    val schedule = if (hoursFrom.isNotBlank() && hoursTo.isNotBlank()) hoursFrom.trim() to hoursTo.trim() else null
                                    val before = zone?.schedule?.let { it.start to it.end }
                                    if (schedule != before) {
                                        if (schedule == null) put("schedule", JsonNull)
                                        else putJsonObject("schedule") { put("start", schedule.first); put("end", schedule.second) }
                                    }
                                } else null
                                onSave(body, if (playlistMode) playlistId else null, !playlistMode && edit?.playlistId != null, zoneChanges)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        ) { Text(if (edit == null) "Create Channel" else "Save Channel", fontWeight = FontWeight.Medium) }
                        OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border)) {
                            Text("Cancel", color = Palette.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)).padding(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .background(if (on) Palette.accent else Palette.backgroundSecondary, RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(label, color = if (on) Palette.bar else Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) Palette.accent.copy(alpha = 0.12f) else Palette.card,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (on) Palette.accent.copy(alpha = 0.6f) else Palette.border),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SIcons.Music, null, tint = if (on) Palette.accent else Palette.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (on) Palette.accent else Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Speakers · <N selected | group name>: the group menu, then the floor › area › speaker picker (1.4, 1.5.6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakersField(
    state: ServerState,
    picker: ChannelRules.Picker?,
    groupId: String?,
    onGroup: (String?) -> Unit,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
) {
    val group = state.groups.firstOrNull { it.id == groupId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Speakers · ${group?.name ?: "${selected.size} selected"}")
        if (state.groups.isNotEmpty()) {
            PickerField(
                label = "Speaker group",
                options = listOf(PickOption("", "-- Choose speakers below --")) + state.groups.map { PickOption(it.id, it.name) },
                current = groupId ?: "",
                onPick = { o ->
                    val id = o.value.ifEmpty { null }
                    onGroup(id)
                    if (id != null) onSelected(emptySet()) // a group clears the hand-picked speakers
                },
            )
        }
        if (group != null) return@Column
        val hierarchy = state.hierarchy
        if (hierarchy == null || picker == null) {
            Text("Loading speakers...", color = Palette.textMuted, fontSize = 13.sp)
            return@Column
        }
        var search by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = search, onValueChange = { search = it }, singleLine = true,
            placeholder = { Text("Search speakers", color = Palette.textMuted) },
            leadingIcon = { Icon(SIcons.Search, null, tint = Palette.textMuted, modifier = Modifier.size(16.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Chips of the selected speakers (unknown ids show as their id)
        val known = (hierarchy.floors.flatMap { f -> f.areas.flatMap { it.speakers } } +
            hierarchy.unassignedAreas.flatMap { it.speakers } + hierarchy.unassignedSpeakers).associateBy { it.entityId }
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { id ->
                    Surface(color = Palette.accent.copy(alpha = 0.12f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Palette.accent.copy(alpha = 0.4f))) {
                        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(known[id]?.name ?: id, color = Palette.text, fontSize = 12.sp)
                            Icon(SIcons.Close, "Remove", tint = Palette.textSecondary, modifier = Modifier.size(22.dp).clickable { onSelected(selected - id) }.padding(4.dp))
                        }
                    }
                }
            }
        }
        val query = search.trim()
        fun matches(s: Speaker) = query.isEmpty() || s.name.contains(query, ignoreCase = true)
        var any = false
        @Composable
        fun areaRows(area: Area) {
            val speakers = picker.visibleSpeakers(area).filter(::matches)
            if (speakers.isEmpty()) return
            any = true
            val ids = picker.areaIds(area)
            TreeRow(area.name, ids, selected, depth = 1) { on -> onSelected(if (on) selected + ids else selected - ids.toSet()) }
            speakers.forEach { s -> SpeakerRow(s, s.entityId in selected) { on -> onSelected(if (on) selected + s.entityId else selected - s.entityId) } }
        }
        hierarchy.floors.forEach { floor ->
            val areas = picker.visibleAreas(floor.areas).filter { a -> picker.visibleSpeakers(a).any(::matches) }
            if (areas.isEmpty()) return@forEach
            val ids = picker.floorIds(floor.areas)
            TreeRow(floor.name, ids, selected, depth = 0) { on -> onSelected(if (on) selected + ids else selected - ids.toSet()) }
            areas.forEach { areaRows(it) }
        }
        val other = picker.visibleAreas(hierarchy.unassignedAreas).filter { a -> picker.visibleSpeakers(a).any(::matches) }
        if (other.isNotEmpty()) {
            val ids = other.flatMap { picker.areaIds(it) }
            TreeRow("Other areas", ids, selected, depth = 0) { on -> onSelected(if (on) selected + ids else selected - ids.toSet()) }
            other.forEach { areaRows(it) }
        }
        val unassigned = hierarchy.unassignedSpeakers.filter { it.entityId in state.enabledSpeakers && it.online && matches(it) }
        if (unassigned.isNotEmpty()) {
            any = true
            Text("Unassigned", color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp))
            unassigned.forEach { s -> SpeakerRow(s, s.entityId in selected) { on -> onSelected(if (on) selected + s.entityId else selected - s.entityId) } }
        }
        if (!any) {
            Text(
                if (query.isNotEmpty()) "No speakers match" else "No speakers available. Enable speakers in Settings.",
                color = Palette.textMuted, fontSize = 13.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected.size == 1) "1 speaker selected" else "${selected.size} speakers selected", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSelected(emptySet()) }, enabled = selected.isNotEmpty()) { Text("Clear selection", color = Palette.textSecondary) }
        }
    }
}

@Composable
private fun TreeRow(name: String, ids: List<String>, selected: Set<String>, depth: Int, onToggle: (Boolean) -> Unit) {
    val count = ids.count { it in selected }
    val tri = when (count) {
        0 -> ToggleableState.Off
        ids.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = (depth * 20).dp), verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
            state = tri, onClick = { onToggle(tri != ToggleableState.On) },
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.bar),
        )
        Text(name, color = Palette.text, fontSize = 14.sp, fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text("$count/${ids.size}", color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
    }
}

private val TYPE_LABELS = mapOf(
    "cast" to "Cast", "sonos" to "Sonos", "dlna" to "DLNA", "airplay" to "AirPlay",
    "linkplay" to "LinkPlay", "heos" to "HEOS", "esphome" to "ESPHome", "denon" to "Denon / Marantz",
)
private val SOURCE_LABELS = mapOf("ha" to "HA", "discovered" to "Discovered", "manual" to "Manual")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerRow(speaker: Speaker, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(
            checked = on, onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.bar),
        )
        Box(Modifier.size(8.dp).background(if (speaker.online) Palette.accent else Palette.textMuted, RoundedCornerShape(50)))
        Text(speaker.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        (speaker.source.mapNotNull { SOURCE_LABELS[it] } + listOfNotNull(speaker.type?.let { TYPE_LABELS[it] })).forEach { badge ->
            Box(Modifier.padding(start = 4.dp).background(Palette.backgroundSecondary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(badge, color = Palette.textSecondary, fontSize = 11.sp)
            }
        }
    }
}
