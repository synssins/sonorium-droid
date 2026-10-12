package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.net.SpaceArea
import app.sonorium.net.SpaceFloor
import app.sonorium.net.Spaces
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings › Floors & Areas (native-api.md Phase 2). */
@Composable
fun SpacesPage(state: ServerState, model: ServerViewModel) {
    var adding by remember { mutableStateOf<String?>(null) } // "floor" / "area"
    var renaming by remember { mutableStateOf<SpaceFloor?>(null) }
    var confirm by remember { mutableStateOf<SpaceConfirm?>(null) }

    LaunchedEffect(Unit) { model.refreshSpaces() }

    val spaces = state.spaces
    val editable = spaces?.editable == true

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                PageHeader("Floors & Areas", null, help = "settings-spaces") {
                    if (editable) {
                        OutlinedButton(onClick = { adding = "floor" }, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) {
                            Icon(SIcons.Plus, null, tint = Palette.text, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add floor", color = Palette.text)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { adding = "area" }, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        ) {
                            Icon(SIcons.Plus, null, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add area")
                        }
                    }
                }
                Text(
                    if (editable) "Group speakers by floor and area. A floor or area with the same name as one in Home Assistant becomes one."
                    else "From Home Assistant. Change them there.",
                    color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (spaces != null) {
                if (spaces.floors.isEmpty() && spaces.unassignedAreas.isEmpty()) {
                    item { EmptyState(
                        if (editable) "No floors or areas yet" else "Home Assistant has no floors or areas",
                        if (editable) "Add a floor or an area to group speakers" else "Add them in Home Assistant",
                    ) }
                }
                items(spaces.floors, key = { "f-${it.id}" }) { f ->
                    FloorBlock(f, f.name, f.areas, f.id, spaces, editable, model,
                        onRename = { renaming = f },
                        onDelete = { confirm = SpaceConfirm("Delete floor “${f.name}”?", "Its areas stay, without a floor.") { model.changeSpaces { it.deleteFloor(f.localId!!) } } },
                        onDeleteArea = { a -> confirm = SpaceConfirm("Delete area “${a.name}”?", "Its speakers move to No area.") { model.changeSpaces { it.deleteArea(a.localId!!) } } },
                    )
                }
                if (spaces.unassignedAreas.isNotEmpty()) {
                    item(key = "nofloor") {
                        FloorBlock(null, "No floor", spaces.unassignedAreas, "", spaces, editable, model, {}, {},
                            onDeleteArea = { a -> confirm = SpaceConfirm("Delete area “${a.name}”?", "Its speakers move to No area.") { model.changeSpaces { it.deleteArea(a.localId!!) } } },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    adding?.let { kind ->
        AddSpaceDialog(kind, spaces, onAdd = { name, floorId, done ->
            model.changeSpaces(onDone = { done(); adding = null }) {
                if (kind == "floor") it.addFloor(buildJsonObject { put("name", name) })
                else it.addArea(buildJsonObject { put("name", name); put("floor_id", floorId?.let { f -> JsonPrimitive(f) } ?: JsonNull) })
            }
        }, onDismiss = { adding = null })
    }
    renaming?.let { f ->
        PromptDialog("Floor name", f.name, onOk = { name ->
            if (name.isNotBlank() && name != f.name) model.changeSpaces { it.updateFloor(f.localId!!, buildJsonObject { put("name", name) }) }
        }, onDismiss = { renaming = null })
    }
    confirm?.let { c ->
        ConfirmDialog(c.title, c.sub, "Delete", onConfirm = c.action, onDismiss = { confirm = null })
    }
}

/** A delete confirmation: what is deleted, and what happens to what it held. */
private class SpaceConfirm(val title: String, val sub: String, val action: () -> Unit)

@Composable
private fun SourceBadges(source: List<String>) {
    if ("ha" in source) { Spacer(Modifier.width(6.dp)); Badge("Home Assistant") }
    if ("local" in source) { Spacer(Modifier.width(6.dp)); Badge("Sonorium") }
}

@Composable
private fun FloorBlock(
    floor: SpaceFloor?,
    title: String,
    areas: List<SpaceArea>,
    floorId: String,
    spaces: Spaces,
    editable: Boolean,
    model: ServerViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDeleteArea: (SpaceArea) -> Unit,
) {
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
                Icon(SIcons.Layers, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                floor?.let { SourceBadges(it.source) }
                Spacer(Modifier.weight(1f))
                if (editable && floor != null) {
                    if (floor.localId != null && "ha" !in floor.source) IconBox(SIcons.Edit, "Rename floor", onRename, size = app.sonorium.ui.UiSize.SMALL)
                    if (floor.localId != null) {
                        Spacer(Modifier.width(6.dp))
                        IconBox(SIcons.Trash, "Delete floor", onDelete, size = app.sonorium.ui.UiSize.SMALL)
                    }
                }
            }
            if (areas.isEmpty()) Text("No areas", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            areas.forEach { a -> AreaRow(a, floorId, spaces, editable, model) { onDeleteArea(a) } }
        }
    }
}

@Composable
private fun AreaRow(area: SpaceArea, floorId: String, spaces: Spaces, editable: Boolean, model: ServerViewModel, onDelete: () -> Unit) {
    val own = editable && area.localId != null && "ha" !in area.source
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (own) {
                // Rename on commit (Done) or when the field loses focus
                var text by remember(area.name, spaces) { mutableStateOf(area.name) }
                var focused by remember { mutableStateOf(false) }
                val focusManager = LocalFocusManager.current
                fun commit() {
                    if (text != area.name) model.changeSpaces { it.updateArea(area.localId!!, buildJsonObject { put("name", text) }) }
                }
                SField(
                    value = text,
                    onValueChange = { if (it.length <= 60) text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Area name" }.onFocusChanged {
                        if (focused && !it.isFocused) commit()
                        focused = it.isFocused
                    },
                )
            } else {
                Text(area.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                SourceBadges(area.source)
                Spacer(Modifier.weight(1f))
            }
            if (editable && area.localId != null) {
                Spacer(Modifier.width(6.dp))
                IconBox(SIcons.Trash, "Delete area", onDelete, size = app.sonorium.ui.UiSize.SMALL)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            if (own) SourceBadges(area.source)
            if (editable && area.localId != null) {
                Spacer(Modifier.width(8.dp))
                PickerField(
                    label = "Floor",
                    options = listOf(PickOption("", "No floor")) + spaces.floors.map { PickOption(it.id, it.name) },
                    current = floorId,
                    onPick = { o ->
                        if (o.value != floorId) model.changeSpaces {
                            it.updateArea(area.localId, buildJsonObject { put("floor_id", if (o.value.isEmpty()) JsonNull else JsonPrimitive(o.value)) })
                        }
                    },
                    modifier = Modifier.widthIn(min = 140.dp, max = 220.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (area.speakers == 0) "No speakers" else "${area.speakers} speaker${if (area.speakers == 1) "" else "s"}", color = Palette.textMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AddSpaceDialog(kind: String, spaces: Spaces?, onAdd: (String, String?, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val title = if (kind == "floor") "Add floor" else "Add area"
    fun go() {
        onAdd(name, floor.ifEmpty { null }) {}
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Name")
                SField(
                    value = name, onValueChange = { if (it.length <= 60) name = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Name" },
                )
                if (kind == "area") {
                    FieldLabel("Floor")
                    PickerField("Floor", listOf(PickOption("", "No floor")) + spaces?.floors.orEmpty().map { PickOption(it.id, it.name) }, floor, { floor = it.value })
                }
            }
        },
        confirmButton = {
            Button(onClick = { go() }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp).semantics { contentDescription = "Save $kind" }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp)) { Text(title) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.textSecondary) }
        },
    )
}
