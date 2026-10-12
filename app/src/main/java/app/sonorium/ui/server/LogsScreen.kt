package app.sonorium.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.LogsRules
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Settings › Logs (native-api.md Phase 3): polled every 3 s while the page shows. */
@Composable
fun LogsPage(model: ServerViewModel) {
    val logs = model.logs
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val restarted by model.restarted.collectAsState()
    val list = rememberLazyListState()
    var copyText by remember { mutableStateOf("Copy") }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("sonorium-logs.txt") }

    LaunchedEffect(restarted) {
        if (restarted != 0L) {
            logs.after = 0
            logs.entries = emptyList()
            if (!logs.paused) logs.shown = emptyList()
        }
        while (true) {
            model.call { it.json("GET", "logs?after=${logs.after}") }.onSuccess { o ->
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                val new = (o["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val x = e as? JsonObject ?: return@mapNotNull null
                    fun f(k: String) = (x[k] as? JsonPrimitive)?.content.orEmpty()
                    LogsRules.Entry(f("time"), f("level"), f("source"), f("message"), (x["seq"] as? JsonPrimitive)?.longOrNull ?: 0)
                }
                // a restarted server counts from 1 again
                if (new.isEmpty() && logs.version.isNotEmpty() && s("version") != logs.version) {
                    logs.after = 0
                    logs.entries = emptyList()
                }
                logs.version = s("version")
                if (new.isNotEmpty()) {
                    logs.after = new.last().seq
                    logs.entries = LogsRules.append(logs.entries, new)
                    if (!logs.paused) logs.shown = logs.entries
                }
                logs.meta = LogsRules.meta(s("version"), s("install"), s("log_level"))
                fileName = "sonorium-logs-${s("version")}.txt"
            }.onFailure { logs.meta = "Can't reach Sonorium (${model.messageOf(it, it.message ?: "error")})" }
            delay(3_000)
        }
    }

    val visible = logs.shown.filter { LogsRules.visible(it, logs.filter, logs.search) }
    // Follow new lines when the list was at (or near) the bottom
    LaunchedEffect(visible.size) {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (visible.isNotEmpty() && (last < 0 || last >= info.totalItemsCount - 3)) list.scrollToItem(visible.size - 1)
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            PageHeader("Logs", null, help = "settings-logs") {
                OutlinedButton(
                    onClick = {
                        logs.paused = !logs.paused
                        if (!logs.paused) logs.shown = logs.entries
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (logs.paused) Palette.accent else Palette.border),
                    modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp).semantics { contentDescription = "${if (logs.paused) "Resume" else "Pause"}. Stop the list moving; new lines wait until Resume" },
                ) {
                    Icon(if (logs.paused) SIcons.Play else SIcons.Pause, null, tint = Palette.textSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH_SMALL.dp))
                    if (!narrow) Text(if (logs.paused) " Resume" else " Pause", color = Palette.text, fontSize = 13.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = {
                        copyText = runCatching { clipboard.setText(AnnotatedString(visible.joinToString("\n") { LogsRules.line(it) })) }
                            .fold({ "Copied" }, { "Copy failed" })
                        scope.launch { delay(1_500); copyText = "Copy" }
                    },
                    shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                ) { Text(copyText, color = Palette.text, fontSize = 13.sp) }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        scope.launch {
                            model.call { it.bytes("logs/download") }.onSuccess { b ->
                                pending = b
                                save.launch(fileName)
                            }.onFailure { model.toast(model.messageOf(it, "Download failed"), error = true) }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                    modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                ) { Text("Download", fontSize = 13.sp) }
            }
            SField(
                logs.search, { logs.search = it }, singleLine = true, placeholder = { Text("Search logs") },
                leadingIcon = { Icon(SIcons.Search, null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search logs" },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Segmented(LogsRules.FILTERS, LogsRules.FILTERS.indexOf(logs.filter).coerceAtLeast(0), { logs.filter = LogsRules.FILTERS[it] })
            }
            Text(logs.meta, color = Palette.textMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (visible.isEmpty()) {
                Text("No matching log entries", color = Palette.textSecondary, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                items(visible, key = { it.seq }) { e ->
                    val color = when (LogsRules.rank(e.level)) {
                        0 -> Palette.textMuted
                        2 -> Palette.warning
                        3, 4 -> Palette.danger
                        else -> Palette.text
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(e.time, color = Palette.textMuted, fontSize = if (narrow) 10.sp else 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false, modifier = Modifier.width(if (narrow) 84.dp else 100.dp))
                        Text(e.level, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(if (narrow) 58.dp else 72.dp))
                        Text(e.message, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
