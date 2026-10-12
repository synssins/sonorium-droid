package app.sonorium.ui.server

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.net.Speaker
import app.sonorium.net.SpeakerHierarchy
import app.sonorium.server.GroupRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.server.SpeakerRules
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings › Speakers (native-api.md Phase 2): inline controls per row, no settings dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakersPage(state: ServerState, model: ServerViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sonorium", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var hideOffline by remember { mutableStateOf(prefs.getBoolean("spkHideOffline", false)) }
    var closed by remember { mutableStateOf(prefs.getStringSet("spkClosed", emptySet()).orEmpty()) }
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) } // title to manual id
    // Busy test buttons survive re-renders: entity id → until (ms)
    val testing = remember { mutableStateMapOf<String, Long>() }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { model.loadSpeakersPage() }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000)
            tick = System.currentTimeMillis()
        }
    }

    val h = state.hierarchy
    val network = state.network
    // Source filter buttons: All, Home Assistant (when HA may be there), Discovered + Manual (network speakers)
    val sources = buildList {
        add(null to "All")
        if (state.hasHa) add("ha" to "Home Assistant")
        if (network != null) {
            add("discovered" to "Discovered")
            add("manual" to "Manual")
        }
    }
    if (sources.none { it.first == source }) source = null

    fun clearFilters() {
        query = ""
        source = null
        hideOffline = false
        prefs.edit().putBoolean("spkHideOffline", false).apply()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speakers", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    PageHelpButton("settings-speakers")
                    if (h != null) {
                        val all = SpeakerRules.all(h)
                        val on = all.count { it.entityId in state.enabledSpeakers }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.semantics { contentDescription = "$on in use of ${all.size}" }) { Badge("$on / ${all.size}") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (network != null) AddButton("Add speaker", narrow) { adding = true }
                }
                // Toolbar
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search speakers", color = Palette.textMuted) },
                        leadingIcon = { Icon(SIcons.Search, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconBox(SIcons.Close, "Clear search", { query = "" }, size = 32, background = Palette.background, border = Palette.background) }) else null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search speakers" },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (sources.size > 2) {
                            Segmented(sources.map { it.second }, sources.indexOfFirst { it.first == source }.coerceAtLeast(0), { source = sources[it].first }, Modifier.semantics { contentDescription = "Show speakers from" })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hide offline", color = Palette.text, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = hideOffline,
                                onCheckedChange = { hideOffline = it; prefs.edit().putBoolean("spkHideOffline", it).apply() },
                                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                                modifier = Modifier.semantics { contentDescription = "Hide offline" },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (network != null) {
                            // Re-read every 60 s so "Last scan" stays current
                            key(tick) {}
                            Text(
                                when {
                                    scanning -> "Scanning..."
                                    network.lastScan == null -> "Not scanned yet"
                                    else -> "Last scan ${SpeakerRules.timeAgo(network.lastScan)} · ${network.found} found"
                                },
                                color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        OutlinedButton(
                            onClick = { scanning = true; model.rescanSpeakers { scanning = false } },
                            enabled = !scanning,
                            border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Icon(SIcons.Refresh, null, tint = Palette.text, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (scanning) "Scanning…" else if (network != null) "Rescan network" else "Refresh from HA", color = Palette.text)
                        }
                    }
                }
            }
            if (h == null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Loading speakers...", color = Palette.textSecondary)
                    }
                }
            } else {
                val sections = SpeakerRules.sections(h) { SpeakerRules.matches(it, query, source, hideOffline) }
                when {
                    SpeakerRules.all(h).isEmpty() -> item { EmptyState("No speakers found yet", null) }
                    sections.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(SIcons.Search, null, tint = Palette.textMuted, modifier = Modifier.size(28.dp))
                            Text("No speakers match", color = Palette.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                            OutlinedButton(onClick = { clearFilters() }, border = BorderStroke(1.dp, Palette.border)) { Text("Clear filters", color = Palette.text) }
                        }
                    }
                }
                sections.forEach { sec ->
                    val isClosed = sec.key in closed
                    item(key = "sec-${sec.key}") {
                        val on = sec.speakers.count { it.entityId in state.enabledSpeakers }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                                closed = if (isClosed) closed - sec.key else closed + sec.key
                                prefs.edit().putStringSet("spkClosed", closed).apply()
                            }.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (isClosed) SIcons.ChevronRight else SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sec.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text("${GroupRules.plural(sec.speakers.size, "speaker")} · $on of ${sec.speakers.size} on", color = Palette.textMuted, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Palette.border)
                    }
                    if (!isClosed) {
                        items(sec.speakers, key = { "${sec.key}-${it.entityId}" }) { s ->
                            SpeakerRow(
                                s, h, used = s.entityId in state.enabledSpeakers, narrow = narrow,
                                busyUntil = testing[s.entityId] ?: 0L,
                                model = model,
                                onTest = {
                                    testing[s.entityId] = Long.MAX_VALUE
                                    model.testSpeaker(s.entityId, s.name) { secs ->
                                        if (secs <= 0) testing.remove(s.entityId)
                                        else {
                                            testing[s.entityId] = System.currentTimeMillis() + (secs * 1000).toLong()
                                            scope.launch {
                                                delay((secs * 1000).toLong())
                                                testing.remove(s.entityId)
                                            }
                                        }
                                    }
                                },
                                onRemove = { title, id -> removing = title to id },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    removing?.let { (title, id) ->
        ConfirmDialog(title, null, "Remove", onConfirm = { model.removeManualSpeaker(id) }, onDismiss = { removing = null })
    }
    if (adding) AddSpeakerDialog(state, model, onClose = { adding = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerRow(
    s: Speaker,
    h: SpeakerHierarchy,
    used: Boolean,
    narrow: Boolean,
    busyUntil: Long,
    model: ServerViewModel,
    onTest: () -> Unit,
    onRemove: (title: String, id: String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().alpha(if (used) 1f else 0.6f).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnlineDot(s.online)
            Spacer(Modifier.width(8.dp))
            if (renaming) {
                RenameField(s, onDone = { typed ->
                    renaming = false
                    if (typed != null) rename(model, s, typed)
                }, modifier = Modifier.weight(1f))
            } else {
                Text(
                    s.name, color = if (s.online) Palette.text else Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "Rename ${s.name}" }.clickable { renaming = true },
                )
                Spacer(Modifier.weight(1f))
            }
            Switch(
                checked = used,
                onCheckedChange = { model.setSpeakerUsed(s.entityId, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                modifier = Modifier.semantics { contentDescription = "Use ${s.name}" },
            )
            Spacer(Modifier.width(6.dp))
            SpeakerMenu(s, model, onRename = { renaming = true }, onRemove = onRemove)
        }
        FlowRow(
            Modifier.padding(start = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if ("ha" in s.source) Badge("HA")
            if ("discovered" in s.source) Badge("Discovered")
            if ("manual" in s.source) Badge("Manual")
            GroupRules.typeLabel(s.type)?.let { Badge(it) }
            Text(s.address?.takeIf { it.isNotEmpty() } ?: s.entityId, color = if (s.address.isNullOrEmpty()) Palette.textMuted else Palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(start = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Area: every floor's areas, then unassigned areas, then "No area" last
            val areas = h.floors.flatMap { it.areas } + h.unassignedAreas
            val options = areas.map { PickOption(it.areaId, it.name) } + PickOption("", "No area")
            PickerField(
                label = "Area for ${s.name}",
                options = options,
                current = s.areaId ?: "",
                onPick = { o ->
                    if (o.value != (s.areaId ?: "")) {
                        val room = SpeakerRules.roomValue(s, o.value)
                        model.saveSpeaker(s.entityId, buildJsonObject { put("room", room?.let { JsonPrimitive(it) } ?: JsonNull) }, "“${s.name}” moved to ${o.label}")
                    }
                },
                modifier = Modifier.weight(1f).widthIn(max = 260.dp),
            )
            Spacer(Modifier.width(6.dp))
            PickerField(
                label = "Volume offset for ${s.name}",
                options = SpeakerRules.offsetOptions(s.volumeOffset).map { PickOption("$it", SpeakerRules.offsetText(it)) },
                current = "${s.volumeOffset}",
                onPick = { o -> if (o.value != "${s.volumeOffset}") model.saveSpeaker(s.entityId, buildJsonObject { put("volume_offset", o.value.toInt()) }, null) },
                modifier = Modifier.width(96.dp),
            )
            Spacer(Modifier.width(6.dp))
            val busy = busyUntil > System.currentTimeMillis()
            IconBox(SIcons.Volume, if (busy) "Playing…" else "Test ${s.name}", onTest, enabled = !busy, size = 44)
        }
    }
    HorizontalDivider(color = Palette.border.copy(alpha = 0.5f))
}

private fun rename(model: ServerViewModel, s: Speaker, typed: String) {
    when (val r = SpeakerRules.rename(s, typed)) {
        SpeakerRules.Rename.None -> {}
        is SpeakerRules.Rename.Reset -> model.saveSpeaker(s.entityId, buildJsonObject { put("name", JsonNull) }, "Name reset to “${r.original}”")
        is SpeakerRules.Rename.To -> model.saveSpeaker(s.entityId, buildJsonObject { put("name", r.name) }, "Renamed to “${r.name}”")
    }
}

/** The inline rename: prefilled and selected; Done or leaving the field saves. */
@Composable
private fun RenameField(s: Speaker, onDone: (String?) -> Unit, modifier: Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(s.name, TextRange(0, s.name.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish(text: String?) {
        if (finished) return
        finished = true
        onDone(text)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.text.length <= 100) value = it },
        singleLine = true,
        placeholder = { Text(s.originalName.orEmpty(), color = Palette.textMuted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(value.text) }),
        trailingIcon = { IconBox(SIcons.Close, "Cancel rename", { finish(null) }, size = 32, background = Palette.background, border = Palette.background) },
        modifier = modifier.focusRequester(focus).semantics { contentDescription = "Name for ${s.name}" }.onFocusChanged {
            if (focused && !it.isFocused) finish(value.text)
            focused = it.isFocused
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun SpeakerMenu(s: Speaker, model: ServerViewModel, onRename: () -> Unit, onRemove: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box {
            IconBox(SIcons.More, "More for ${s.name}", { open = true }, size = 40)
            if (SpeakerRules.menuDot(s)) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(6.dp)) { Dot(Palette.accent, 6) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card) {
            DropdownMenuItem(text = { Text("Rename", color = Palette.text) }, leadingIcon = { Icon(SIcons.Edit, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) }, onClick = { open = false; onRename() })
            val renamed = SpeakerRules.renamed(s)
            DropdownMenuItem(
                enabled = renamed,
                text = {
                    Column {
                        Text("Reset name", color = if (renamed) Palette.text else Palette.textMuted)
                        s.originalName?.let { Text(it, color = Palette.textMuted, fontSize = 12.sp) }
                    }
                },
                onClick = {
                    open = false
                    model.saveSpeaker(s.entityId, buildJsonObject { put("name", JsonNull) }, "Name reset to “${s.originalName ?: s.name}”")
                },
            )
            if (s.merged.isNotEmpty()) {
                HorizontalDivider(color = Palette.border)
                Text("Play via", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val via = s.playVia ?: "ha"
                (listOf("ha" to "Home Assistant") + s.merged.map { it.id to SpeakerRules.playViaLabel(it.type) }).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text((if (value == via) "● " else "○ ") + label, color = Palette.text) },
                        onClick = {
                            open = false
                            if (value != via) model.saveSpeaker(s.entityId, buildJsonObject { put("play_via", value) }, "Plays via $label")
                        },
                    )
                }
            }
            val manual = if (SpeakerRules.isManual(s)) listOf("Remove…" to ("Remove “${s.name}”?" to s.entityId))
            else s.merged.filter { it.source == "manual" }.map { "Remove the address added manually…" to ("Remove the manual address for “${s.name}”?" to it.id) }
            manual.forEachIndexed { i, (label, target) ->
                if (i == 0) HorizontalDivider(color = Palette.border)
                DropdownMenuItem(
                    text = { Text(label, color = Palette.danger) },
                    leadingIcon = { Icon(SIcons.Trash, null, tint = Palette.danger, modifier = Modifier.size(16.dp)) },
                    onClick = { open = false; onRemove(target.first, target.second) },
                )
            }
        }
    }
}

@Composable
private fun AddSpeakerDialog(state: ServerState, model: ServerViewModel, onClose: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("auto") }
    var room by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean?, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun form(): kotlinx.serialization.json.JsonObject? {
        SpeakerRules.checkAddress(address, port)?.let { model.toast(it, error = true); return null }
        return buildJsonObject {
            put("address", address.trim())
            put("type", type)
            if (name.isNotBlank()) put("name", name.trim())
            if (room.isNotEmpty()) put("room", room)
            if (port.isNotBlank()) put("port", port.trim().toInt())
        }
    }

    fun add() {
        val body = form() ?: return
        adding = true
        model.addManualSpeaker(body) { ok -> adding = false; if (ok) onClose() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.card,
        title = { Text("Add speaker", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Address")
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    placeholder = { Text("192.168.1.50 or speaker.local", color = Palette.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "IP address or network name of the speaker" },
                )
                FieldLabel("Port")
                OutlinedTextField(
                    value = port, onValueChange = { port = it }, singleLine = true,
                    placeholder = { Text("Auto", color = Palette.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Default for the type" },
                )
                FieldLabel("Type")
                PickerField("Type", SpeakerRules.ADD_TYPES.map { PickOption(it.first, it.second) }, type, { type = it.value })
                FieldLabel("Area")
                val areas = state.hierarchy?.let { h -> h.floors.flatMap { it.areas } + h.unassignedAreas }.orEmpty()
                PickerField("Area", listOf(PickOption("", "No area")) + areas.map { PickOption(it.areaId, it.name) }, room, { room = it.value })
                FieldLabel("Name")
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 100) name = it }, singleLine = true,
                    placeholder = { Text("Speaker's own name", color = Palette.textMuted) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                )
                result?.let { (found, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (found != null) Box(Modifier.size(8.dp)) { Dot(if (found) Palette.accent else Palette.textMuted) }
                        Spacer(Modifier.width(6.dp))
                        Text(text, color = Palette.textSecondary, fontSize = 13.sp)
                    }
                }
                TextButton(
                    enabled = !checking,
                    onClick = {
                        val body = form() ?: return@TextButton
                        checking = true
                        result = null to "Checking..."
                        scope.launch {
                            model.checkManualSpeaker(body).fold(
                                onSuccess = { r ->
                                    result = r.found to (r.message ?: if (r.found) "Speaker found." else "No speaker answered.")
                                    if (r.found) {
                                        if (name.isBlank()) r.name?.let { name = it }
                                        if (type == "auto") r.type?.let { type = it }
                                    }
                                },
                                onFailure = { e -> result = false to ((e as? app.sonorium.net.ApiException)?.detail?.takeIf { it.isNotBlank() } ?: "Check failed") },
                            )
                            checking = false
                        }
                    },
                ) { Text("Check connection", color = Palette.accent) }
            }
        },
        confirmButton = {
            Button(
                onClick = { add() }, enabled = !adding,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
            ) { Text("Add speaker") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Palette.textSecondary) } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
