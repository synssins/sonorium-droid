package app.sonorium.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.AdvancedPageRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings › Advanced (native-api.md Phase 3): features, playback values, Zones, Stream URL. */
@Composable
fun AdvancedSettingsPage(state: ServerState, model: ServerViewModel) {
    val scope = rememberCoroutineScope()
    val restarted by model.restarted.collectAsState()
    val settingsChanged by model.settingsChanged.collectAsState()
    var install by remember { mutableStateOf<JsonObject?>(null) }
    var values by remember { mutableStateOf<JsonObject?>(null) }
    var defaults by remember { mutableStateOf<JsonObject?>(null) }
    var zonesOn by remember { mutableStateOf<Boolean?>(null) }
    var connection by remember { mutableStateOf<JsonObject?>(null) }
    var restarting by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var bad by remember { mutableStateOf(emptySet<String>()) }
    var redraw by remember { mutableStateOf(0) }
    val perms = state.me?.perms.orEmpty().toSet()

    LaunchedEffect(restarted, settingsChanged) {
        restarting = false
        if (model.advancedRunningAfter != restarted) {
            model.advancedRunning = null
            model.advancedRunningAfter = restarted
        }
        install = model.call { it.json("GET", "install") }.getOrNull()
        install?.let { i ->
            if (model.advancedRunning == null) model.advancedRunning = features(i).mapValues { it.value.second }
        }
        model.call { it.json("GET", "settings/playback-values") }.onSuccess { o ->
            values = o["values"] as? JsonObject
            defaults = o["defaults"] as? JsonObject
        }
        zonesOn = if ("ch.view" in perms) model.call { it.json("GET", "zones") }.getOrNull()?.let { (it["on"] as? JsonPrimitive)?.booleanOrNull == true } else null
        connection = if ("admin.connection" in perms) model.call { it.json("GET", "connection") }.getOrNull() else null
    }

    fun saveValues(body: JsonObject) = scope.launch {
        model.call { it.json("PUT", "settings/playback-values", body) }.onSuccess { o ->
            values = o["values"] as? JsonObject
            defaults = o["defaults"] as? JsonObject ?: defaults
        }.onFailure { model.toast(model.messageOf(it, "Failed to save"), error = true) }
        redraw++
    }

    val feats = install?.let { features(it) }.orEmpty()
    val overridable = AdvancedPageRules.FEATURES.filter { (k, _) -> feats[k]?.first == true }
    val running = model.advancedRunning.orEmpty()
    val pending = overridable.any { (k, _) -> running[k] != null && feats[k]?.second != running[k] }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                PageHeader("Advanced", null, help = "settings-advanced") {
                    (install?.get("label") as? JsonPrimitive)?.content?.let { label ->
                        Text(label, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = "Install type: $label" })
                    }
                }
            }
            if (pending || restarting) item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (restarting) "Restarting…" else "⚠ Restart Sonorium to apply.", color = Palette.warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!restarting) Button(
                        onClick = {
                            ask = Ask("Restart Sonorium?", null, "Restart") {
                                restarting = true
                                scope.launch {
                                    model.call { it.json("PUT", "install/features", buildJsonObject { put("restart", true) }) }.onSuccess {
                                        model.toast("Restarting Sonorium...", error = false)
                                        model.waitForRestart()
                                    }.onFailure { restarting = false; model.toast(model.messageOf(it, "Not saved"), error = true) }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("Restart now") }
                }
            }
            if (overridable.isNotEmpty()) {
                item { SectionTitle("Features") }
                overridable.forEach { (key, texts) ->
                    item(key = "f-$key") {
                        val on = feats[key]?.second == true
                        Block(changed = false) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(texts.first, color = Palette.text, fontSize = 16.sp)
                                    Text("Default: Off", color = Palette.textMuted, fontSize = 12.sp)
                                }
                                if (running[key] != null && running[key] != on) {
                                    Text("Restart to apply", color = Palette.warning, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp).semantics { contentDescription = "Restart to apply. Takes effect after a restart" })
                                }
                                Switch(
                                    checked = on, enabled = !restarting,
                                    onCheckedChange = { v ->
                                        scope.launch {
                                            model.call { it.json("PUT", "install/features", buildJsonObject { put(key, v) }) }
                                                .onSuccess { install = it }
                                                .onFailure { model.toast(model.messageOf(it, "Not saved"), error = true) }
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                                    modifier = Modifier.semantics { contentDescription = texts.first },
                                )
                            }
                            Text(texts.second, color = Palette.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
            zonesOn?.let { on ->
                item { SectionTitle("Zones") }
                item(key = "zones") {
                    Block(changed = on) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Zones", color = if (on) Palette.accent else Palette.text, fontSize = 16.sp)
                                Text("Default: Off", color = Palette.textMuted, fontSize = 12.sp)
                            }
                            IconBox(SIcons.Refresh, "Zones: back to the default", {
                                scope.launch {
                                    model.call { it.json("PUT", "zones/switch", buildJsonObject { put("on", false) }) }
                                        .onSuccess { zonesOn = false; model.afterPluginsChanged() }
                                        .onFailure { model.toast(model.messageOf(it, "Not saved"), error = true) }
                                }
                            }, size = 40, enabled = on)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = on,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        model.call { it.json("PUT", "zones/switch", buildJsonObject { put("on", v) }) }
                                            .onSuccess { o -> zonesOn = (o["on"] as? JsonPrimitive)?.booleanOrNull == true; model.afterPluginsChanged() }
                                            .onFailure { model.toast(model.messageOf(it, "Not saved"), error = true) }
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                                modifier = Modifier.semantics { contentDescription = "Zones" },
                            )
                        }
                        Text(AdvancedPageRules.ZONES_DESCRIPTION, color = Palette.textSecondary, fontSize = 13.sp)
                    }
                }
            }
            if (values != null) {
                AdvancedPageRules.ROWS.groupBy { it.section }.forEach { (section, rows) ->
                    item(key = "s-$section") { SectionTitle(section) }
                    rows.forEach { r ->
                        item(key = "v-${r.name}-$redraw") {
                            val changed = AdvancedPageRules.changed(r, values, defaults)
                            val texts = remember(values) { r.keys.map { AdvancedPageRules.shown(r, AdvancedPageRules.value(values, it)) }.toMutableList() }
                            Block(changed) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.name, color = if (changed) Palette.accent else Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                    IconBox(SIcons.Refresh, "${r.name}: back to the default", { saveValues(AdvancedPageRules.reset(r)) }, size = 40, enabled = changed)
                                }
                                Text(r.description, color = Palette.textSecondary, fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    r.keys.forEachIndexed { i, k ->
                                        if (i > 0) Text("–", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                                        NumberBox(
                                            texts[i], "", true,
                                            if (r.keys.size == 2) "${r.name}, ${if (i == 0) "shortest" else "longest"}" else r.name,
                                            width = 80, error = k in bad,
                                        ) { t ->
                                            texts[i] = t
                                            val (body, wrong) = AdvancedPageRules.body(r, texts, defaults)
                                            bad = bad - r.keys.toSet() + wrong
                                            body?.let { saveValues(it) }
                                        }
                                    }
                                    Text(r.unit, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                                    Spacer(Modifier.weight(1f))
                                    Text("Default ${AdvancedPageRules.defaultText(r, defaults)}", color = Palette.textMuted, fontSize = 12.sp)
                                }
                                if (changed) AdvancedPageRules.warning(r, values)?.let { Text(it, color = Palette.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                }
            }
            val conn = connection
            if (conn != null) {
                item { SectionTitle("Network") }
                item(key = "stream-url-$redraw") {
                    val saved = (conn["stream_url"] as? JsonPrimitive)?.content.orEmpty()
                    var urlBad by remember { mutableStateOf(false) }
                    fun apply(text: String) {
                        if (!AdvancedPageRules.streamUrlOk(text.trim())) {
                            urlBad = true
                            model.toast("Must start with http:// or https://", error = true)
                            return
                        }
                        urlBad = false
                        ask = Ask("Restart Sonorium?", "Playing channels stop for a few seconds.", "Restart") {
                            scope.launch {
                                model.call { it.json("PUT", "connection", buildJsonObject { put("stream_url", text.trim()) }) }.onSuccess {
                                    model.toast("Restarting Sonorium...", error = false)
                                    model.waitForRestart()
                                }.onFailure { model.toast(model.messageOf(it, "Not saved"), error = true) }
                            }
                        }
                    }
                    Block(saved.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Stream URL", color = if (saved.isNotEmpty()) Palette.accent else Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            IconBox(SIcons.Refresh, "Stream URL: back to the default", { apply("") }, size = 40, enabled = saved.isNotEmpty())
                        }
                        Text("The address speakers are given to reach Sonorium.", color = Palette.textSecondary, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            NumberBox(saved, "Automatic", true, "Stream URL", width = 240, error = urlBad, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri) { apply(it) }
                            Spacer(Modifier.weight(1f))
                            Text("Default Automatic", color = Palette.textMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

/** Feature name → (overridable, enabled). */
private fun features(install: JsonObject): Map<String, Pair<Boolean, Boolean>> =
    (install["features"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        fun b(f: String) = (o[f] as? JsonPrimitive)?.booleanOrNull == true
        k to (b("overridable") to b("enabled"))
    }.toMap()

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Block(changed: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.card, RoundedCornerShape(8.dp))
            .border(1.dp, if (changed) Palette.accent.copy(alpha = 0.6f) else Palette.border, RoundedCornerShape(8.dp)).padding(10.dp),
    ) { content() }
}
