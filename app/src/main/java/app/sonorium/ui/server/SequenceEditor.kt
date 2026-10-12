package app.sonorium.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.net.EditorPreset
import app.sonorium.server.EditorUi
import app.sonorium.server.SequenceRules
import app.sonorium.server.ThemeEditor
import app.sonorium.ui.Palette
import kotlinx.coroutines.launch

private val END_OPTIONS = listOf("reverse" to "Reverse", "restart" to "Start again", "stop" to "Stop")

private fun marks(p: EditorPreset?) = (if (p?.isDefault == true) " ★" else "") + (if (p?.hasIntrusion == true) " ⚭" else "")

/** The sequence editor (`#sq-modal`), over the Theme Editor. [onAdvanced] opens a step's Advanced dialog. */
@Composable
fun SequenceEditorScreen(editor: ThemeEditor, ui: EditorUi, onAdvanced: (Int) -> Unit) {
    val draft by editor.seq.collectAsState()
    val d = draft ?: return
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    val nameFocus = remember { FocusRequester() }
    val t = SequenceRules.timing(d.min, d.max, d.ramp, ui.defaults)
    fun save() = scope.launch { editor.saveSequence() }

    Dialog(onDismissRequest = editor::closeSequence, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EditorSurface {
            BackHandler { editor.closeSequence() }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (d.id != null) "Edit sequence" else "Add sequence", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(ui.name.trim().ifEmpty { ui.title }, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.id != null) {
                        MoreMenu(
                            "More sequence actions",
                            listOf(
                                MenuAction("Duplicate", SIcons.Copy) { editor.duplicateSequence(); scope.launch { nameFocus.requestFocus() } },
                                MenuAction("Delete sequence…", SIcons.Trash, danger = true) {
                                    val name = ui.sequences.firstOrNull { it.id == d.id }?.name ?: d.name
                                    ask = Ask("Delete “$name”?", null, "Delete") { editor.deleteSequence() }
                                },
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    IconBox(SIcons.Close, "Close", editor::closeSequence)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    item(key = "fields") {
                        Column(Modifier.padding(top = 8.dp)) {
                            FieldLabel("Name")
                            OutlinedTextField(
                                d.name, { v -> if (v.length <= 100) editor.editSequence { it.copy(name = v, bad = it.bad - "name") } },
                                singleLine = true, isError = "name" in d.bad, placeholder = { Text("Sequence name") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).semantics { contentDescription = "Sequence name" },
                            )
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.semantics { contentDescription = "A random time in this range, drawn fresh each time. Empty uses the theme default." }) {
                                    FieldLabel("Time on each preset")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SmallField(d.min, SequenceRules.minutes(ui.defaults.holdMin), "min" in d.bad, "Shortest time on each preset (minutes)") { v -> editor.editSequence { it.copy(min = v, bad = it.bad - "min" - "max") } }
                                        Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                                        SmallField(d.max, SequenceRules.minutes(ui.defaults.holdMax), "max" in d.bad, "Longest time on each preset (minutes)") { v -> editor.editSequence { it.copy(max = v, bad = it.bad - "min" - "max") } }
                                        Text("min", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                Column(Modifier.semantics { contentDescription = "How long the glide to the next preset takes. Empty uses the theme default." }) {
                                    FieldLabel("Ramp")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SmallField(d.ramp, SequenceRules.plain(ui.defaults.ramp), "ramp" in d.bad, "Ramp (seconds)") { v -> editor.editSequence { it.copy(ramp = v, bad = it.bad - "ramp") } }
                                        Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            FieldLabel("At the end")
                            Segmented(END_OPTIONS.map { it.second }, END_OPTIONS.indexOfFirst { it.first == d.atEnd }.coerceAtLeast(0), { i ->
                                editor.editSequence { it.copy(atEnd = END_OPTIONS[i].first) }
                            })
                            if (d.steps.isNotEmpty()) {
                                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                    d.steps.forEach { id ->
                                        val p = ui.presets.firstOrNull { it.id == id }
                                        Text(
                                            (p?.name ?: id) + marks(p), color = Palette.text, fontSize = 12.sp,
                                            modifier = Modifier.background(Palette.card, RoundedCornerShape(10.dp)).border(1.dp, Palette.border, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                        Text("→", color = Palette.textMuted, fontSize = 12.sp)
                                    }
                                    Text(SequenceRules.END_LABELS[d.atEnd].orEmpty(), color = if (d.atEnd == "stop") Palette.danger else Palette.accent, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item(key = "list-head") {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Presets", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            CountBadge(d.steps.size)
                            Spacer(Modifier.weight(1f))
                            AddPresetMenu(ui, d.steps) { id -> editor.editSequence { it.copy(steps = it.steps + id) } }
                        }
                    }
                    if (d.steps.isEmpty()) item(key = "empty") {
                        AddPresetMenu(ui, d.steps, link = true) { id -> editor.editSequence { it.copy(steps = it.steps + id) } }
                    }
                    itemsIndexed(d.steps, key = { _, id -> "step-$id" }) { i, id ->
                        val p = ui.presets.firstOrNull { it.id == id }
                        val name = p?.name ?: id
                        val advanced = d.transitions.containsKey(id) || id in d.whileSteps || d.layers.containsKey(id)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                            Text(name + marks(p), color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box {
                                MoreMenu(
                                    "More for $name",
                                    listOf(
                                        MenuAction("Move up", enabled = i > 0) { editor.editSequence { it.copy(steps = SequenceRules.move(it.steps, i, i - 1)) } },
                                        MenuAction("Move down", enabled = i < d.steps.size - 1) { editor.editSequence { it.copy(steps = SequenceRules.move(it.steps, i, i + 1)) } },
                                        MenuAction(if (advanced) "Advanced… (On)" else "Advanced…", SIcons.Settings, separatorBefore = true) { onAdvanced(i) },
                                        MenuAction("Remove", SIcons.Trash, danger = true, separatorBefore = true) { editor.editSequence { it.copy(steps = it.steps - id) } },
                                    ),
                                    size = 36,
                                )
                                if (advanced) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).semantics { contentDescription = "Advanced set" }) { Dot(Palette.accent, 6) }
                            }
                        }
                    }
                    item(key = "sum") {
                        Text(SequenceRules.summary(d.steps.size, t), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Palette.bar).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = editor::closeSequence, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("Cancel", color = Palette.text)
                    }
                    Button(
                        onClick = { save() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
    LaunchedEffect(d.id == null && d.name.isEmpty()) { if (d.id == null && d.name.isEmpty()) runCatching { nameFocus.requestFocus() } }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

@Composable
private fun SmallField(value: String, placeholder: String, error: Boolean, description: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, singleLine = true, isError = error,
        placeholder = { Text(placeholder, color = Palette.textMuted) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(errorBorderColor = Palette.danger),
        modifier = Modifier.width(84.dp).semantics { contentDescription = description },
    )
}

/** Add preset: every preset of the theme; added ones disabled with `added`. */
@Composable
private fun AddPresetMenu(ui: EditorUi, steps: List<String>, link: Boolean = false, onAdd: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (link) {
            TextButton(onClick = { open = true }) { Text("Add preset", color = Palette.accent) }
        } else {
            OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = 40.dp)) {
                Icon(SIcons.Plus, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add preset", color = Palette.text, fontSize = 13.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 220.dp)) {
            Text("Add preset", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (ui.presets.isEmpty()) Text("No presets yet", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ui.presets.forEach { p ->
                val added = p.id in steps
                DropdownMenuItem(
                    enabled = !added,
                    text = { Text(p.name + marks(p), color = if (added) Palette.textMuted else Palette.text) },
                    trailingIcon = if (added) ({ Text("added", color = Palette.textMuted, fontSize = 12.sp) }) else null,
                    onClick = { open = false; onAdd(p.id) },
                )
            }
        }
    }
}
