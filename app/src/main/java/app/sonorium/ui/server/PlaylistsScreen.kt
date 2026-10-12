package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.sonorium.net.Playlist
import app.sonorium.net.PlaylistEntry
import app.sonorium.server.ChannelRules
import app.sonorium.server.PlaylistRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay

/** Playback › Playlists (native-api.md Phase 2, Playlists). */
@Composable
fun PlaylistsPage(state: ServerState, model: ServerViewModel) {
    // Expanded rows live in memory only (plOpenRows)
    val open = remember { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(Unit) {
        model.refreshPlaylists()
        model.refreshSessions()
    }
    LaunchedEffect(state.playlists, state.themes) { model.loadPlaylistPresets() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        val side = if (narrow) 12.dp else 24.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = side)) {
            item {
                PageHeader("Playlists", state.playlists.size, help = "playlists") {
                    // Playlists live on the server: nothing to add to without one (R15)
                    if (state.record != null) AddButton("Add playlist", narrow) { if (model.reachableOrSay()) adding = true }
                }
            }
            if (state.record == null) {
                item { EmptyState("No server connected", "Playlists are kept on your Sonorium server. Connect to see and add them.") }
            } else if (state.playlists.isEmpty()) {
                item { EmptyState("No playlists", "Play themes one after another, in order or shuffled") }
            } else if (!narrow) {
                item { PlaylistHeaderRow() }
            }
            itemsIndexed(state.playlists, key = { _, p -> p.id }) { _, p ->
                val expanded = p.id in open
                PlaylistRow(
                    playlist = p,
                    state = state,
                    narrow = narrow,
                    expanded = expanded,
                    onToggle = { if (expanded) open.remove(p.id) else open.add(p.id) },
                    onEdit = { editing = p },
                    onDuplicate = { model.duplicatePlaylist(p) },
                    onDelete = { deleting = p },
                )
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (adding || editing != null) {
        PlaylistEditor(
            edit = editing,
            state = state,
            onLoadPresets = { id -> model.loadPresetsAsync(id) },
            onError = { model.toast(it, error = true) },
            onSave = { id, body, done ->
                model.savePlaylist(id, body) { saved ->
                    if (id == null && saved.id !in open) open.add(saved.id)
                    done()
                    adding = false
                    editing = null
                }
            },
            onDelete = { p -> deleting = p },
            onClose = { adding = false; editing = null },
        )
    }

    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete “${p.name}”?",
            sub = PlaylistRules.deleteNote(p.id, state.sessions),
            action = "Delete",
            onConfirm = {
                model.deletePlaylist(p) {
                    if (editing?.id == p.id) editing = null
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PlaylistHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(44.dp))
        Header("Name", Modifier.weight(1f))
        Header("Entries", Modifier.width(72.dp))
        Header("Order", Modifier.width(110.dp))
        Header("Play each", Modifier.width(110.dp))
        Header("Repeat", Modifier.width(64.dp))
        Spacer(Modifier.width(120.dp))
    }
    HorizontalDivider(color = Palette.border)
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    state: ServerState,
    narrow: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val playingOn = PlaylistRules.playingOn(playlist.id, state.sessions)
    val shuffle = playlist.order == "shuffle"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                if (expanded) SIcons.ChevronDown else SIcons.ChevronRight,
                if (expanded) "Hide entries of ${playlist.name}" else "Show entries of ${playlist.name}",
                onToggle, size = app.sonorium.ui.UiSize.SMALL, background = Palette.background, border = Palette.background,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(SIcons.Playlist, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(playlist.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playingOn.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.semantics { contentDescription = "Playing on ${playingOn.joinToString(", ") { it.name }}" }) { Dot(Palette.accent) }
                    }
                }
                if (narrow) {
                    // Phone layout: the cells as one line, with their words
                    val parts = listOf(
                        PlaylistRules.entries(playlist.entries.size),
                        if (shuffle) "Shuffle" else "In order",
                        PlaylistRules.playEach(playlist),
                    ) + if (playlist.repeat) listOf("Repeat") else emptyList()
                    Text(parts.joinToString(" · "), color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2)
                }
            }
            if (!narrow) {
                Text("${playlist.entries.size}", color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.width(72.dp))
                Row(Modifier.width(110.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (shuffle) SIcons.Shuffle else SIcons.InOrder, null, tint = Palette.textSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (shuffle) "Shuffle" else "In order", color = Palette.textSecondary, fontSize = 14.sp)
                }
                Text(PlaylistRules.playEach(playlist), color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.width(110.dp))
                Box(Modifier.width(64.dp)) {
                    if (playlist.repeat) Icon(SIcons.Repeat, "Repeats", tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                    else Text("–", color = Palette.textMuted, fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "Stops after the last entry" })
                }
            }
            OutlinedButton(onClick = onEdit, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
                Text("Edit", color = Palette.text)
            }
            Spacer(Modifier.width(6.dp))
            MoreMenu(
                "More for ${playlist.name}",
                listOf(
                    MenuAction("Duplicate", SIcons.Copy, onClick = onDuplicate),
                    MenuAction("Delete…", SIcons.Trash, danger = true, separatorBefore = true, onClick = onDelete),
                ),
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 8.dp)) {
                if (playlist.entries.isEmpty()) Text("No entries", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                playlist.entries.forEachIndexed { i, e -> EntryLine(i, e, state) }
            }
        }
        HorizontalDivider(color = Palette.border)
    }
}

@Composable
private fun EntryLine(index: Int, entry: PlaylistEntry, state: ServerState) {
    val theme = state.theme(entry.themeId)
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        if (theme == null) {
            Text("Theme deleted", color = Palette.textMuted, fontSize = 14.sp)
            return@Row
        }
        Text(ChannelRules.themeIcon(theme.id, theme), fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Text(theme.name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        EntryWhat(entry, state)
    }
}

/** `plWhatHtml`: the sequence or preset with its marks, or "Default settings". */
@Composable
private fun EntryWhat(entry: PlaylistEntry, state: ServerState) {
    val presets = entry.themeId?.let { state.presets[it] }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            entry.sequenceId != null -> {
                val seq = presets?.sequences?.firstOrNull { it.id == entry.sequenceId }
                Text(seq?.name ?: entry.sequenceId, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Marks(PickOption("", "", intrusion = seq?.hasIntrusion == true, sequence = true))
            }
            entry.presetId != null -> {
                val preset = presets?.presets?.firstOrNull { it.id == entry.presetId }
                Text(preset?.name ?: entry.presetId, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Marks(PickOption("", "", isDefault = preset?.isDefault == true, intrusion = preset?.hasIntrusion == true))
            }
            else -> Text("Default settings", color = Palette.textMuted, fontSize = 14.sp)
        }
    }
}

/** The editor dialog (`plOpen`): Add playlist / Edit playlist. */
@Composable
private fun PlaylistEditor(
    edit: Playlist?,
    state: ServerState,
    onLoadPresets: (String) -> Unit,
    onError: (String) -> Unit,
    onSave: (id: String?, body: kotlinx.serialization.json.JsonObject, done: () -> Unit) -> Unit,
    onDelete: (Playlist) -> Unit,
    onClose: () -> Unit,
) {
    // Duplicate (editor ⋯) turns this into an unsaved new playlist: id cleared
    var id by remember { mutableStateOf(edit?.id) }
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var minText by remember { mutableStateOf(PlaylistRules.minutes(edit?.playMin ?: 1800.0)) }
    var maxText by remember { mutableStateOf(PlaylistRules.minutes(edit?.playMax ?: 3600.0)) }
    var shuffle by remember { mutableStateOf(edit?.order == "shuffle") }
    var repeat by remember { mutableStateOf(edit?.repeat ?: true) }
    var fit by remember { mutableStateOf(edit?.fitSequences ?: false) }
    val entries = remember { mutableStateListOf<PlaylistEntry>().apply { addAll(edit?.entries.orEmpty()) } }
    var nameError by remember { mutableStateOf(false) }
    var rangeError by remember { mutableStateOf(false) }
    var badRows by remember { mutableStateOf(emptySet<Int>()) }
    var saving by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var focusRow by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        entries.mapNotNull { it.themeId }.distinct().filter { it !in state.presets }.forEach(onLoadPresets)
        if (edit == null) {
            delay(50)
            runCatching { nameFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusRow) {
        focusRow?.let { list.animateScrollToItem(it + 1) }
    }

    fun save() {
        val problem = PlaylistRules.validate(id, name, minText, maxText, entries, state.playlists, state.themes)
        if (problem != null) {
            when (val c = problem.check) {
                PlaylistRules.Check.Name -> { nameError = true; runCatching { nameFocus.requestFocus() } }
                PlaylistRules.Check.Range -> rangeError = true
                is PlaylistRules.Check.Themes -> badRows = c.rows
                PlaylistRules.Check.Entries -> {}
            }
            onError(problem.toast)
            return
        }
        saving = true
        val body = PlaylistRules.body(name, entries.toList(), shuffle, repeat, minText.trim().toDouble(), maxText.trim().toDouble(), fit)
        onSave(id, body) { saving = false }
    }

    val minVal = minText.trim().toDoubleOrNull()
    val maxVal = maxText.trim().toDoubleOrNull()
    val isNew = id == null

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Column(Modifier.safeDrawingPadding().imePadding().fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "Add playlist" else "Edit playlist", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!isNew) {
                        MoreMenu(
                            "Playlist menu",
                            listOf(
                                MenuAction("Duplicate", SIcons.Copy) {
                                    id = null
                                    name = PlaylistRules.freeName(name.trim(), state.playlists.map { it.name })
                                },
                                MenuAction("Delete playlist…", SIcons.Trash, danger = true, separatorBefore = true) {
                                    state.playlist(id)?.let(onDelete)
                                },
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    IconBox(SIcons.Close, "Close", onClose, size = app.sonorium.ui.UiSize.STANDARD)
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.Start) {
                    item {
                        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FieldLabel("Name")
                            SField(
                                value = name,
                                onValueChange = { if (it.length <= 100) name = it; nameError = false },
                                singleLine = true,
                                isError = nameError,
                                placeholder = { Text("Playlist name", color = Palette.textMuted) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).semantics { contentDescription = "Name" },
                            )
                            FieldLabel("Play each entry")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NumberField(minText, "Minimum minutes", rangeError) { minText = it; rangeError = false }
                                Text("  –  ", color = Palette.textSecondary)
                                NumberField(maxText, "Maximum minutes", rangeError) { maxText = it; rangeError = false }
                                Text("  min", color = Palette.textSecondary)
                            }
                            FieldLabel("Order")
                            Segmented(listOf("In order", "Shuffle"), if (shuffle) 1 else 0, { shuffle = it == 1 })
                            SwitchRow("Repeat", repeat, { repeat = it })
                            SwitchRow("Fit sequences into the playlist time", fit, { fit = it })
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Entries", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                CountBadge(entries.size)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { entries.add(PlaylistEntry()); focusRow = entries.lastIndex }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
                                    Icon(SIcons.Plus, null, tint = Palette.accent, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add entry", color = Palette.accent)
                                }
                            }
                            if (entries.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("No entries", color = Palette.textMuted, fontSize = 14.sp)
                                    TextButton(onClick = { entries.add(PlaylistEntry()); focusRow = 0 }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Add entry", color = Palette.accent) }
                                }
                            }
                        }
                    }
                    itemsIndexed(entries) { i, entry ->
                        EntryEditor(
                            index = i,
                            entry = entry,
                            count = entries.size,
                            state = state,
                            bad = i in badRows,
                            fit = fit,
                            min = minVal,
                            max = maxVal,
                            onTheme = { themeId ->
                                if (themeId != entry.themeId) {
                                    entries[i] = PlaylistEntry(themeId, null, null)
                                    if (themeId !in state.presets) onLoadPresets(themeId)
                                    badRows = badRows - i
                                }
                            },
                            onWhat = { presetId, sequenceId -> entries[i] = entry.copy(presetId = presetId, sequenceId = sequenceId) },
                            onMove = { to -> entries.add(to, entries.removeAt(i)) },
                            onDuplicate = { entries.add(i + 1, entry) },
                            onRemove = { entries.removeAt(i); badRows = emptySet() },
                        )
                    }
                    item { Spacer(Modifier.size(16.dp)) }
                }
                HorizontalDivider(color = Palette.border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(PlaylistRules.summary(entries.size, minVal, maxVal), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.text) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { save() },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp).semantics { contentDescription = "Save playlist" },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    ) { Text(if (isNew) "Add playlist" else "Save", fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, description: String, error: Boolean, onChange: (String) -> Unit) {
    SField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
        modifier = Modifier.width(96.dp).semantics { contentDescription = description },
    )
}

@Composable
private fun EntryEditor(
    index: Int,
    entry: PlaylistEntry,
    count: Int,
    state: ServerState,
    bad: Boolean,
    fit: Boolean,
    min: Double?,
    max: Double?,
    onTheme: (String) -> Unit,
    onWhat: (presetId: String?, sequenceId: String?) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
) {
    val presets = entry.themeId?.let { state.presets[it] }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", color = Palette.textMuted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
            // Theme: all themes in GET /themes order (not sorted)
            val themeOptions = state.themes.map { PickOption(it.id, "${ChannelRules.themeIcon(it.id, it)}  ${it.name}") }
            Box(Modifier.weight(1f).let { if (bad) it.background(Palette.danger.copy(alpha = 0.25f), RoundedCornerShape(8.dp)) else it }) {
                PickerField(
                    label = "Theme ${index + 1}",
                    options = themeOptions.ifEmpty { listOf(PickOption("", "No themes yet")) },
                    current = entry.themeId?.takeIf { state.theme(it) != null },
                    onPick = { if (it.value.isNotEmpty()) onTheme(it.value) },
                    placeholder = if (entry.themeId != null && state.theme(entry.themeId) == null) "Theme deleted" else "Choose theme",
                )
            }
            Spacer(Modifier.width(6.dp))
            MoreMenu(
                "Entry ${index + 1} menu",
                listOf(
                    MenuAction("Move up", enabled = index > 0) { onMove(index - 1) },
                    MenuAction("Move down", enabled = index < count - 1) { onMove(index + 1) },
                    MenuAction("Duplicate", SIcons.Copy, onClick = onDuplicate),
                    MenuAction("Remove", SIcons.Trash, danger = true, separatorBefore = true, onClick = onRemove),
                ),
            )
        }
        Spacer(Modifier.size(6.dp))
        val what = buildList {
            add(PickOption("", "Default settings"))
            presets?.presets?.forEach { add(PickOption("p:${it.id}", it.name, isDefault = it.isDefault, intrusion = it.hasIntrusion)) }
            presets?.sequences?.forEachIndexed { i, s -> add(PickOption("s:${s.id}", s.name, intrusion = s.hasIntrusion, sequence = true, separatorBefore = i == 0)) }
            // A deleted preset/sequence still shows by its raw id
            entry.presetId?.let { p -> if (none { it.value == "p:$p" }) add(PickOption("p:$p", p)) }
            entry.sequenceId?.let { s -> if (none { it.value == "s:$s" }) add(PickOption("s:$s", s, sequence = true)) }
        }
        val current = entry.sequenceId?.let { "s:$it" } ?: entry.presetId?.let { "p:$it" } ?: ""
        PickerField(
            label = "Preset or sequence ${index + 1}",
            options = what,
            current = current,
            enabled = entry.themeId != null,
            onPick = {
                when {
                    it.value.startsWith("p:") -> onWhat(it.value.removePrefix("p:"), null)
                    it.value.startsWith("s:") -> onWhat(null, it.value.removePrefix("s:"))
                    else -> onWhat(null, null)
                }
            },
            modifier = Modifier.padding(start = 24.dp, end = 46.dp),
        )
        PlaylistRules.entryNote(entry, presets, fit, min, max)?.let {
            Text(it, color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp, top = 4.dp))
        }
    }
}
