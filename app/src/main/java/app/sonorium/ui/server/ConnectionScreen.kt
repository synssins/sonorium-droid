package app.sonorium.ui.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.ConnectionRules
import app.sonorium.server.ConnectionRules.Row as ConnRow
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings › Connection (native-api.md Phase 3): Home Assistant, MQTT and plugin connections. */
@Composable
fun ConnectionPage(state: ServerState, model: ServerViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val restarted by model.restarted.collectAsState()
    var core by remember { mutableStateOf<JsonObject?>(null) }
    var ext by remember { mutableStateOf<JsonObject?>(null) }
    var cards by remember { mutableStateOf<JsonObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConnRow?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var testing by remember { mutableStateOf(setOf<String>()) }
    var pendingCards by remember { mutableStateOf<ByteArray?>(null) }

    suspend fun load() {
        core = model.call { it.json("GET", "connection") }.getOrNull()
        ext = model.call { it.json("GET", "extensions") }.getOrNull()
        cards = if (core != null) model.call { it.json("GET", "ha-cards") }.getOrNull() else null
        loaded = true
    }
    val settingsChanged by model.settingsChanged.collectAsState()
    val pluginsChanged by model.pluginsChanged.collectAsState()
    LaunchedEffect(restarted, settingsChanged, pluginsChanged) { load() }

    val saveCards = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/javascript")) { uri ->
        val bytes = pendingCards
        pendingCards = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
    }

    val rows = core?.let { ConnectionRules.coreRows(it) }.orEmpty() + ext?.let { ConnectionRules.pluginRows(it) }.orEmpty()

    fun rowKey(r: ConnRow) = r.kind + (r.connectionId ?: "")

    fun test(r: ConnRow) {
        testing = testing + rowKey(r)
        scope.launch {
            val result = model.call { api ->
                if (r.kind == "plugin") api.json("POST", "plugins/${r.pluginId}/connections/${r.connectionId}/test", buildJsonObject { put("values", JsonObject(emptyMap())) })
                else api.json("POST", "connection/test", buildJsonObject { put("kind", r.kind); put("values", JsonObject(emptyMap())) })
            }
            result.onSuccess { o ->
                val ok = (o["success"] as? JsonPrimitive)?.booleanOrNull == true
                val msg = (o["message"] as? JsonPrimitive)?.content?.ifEmpty { null } ?: if (ok) "Connected" else "Failed"
                model.toast("${r.title}: $msg", error = !ok)
            }.onFailure { model.toast(model.messageOf(it, "Failed"), error = true) }
            testing = testing - rowKey(r)
        }
    }

    fun remove(r: ConnRow) = scope.launch {
        if (r.kind == "plugin") {
            model.call { it.json("PUT", "plugins/${r.pluginId}/connections/${r.connectionId}", buildJsonObject {
                put("values", JsonObject(emptyMap()))
                put("clear", JsonArray(r.fields.map { f -> JsonPrimitive(f.name) }))
            }) }.onSuccess {
                model.toast("${r.title} removed", error = false)
                load()
            }.onFailure { model.toast(model.messageOf(it, "Could not remove the connection"), error = true) }
        } else {
            model.call { it.json("DELETE", "connection/${r.kind}") }.onSuccess { o ->
                core = (o["connection"] as? JsonObject) ?: model.call { it.json("GET", "connection") }.getOrNull()
                val restart = (o["restart_required"] as? JsonPrimitive)?.booleanOrNull == true
                model.toast(
                    when {
                        restart -> "MQTT removed. Restart Sonorium to finish."
                        r.kind == "ha" -> "Home Assistant removed"
                        else -> "MQTT removed"
                    },
                    error = false,
                )
                if (r.kind == "ha") model.afterHomeAssistantRemoved()
            }.onFailure { model.toast(model.messageOf(it, "Could not remove the connection"), error = true) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (narrow) 12.dp else 24.dp)) {
            item { PageHeader("Connection", null, help = "settings-connection") }
            if (loaded && rows.isEmpty()) item { EmptyState("No connections", "Home Assistant, MQTT and plugin connections show here") }
            items(rows, key = { rowKey(it) }) { r ->
                val (word, tone) = ConnectionRules.statusWord(r.state)
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.card, RoundedCornerShape(12.dp)).border(1.dp, Palette.border, RoundedCornerShape(12.dp)).padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.kind == "ha") SIcons.Server else if (r.kind == "mqtt") SIcons.Activity else SIcons.Layers, null, tint = Palette.textSecondary, modifier = Modifier.size(app.sonorium.ui.UiSize.GLYPH.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(r.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (r.kind == "plugin") {
                            Spacer(Modifier.width(6.dp))
                            Text("Plugin", color = Palette.textSecondary, fontSize = 11.sp, modifier = Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        val color = when (tone) { 1 -> Palette.accent; -1 -> Palette.danger; else -> Palette.textMuted }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.semantics { contentDescription = word + if (r.message.isNotEmpty()) ": ${r.message}" else "" },
                        ) {
                            Dot(color)
                            Spacer(Modifier.width(6.dp))
                            Text(word, color = color, fontSize = 13.sp)
                        }
                    }
                    Text(
                        r.pointsAt.ifEmpty { "Not set up" }, color = if (r.pointsAt.isEmpty()) Palette.textMuted else Palette.textSecondary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp, top = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (r.test) {
                            OutlinedButton(
                                onClick = { test(r) }, enabled = r.configured && rowKey(r) !in testing,
                                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                            ) { Text("Test", color = if (r.configured) Palette.text else Palette.textMuted) }
                        }
                        OutlinedButton(
                            onClick = { editing = r }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                        ) { Text("Edit", color = Palette.text) }
                        MoreMenu("More for ${r.title}", listOf(MenuAction("Remove…", SIcons.Trash, danger = true, enabled = r.configured) {
                            ask = Ask("Remove “${r.title}”?", "Its saved settings are cleared.", "Remove") { remove(r) }
                        }))
                    }
                }
            }
            cards?.let { c ->
                val st = (c["state"] as? JsonPrimitive)?.content.orEmpty()
                val line = (c["line"] as? JsonPrimitive)?.content.orEmpty()
                if (st != "needs_ha" && line.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                                .semantics { contentDescription = line + ((c["reason"] as? JsonPrimitive)?.content?.let { ". $it" } ?: "") },
                        )
                        if (st == "by_hand") TextButton(onClick = {
                            scope.launch {
                                model.call { it.bytes("ha-cards/download") }.onSuccess { b ->
                                    pendingCards = b
                                    saveCards.launch("sonorium-cards.js")
                                }.onFailure { model.toast(model.messageOf(it, "Download failed"), error = true) }
                            }
                        }, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Download", color = Palette.accent) }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    editing?.let { r ->
        ConnectionDialog(r, model, onClose = { editing = null }, onSaved = { scope.launch { load() } }, onAsk = { ask = it })
    }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

/** Edit: the connection's fields, Test with the typed values, Save (core rows restart Sonorium). */
@Composable
private fun ConnectionDialog(r: ConnRow, model: ServerViewModel, onClose: () -> Unit, onSaved: () -> Unit, onAsk: (Ask) -> Unit) {
    val scope = rememberCoroutineScope()
    val texts = remember { mutableStateMapOf<String, String>().apply { r.fields.forEach { put(it.name, ConnectionRules.initial(it, r.values)) } } }
    val bools = remember {
        mutableStateMapOf<String, Boolean>().apply {
            r.fields.filter { it.type == "boolean" }.forEach { f -> put(f.name, ((r.values[f.name] ?: f.default) as? JsonPrimitive)?.booleanOrNull == true) }
        }
    }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun problem(): String? = ConnectionRules.check(r.fields, texts, r.values)?.also { p ->
        if (r.test) result = p to false else model.toast(p, error = true)
    }

    fun test() {
        if (problem() != null) return
        result = "Testing…" to true
        scope.launch {
            val values = ConnectionRules.values(r.fields, texts, bools, forTest = true)
            model.call { api ->
                if (r.kind == "plugin") api.json("POST", "plugins/${r.pluginId}/connections/${r.connectionId}/test", buildJsonObject { put("values", values) })
                else api.json("POST", "connection/test", buildJsonObject { put("kind", r.kind); put("values", values) })
            }.onSuccess { o ->
                val ok = (o["success"] as? JsonPrimitive)?.booleanOrNull == true
                result = ((o["message"] as? JsonPrimitive)?.content?.ifEmpty { null } ?: if (ok) "Connected" else "Failed") to ok
            }.onFailure { result = model.messageOf(it, "Failed") to false }
        }
    }

    fun save() {
        if (problem() != null) return
        val values = ConnectionRules.values(r.fields, texts, bools)
        if (r.kind == "plugin") {
            busy = true
            scope.launch {
                model.call { it.json("PUT", "plugins/${r.pluginId}/connections/${r.connectionId}", buildJsonObject { put("values", values) }) }
                    .onSuccess {
                        onClose()
                        model.toast("${r.title} saved", error = false)
                        onSaved()
                    }.onFailure { e ->
                        val m = model.messageOf(e, "Not saved")
                        if (r.test) result = m to false else model.toast(m, error = true)
                    }
                busy = false
            }
        } else {
            onAsk(Ask("Restart Sonorium?", "Playing channels stop for a few seconds.", "Restart") {
                onClose()
                scope.launch {
                    val body = if (r.kind == "mqtt" && "mqtt_port" !in values) JsonObject(values + ("mqtt_port" to JsonPrimitive(1883))) else values
                    model.call { it.json("PUT", "connection", body) }
                        .onSuccess {
                            model.toast("Restarting Sonorium…", error = false)
                            model.waitForRestart()
                        }.onFailure { model.toast(model.messageOf(it, "Not saved"), error = true) }
                }
            })
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.card,
        title = { Text(r.title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                r.fields.forEach { f ->
                    SettingsField(
                        f, texts[f.name].orEmpty(), bools[f.name] == true,
                        saved = (r.values["${f.name}_set"] as? JsonPrimitive)?.booleanOrNull == true,
                        onText = { texts[f.name] = it; result = null }, onChecked = { bools[f.name] = it },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                r.pluginName?.let { Text("From the plugin $it", color = Palette.textSecondary, fontSize = 12.sp) }
                if (r.test) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = ::test, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) { Text("Test", color = Palette.text) }
                        Spacer(Modifier.width(12.dp))
                        result?.let { (m, ok) ->
                            Text(m, color = if (m == "Testing…") Palette.textSecondary else if (ok) Palette.accent else Palette.danger, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::save, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp)) { Text("Cancel", color = Palette.textSecondary) } },
    )
}
