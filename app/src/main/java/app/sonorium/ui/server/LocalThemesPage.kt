package app.sonorium.ui.server

import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.engine.PhonePlayer
import app.sonorium.local.LocalTheme
import app.sonorium.local.ThemeStore
import app.sonorium.net.ThemeSummary
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
import kotlinx.coroutines.launch

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/** "1.2 MB" / "820 KB". */
fun bytesText(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.ROOT, b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, b / 1e6)
    else -> "${(b + 999) / 1000} KB"
}

/**
 * The themes on this phone: Import theme (a zip from the server's Export), the cards (Play, Remove from
 * phone), Refresh and Change themes folder. Importing needs the paid features (test builds unlock them
 * locally) and a themes folder, asked for once (explained first). [hasServer]: a server is linked, so
 * the texts can mention saving themes from it (null: from the model).
 */
@Composable
fun LocalThemesPage(model: ServerViewModel, header: @Composable () -> Unit = {}, hasServer: Boolean? = null) {
    val context = LocalContext.current
    val themes by model.localThemes.collectAsState()
    val folder by model.localFolder.collectAsState()
    val importing by model.importing.collectAsState()
    val isPro by model.entitlement.isPro.collectAsState()
    val serverState by model.state.collectAsState()
    val server = hasServer ?: (serverState.record != null)
    val phone by PhonePlayer.state.collectAsState()
    var removing by remember { mutableStateOf<LocalTheme?>(null) }
    var unlock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshLocalThemes() }

    val folderPicker = rememberThemesFolderPicker(model)
    val startImport = rememberThemeImport(model, folderPicker)

    fun play(t: LocalTheme) {
        if (!isPro) {
            unlock = true
            return
        }
        if (phone.theme?.docId == t.docId && (phone.playing || phone.preparing != null)) {
            PhonePlayer.stop(context)
            return
        }
        PhonePlayer.choose(context, t)
        PhonePlayer.play(context)
        model.toast("Playing “${t.name}” on this phone. Its controls are in My channel.", error = false)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (maxWidth < 600.dp) 12.dp else 24.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    header()
                    PageHeader("On this phone", themes?.size, help = "phone-themes") {
                        Button(
                            onClick = startImport, enabled = importing == null,
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        ) {
                            if (importing != null) {
                                CircularProgressIndicator(color = Palette.bar, strokeWidth = 2.dp, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                            } else {
                                Icon(SIcons.Upload, null, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (importing != null) "Importing…" else "Import theme", fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        MoreMenu(
                            "More",
                            listOfNotNull(
                                MenuAction("Refresh", SIcons.Refresh) { model.refreshLocalThemes() },
                                if (folder != ThemeStore.FolderState.NONE) {
                                    MenuAction("Change themes folder…", SIcons.Folder, separatorBefore = true) { folderPicker.ask(changing = true, then = null) }
                                } else null,
                            ),
                            size = UiSize.STANDARD,
                        )
                    }
                    importing?.let { name ->
                        Text("Importing “$name”…", color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            val list = themes
            when {
                folder == ThemeStore.FolderState.MISSING -> item(span = { GridItemSpan(maxLineSpan) }) {
                    FolderMissing { folderPicker.ask(changing = false, then = null) }
                }
                list == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Palette.accent)
                    }
                }
                list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        "No themes on this phone",
                        if (server) "Import a theme exported from your server (a .zip), or save one from your server's Themes (⋯ › Save to phone)."
                        else "Import a theme zip, such as one exported from a Sonorium server.",
                    )
                }
            }
            if (folder != ThemeStore.FolderState.MISSING) {
                items(list.orEmpty(), key = { it.docId }) { t ->
                    val on = phone.theme?.docId == t.docId && (phone.playing || phone.preparing != null)
                    LocalThemeCard(t, playing = on, onPlay = { play(t) }, onRemove = { removing = t })
                }
            }
        }
    }

    if (unlock) UnlockScreen(model, onClose = { unlock = false }, onUnlocked = { unlock = false })
    removing?.let { t ->
        val playingIt = phone.theme?.docId == t.docId && (phone.playing || phone.preparing != null)
        ConfirmDialog(
            "Remove “${t.name}” from this phone?",
            removeText(t, playingIt, server),
            "Remove",
            onConfirm = { model.removeLocalTheme(t) },
            onDismiss = { removing = null },
        )
    }
}

/** The Remove dialog's text: what happens to a playing theme, and where the theme still is. */
internal fun removeText(t: LocalTheme, playing: Boolean, hasServer: Boolean): String = listOfNotNull(
    if (playing) "This theme is playing; removing it stops it." else null,
    if (t.themeId != null && hasServer) "The theme stays on your server." else "Its files are deleted from this phone.",
).joinToString(" ")

/** The themes folder was picked but can't be found now: say so, and offer to choose it again. */
@Composable
private fun FolderMissing(onChoose: () -> Unit) {
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.warning)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Folder, null, tint = Palette.warning, modifier = Modifier.size(UiSize.GLYPH_SMALL.dp))
                Spacer(Modifier.width(8.dp))
                Text("Your themes folder can't be found", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            Text(
                "It may have been moved or deleted, or Sonorium can no longer open it. Choose it again, or choose a new folder for your themes.",
                color = Palette.textSecondary, fontSize = 14.sp,
            )
            Button(
                onClick = onChoose, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
            ) { Text("Choose folder", fontWeight = FontWeight.Medium) }
        }
    }
}

/** Asks for the themes folder: [ask] explains it first, then opens the system folder picker. */
@Stable
class ThemesFolderPicker internal constructor(private val start: (Boolean, (() -> Unit)?) -> Unit) {
    /** [changing]: "Change themes folder…" (never continues to [then]); [then] runs once a folder is set. */
    fun ask(changing: Boolean, then: (() -> Unit)?) = start(changing, then)
}

private data class FolderRequest(val changing: Boolean, val then: (() -> Unit)?)

@Composable
fun rememberThemesFolderPicker(model: ServerViewModel): ThemesFolderPicker {
    var asking by remember { mutableStateOf<FolderRequest?>(null) }
    var pending by remember { mutableStateOf<FolderRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = pending
        pending = null
        if (uri == null) {
            if (request?.changing != true) model.toast("Sonorium needs a folder to keep themes on this phone. Choose one to continue.", error = true)
            return@rememberLauncherForActivityResult
        }
        val changing = request?.changing == true
        if (model.useThemesFolder(uri, changing) && !changing) request?.then?.invoke()
    }
    asking?.let { request ->
        FolderExplainDialog(
            changing = request.changing,
            onChoose = {
                asking = null
                pending = request
                runCatching { launcher.launch(ThemeStore.suggestedStart()) }.onFailure {
                    Log.w("LocalThemes", "No folder picker", it)
                    pending = null
                    model.toast("This phone has no folder picker, so a themes folder can't be chosen", error = true)
                }
            },
            onDismiss = { asking = null },
        )
    }
    return remember { ThemesFolderPicker { changing, then -> asking = FolderRequest(changing, then) } }
}

/** What the folder picker is for, before it opens (M9). */
@Composable
private fun FolderExplainDialog(changing: Boolean, onChoose: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text(if (changing) "Change themes folder" else "Choose a folder for your themes", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sonorium keeps the themes on this phone in a folder you choose, so you can also see them in the Files app.", color = Palette.text, fontSize = 14.sp)
                Text("On the next screen, open Music, then choose or create a folder named Sonorium, and tap Use this folder.", color = Palette.textSecondary, fontSize = 14.sp)
                if (changing) Text("Themes in the current folder stay there; they aren't moved.", color = Palette.textSecondary, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onChoose, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
            ) { Text("Choose folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.textSecondary) }
        },
    )
}

/**
 * Import theme: the unlock first (paid features), then the themes folder (explained), then the zip
 * picker. Returns what the Import button calls.
 */
@Composable
fun rememberThemeImport(model: ServerViewModel, folderPicker: ThemesFolderPicker = rememberThemesFolderPicker(model)): () -> Unit {
    val context = LocalContext.current
    var unlock by remember { mutableStateOf(false) }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "theme.zip"
        model.importLocalTheme(uri, name)
    }
    fun zip() {
        runCatching { pickZip.launch(ZIP_TYPES) }.onFailure {
            Log.w("LocalThemes", "No file picker", it)
            model.toast("This phone has no file picker to choose a theme zip", error = true)
        }
    }
    fun go() {
        when {
            !model.entitlement.isPro.value -> unlock = true
            model.themeStore.root == null || model.localFolder.value == ThemeStore.FolderState.MISSING -> folderPicker.ask(changing = false) { zip() }
            else -> zip()
        }
    }
    if (unlock) UnlockScreen(model, onClose = { unlock = false }, onUnlocked = { unlock = false; go() })
    return { go() }
}

@Composable
private fun LocalThemeCard(t: LocalTheme, playing: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (playing) Palette.accent else Palette.border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(UiSize.STANDARD.dp).background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(ChannelRules.themeIcon(t.folder, ThemeSummary(t.folder, icon = t.icon)), fontSize = 22.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${if (t.tracks == 1) "1 track" else "${t.tracks} tracks"} · ${bytesText(t.bytes)}",
                        color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconBox(
                    if (playing) SIcons.Stop else SIcons.Play,
                    if (playing) "Stop ${t.name}" else "Play ${t.name} on this phone",
                    onPlay,
                    tint = if (playing) Palette.danger else Palette.accent,
                    size = UiSize.SMALL,
                )
                Spacer(Modifier.width(6.dp))
                MoreMenu("More for ${t.name}", listOf(MenuAction("Remove from phone…", SIcons.Trash, danger = true) { onRemove() }), size = UiSize.SMALL)
            }
            if (t.description.isNotBlank()) Text(t.description, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.categories.isNotEmpty()) Text(t.categories.joinToString(" · "), color = Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Unlock: what the paid features do, and the purchase (a local "yes" in test builds). */
@Composable
fun UnlockScreen(model: ServerViewModel, onClose: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val testBuild = app.sonorium.BuildConfig.DEBUG
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(40.dp))
                    Text("Unlock Pro", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    listOf(
                        "Play themes on this phone, with the same engine as your server.",
                        "No connection needed: away from home, in the car, on a plane.",
                        "Download themes from your server, or import theme files.",
                    ).forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(SIcons.Check, null, tint = Palette.accent, modifier = Modifier.padding(top = 2.dp).size(UiSize.GLYPH_SMALL.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(line, color = Palette.text, fontSize = 14.sp)
                        }
                    }
                    if (testBuild) Text("Test build: unlocking doesn't charge anything.", color = Palette.textMuted, fontSize = 12.sp)
                    Button(
                        onClick = {
                            scope.launch {
                                if (model.entitlement.purchase()) {
                                    model.toast("Pro unlocked", error = false)
                                    onUnlocked()
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiSize.STANDARD.dp),
                    ) { Text("Unlock Pro", fontWeight = FontWeight.SemiBold) }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { scope.launch { if (model.entitlement.restore()) onUnlocked() else model.toast("No earlier purchase found", error = true) } },
                            modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                        ) {
                            Text("Restore purchase", color = Palette.textSecondary)
                        }
                        OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp)) {
                            Text("Not now", color = Palette.text)
                        }
                    }
                }
            }
        }
    }
}
