package app.sonorium.ui.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.server.AdvancedRules
import app.sonorium.server.AdvancedRules.Row as TxRow
import app.sonorium.server.AdvancedRules.While
import app.sonorium.server.EditorUi
import app.sonorium.server.SequenceRules
import app.sonorium.server.ThemeEditor
import app.sonorium.server.ThemeEditorRules
import app.sonorium.server.ThemeEditorRules.Section
import app.sonorium.ui.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private fun dbl(e: JsonElement?, default: Double = 0.0) = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull ?: default
private fun bool(e: JsonElement?) = (e as? JsonPrimitive)?.booleanOrNull == true

/** A changed value shows green (`ovr`). */
private fun tint(changed: Boolean) = if (changed) Palette.accent else Palette.textSecondary

/** A sequence step's ⋯ › Advanced… (`#tx-modal`): While playing, Coming in, Leaving. Nothing is sent until Save. */
@Composable
fun AdvancedDialog(editor: ThemeEditor, ui: EditorUi, index: Int, onClose: () -> Unit) {
    val draft by editor.seq.collectAsState()
    val d = draft
    val step = d?.steps?.getOrNull(index)
    if (d == null || step == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val (prev, next) = SequenceRules.neighbours(index, d.steps, d.atEnd)
    fun pname(id: String?) = id?.let { i -> ui.presets.firstOrNull { it.id == i }?.name ?: i }
    val name = pname(step).orEmpty()
    val t = SequenceRules.timing(d.min, d.max, d.ramp, ui.defaults)
    val ramp = if (!t.ramp.isNaN() && t.ramp >= 0) t.ramp.roundToInt() else ui.defaults.ramp.roundToInt()
    val scope = rememberCoroutineScope()

    var tab by remember(step) { mutableStateOf("while") }
    var loading by remember(step) { mutableStateOf(true) }
    var w by remember(step) { mutableStateOf<While?>(null) }
    var rows by remember(step) { mutableStateOf(mapOf("in" to emptyList<TxRow>(), "out" to emptyList())) }
    var keep by remember(step) { mutableStateOf(mapOf("in" to JsonObject(emptyMap()), "out" to JsonObject(emptyMap()))) }
    var closed by remember(step) { mutableStateOf(emptySet<String>()) }
    var help by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var presetRise by remember(step) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(step) {
        editor.advancedData(step, prev, next).onSuccess { (tracks, preset, layer) ->
            w = AdvancedRules.start(ui.tracks, ui.groups, preset, layer)
            presetRise = ((preset["groups"] as? JsonObject)?.filterValues { g -> (g as? JsonObject)?.get("rise").let { it != null && it !is JsonNull } }?.keys).orEmpty()
            val saved = d.transitions[step] as? JsonObject
            fun side(s: String) = ((tracks[s] as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }.map { AdvancedRules.rowFrom(it, s, saved?.get(s) as? JsonObject) }
            val r = mapOf("in" to side("in"), "out" to side("out"))
            rows = r
            keep = r.mapValues { (s, list) -> JsonObject(((saved?.get(s) as? JsonObject) ?: JsonObject(emptyMap())).filterKeys { k -> list.none { it.key == k } }) }
        }
        loading = false
    }

    fun setRow(side: String, i: Int, change: (TxRow) -> TxRow) {
        rows = rows + (side to rows.getValue(side).mapIndexed { n, r -> if (n == i) change(r) else r })
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EditorSurface {
            BackHandler { onClose() }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                val seqName = d.name.trim()
                val (title, sub) = when (tab) {
                    "while" -> name to "${if (seqName.isNotEmpty()) "in $seqName · " else ""}step ${index + 1}"
                    "in" -> "Coming into $name" to (if (prev != null) "from ${pname(prev)} · ramp $ramp s" else "sequence start · ramp $ramp s")
                    else -> "Leaving $name" to (if (next != null) "to ${pname(next)} · ramp $ramp s" else "last preset · ramp $ramp s")
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Palette.textSecondary, fontSize = 12.sp)
                    }
                    IconBox(SIcons.Help, "Advanced transitions help", { help = true }, size = 44)
                    Spacer(Modifier.width(6.dp))
                    MoreMenu("More transition actions", listOf(MenuAction("Reset to Smooth…", SIcons.Refresh) {
                        ask = Ask("Reset every track in “$name” to Smooth?", null, "Reset") {
                            rows = rows.mapValues { (_, list) -> list.map { it.copy(on = emptySet(), pre = 0) } }
                            keep = mapOf("in" to JsonObject(emptyMap()), "out" to JsonObject(emptyMap()))
                            editor.toastInfo("Reset to Smooth")
                        }
                    }))
                    Spacer(Modifier.width(6.dp))
                    IconBox(SIcons.Close, "Cancel", onClose, size = 44)
                }
                // Tabs with counts
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("while", "While playing", w?.let { AdvancedRules.count(it) } ?: 0),
                        Triple("in", "Coming in", rows.getValue("in").size),
                        Triple("out", "Leaving $name", rows.getValue("out").size),
                    ).forEach { (k, label, n) ->
                        val on = tab == k
                        Row(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .background(if (on) Palette.card else Palette.backgroundSecondary, RoundedCornerShape(8.dp))
                                .border(1.dp, if (on) Palette.accent else Palette.border, RoundedCornerShape(8.dp))
                                .clickable { tab = k }
                                .padding(horizontal = 6.dp)
                                .semantics { contentDescription = "$label, $n" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(label, color = if (on) Palette.text else Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(4.dp))
                            CountBadge(n)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    if (loading) {
                        item { Text(if (tab == "while") "Loading tracks…" else "Loading…", color = Palette.textSecondary, modifier = Modifier.padding(16.dp)) }
                    } else if (tab == "while") {
                        val cur = w
                        if (cur != null) {
                            item(key = "note") {
                                Column(Modifier.padding(top = 10.dp)) {
                                    Text("For this step only. Anything you don't change follows the preset, $name.", color = Palette.textSecondary, fontSize = 13.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                        Text("Tracks", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        CountBadge(cur.tracks.size)
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = {
                                            w = AdvancedRules.revert(cur, null, null)
                                            editor.toastInfo("Reverted to the last save")
                                        }, enabled = AdvancedRules.unsaved(cur)) { Text("Revert changes") }
                                    }
                                }
                            }
                            whileSections(cur, ui, closed, presetRise, onFold = { g -> closed = if (g in closed) closed - g else closed + g }, onChange = { w = it }, editor = editor)
                        }
                    } else {
                        val list = rows.getValue(tab)
                        if (list.isEmpty()) item {
                            val text = if (tab == "out") (if (next != null) "No track leaves at the next step" else "Nothing comes after this preset")
                            else (if (prev != null) "No track comes in at this step" else "The sequence starts here")
                            Text(text, color = Palette.textSecondary, modifier = Modifier.padding(16.dp))
                        }
                        list.forEachIndexed { i, r ->
                            item(key = "$tab-${r.key}") { TransitionRow(r, tab, ramp) { change -> setRow(tab, i, change) } }
                        }
                    }
                }
                // Summary and footer
                val summary = if (tab == "while") {
                    val n = w?.let { AdvancedRules.count(it) } ?: 0
                    if (n > 0) "$n ${if (n == 1) "change" else "changes"} from the preset" else "Follows the preset"
                } else {
                    val n = rows.getValue("in").count { it.isSet } + rows.getValue("out").count { it.isSet }
                    if (n > 0) "$n ${if (n == 1) "track" else "tracks"} set · the rest follow the $ramp s ramp" else "All Smooth · the $ramp s ramp"
                }
                Row(Modifier.fillMaxWidth().background(Palette.bar).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("Cancel", color = Palette.text)
                    }
                    Button(
                        onClick = {
                            val transitions = AdvancedRules.stepTransitions(rows.getValue("in"), rows.getValue("out"), keep.getValue("in"), keep.getValue("out"))
                            val layer = w?.takeIf { AdvancedRules.unsaved(it) }?.let { AdvancedRules.layer(it) }
                            // in the editor's scope: this dialog's own scope ends when it closes
                            editor.saveAdvanced(step, transitions, layer, name)
                            onClose()
                        },
                        enabled = !loading,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
    if (help) HelpPanel("Advanced transitions", HelpTexts.ADVANCED) { help = false }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

// ---------- While playing: the editor's sections with this step's values ----------

private fun LazyListScope.whileSections(
    w: While,
    ui: EditorUi,
    closed: Set<String>,
    presetRise: Set<String>,
    onFold: (String) -> Unit,
    onChange: (While) -> Unit,
    editor: ThemeEditor,
) {
    Section.entries.filter { it != Section.Unassigned }.forEach { section ->
        val keys = w.tracks.keys.filter { w.modes[it] == section }
        val groups = if (section.groupMode == null) emptyList() else ui.listedGroups.filter { Section.ofGroupMode(ThemeEditorRules.groupMode(it)) == section && it.name in w.groups }
        item(key = "tw-sec-${section.mode}") {
            Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                HorizontalDivider(color = Palette.border)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.label, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    CountBadge(keys.size)
                }
            }
        }
        groups.forEach { g ->
            val members = keys.filter { k -> ui.tracks.firstOrNull { it.name == k }?.let { ThemeEditorRules.groupOf(it) } == g.name }
            item(key = "tw-g-${g.name}") { WhileGroup(g.name, section, members.size, w, g.name in closed, g.name in presetRise, { onFold(g.name) }, onChange, editor) }
            if (g.name !in closed) members.forEach { k -> item(key = "tw-t-$k") { WhileTrack(k, section, g.name, w, onChange, editor) } }
        }
        keys.filter { k -> groups.none { g -> ui.tracks.firstOrNull { it.name == k }?.let { ThemeEditorRules.groupOf(it) } == g.name } }
            .forEach { k -> item(key = "tw-t-$k") { WhileTrack(k, section, null, w, onChange, editor) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhileTrack(key: String, section: Section, group: String?, w: While, onChange: (While) -> Unit, editor: ThemeEditor) {
    val v = w.tracks.getValue(key)
    val base = w.baseTracks.getValue(key)
    fun changed(f: String) = !AdvancedRules.same(f, v[f], base[f])
    fun set(vararg pairs: Pair<String, JsonElement>) = onChange(AdvancedRules.set(w, false, key, mapOf(*pairs)))
    val muted = bool(v["muted"])
    val groupMuted = group?.let { bool(w.groups[it]?.get("muted")) } == true
    val short = key.substringAfterLast('/')
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (group != null) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            .background(Palette.card, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).alpha(if (muted || groupMuted) 0.55f else 1f)) {
                Text(short, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (group != null) section.groupLabel else section.label, color = Palette.textMuted, fontSize = 12.sp)
            }
            IconBox(
                if (muted) SIcons.Muted else SIcons.Volume, if (muted) "Unmute" else "Mute", { set("muted" to JsonPrimitive(!muted)) },
                size = 36, tint = if (changed("muted")) Palette.accent else if (muted) Palette.danger else Palette.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            WhileTrackMenu(key, section, group, w, onChange, editor)
        }
        if (section == Section.EbbFlow) {
            val lo = (minOf(dbl(v["volume"], 1.0), dbl(v["volume_low"], 0.2)) * 100).roundToInt()
            val hi = (dbl(v["volume"], 1.0) * 100).roundToInt()
            var range by remember(lo, hi) { mutableStateOf(lo.toFloat()..hi.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = tint(changed("volume") || changed("volume_low")), fontSize = 12.sp, modifier = Modifier.width(64.dp))
                RangeSlider(
                    value = range, onValueChange = { range = it },
                    onValueChangeFinished = { set("volume_low" to JsonPrimitive(range.start.roundToInt() / 100.0), "volume" to JsonPrimitive(range.endInclusive.roundToInt() / 100.0)) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.border),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Lowest volume: $short, Highest volume: $short" },
                )
                Text("${range.start.roundToInt()}–${range.endInclusive.roundToInt()}%", color = tint(changed("volume") || changed("volume_low")), fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp).padding(start = 6.dp))
            }
        } else {
            ValueSlider("Volume", (dbl(v["volume"], 1.0) * 100).roundToInt(), 0, 100, "Volume: $short", changed("volume"), { "$it%" }) { set("volume" to JsonPrimitive(it / 100.0)) }
        }
        section.second?.let { label ->
            val ebb = section == Section.EbbFlow
            ValueSlider(label, (dbl(v["presence"], 1.0) * 100).roundToInt(), 0, 100, "$label: $short", changed("presence"), { if (ebb) "" else "$it%" }) { set("presence" to JsonPrimitive(it / 100.0)) }
        }
    }
}

@Composable
private fun WhileTrackMenu(key: String, section: Section, group: String?, w: While, onChange: (While) -> Unit, editor: ThemeEditor) {
    var open by remember { mutableStateOf(false) }
    val v = w.tracks.getValue(key)
    val dot = section == Section.EbbFlow && AdvancedRules.differs(w, false, key, listOf("rise", "pause_between", "pause_min", "pause_max"))
    fun set(vararg pairs: Pair<String, JsonElement>) = onChange(AdvancedRules.set(w, false, key, mapOf(*pairs)))
    val short = key.substringAfterLast('/')
    Box {
        Box {
            IconBox(SIcons.More, "More for $short", { open = true }, size = 36)
            if (dot) Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { Dot(Palette.accent, 6) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 260.dp)) {
            if (section == Section.EbbFlow) {
                val groupRise = group?.let { w.groups[it]?.get("rise") }?.takeIf { it !is JsonNull }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Rise" + if (groupRise != null) "  set by group" else "", color = Palette.text, fontSize = 14.sp)
                    ValueSlider("", (dbl(groupRise ?: v["rise"]) * 100).roundToInt(), 0, 100, "Rise: $short", AdvancedRules.differs(w, false, key, listOf("rise")), { "" }, enabled = groupRise == null, ends = "Gradual" to "Sudden") {
                        set("rise" to JsonPrimitive(it / 100.0))
                    }
                }
                HorizontalDivider(color = Palette.border)
                val groupPause = group?.let { bool(w.groups[it]?.get("pause_between")) } == true
                Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pause between plays" + if (groupPause) "  set by group" else "", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = groupPause || bool(v["pause_between"]), enabled = !groupPause,
                        onCheckedChange = { set("pause_between" to JsonPrimitive(it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                    )
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pause", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val min = SequenceRules.plain(dbl(v["pause_min"], 30.0))
                    val max = SequenceRules.plain(dbl(v["pause_max"], 300.0))
                    NumberBox(min, "30", true, "Shortest pause (seconds)") { a ->
                        val (x, y) = ThemeEditorRules.pausePair(a, max)
                        set("pause_min" to JsonPrimitive(x), "pause_max" to JsonPrimitive(y))
                    }
                    Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                    NumberBox(max, "300", true, "Longest pause (seconds)") { b ->
                        val (x, y) = ThemeEditorRules.pausePair(min, b)
                        set("pause_min" to JsonPrimitive(x), "pause_max" to JsonPrimitive(y))
                    }
                    Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
                HorizontalDivider(color = Palette.border)
            }
            val canRevert = AdvancedRules.differs(w, false, key, AdvancedRules.TRACK_FIELDS, w.savedTracks)
            androidx.compose.material3.DropdownMenuItem(
                enabled = canRevert,
                text = { Text("Revert changes", color = if (canRevert) Palette.text else Palette.textMuted) },
                onClick = { open = false; onChange(AdvancedRules.revert(w, false, key)); editor.toastInfo("Reverted $short") },
            )
        }
    }
}

@Composable
private fun WhileGroup(
    name: String,
    section: Section,
    count: Int,
    w: While,
    collapsed: Boolean,
    presetRise: Boolean,
    onFold: () -> Unit,
    onChange: (While) -> Unit,
    editor: ThemeEditor,
) {
    val v = w.groups.getValue(name)
    val base = w.baseGroups.getValue(name)
    fun changed(f: String) = !AdvancedRules.same(f, v[f], base[f])
    fun set(vararg pairs: Pair<String, JsonElement>) = onChange(AdvancedRules.set(w, true, name, mapOf(*pairs)))
    val muted = bool(v["muted"])
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(if (collapsed) SIcons.ChevronRight else SIcons.ChevronDown, if (collapsed) "Expand $name" else "Collapse $name", onFold, size = 36)
            Spacer(Modifier.width(6.dp))
            Text(name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(if (count == 1) "1 track" else "$count tracks", color = Palette.textMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            IconBox(
                if (muted) SIcons.Muted else SIcons.Volume, if (muted) "Unmute group" else "Mute group", { set("muted" to JsonPrimitive(!muted)) },
                size = 36, tint = if (changed("muted")) Palette.accent else if (muted) Palette.danger else Palette.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
            Box {
                IconBox(SIcons.More, "More for group $name", { open = true }, size = 36)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 240.dp)) {
                    if (section == Section.EbbFlow) {
                        val rise = v["rise"]?.takeIf { it !is JsonNull }
                        Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Same Rise for every file", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = presetRise || rise != null, enabled = !presetRise,
                                onCheckedChange = { on -> set("rise" to if (on) JsonPrimitive(0.5) else JsonNull) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                            )
                        }
                        if (rise != null) Column(Modifier.padding(horizontal = 16.dp)) {
                            ValueSlider("Rise", (dbl(rise) * 100).roundToInt(), 0, 100, "How fast every file rises", changed("rise"), { "" }, ends = "Gradual" to "Sudden") {
                                set("rise" to JsonPrimitive(it / 100.0))
                            }
                        }
                        HorizontalDivider(color = Palette.border)
                    }
                    val canRevert = AdvancedRules.differs(w, true, name, AdvancedRules.GROUP_FIELDS, w.savedGroups)
                    androidx.compose.material3.DropdownMenuItem(
                        enabled = canRevert,
                        text = { Text("Revert changes", color = if (canRevert) Palette.text else Palette.textMuted) },
                        onClick = { open = false; onChange(AdvancedRules.revert(w, true, name)); editor.toastInfo("Reverted $name") },
                    )
                }
            }
        }
        when (section) {
            Section.Intermittent -> Row(Modifier.padding(start = 42.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val min = ThemeEditorRules.minutes(v["gap_min"]?.let { (it as? JsonPrimitive)?.doubleOrNull })
                val max = ThemeEditorRules.minutes(v["gap_max"]?.let { (it as? JsonPrimitive)?.doubleOrNull })
                Text("Gap", color = tint(changed("gap_min") || changed("gap_max")), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                fun gap(a: String, b: String) {
                    val (x, y) = ThemeEditorRules.gapPair(ThemeEditorRules.gapSeconds(a), ThemeEditorRules.gapSeconds(b))
                    set("gap_min" to (x?.let { JsonPrimitive(it) } ?: JsonNull), "gap_max" to (y?.let { JsonPrimitive(it) } ?: JsonNull))
                }
                NumberBox(min, "2", true, "Shortest gap (minutes): $name", width = 56) { gap(it, max) }
                Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                NumberBox(max, "2", true, "Longest gap (minutes): $name", width = 56) { gap(min, it) }
                Text("min", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Section.EbbFlow -> Row(Modifier.padding(start = 42.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pause between plays", color = tint(changed("pause_between")), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = bool(v["pause_between"]), onCheckedChange = { set("pause_between" to JsonPrimitive(it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                )
            }
            else -> Unit
        }
        ValueSlider("Volume", (dbl(v["volume"], 1.0) * 100).roundToInt(), 0, 100, "Group volume: $name", changed("volume"), { "$it%" }) { set("volume" to JsonPrimitive(it / 100.0)) }
        section.second?.let { label ->
            val ebb = section == Section.EbbFlow
            ValueSlider(label, (dbl(v["presence"], 1.0) * 100).roundToInt(), 0, 100, "Group ${label.lowercase()}: $name", changed("presence"), { if (ebb) "" else "$it%" }, ends = if (ebb) "Shorter" to "Longer" else null) {
                set("presence" to JsonPrimitive(it / 100.0))
            }
        }
    }
}

// ---------- Coming in / Leaving rows ----------

@Composable
private fun TransitionRow(r: TxRow, side: String, ramp: Int, change: ((TxRow) -> TxRow) -> Unit) {
    val names = AdvancedRules.NAMES.getValue(side)
    val tips = AdvancedRules.TIPS.getValue(side)
    val out = side == "out"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.mode, color = Palette.textMuted, fontSize = 12.sp)
            }
            Text(
                "${AdvancedRules.pct(r.from)} → ${AdvancedRules.pct(r.to)}", color = Palette.textSecondary, fontSize = 13.sp,
                modifier = Modifier.semantics { contentDescription = "${AdvancedRules.pct(r.from)} to ${AdvancedRules.pct(r.to)}" },
            )
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdvancedRules.STYLES.forEach { k ->
                val disabled = k == "build" && !r.intermittent
                val on = k in r.on && !disabled
                val label = names.getValue(k)
                Box(
                    Modifier
                        .heightIn(min = 36.dp)
                        .alpha(if (disabled) 0.4f else 1f)
                        .background(if (on) (if (out) Palette.warning else Palette.accent).copy(alpha = 0.18f) else Palette.backgroundSecondary, RoundedCornerShape(16.dp))
                        .border(1.dp, if (on) (if (out) Palette.warning else Palette.accent) else Palette.border, RoundedCornerShape(16.dp))
                        .clickable(enabled = !disabled) { change { it.copy(on = if (k in it.on) it.on - k else it.on + k) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { contentDescription = if (disabled) "$label: Intermittent tracks only" else tips.getValue(k) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) Palette.text else Palette.textSecondary, fontSize = 12.sp) }
            }
        }
        val active = r.active
        if (active.isEmpty()) {
            Row(Modifier.padding(top = 8.dp)) {
                Text("Smooth", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("ramp $ramp s", color = Palette.textMuted, fontSize = 13.sp)
            }
        }
        active.forEach { k ->
            Column(Modifier.padding(top = 8.dp)) {
                if (k == "fresh") {
                    Row {
                        Text(names.getValue(k), color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (out) "no new play" else "from the file start", color = Palette.textSecondary, fontSize = 13.sp)
                    }
                } else {
                    val st = AdvancedRules.slider(r, k, ramp)
                    ValueSlider(AdvancedRules.SLIDER.getValue(k), st.value, st.min, st.max, "${names.getValue(k)}, ${AdvancedRules.SLIDER.getValue(k)}: ${r.name}", false, { AdvancedRules.slider(r.copy(v = r.v + (k to it)), k, ramp).text }, live = true) { value ->
                        change { it.copy(v = it.v + (k to value)) }
                    }
                    if (k == "build") {
                        val (a, b) = AdvancedRules.hints(r, out)
                        Text(a, color = Palette.textMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 64.dp))
                        Text(b, color = Palette.textMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 64.dp))
                    }
                    if (k == "gusts") {
                        ValueSlider("Max volume", minOf(r.maxVol, r.cap), 0, r.cap, "Max volume: ${r.name}", false, { "$it% of ${r.cap}%" }, live = true) { value ->
                            change { it.copy(maxVol = value) }
                        }
                    }
                }
            }
        }
        ValueSlider("Pre-fade", r.pre, 0, 90, "Pre-fade: ${r.name}. Starts this long before the step", false, { if (it > 0) "$it s" else "Off" }, step = 5, live = true) { value ->
            change { it.copy(pre = value) }
        }
    }
}

/**
 * A slider over [min]..[max] (whole numbers, [step]); [changed] shows the label green. Reports on release,
 * or as it moves when [live].
 */
@Composable
private fun ValueSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    description: String,
    changed: Boolean,
    text: (Int) -> String,
    enabled: Boolean = true,
    ends: Pair<String, String>? = null,
    step: Int = 1,
    live: Boolean = false,
    onSet: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(value.toFloat()) }
    if (!dragging) local = value.toFloat()
    val span = (max - min).coerceAtLeast(1)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label.isNotEmpty()) Text(label, color = tint(changed), fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = local.coerceIn(min.toFloat(), maxOf(min, max).toFloat()),
                onValueChange = { dragging = true; local = it; if (live) onSet(it.roundToInt()) },
                onValueChangeFinished = { dragging = false; if (!live && local.roundToInt() != value) onSet(local.roundToInt()) },
                valueRange = min.toFloat()..maxOf(min + 1, max).toFloat(),
                steps = if (step > 1) (span / step - 1).coerceAtLeast(0) else 0,
                enabled = enabled && max > min,
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.border),
                modifier = Modifier.weight(1f).semantics { contentDescription = description },
            )
            Text(text(local.roundToInt()), color = tint(changed), fontSize = 12.sp, modifier = Modifier.widthIn(min = 72.dp).padding(start = 6.dp))
        }
        if (ends != null) {
            Row(Modifier.padding(start = if (label.isNotEmpty()) 64.dp else 0.dp, end = 78.dp)) {
                Text(ends.first, color = Palette.textMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(ends.second, color = Palette.textMuted, fontSize = 11.sp)
            }
        }
    }
}
