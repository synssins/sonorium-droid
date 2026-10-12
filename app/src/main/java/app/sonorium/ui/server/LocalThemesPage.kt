package app.sonorium.ui.server

import android.provider.OpenableColumns
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sonorium.local.LocalTheme
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
 * The themes on this phone: Import theme (a zip from the server's Export), the cards, Remove from phone.
 * Importing needs the paid features (test builds unlock them locally) and a themes folder, asked for once.
 */
@Composable
fun LocalThemesPage(model: ServerViewModel, header: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val themes by model.localThemes.collectAsState()
    var unlock by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LocalTheme?>(null) }
    var folderKnown by remember { mutableStateOf(model.themeStore.root != null) }
    LaunchedEffect(Unit) { model.refreshLocalThemes() }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "theme.zip"
        model.importLocalTheme(uri, name)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { model.themeStore.setRoot(uri) }
            .onSuccess { folderKnown = true; model.refreshLocalThemes(); pickZip.launch(ZIP_TYPES) }
            .onFailure { model.toast("Couldn't use that folder", error = true) }
    }
    fun import() {
        when {
            !model.entitlement.isPro.value -> unlock = true
            model.themeStore.root == null -> pickFolder.launch(ThemeStoreStart)
            else -> pickZip.launch(ZIP_TYPES)
        }
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
                    PageHeader("On this phone", themes?.size, help = "themes") {
                        Button(
                            onClick = ::import, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        ) {
                            Icon(SIcons.Upload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import theme", fontWeight = FontWeight.Medium)
                        }
                        if (folderKnown) {
                            Spacer(Modifier.width(6.dp))
                            MoreMenu("More", listOf(MenuAction("Change themes folder…", SIcons.Folder) { pickFolder.launch(ThemeStoreStart) }), size = UiSize.STANDARD)
                        }
                    }
                }
            }
            val list = themes
            if (list != null && list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    "No themes on this phone",
                    "Import a theme exported from your server (a .zip), or download one from your server's Themes.",
                )
            }
            items(list.orEmpty(), key = { it.docId }) { t -> LocalThemeCard(t) { removing = t } }
        }
    }

    if (unlock) UnlockScreen(model, onClose = { unlock = false }, onUnlocked = {
        unlock = false
        if (model.themeStore.root == null) pickFolder.launch(ThemeStoreStart) else pickZip.launch(ZIP_TYPES)
    })
    removing?.let { t ->
        ConfirmDialog("Remove “${t.name}” from this phone?", "The theme stays on your server.", "Remove", onConfirm = { model.removeLocalTheme(t) }, onDismiss = { removing = null })
    }
}

/** The folder picker's starting place: Music (the user can make or pick Music/Sonorium there). */
private val ThemeStoreStart get() = app.sonorium.local.ThemeStore.suggestedStart()

@Composable
private fun LocalThemeCard(t: LocalTheme, onRemove: () -> Unit) {
    Surface(color = Palette.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(UiSize.STANDARD.dp).background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(ChannelRules.themeIcon(t.folder, ThemeSummary(t.folder, icon = t.icon)), fontSize = 22.sp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (t.tracks == 1) "1 track" else "${t.tracks} tracks"} · ${bytesText(t.bytes)}", color = Palette.textSecondary, fontSize = 13.sp)
                }
                MoreMenu("More for ${t.name}", listOf(MenuAction("Remove from phone…", SIcons.Trash, danger = true) { onRemove() }), size = UiSize.SMALL)
            }
            if (t.description.isNotBlank()) Text(t.description, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.categories.isNotEmpty()) Text(t.categories.joinToString(" · "), color = Palette.textMuted, fontSize = 12.sp)
        }
    }
}

/** Unlock: what the paid features do, and the purchase (a local "yes" in test builds). */
@Composable
fun UnlockScreen(model: ServerViewModel, onClose: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EditorSurface {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(40.dp))
                    Text("Unlock Pro", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Play themes on this phone, with the same engine as your server.",
                        "No connection needed: away from home, in the car, on a plane.",
                        "Download themes from your server, or import theme files.",
                    ).forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(SIcons.Check, null, tint = Palette.accent, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(line, color = Palette.text, fontSize = 14.sp)
                        }
                    }
                    Text("Test build: unlocking doesn't charge anything.", color = Palette.textMuted, fontSize = 12.sp)
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
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { scope.launch { if (model.entitlement.restore()) onUnlocked() else model.toast("No earlier purchase found", error = true) } }) {
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
