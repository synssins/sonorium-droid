package app.sonorium.ui.server

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.net.EditorPreset
import app.sonorium.net.SourceTracks
import app.sonorium.net.ThemeSummary
import app.sonorium.server.ChannelRules
import app.sonorium.server.EditorUi
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.server.ThemeEditor
import app.sonorium.server.ThemeEditorRules
import app.sonorium.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `availableIcons` (app.js), verbatim. */
private val THEME_ICONS = listOf(
    "🌧️", "🌲", "🌊", "🔥", "⛈️", "💨", "🐦", "🌙", "❄️", "☀️", "🌸", "🍂", "💧", "🌴", "🏞️", "🏔️", "🏜️",
    "☕", "🏙️", "🏖️", "🏰", "🚂", "🚀",
    "🐉", "⚔️", "👻", "🎃", "🧘", "💆",
    "🎵", "🎷", "🎹", "📚",
    "🍺", "🍷", "🍵",
    "🎄", "🎅", "🎁", "❤️", "🌺",
    "🐺", "🦉", "🐋", "🦋", "🐸",
    "✨", "🌈", "💎", "🕯️", "🔔",
)

/**
 * The Theme Editor (native-api.md Phase 3): full screen over Themes. Every control saves as it
 * changes; Save theme keeps it all, Cancel / × / Back put the mix back.
 */
@Composable
fun ThemeEditorScreen(editor: ThemeEditor, model: ServerViewModel, state: ServerState) {
    val ui by editor.ui.collectAsState()
    val sequence by editor.seq.collectAsState()
    var advanced by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var help by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<NamePrompt?>(null) }
    var importing by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var uploadGroup by remember { mutableStateOf<String?>(null) }
    var pendingCategory by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val live = remember { mutableStateMapOf<String, Double>() }
    var closing by remember { mutableStateOf(false) }

    fun cancel() {
        if (closing) return
        closing = true
        scope.launch {
            editor.restoreMix()
            model.closeThemeEditor()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val group = uploadGroup
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { readFile(context, it) } }
            if (files.isNotEmpty()) editor.upload(files, group)
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onSuccess { model.toast("Exported: ${uri.lastPathSegment?.substringAfterLast('/') ?: ""}", error = false) }
                .onFailure { model.toast("Failed to export theme", error = true) }
        }
    }
    fun upload(group: String?) {
        uploadGroup = group
        pickFiles.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/ogg", "audio/*"))
    }

    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EditorSurface {
            // Older Android focuses the first field of a new window (and opens the keyboard): not here
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val childOpen = help || ask != null || prompt != null || importing || exported != null || picker || sequence != null
            LaunchedEffect(childOpen) {
                // also when a child dialog closes: the window gives focus back to its first field
                if (!childOpen) {
                    kotlinx.coroutines.delay(150)
                    focusManager.clearFocus()
                }
            }
            BackHandler { if (ui.renaming != null) editor.startRename(null) else if (deleting != null) deleting = null else cancel() }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                // Header
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Edit theme", color = Palette.textSecondary, fontSize = 12.sp)
                        Text(ui.title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBox(SIcons.Help, "How a theme plays", { help = true }, size = 44)
                    Spacer(Modifier.width(6.dp))
                    MoreMenu("More theme options", listOf(MenuAction("Export theme", SIcons.Download) {
                        model.toast("Preparing export...", error = false)
                        model.exportTheme(ThemeSummary(editor.themeId, ui.title)) { name, bytes ->
                            pendingExport = bytes
                            saveZip.launch(name)
                        }
                    }))
                    Spacer(Modifier.width(6.dp))
                    IconBox(SIcons.Close, "Cancel", { cancel() }, size = 44)
                }
                val hooks = ListHooks(editor, { ask = it }, ::upload, live, deleting) { deleting = it }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    item(key = "details") { DetailsBlock(ui, editor, onPending = { pendingCategory = it }) }
                    item(key = "toolbar") {
                        Toolbar(ui, editor, onUpload = ::upload, onPicker = { picker = true }, onReset = {
                            ask = Ask("Reset all tracks and groups to defaults?", null, "Reset") { editor.resetAll() }
                        }, onSequence = { id -> editor.openSequence(id) })
                    }
                    when {
                        ui.loading -> item { Text("Loading tracks…", color = Palette.textSecondary, modifier = Modifier.padding(16.dp)) }
                        ui.loadError != null -> item { Text(ui.loadError!!, color = Palette.danger, modifier = Modifier.padding(16.dp)) }
                        else -> editorSections(ui, hooks)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                Footer(
                    ui, editor,
                    onPrompt = { prompt = it },
                    onAsk = { ask = it },
                    onImport = { importing = true },
                    onExport = { scope.launch { editor.exportPreset()?.let { exported = it } } },
                    onEditSequence = { ui.selectedSequence?.let { editor.openSequence(it.id) } },
                    onSave = { scope.launch { if (editor.saveTheme(quiet = false, pending = pendingCategory)) model.closeThemeEditor() } },
                    onCancel = { cancel() },
                )
            }
        }
    }

    if (help) HelpPanel("How a theme plays", HelpTexts.THEME_EDITOR) { help = false }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
    prompt?.let { p -> NamePromptDialog(p) { prompt = null } }
    if (importing) ImportPresetDialog(editor) { importing = false }
    exported?.let { text -> ExportPresetDialog(text, model) { exported = null } }
    if (sequence != null) SequenceEditorScreen(editor, ui, onAdvanced = { advanced = it })
    advanced?.let { i -> if (sequence != null) AdvancedDialog(editor, ui, i) { advanced = null } else advanced = null }
    if (picker) LinkPicker(state.themes.filter { it.id != editor.themeId }, ui, editor) { picker = false }
}

private fun readFile(context: android.content.Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    name to bytes
}.getOrNull()

// ---------- Details: icon, Name, Description, Categories ----------

@Composable
private fun DetailsBlock(ui: EditorUi, editor: ThemeEditor, onPending: (String) -> Unit) {
    var iconMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(Palette.card, RoundedCornerShape(10.dp))
                        .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
                        .clickable { iconMenu = true }
                        .semantics { contentDescription = "Change icon" },
                    contentAlignment = Alignment.Center,
                ) { Text(ChannelRules.themeIcon(ui.themeId, ThemeSummary(ui.themeId, icon = ui.icon)), fontSize = 28.sp) }
                DropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }, containerColor = Palette.card) {
                    FlowRow(Modifier.widthIn(max = 300.dp).padding(8.dp)) {
                        THEME_ICONS.forEach { icon ->
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(if (icon == ui.icon) Palette.accent.copy(alpha = 0.2f) else Palette.card, RoundedCornerShape(6.dp))
                                    .clickable { editor.setIcon(icon); iconMenu = false }
                                    .semantics { contentDescription = icon },
                                contentAlignment = Alignment.Center,
                            ) { Text(icon, fontSize = 22.sp) }
                        }
                    }
                    HorizontalDivider(color = Palette.border)
                    DropdownMenuItem(
                        text = { Text("Auto", color = if (ui.icon.isEmpty()) Palette.accent else Palette.text) },
                        trailingIcon = { Text("from theme", color = Palette.textMuted, fontSize = 12.sp) },
                        onClick = { editor.setIcon(""); iconMenu = false },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Name")
                OutlinedTextField(
                    ui.name, editor::setName, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FieldLabel("Description")
        OutlinedTextField(
            ui.description, editor::setDescription, maxLines = 2, placeholder = { Text("Optional") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Description" },
        )
        Spacer(Modifier.height(8.dp))
        FieldLabel("Categories")
        CategoriesField(ui, editor, onPending)
    }
}

@Composable
private fun CategoriesField(ui: EditorUi, editor: ThemeEditor, onPending: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val options = ThemeEditorRules.categoryOptions(ui.knownCategories, ui.categories, text)
    fun add(name: String) {
        if (name.isBlank()) return
        editor.setCategories(ThemeEditorRules.addCategory(ui.categories, ui.knownCategories, name))
        text = ""
        onPending("")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.backgroundSecondary, RoundedCornerShape(8.dp))
            .border(1.dp, if (focused) Palette.accent else Palette.border, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.categories.forEach { c ->
                Row(
                    Modifier.background(Palette.card, RoundedCornerShape(12.dp)).border(1.dp, Palette.border, RoundedCornerShape(12.dp)).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c, color = Palette.text, fontSize = 13.sp)
                    Box(
                        Modifier.size(32.dp).clickable { editor.setCategories(ui.categories - c) }.semantics { contentDescription = "Remove $c" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(SIcons.Close, null, tint = Palette.textSecondary, modifier = Modifier.size(12.dp)) }
                }
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it; onPending(it) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(options.firstOrNull()?.name ?: text) }),
                modifier = Modifier
                    .widthIn(min = 140.dp)
                    .heightIn(min = 32.dp)
                    .padding(top = 6.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "Categories" },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty() && ui.categories.isEmpty()) Text("Add a category…", color = Palette.textMuted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
        }
        if (focused && options.isNotEmpty()) {
            HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 6.dp))
            options.take(8).forEachIndexed { i, o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(if (i == 0) Palette.card else Palette.backgroundSecondary, RoundedCornerShape(6.dp))
                        .clickable { add(o.name) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (o.create) Icon(SIcons.Plus, null, tint = Palette.accent, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                    Text(if (o.create) "Create “${o.name}”" else o.name, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (i == 0) Text("Enter", color = Palette.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------- Tracks toolbar ----------

@Composable
private fun Toolbar(ui: EditorUi, editor: ThemeEditor, onUpload: (String?) -> Unit, onPicker: () -> Unit, onReset: () -> Unit, onSequence: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tracks", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            CountBadge(ui.tracks.size)
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SequencesMenu(ui, editor, onSequence)
            ToolButton(
                if (ui.previewMix) "Stop preview" else "Preview mix", if (ui.previewMix) SIcons.Stop else SIcons.Play,
                "Play the whole theme on this device, with your changes, without changing the speakers", pressed = ui.previewMix,
            ) { editor.togglePreviewMix() }
            var uploadMenu by remember { mutableStateOf(false) }
            Box {
                ToolButton("Upload", SIcons.Upload, "Upload") { uploadMenu = true }
                DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }, containerColor = Palette.card) {
                    Text("Upload to", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    DropdownMenuItem(
                        leadingIcon = { Icon(SIcons.Folder, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                        text = { Text("Unassigned", color = Palette.text) },
                        onClick = { uploadMenu = false; onUpload(null) },
                    )
                    if (ui.groupsOk) ui.listedGroups.forEach { g ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(SIcons.Folder, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                            text = { Text(g.name, color = Palette.text) },
                            onClick = { uploadMenu = false; onUpload(g.name) },
                        )
                    }
                }
            }
            ToolButton("Add from another theme", SIcons.Link, "Add from another theme", onClick = onPicker)
            ToolButton("Reset all", SIcons.Refresh, "Reset all", onClick = onReset)
        }
        if (ui.waiting) Text("Updating…", color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, pressed: Boolean = false, badge: Int? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (pressed) Palette.accent else Palette.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (pressed) Palette.accent.copy(alpha = 0.12f) else Palette.backgroundSecondary),
        modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
    ) {
        Icon(icon, null, tint = if (pressed) Palette.accent else Palette.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.text, fontSize = 13.sp)
        badge?.let {
            Spacer(Modifier.width(6.dp))
            CountBadge(it)
        }
    }
}

/** Sequences: the theme's sequences, Add sequence…, and the Theme defaults. */
@Composable
private fun SequencesMenu(ui: EditorUi, editor: ThemeEditor, onSequence: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(emptySet<String>()) }
    Box {
        ToolButton("Sequences", SIcons.Sequence, "Sequences", badge = ui.sequences.size) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 260.dp)) {
            Text("Sequences", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            ui.sequences.forEach { s ->
                DropdownMenuItem(
                    leadingIcon = { Icon(SIcons.Sequence, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                    text = { Text(s.name + if (s.hasIntrusion) "  ⚭" else "", color = Palette.text) },
                    trailingIcon = { Text("${s.presets.size} preset(s)", color = Palette.textMuted, fontSize = 12.sp) },
                    onClick = { open = false; onSequence(s.id) },
                )
            }
            DropdownMenuItem(
                leadingIcon = { Icon(SIcons.Plus, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                text = { Text("Add sequence…", color = Palette.text) },
                onClick = { open = false; onSequence(null) },
            )
            HorizontalDivider(color = Palette.border)
            Text("Theme defaults", color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            val d = ui.defaults
            var min by remember(d) { mutableStateOf(ThemeEditorRules.minutes(d.holdMin)) }
            var max by remember(d) { mutableStateOf(ThemeEditorRules.minutes(d.holdMax)) }
            var ramp by remember(d) { mutableStateOf(d.ramp.toLong().toString()) }
            fun save() { bad = editor.setSequenceDefaults(min, max, ramp) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Time on each preset", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                NumberBox(min, "", true, "Shortest time on each preset (minutes)", width = 52, error = "min" in bad) { min = it; save() }
                Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                NumberBox(max, "", true, "Longest time on each preset (minutes)", width = 52, error = "max" in bad) { max = it; save() }
                Text("min", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ramp", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                NumberBox(ramp, "", true, "Ramp (seconds)", width = 52, error = "ramp" in bad) { ramp = it; save() }
                Text("s", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

// ---------- Footer: presets, Save theme, Cancel ----------

/** The New / Rename preset popover; [onOk] answers whether to close. */
data class NamePrompt(val title: String, val initial: String, val onOk: suspend (String) -> Boolean)

@Composable
private fun Footer(
    ui: EditorUi,
    editor: ThemeEditor,
    onPrompt: (NamePrompt) -> Unit,
    onAsk: (Ask) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onEditSequence: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val preset = ui.selectedPreset
    val sequence = ui.selectedSequence
    Column(Modifier.fillMaxWidth().background(Palette.bar).padding(horizontal = 12.dp, vertical = 8.dp)) {
        HorizontalDivider(color = Palette.border)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preset", color = Palette.textSecondary, fontSize = 13.sp)
            PresetButton(ui, editor)
            MoreMenu(
                "More preset actions",
                listOf(
                    MenuAction("Rename…", SIcons.Edit, enabled = preset != null) {
                        preset?.let { p -> onPrompt(NamePrompt("Rename preset", p.name) { editor.renamePreset(p, it) }) }
                    },
                    MenuAction("Set as default", SIcons.Star, enabled = preset != null && !preset.isDefault) { preset?.let { editor.setDefault(it) } },
                    MenuAction("Import…", SIcons.Upload, separatorBefore = true) { onImport() },
                    MenuAction("Export", SIcons.Download, enabled = preset != null) { onExport() },
                    MenuAction("Delete…", SIcons.Trash, danger = true, enabled = preset != null, separatorBefore = true) {
                        preset?.let { p -> onAsk(Ask("Delete “${p.name}”?", null, "Delete") { editor.deletePreset(p) }) }
                    },
                ),
            )
            if (sequence != null) {
                TextButton(onClick = onEditSequence) { Text("Edit", color = Palette.accent) }
            } else if (preset != null && ui.mixDirty) {
                TextButton(onClick = { editor.loadPreset(preset) }) { Text("Reload", color = Palette.accent) }
            }
            TextButton(onClick = { onPrompt(NamePrompt("New preset", "") { editor.newPreset(it) }) }) { Text("New", color = Palette.accent) }
            if (preset != null && ui.mixDirty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(Palette.warning)
                    Spacer(Modifier.width(6.dp))
                    Text("Mix changed", color = Palette.warning, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { editor.savePreset() },
                enabled = preset != null,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Palette.border),
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Save preset. Saves loaded preset" },
            ) { Text("Save preset", color = if (preset != null) Palette.text else Palette.textMuted) }
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text("Save theme", fontWeight = FontWeight.SemiBold)
                if (ui.unsaved) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.semantics { contentDescription = "Unsaved changes" }) { Dot(Palette.bar) }
                }
            }
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Cancel", color = Palette.text)
            }
        }
    }
}

/** The preset field: Current settings, presets (picking one loads it), then sequences. */
@Composable
private fun PresetButton(ui: EditorUi, editor: ThemeEditor) {
    var open by remember { mutableStateOf(false) }
    val preset = ui.selectedPreset
    val sequence = ui.selectedSequence
    val label = preset?.name ?: sequence?.name ?: "— Current settings —"
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border),
            modifier = Modifier.heightIn(min = 40.dp).widthIn(max = 260.dp).semantics { contentDescription = "Preset: $label" },
        ) {
            Text(label, color = if (preset == null && sequence == null) Palette.textSecondary else Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (preset?.isDefault == true) Text(" ★", color = Palette.warning, fontSize = 13.sp)
            if (preset?.hasIntrusion == true || sequence?.hasIntrusion == true) Text(" ⚭", color = Palette.textSecondary, fontSize = 13.sp)
            if (sequence != null) Icon(SIcons.Sequence, "Sequence", tint = Palette.textSecondary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            if (preset != null && ui.mixDirty) Box(Modifier.padding(start = 6.dp).semantics { contentDescription = "Mix changed" }) { Dot(Palette.warning) }
            Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card, modifier = Modifier.widthIn(min = 240.dp)) {
            DropdownMenuItem(text = { Text("— Current settings —", color = Palette.textSecondary) }, onClick = { open = false; editor.selectCurrent() })
            ui.presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name + (if (p.isDefault) " ★" else "") + (if (p.hasIntrusion) " ⚭" else ""), color = if (p.id == ui.selected) Palette.accent else Palette.text) },
                    trailingIcon = if (!p.isDefault) ({ IconBox(SIcons.Star, "Set as default", { editor.setDefault(p) }, size = 40) }) else null,
                    onClick = { open = false; editor.loadPreset(p) },
                )
            }
            if (ui.sequences.isNotEmpty()) HorizontalDivider(color = Palette.border)
            ui.sequences.forEach { s ->
                DropdownMenuItem(
                    leadingIcon = { Icon(SIcons.Sequence, "Sequence", tint = Palette.textSecondary, modifier = Modifier.size(14.dp)) },
                    text = { Text(s.name + if (s.hasIntrusion) " ⚭" else "", color = if (ui.selected == "seq:${s.id}") Palette.accent else Palette.text) },
                    onClick = { open = false; editor.selectSequence(s.id) },
                )
            }
        }
    }
}

@Composable
private fun NamePromptDialog(p: NamePrompt, onClose: () -> Unit) {
    var text by remember { mutableStateOf(p.initial) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun ok() {
        if (text.isBlank() || busy) return
        busy = true
        scope.launch {
            if (p.onOk(text)) onClose()
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.card,
        title = { Text(p.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                text, { if (it.length <= 80) text = it }, singleLine = true, placeholder = { Text("Preset name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { ok() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Preset name" },
            )
        },
        confirmButton = {
            Button(
                onClick = { ok() }, enabled = text.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Palette.textSecondary) } },
    )
}

@Composable
private fun ImportPresetDialog(editor: ThemeEditor, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.card,
        title = { Text("Import Preset", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                FieldLabel("Preset Name")
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("e.g., Sleep Mode, Focus Mix...") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Preset Name" })
                Spacer(Modifier.height(8.dp))
                FieldLabel("Preset JSON *")
                OutlinedTextField(
                    json, { json = it }, minLines = 5, maxLines = 10,
                    placeholder = { Text("{\"name\": \"My Preset\", \"tracks\": {...}}") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Preset JSON" },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { scope.launch { if (editor.importPreset(name, json)) onClose() } },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Palette.textSecondary) } },
    )
}

@Composable
private fun ExportPresetDialog(text: String, model: ServerViewModel, onClose: () -> Unit) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.card,
        title = { Text("Export Preset", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                FieldLabel("Preset JSON")
                Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Palette.backgroundSecondary, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    Text(text, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text("Copy this JSON to share with others or import into another theme.", color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { clipboard.setText(AnnotatedString(text)) }
                        .onSuccess { model.toast("Copied to clipboard", error = false) }
                        .onFailure { model.toast("Please copy manually (Ctrl+C)", error = true) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
            ) { Text("Copy to Clipboard") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close", color = Palette.textSecondary) } },
    )
}

// ---------- Add from another theme (`#te-pk`) ----------

@Composable
private fun LinkPicker(themes: List<ThemeSummary>, ui: EditorUi, editor: ThemeEditor, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<ThemeSummary?>(null) }
    var loaded by remember { mutableStateOf<Result<SourceTracks>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val narrow = LocalConfiguration.current.screenWidthDp < 600
    LaunchedEffect(source) {
        loaded = null
        chosen = emptySet()
        source?.let { loaded = editor.sourceTracks(it.id) }
    }
    fun close() {
        if (ui.filePreview?.startsWith("pk:") == true) editor.stopPreviews()
        onClose()
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (source != null) {
                        IconBox(SIcons.ChevronRight, "Back to themes", { source = null }, modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(source?.name ?: "Add from another theme", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconBox(SIcons.Close, "Cancel", ::close, size = 44)
                }
                Spacer(Modifier.height(8.dp))
                val src = source
                if (src == null) {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, placeholder = { Text("Search themes") },
                        leadingIcon = { Icon(SIcons.Search, null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconBox(SIcons.Close, "Clear", { query = "" }, size = 40) }) else null,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search themes" },
                    )
                    val q = query.trim().lowercase()
                    val rows = themes.filter { t -> q.isEmpty() || t.name.lowercase().contains(q) || t.categories.any { it.lowercase().contains(q) } }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    if (rows.isEmpty()) Text("No themes found", color = Palette.textSecondary, modifier = Modifier.padding(16.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(rows, key = { it.id }) { t ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { source = t }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ChannelRules.themeIcon(t.id, t), fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, color = Palette.text, fontSize = 16.sp)
                                    val cats = t.categories.joinToString(", ")
                                    Text((if (cats.isNotEmpty()) "$cats · " else "") + "${t.totalTracks} track(s)", color = Palette.textSecondary, fontSize = 12.sp)
                                }
                                if (!narrow) Text(t.categories.joinToString(", ").ifEmpty { "—" }, color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.width(160.dp))
                                Icon(SIcons.ChevronRight, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
                            }
                            HorizontalDivider(color = Palette.border)
                        }
                    }
                } else {
                    val result = loaded
                    val tracks = result?.getOrNull()?.tracks.orEmpty()
                    fun already(id: String) = ui.tracks.any { it.linked?.themeId == src.id && it.linked.track == id }
                    val free = tracks.filter { !already(it.id) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CountBadge(tracks.size)
                        Text(" track(s)", color = Palette.textSecondary, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("All", color = Palette.text, fontSize = 13.sp)
                        Checkbox(
                            checked = free.isNotEmpty() && free.all { it.id in chosen },
                            onCheckedChange = { on -> chosen = if (on) free.map { it.id }.toSet() else emptySet() },
                            colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                            modifier = Modifier.semantics { contentDescription = "All" },
                        )
                    }
                    when {
                        result == null -> Text("Loading tracks…", color = Palette.textSecondary, modifier = Modifier.padding(16.dp))
                        result.isFailure -> Text((result.exceptionOrNull() as? app.sonorium.net.ApiException)?.detail ?: "Can't reach the server.", color = Palette.danger, modifier = Modifier.padding(16.dp))
                        tracks.isEmpty() -> Text("No tracks in this theme", color = Palette.textSecondary, modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(tracks, key = { it.id }) { t ->
                            val added = already(t.id)
                            val playing = ui.filePreview == "pk:${src.id}:${t.id}"
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBox(if (playing) SIcons.Stop else SIcons.Play, if (playing) "Stop preview" else "Preview", { editor.toggleFilePreview("pk:${src.id}:${t.id}", t.previewUrl) }, size = 40)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.displayName.ifEmpty { t.name }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(t.group, ThemeEditorRules.formatLength(t.durationSeconds), if (added) "Added" else null).joinToString(" · "),
                                        color = Palette.textSecondary, fontSize = 12.sp,
                                    )
                                }
                                Checkbox(
                                    checked = added || t.id in chosen,
                                    enabled = !added,
                                    onCheckedChange = { on -> chosen = if (on) chosen + t.id else chosen - t.id },
                                    colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
                                    modifier = Modifier.semantics { contentDescription = if (added) "Already added" else "Add" },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = Palette.border)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${chosen.size} selected", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::close, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border)) { Text("Cancel", color = Palette.text) }
                    Button(
                        onClick = {
                            val s = source ?: return@Button
                            busy = true
                            scope.launch {
                                if (editor.addLinks(s.id, chosen.toList())) close()
                                busy = false
                            }
                        },
                        enabled = chosen.isNotEmpty() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
                    ) { Text("Add") }
                }
            }
        }
    }
}
