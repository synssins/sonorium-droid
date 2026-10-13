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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
import app.sonorium.ui.coach.coachTarget
import kotlinx.coroutines.delay

/** Configuration › Themes (native-api.md Phase 2); Edit opens the web Theme Editor until Phase 3. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesPage(state: ServerState, model: ServerViewModel, onEdit: (ThemeSummary) -> Unit) {
    val context = LocalContext.current
    // First visit: the guide over the page, once its themes show (item 13)
    val coach = app.sonorium.ui.coach.LocalCoach.current
    val guide = app.sonorium.ui.coach.Guides.themes()
    LaunchedEffect(state.themes.isNotEmpty()) {
        if (state.themes.isNotEmpty()) {
            kotlinx.coroutines.delay(1_000)
            coach?.showOnce(guide)
        }
    }
    val preview by model.preview.collectAsState()
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(ThemeRules.ALL) }
    var byTracks by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ThemeSummary?>(null) }
    var updating by remember { mutableStateOf<ThemeSummary?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val packages by model.packages.collectAsState()
    val localThemes by model.localThemes.collectAsState()
    val downloads by model.downloads.collectAsState()
    var unlockFor by remember { mutableStateOf<String?>(null) }
    // Save to phone: the unlock, then the themes folder (explained first), then the download
    val folderPicker = rememberThemesFolderPicker(model)
    fun saveToPhone(id: String) {
        when {
            !model.entitlement.isPro.value -> unlockFor = id
            model.themeStore.root == null || model.localFolder.value == app.sonorium.local.ThemeStore.FolderState.MISSING ->
                // The view model starts it once the folder is chosen (this page may be gone by then)
                model.saveToPhoneAfterFolder(id).also { folderPicker.ask(changing = false) { model.saveThemeToPhone(id) } }
            else -> model.saveThemeToPhone(id)
        }
    }

    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onSuccess { model.toast("Exported: ${uri.lastPathSegment?.substringAfterLast('/') ?: ""}", error = false) }
                .onFailure { model.toast("Couldn’t export the theme", error = true) }
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // The file's own name (a document id like "msf:388" isn't one: R2)
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.ifBlank { null } ?: "theme.zip"
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?.let { model.importTheme(name, it, uri) } ?: model.toast("Couldn’t import the theme", error = true)
        }
    }

    LaunchedEffect(Unit) {
        model.refreshCategories()
        model.refreshThemeList()
        model.refreshPackages()
        model.refreshLocalThemes()
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
                        PageHeader("Themes", themes.size, help = "themes") {
                            Button(
                                onClick = { if (model.reachableOrSay() && model.proOrAsk()) creating = true }, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp).coachTarget(app.sonorium.ui.coach.Targets.THEME_CREATE),
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                            ) {
                                Icon(SIcons.Plus, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Create theme", fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(6.dp))
                            MoreMenu("More", listOf(
                                MenuAction("Import theme…", SIcons.Upload) { if (model.proOrAsk()) pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                MenuAction("Manage categories…", SIcons.Tag) { managing = true },
                                MenuAction("Refresh", SIcons.Refresh, separatorBefore = true) { model.refreshThemesNow() },
                            ), size = app.sonorium.ui.UiSize.STANDARD)
                        }
                        SField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search themes", color = Palette.textMuted) },
                            leadingIcon = { Icon(SIcons.Search, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) },
                            trailingIcon = if (query.isNotEmpty()) ({ IconBox(SIcons.Close, "Clear search", { query = "" }, size = app.sonorium.ui.UiSize.SMALL, background = Palette.background, border = Palette.background) }) else null,
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
                            Text("No themes match", color = Palette.text, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
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
                        phoneAction = app.sonorium.local.PackageRules.action(packages?.get(t.id), localThemes, state.record?.id)?.let { a -> packages?.get(t.id)?.sizeBytes?.takeIf { it > 0 }?.let { "$a (${bytesText(it)})" } ?: a },
                        phoneBlocked = app.sonorium.local.PackageRules.blocked(packages?.get(t.id), localThemes, state.record?.id),
                        onPhone = app.sonorium.local.PackageRules.onPhone(packages?.get(t.id), localThemes, state.record?.id) != app.sonorium.local.PackageRules.OnPhone.NONE,
                        downloading = downloads[t.id],
                        onSaveToPhone = {
                            // Updating replaces the phone's folder: confirm first, phone edits are lost
                            if (app.sonorium.local.PackageRules.onPhone(packages?.get(t.id), localThemes, state.record?.id) == app.sonorium.local.PackageRules.OnPhone.UPDATE) updating = t
                            else saveToPhone(t.id)
                        },
                        onCancelSave = { model.cancelSaveToPhone(t.id) },
                        onClearCache = { model.clearThemeCache(t) },
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
    unlockFor?.let { id ->
        UnlockScreen(model, onClose = { unlockFor = null }, onUnlocked = { unlockFor = null; saveToPhone(id) })
    }
    if (managing) CategoriesDialog(state, model, onClosedChip = { if (chip == it) chip = ThemeRules.ALL }, onDismiss = { managing = false })
    updating?.let { t ->
        SyncDialog(t.name, onSync = { overwrite -> updating = null; model.saveThemeToPhone(t.id, overwrite) }, onDismiss = { updating = null })
    }
    deleting?.let { t ->
        ConfirmDialog(
            "Delete “${t.name}”?",
            if (LocalOnDevice.current) "The theme and its tracks are removed from this device." else "The theme and its tracks are removed from the server.",
            "Delete", onConfirm = { model.deleteTheme(t) }, onDismiss = { deleting = null },
        )
    }
}

/** "Sync now?": the server's new and changed files, new presets and sequences; the phone's mix stays unless asked. */
@Composable
private fun SyncDialog(name: String, onSync: (overwrite: Boolean) -> Unit, onDismiss: () -> Unit) {
    var overwrite by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.sync_title, name), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.sync_text), color = Palette.textSecondary, fontSize = 14.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.ROW.dp).clickable { overwrite = !overwrite },
                ) {
                    androidx.compose.material3.Checkbox(checked = overwrite, onCheckedChange = { overwrite = it }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Palette.accent))
                    Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.sync_overwrite), color = Palette.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSync(overwrite) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
            ) { Text(androidx.compose.ui.res.stringResource(app.sonorium.R.string.sync_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.textSecondary) }
        },
    )
}

@Composable
private fun SortButton(byTracks: Boolean, onPick: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
            Text("Sort: ${if (byTracks) "Most tracks" else "Name"}", color = Palette.text, fontSize = 13.sp)
            Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.padding(start = 4.dp).size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
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
        modifier = Modifier
            .heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)
            .alpha(if (count == 0 && !selected) app.sonorium.ui.UiSize.DISABLED_ALPHA else 1f)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
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
    phoneAction: String? = null,
    /** Why it can't be saved to the phone (its licence): a disabled menu entry. */
    phoneBlocked: String? = null,
    onPhone: Boolean = false,
    downloading: Float? = null,
    onSaveToPhone: () -> Unit = {},
    onCancelSave: () -> Unit = {},
    onClearCache: () -> Unit = {},
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
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(ChannelRules.themeIcon(t.id, t), fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // On this device: the space the theme takes, and its ready-to-play copy
                    val space = t.sizeBytes?.let { b -> " · " + bytesText(b) + (t.cacheBytes?.takeIf { it > 0 }?.let { c -> " + ${bytesText(c)} cache" } ?: "") }.orEmpty()
                    Text(ThemeRules.tracks(t.totalTracks) + space, color = Palette.textSecondary, fontSize = 12.sp)
                }
                IconBox(
                    if (t.isFavorite) SIcons.StarFilled else SIcons.Star,
                    if (t.isFavorite) "Remove from favorites" else "Add to favorites",
                    onFavorite, tint = if (t.isFavorite) Palette.warning else Palette.textSecondary,
                    background = Palette.card, border = Palette.card, size = app.sonorium.ui.UiSize.SMALL,
                )
            }
            if (t.description.isNotBlank()) {
                Text(t.description, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            if (onAir.isNotEmpty() || t.categories.isNotEmpty() || onPhone) {
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
                    if (onPhone) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(SIcons.Headphones, null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (phoneAction != null) "On this phone · update ready" else "On this phone", color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1)
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
                    modifier = Modifier.size(app.sonorium.ui.UiSize.STANDARD.dp).coachTarget(app.sonorium.ui.coach.Targets.THEME_PREVIEW).alpha(if (can) 1f else app.sonorium.ui.UiSize.DISABLED_ALPHA)
                        .semantics { contentDescription = if (!can) "No tracks" else if (previewing) "Stop preview" else "Preview here" }
                        .clickable(enabled = can, onClick = onPreview),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (previewing) SIcons.Stop else SIcons.Play, null, tint = Palette.text, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onEdit, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp).coachTarget(app.sonorium.ui.coach.Targets.THEME_EDIT)) {
                    Text("Edit", color = Palette.text)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.coachTarget(app.sonorium.ui.coach.Targets.THEME_MORE)) { MoreMenu("More for ${t.name}", listOfNotNull(
                    phoneAction?.takeIf { downloading == null }?.let { MenuAction(it, SIcons.Headphones, onClick = onSaveToPhone) },
                    phoneBlocked?.let { MenuAction(it, SIcons.Headphones, enabled = false) {} },
                    MenuAction("Export", SIcons.Download, onClick = onExport),
                    t.cacheBytes?.takeIf { it > 0 }?.let { MenuAction("Free up space (${bytesText(it)})", SIcons.Refresh, onClick = onClearCache) },
                    // The sample comes with the app and can't be changed or deleted (R1)
                    if (t.readOnly) null else MenuAction("Delete…", SIcons.Trash, danger = true, separatorBefore = true, onClick = onDelete),
                )) }
            }
            if (downloading != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saving to this phone… ${(downloading * 100).toInt()}%", color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    // Stops the download; what's downloaded is kept for next time
                    TextButton(onClick = onCancelSave, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Cancel", color = Palette.textSecondary) }
                }
                LinearProgressIndicator(
                    progress = { downloading }, color = Palette.accent, trackColor = Palette.backgroundSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** The preview bar: `Previewing on this phone:`, the theme, play/pause, volume, close. */
@Composable
private fun PreviewBar(p: ServerViewModel.Preview, model: ServerViewModel) {
    Surface(color = Palette.bar, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            HorizontalDivider(color = Palette.border)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Previewing on this phone:", color = Palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(p.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconBox(if (p.paused) SIcons.Play else SIcons.Pause, if (p.paused) "Resume preview" else "Pause preview", { model.pausePreview() }, size = app.sonorium.ui.UiSize.SMALL)
                Spacer(Modifier.width(6.dp))
                IconBox(SIcons.Close, "Close preview", { model.closePreview() }, size = app.sonorium.ui.UiSize.SMALL)
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
            SField(
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
                modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.textSecondary) } },
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
                IconBox(SIcons.Close, "Close", onDismiss, size = app.sonorium.ui.UiSize.SMALL, background = Palette.card, border = Palette.card)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.categories.isEmpty()) Text("No categories", color = Palette.textMuted, modifier = Modifier.padding(vertical = 8.dp))
                state.categories.forEach { c ->
                    val n = state.themes.count { c in it.categories }
                    val label = "$n theme${if (n == 1) "" else "s"}"
                    if (confirming == c) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("Delete category “$c”?", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (n == 0) "No themes use it." else "$label lose this category; the themes stay.", color = Palette.textSecondary, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                TextButton(onClick = { confirming = null }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Cancel", color = Palette.textSecondary) }
                                Button(
                                    onClick = { confirming = null; model.deleteCategory(c) { onClosedChip(c) } },
                                    colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Palette.text), shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                                ) { Text("Delete") }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Badge(label)
                            Spacer(Modifier.width(6.dp))
                            IconBox(SIcons.Trash, "Delete $c", { confirming = c }, size = app.sonorium.ui.UiSize.SMALL)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    SField(
                        value = text, onValueChange = { if (it.length <= 60) text = it }, singleLine = true,
                        placeholder = { Text("New category", color = Palette.textMuted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "New category" },
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { add() }, enabled = ThemeRules.canAddCategory(text, state.categories), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Done", color = Palette.accent) } },
    )
}
