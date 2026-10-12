package app.sonorium.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.net.ThemeSummary
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.server.ThemeRules
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay

/** Configuration › Themes (native-api.md Phase 2); Edit opens the web Theme Editor until Phase 3. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesPage(state: ServerState, model: ServerViewModel, onEdit: (ThemeSummary) -> Unit) {
    val context = LocalContext.current
    val preview by model.preview.collectAsState()
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(ThemeRules.ALL) }
    var byTracks by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ThemeSummary?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onSuccess { model.toast("Exported: ${uri.lastPathSegment?.substringAfterLast('/') ?: ""}", error = false) }
                .onFailure { model.toast("Failed to export theme", error = true) }
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.let { if (it.endsWith(".zip")) it else "$it.zip" } ?: "theme.zip"
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?.let { model.importTheme(name, it) } ?: model.toast("Failed to import theme", error = true)
        }
    }

    LaunchedEffect(Unit) {
        model.refreshCategories()
        model.refreshThemeList()
    }

    val themes = state.themes
    val chips = ThemeRules.categoryChips(state.categories, themes)
    if (chip != ThemeRules.ALL && chip != ThemeRules.FAVORITES && chip !in chips) chip = ThemeRules.ALL
    val hits = themes.filter { ThemeRules.matches(it, query) }
    val shown = ThemeRules.sorted(hits.filter { ThemeRules.inChip(it, chip) }, byTracks)

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val narrow = maxWidth < 600.dp
            val side = if (narrow) 12.dp else 24.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        PageHeader("Themes", themes.size) {
                            Button(
                                onClick = { creating = true }, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                            ) {
                                Icon(SIcons.Plus, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Create theme", fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(6.dp))
                            MoreMenu("More", listOf(
                                MenuAction("Import theme…", SIcons.Upload) { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                MenuAction("Manage categories…", SIcons.Tag) { managing = true },
                                MenuAction("Refresh", SIcons.Refresh, separatorBefore = true) { model.refreshThemesNow() },
                            ), size = 44)
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search themes", color = Palette.textMuted) },
                            leadingIcon = { Icon(SIcons.Search, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                            trailingIcon = if (query.isNotEmpty()) ({ IconBox(SIcons.Close, "Clear search", { query = "" }, size = 32, background = Palette.background, border = Palette.background) }) else null,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search themes" },
                        )
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ThemeRules.count(shown.size), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            SortButton(byTracks) { byTracks = it }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipButton("All", hits.size, chip == ThemeRules.ALL) { chip = ThemeRules.ALL }
                            ChipButton("★ Favorites", hits.count { it.isFavorite }, chip == ThemeRules.FAVORITES) { chip = ThemeRules.FAVORITES }
                            chips.forEach { c -> ChipButton(c, hits.count { c in it.categories }, chip == c) { chip = c } }
                        }
                    }
                }
                if (themes.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No themes", null) }
                } else if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(SIcons.Search, null, tint = Palette.textMuted, modifier = Modifier.size(28.dp))
                            Text("No themes match", color = Palette.text, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                            if (query.isNotBlank()) Text("“${query.trim()}”", color = Palette.textSecondary, fontSize = 14.sp)
                            OutlinedButton(onClick = { query = ""; chip = ThemeRules.ALL }, border = BorderStroke(1.dp, Palette.border), modifier = Modifier.padding(top = 8.dp)) {
                                Text("Clear filters", color = Palette.text)
                            }
                        }
                    }
                }
                items(shown, key = { it.id }) { t ->
                    ThemeCard(
                        t, state, previewing = preview?.themeId == t.id,
                        onFavorite = { model.toggleFavorite(t.id) },
                        onPreview = { model.togglePreview(t) },
                        onEdit = { onEdit(t) },
                        onExport = { model.exportTheme(t) { name, bytes -> pendingExport = bytes; saveZip.launch(name) } },
                        onDelete = { deleting = t },
                    )
                }
            }
        }
        preview?.let { p -> PreviewBar(p, model) }
    }

    if (creating) {
        CreateThemeDialog(onCreate = { name, done ->
            val category = chip.takeIf { it != ThemeRules.ALL && it != ThemeRules.FAVORITES }
            model.createTheme(name, category) { ok, newId ->
                done()
                if (ok) {
                    creating = false
                    newId?.let { id -> state.themes.firstOrNull { it.id == id } ?: ThemeSummary(id, name) }?.let(onEdit)
                }
            }
        }, onDismiss = { creating = false })
    }
    if (managing) CategoriesDialog(state, model, onClosedChip = { if (chip == it) chip = ThemeRules.ALL }, onDismiss = { managing = false })
    deleting?.let { t ->
        ConfirmDialog("Delete “${t.name}”?", null, "Delete", onConfirm = { model.deleteTheme(t) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun SortButton(byTracks: Boolean, onPick: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${if (byTracks) "Most tracks" else "Name"}", color = Palette.text, fontSize = 13.sp)
            Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.padding(start = 4.dp).size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card) {
            listOf(false to "Name", true to "Most tracks").forEach { (v, label) ->
                DropdownMenuItem(text = { Text((if (v == byTracks) "● " else "○ ") + label, color = Palette.text) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

@Composable
private fun ChipButton(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Palette.accent.copy(alpha = 0.15f) else Palette.backgroundSecondary,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.border),
        modifier = Modifier.heightIn(min = 36.dp).alpha(if (count == 0 && !selected) 0.5f else 1f).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (selected) Palette.accent else Palette.text, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("$count", color = Palette.textSecondary, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeCard(
    t: ThemeSummary,
    state: ServerState,
    previewing: Boolean,
    onFavorite: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val onAir = ThemeRules.onAir(t.id, state.sessions)
    Surface(
        color = Palette.card, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, when {
            previewing -> Palette.accentSecondary
            onAir.isNotEmpty() -> Palette.accent
            else -> Palette.border
        }),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(ChannelRules.themeIcon(t.id, t), fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ThemeRules.tracks(t.totalTracks), color = Palette.textSecondary, fontSize = 12.sp)
                }
                IconBox(
                    if (t.isFavorite) SIcons.StarFilled else SIcons.Star,
                    if (t.isFavorite) "Remove from favorites" else "Add to favorites",
                    onFavorite, tint = if (t.isFavorite) Palette.warning else Palette.textSecondary,
                    background = Palette.card, border = Palette.card, size = 40,
                )
            }
            if (t.description.isNotBlank()) {
                Text(t.description, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            if (onAir.isNotEmpty() || t.categories.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onAir.isNotEmpty()) {
                        Row(
                            Modifier.semantics { contentDescription = "Playing on ${onAir.joinToString(", ") { it.name }}" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(Palette.accent)
                            Spacer(Modifier.width(4.dp))
                            Text(ThemeRules.onAirLabel(onAir), color = Palette.accent, fontSize = 12.sp)
                        }
                    }
                    t.categories.forEach { Badge(it) }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val can = ThemeRules.canPreview(t)
                Surface(
                    color = if (previewing) Palette.accentSecondary else Palette.backgroundSecondary,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Palette.border),
                    modifier = Modifier.size(44.dp).alpha(if (can) 1f else 0.4f)
                        .semantics { contentDescription = if (!can) "No tracks" else if (previewing) "Stop preview" else "Preview here" }
                        .clickable(enabled = can, onClick = onPreview),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (previewing) SIcons.Stop else SIcons.Play, null, tint = Palette.text, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onEdit, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("Edit", color = Palette.text)
                }
                Spacer(Modifier.width(6.dp))
                MoreMenu("More for ${t.name}", listOf(
                    MenuAction("Export", SIcons.Download, onClick = onExport),
                    MenuAction("Delete…", SIcons.Trash, danger = true, separatorBefore = true, onClick = onDelete),
                ))
            }
        }
    }
}

/** The preview bar: `Local Preview:`, the theme, play/pause, volume, close. */
@Composable
private fun PreviewBar(p: ServerViewModel.Preview, model: ServerViewModel) {
    Surface(color = Palette.bar, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            HorizontalDivider(color = Palette.border)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Local Preview:", color = Palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(p.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBox(if (p.paused) SIcons.Play else SIcons.Pause, if (p.paused) "Play" else "Pause", { model.pausePreview() }, size = 40)
                Spacer(Modifier.width(6.dp))
                IconBox(SIcons.Close, "Close preview", { model.closePreview() }, size = 40)
            }
            PercentSlider(p.volume, "Preview volume", onRelease = { model.setPreviewVolume(it) }, onChange = { model.setPreviewVolume(it) })
        }
    }
}

@Composable
private fun CreateThemeDialog(onCreate: (String, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun go() {
        if (name.trim().isEmpty() || busy) return
        busy = true
        onCreate(name) { busy = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text("New theme", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { if (it.length <= 80) name = it }, singleLine = true,
                placeholder = { Text("Theme name", color = Palette.textMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Theme name" },
            )
        },
        confirmButton = {
            Button(
                onClick = { go() }, enabled = name.trim().isNotEmpty() && !busy, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.textSecondary) } },
    )
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun CategoriesDialog(state: ServerState, model: ServerViewModel, onClosedChip: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { model.refreshCategories() }
    fun add() {
        if (!ThemeRules.canAddCategory(text, state.categories)) return
        model.addCategory(text) {
            text = ""
            runCatching { focus.requestFocus() }
        }
    }
    AlertDialog(
        onDismissRequest = { if (confirming != null) confirming = null else onDismiss() },
        containerColor = Palette.card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Categories", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconBox(SIcons.Close, "Close", onDismiss, size = 36, background = Palette.card, border = Palette.card)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.categories.isEmpty()) Text("—", color = Palette.textMuted)
                state.categories.forEach { c ->
                    val n = state.themes.count { c in it.categories }
                    val label = "$n theme${if (n == 1) "" else "s"}"
                    if (confirming == c) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("Delete “$c”? ($label)", color = Palette.text, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { confirming = null }) { Text("Cancel", color = Palette.textSecondary) }
                                Button(
                                    onClick = { confirming = null; model.deleteCategory(c) { onClosedChip(c) } },
                                    colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Palette.text), shape = RoundedCornerShape(8.dp),
                                ) { Text("Delete") }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Badge(label)
                            Spacer(Modifier.width(6.dp))
                            IconBox(SIcons.Trash, "Delete $c", { confirming = c }, size = 36)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedTextField(
                        value = text, onValueChange = { if (it.length <= 60) text = it }, singleLine = true,
                        placeholder = { Text("New category", color = Palette.textMuted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "New category" },
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { add() }, enabled = ThemeRules.canAddCategory(text, state.categories), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Palette.accent) } },
    )
}
