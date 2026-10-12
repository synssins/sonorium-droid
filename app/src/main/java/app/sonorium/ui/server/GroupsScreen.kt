package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.net.Area
import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerGroup
import app.sonorium.server.GroupRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings › Speaker Groups (native-api.md Phase 2). */
@Composable
fun GroupsPage(state: ServerState, model: ServerViewModel) {
    val open = remember { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<SpeakerGroup?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SpeakerGroup?>(null) }

    LaunchedEffect(Unit) {
        model.refreshGroups()
        model.refreshHierarchy()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                PageHeader("Speaker Groups", state.groups.size, help = "settings-groups") { AddButton("Add group", narrow) { adding = true } }
            }
            if (state.groups.isEmpty()) item { EmptyState("No speaker groups", null) }
            items(state.groups, key = { it.id }) { g ->
                val expanded = g.id in open
                GroupRow(
                    group = g,
                    state = state,
                    narrow = narrow,
                    expanded = expanded,
                    onToggle = { if (expanded) open.remove(g.id) else open.add(g.id) },
                    onEdit = { editing = g },
                    onDelete = { deleting = g },
                )
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (adding || editing != null) {
        GroupEditor(
            edit = editing,
            state = state,
            onError = { model.toast(it, error = true) },
            onSave = { id, body ->
                model.saveGroup(id, body) { saved ->
                    if (id == null && saved.id !in open) open.add(saved.id)
                    adding = false
                    editing = null
                }
            },
            onClose = { adding = false; editing = null },
        )
    }
    deleting?.let { g ->
        ConfirmDialog("Delete “${g.name}”?", null, "Delete", onConfirm = { model.deleteGroup(g) }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupRow(
    group: SpeakerGroup,
    state: ServerState,
    narrow: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sel = GroupRules.Selection(group)
    val members = GroupRules.members(sel, state.hierarchy)
    val count = members.count { !it.excluded }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                if (expanded) SIcons.ChevronDown else SIcons.ChevronRight,
                if (expanded) "Hide speakers in ${group.name}" else "Show speakers in ${group.name}",
                onToggle, size = 36, background = Palette.background, border = Palette.background,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔊", fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(group.name, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(GroupRules.plural(count, "speaker"), color = Palette.textSecondary, fontSize = 13.sp)
                }
                val chips = GroupRules.chips(sel, state.hierarchy)
                val max = if (narrow) 3 else 5
                FlowRow(
                    Modifier.padding(top = 4.dp).semantics { contentDescription = GroupRules.parts(sel).joinToString(", ") },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    chips.take(max).forEach { IncludeChip(it) }
                    if (chips.size > max) Text("+${chips.size - max}", color = Palette.textSecondary, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = onEdit, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                Text("Edit", color = Palette.text)
            }
            Spacer(Modifier.width(6.dp))
            MoreMenu("More for ${group.name}", listOf(MenuAction("Delete…", SIcons.Trash, danger = true, onClick = onDelete)))
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 8.dp)) {
                if (members.isEmpty()) Text("No speakers", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                members.forEach { m ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).alpha(if (m.excluded) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
                        OnlineDot(m.placed.speaker.online)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.placed.speaker.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.location, color = Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        GroupRules.typeLabel(m.placed.speaker.type)?.let { Badge(it) }
                        if (m.excluded) {
                            Spacer(Modifier.width(4.dp))
                            Badge("Excluded")
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.border)
    }
}

@Composable
private fun IncludeChip(chip: GroupRules.Chip) {
    val (icon, desc) = when (chip.kind) {
        GroupRules.ChipKind.Floor -> SIcons.Layers to "Floor"
        GroupRules.ChipKind.Area -> SIcons.MapPin to "Area"
        GroupRules.ChipKind.Speaker -> SIcons.Speakers to "Speaker"
        GroupRules.ChipKind.ExcludedArea -> SIcons.Close to "Excluded area"
        GroupRules.ChipKind.ExcludedSpeaker -> SIcons.Close to "Excluded speaker"
    }
    Row(
        Modifier.background(Palette.backgroundSecondary, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp).semantics { contentDescription = "$desc: ${chip.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            chip.label, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1,
            textDecoration = if (chip.kind == GroupRules.ChipKind.ExcludedSpeaker) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, color = Palette.textSecondary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun OnlineDot(online: Boolean) {
    Box(Modifier.semantics { contentDescription = if (online) "Online" else "Offline" }) { Dot(if (online) Palette.accent else Palette.textMuted) }
}

/** The group editor: name and the include tree (floors, areas, speakers; exclusions kept as they are). */
@Composable
private fun GroupEditor(
    edit: SpeakerGroup?,
    state: ServerState,
    onError: (String) -> Unit,
    onSave: (id: String?, body: kotlinx.serialization.json.JsonObject) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var nameError by remember { mutableStateOf(false) }
    val floors = remember { mutableStateListOf<String>().apply { addAll(edit?.includeFloors.orEmpty()) } }
    val areas = remember { mutableStateListOf<String>().apply { addAll(edit?.includeAreas.orEmpty()) } }
    val speakers = remember { mutableStateListOf<String>().apply { addAll(edit?.includeSpeakers.orEmpty()) } }
    val collapsed = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    val excludeAreas = edit?.excludeAreas.orEmpty()
    val excludeSpeakers = edit?.excludeSpeakers.orEmpty()
    val sel = GroupRules.Selection(floors.toList(), areas.toList(), speakers.toList(), excludeAreas, excludeSpeakers)
    val h = state.hierarchy

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }

    fun toggle(list: MutableList<String>, id: String) {
        if (id in list) list.remove(id) else list.add(id)
    }

    fun save() {
        GroupRules.validate(name, sel)?.let {
            if (name.trim().isEmpty()) {
                nameError = true
                runCatching { focus.requestFocus() }
            }
            onError(it)
            return
        }
        onSave(edit?.id, buildJsonObject {
            put("name", name.trim())
            put("include_floors", buildJsonArray { floors.forEach { add(JsonPrimitive(it)) } })
            put("include_areas", buildJsonArray { areas.forEach { add(JsonPrimitive(it)) } })
            put("include_speakers", buildJsonArray { speakers.forEach { add(JsonPrimitive(it)) } })
        })
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Column(Modifier.safeDrawingPadding().imePadding().fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (edit == null) "Add group" else "Edit group", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconBox(SIcons.Close, "Close", onClose, size = 40)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                    item {
                        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldLabel("Name")
                            OutlinedTextField(
                                value = name,
                                onValueChange = { if (it.length <= 60) name = it; nameError = false },
                                singleLine = true,
                                isError = nameError,
                                placeholder = { Text("Downstairs", color = Palette.textMuted) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Name" },
                            )
                            FieldLabel("Speakers", Modifier.padding(top = 8.dp))
                        }
                    }
                    if (h == null || GroupRules.placed(h).isEmpty()) {
                        item { Text("No speakers available", color = Palette.textMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                    } else {
                        h.floors.forEach { f ->
                            val all = f.areas.sumOf { it.speakers.size }
                            item(key = "f-${f.floorId}") {
                                TreeRow(0, f.floorId in floors, enabled = true, implied = null, SIcons.Layers, f.name, GroupRules.plural(all, "speaker"),
                                    collapsed = f.floorId in collapsed, onChevron = { toggle(collapsed, f.floorId) }) { toggle(floors, f.floorId) }
                            }
                            if (f.floorId !in collapsed) {
                                f.areas.forEach { a -> areaItems(this, a, if (f.floorId in floors) f.name else null, areas, speakers, collapsed, excludeAreas, excludeSpeakers, ::toggle) }
                            }
                        }
                        val other = h.unassignedAreas.filter { it.speakers.isNotEmpty() }
                        if (other.isNotEmpty()) {
                            item { SectionTitle("Other areas") }
                            other.forEach { a -> areaItems(this, a, null, areas, speakers, collapsed, excludeAreas, excludeSpeakers, ::toggle) }
                        }
                        if (h.unassignedSpeakers.isNotEmpty()) {
                            item { SectionTitle("Unassigned") }
                            h.unassignedSpeakers.forEach { s ->
                                item(key = "s-${s.entityId}") { SpeakerTreeRow(1, s, s.entityId in speakers, null, s.entityId in excludeSpeakers) { toggle(speakers, s.entityId) } }
                            }
                        }
                    }
                    item { Spacer(Modifier.size(16.dp)) }
                }
                HorizontalDivider(color = Palette.border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val parts = GroupRules.parts(sel)
                        Text(if (parts.isEmpty()) "Nothing selected" else parts.joinToString(" · "), color = Palette.textSecondary, fontSize = 13.sp)
                        if (parts.isNotEmpty()) Text("= ${GroupRules.plural(GroupRules.count(sel, h), "speaker")}", color = Palette.accent, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp)) { Text("Cancel", color = Palette.text) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { save() },
                        modifier = Modifier.semantics { contentDescription = "Save group" },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    ) { Text(if (edit == null) "Add group" else "Save", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/** An area and its speakers in the editor tree; [floorTicked] = the floor's name when its floor is ticked. */
private fun areaItems(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    a: Area,
    floorTicked: String?,
    areas: MutableList<String>,
    speakers: MutableList<String>,
    collapsed: MutableList<String>,
    excludeAreas: List<String>,
    excludeSpeakers: List<String>,
    toggle: (MutableList<String>, String) -> Unit,
) {
    val explicit = a.areaId in areas
    val impliedArea = floorTicked != null && !explicit
    scope.item(key = "a-${a.areaId}") {
        TreeRow(
            1, explicit || impliedArea, enabled = !impliedArea, implied = if (impliedArea) "Included with $floorTicked" else null,
            SIcons.MapPin, a.name, GroupRules.plural(a.speakers.size, "speaker"),
            collapsed = a.areaId in collapsed, onChevron = { toggle(collapsed, a.areaId) },
            excluded = a.areaId in excludeAreas,
        ) { toggle(areas, a.areaId) }
    }
    if (a.areaId in collapsed) return
    a.speakers.forEach { s ->
        val by = when {
            s.entityId in speakers -> null
            explicit -> a.name
            floorTicked != null -> floorTicked
            else -> null
        }
        scope.item(key = "s-${a.areaId}-${s.entityId}") {
            SpeakerTreeRow(2, s, s.entityId in speakers, by, s.entityId in excludeSpeakers) { toggle(speakers, s.entityId) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun TreeRow(
    indent: Int,
    checked: Boolean,
    enabled: Boolean,
    implied: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    count: String,
    collapsed: Boolean,
    onChevron: () -> Unit,
    excluded: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = (indent * 24).dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.bar, disabledCheckedColor = Palette.accent.copy(alpha = 0.45f)),
            modifier = Modifier.semantics { contentDescription = implied ?: name },
        )
        Box(Modifier.size(32.dp).clickable(onClick = onChevron), contentAlignment = Alignment.Center) {
            Icon(if (collapsed) SIcons.ChevronRight else SIcons.ChevronDown, if (collapsed) "Expand $name" else "Collapse $name", tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
        }
        Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = Palette.text, fontSize = 14.sp, fontWeight = if (indent == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(count, color = Palette.textMuted, fontSize = 12.sp)
        if (excluded) {
            Spacer(Modifier.width(6.dp))
            Badge("Excluded")
        }
    }
}

@Composable
private fun SpeakerTreeRow(indent: Int, s: Speaker, checked: Boolean, impliedBy: String?, excluded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = (indent * 24).dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked || impliedBy != null, onCheckedChange = { onToggle() }, enabled = impliedBy == null,
            colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.bar, disabledCheckedColor = Palette.accent.copy(alpha = 0.45f)),
            modifier = Modifier.semantics { contentDescription = impliedBy?.let { "Included with $it" } ?: s.name },
        )
        Text(s.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (excluded) {
            Badge("Excluded")
            Spacer(Modifier.width(4.dp))
        }
        GroupRules.typeLabel(s.type)?.let { Badge(it) }
        Spacer(Modifier.width(8.dp))
        OnlineDot(s.online)
        Spacer(Modifier.width(4.dp))
        Text(if (s.online) "Online" else "Offline", color = Palette.textMuted, fontSize = 12.sp)
    }
}
