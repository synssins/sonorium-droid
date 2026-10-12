package app.sonorium.ui.server

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.PluginRules
import app.sonorium.server.PluginRules.Plugin
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val REQUIREMENTS = """class MyPlugin(BasePlugin):
    id = "my_plugin"           # Unique identifier
    name = "My Plugin"         # Display name
    version = "1.0.0"          # MAJOR.MINOR.PATCH
    description = "..."        # Brief description
    author = "Your Name"       # Plugin author"""

/** Settings › Plugins (native-api.md Phase 3): Installed (on/off, forms, settings, uninstall) and Catalog. */
@Composable
fun PluginsPage(model: ServerViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var plugins by remember { mutableStateOf<List<Plugin>?>(null) }
    var catalog by remember { mutableStateOf<Result<JsonObject>?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var requirements by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }

    suspend fun load() {
        model.call { it.jsonArray("plugins") }.onSuccess { plugins = PluginRules.plugins(it) }
            .onFailure { if (plugins == null) plugins = emptyList() }
    }
    suspend fun loadCatalog() {
        catalog = null
        catalog = model.call { it.json("GET", "plugins/catalog") }
    }
    val pluginsChanged by model.pluginsChanged.collectAsState()
    val restarted by model.restarted.collectAsState()
    LaunchedEffect(pluginsChanged, restarted) { load() }
    LaunchedEffect(model.pluginsCatalogTab) { if (model.pluginsCatalogTab && catalog == null) loadCatalog() }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            if (!name.lowercase().endsWith(".zip")) return@launch model.toast("Please select a ZIP file", error = true)
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
                ?: return@launch model.toast("Failed to upload plugin", error = true)
            uploading = true
            model.call { it.uploadPlugin(name, bytes) }.onSuccess { o ->
                val warning = (o["status"] as? JsonPrimitive)?.content == "warning"
                val pname = ((o["plugin"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: "Unknown"
                if (warning) model.toast((o["message"] as? JsonPrimitive)?.content ?: "Plugin \"$pname\" installed successfully!", error = true)
                else model.toast("Plugin \"$pname\" installed successfully!", error = false)
                catalog = null
                model.pluginsCatalogTab = false
                load()
                model.afterPluginsChanged()
            }.onFailure { model.toast(model.messageOf(it, "Failed to upload plugin"), error = true) }
            uploading = false
        }
    }

    fun toggle(p: Plugin, on: Boolean) = scope.launch {
        model.call { it.json("PUT", "plugins/${p.id}/${if (on) "enable" else "disable"}") }.onSuccess {
            model.toast(if (on) "Plugin enabled" else "Plugin disabled", error = false)
            if (on) model.pluginOpen[p.id] = true
            load()
            model.afterPluginsChanged()
        }.onFailure { model.toast(model.messageOf(it, "Failed to toggle plugin"), error = true) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                PageHeader("Plugins", plugins?.size, help = "settings-plugins") {
                    if (uploading) Text("Uploading…", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    Button(
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        enabled = !uploading,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Upload plugin. Plugin .zip" },
                    ) { Text(if (narrow) "Upload" else "Upload plugin") }
                    Spacer(Modifier.width(6.dp))
                    MoreMenu("More", listOf(MenuAction("Plugin requirements…") { requirements = true }))
                }
            }
            item {
                Segmented(
                    listOf("Installed (${plugins?.size ?: 0})", "Catalog"),
                    if (model.pluginsCatalogTab) 1 else 0,
                    { model.pluginsCatalogTab = it == 1 },
                    Modifier.padding(bottom = 8.dp),
                )
            }
            if (!model.pluginsCatalogTab) {
                val list = plugins
                if (list != null && list.isEmpty()) item { EmptyState("No plugins installed", null) }
                items(list.orEmpty(), key = { it.id }) { p ->
                    PluginRow(p, model, onToggle = { on ->
                        if (!on && p.authProvider) {
                            ask = Ask("Switch off “${p.name}”?", "Sonorium will be open to anyone on your network.", "Switch off") { toggle(p, false) }
                        } else toggle(p, on)
                    }, onUninstall = {
                        ask = Ask(
                            "Uninstall “${p.name}”?",
                            if (p.authProvider) "Sonorium will be open to anyone on your network." else "Removes the plugin files.",
                            "Uninstall",
                        ) {
                            scope.launch {
                                model.call { it.json("DELETE", "plugins/${p.id}") }.onSuccess {
                                    model.toast("Plugin \"${p.name}\" uninstalled successfully", error = false)
                                    catalog = null
                                    load()
                                    model.afterPluginsChanged()
                                }.onFailure { model.toast(model.messageOf(it, "Failed to uninstall plugin"), error = true) }
                            }
                        }
                    }, onChanged = { scope.launch { load() } })
                }
            } else {
                val c = catalog
                when {
                    c == null -> item { Text("Loading catalog…", color = Palette.textSecondary, modifier = Modifier.padding(16.dp)) }
                    c.isFailure -> item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                            Text("Couldn’t load the catalog", color = Palette.danger, modifier = Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { loadCatalog() } }) { Text("Retry", color = Palette.accent) }
                        }
                    }
                    else -> {
                        val rows = (c.getOrNull()?.get("plugins") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        if (rows.isEmpty()) item { EmptyState("No plugins available", null) }
                        items(rows, key = { (it["id"] as? JsonPrimitive)?.content.orEmpty() }) { r ->
                            CatalogRow(r, installing) { id, name ->
                                installing = id
                                scope.launch {
                                    model.call { it.json("POST", "plugins/install-from-catalog", buildJsonObject { put("plugin_id", id) }) }.onSuccess {
                                        model.toast("${name.ifEmpty { id }} installed successfully", error = false)
                                        load()
                                        loadCatalog()
                                        model.afterPluginsChanged()
                                    }.onFailure { model.toast("Failed to install: ${model.messageOf(it, "")}", error = true) }
                                    installing = null
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
    if (requirements) AlertDialog(
        onDismissRequest = { requirements = false },
        containerColor = Palette.card,
        title = { Text("Plugin requirements", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(REQUIREMENTS, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.background(Palette.backgroundSecondary, RoundedCornerShape(6.dp)).padding(8.dp))
                Text("A .zip with plugin.py; manifest.json optional.", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = { requirements = false }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar), shape = RoundedCornerShape(8.dp)) { Text("Done") }
        },
    )
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

@Composable
private fun PluginRow(p: Plugin, model: ServerViewModel, onToggle: (Boolean) -> Unit, onUninstall: () -> Unit, onChanged: () -> Unit) {
    val open = p.enabled && p.hasOptions && (model.pluginOpen[p.id] ?: true)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(
                if (open) SIcons.ChevronDown else SIcons.ChevronRight,
                if (!p.hasOptions) "No options" else if (!p.enabled) "Enable to use" else if (open) "Hide ${p.name}" else "Show ${p.name}",
                { model.pluginOpen[p.id] = !open }, size = 40, enabled = p.enabled && p.hasOptions,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).alpha(if (p.enabled) 1f else 0.55f)) {
                Text(p.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("v${p.version}", color = Palette.textSecondary, fontSize = 12.sp)
                    if (p.builtin) {
                        Spacer(Modifier.width(6.dp))
                        Text("Built-in", color = Palette.textSecondary, fontSize = 11.sp, modifier = Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp))
                    }
                    if (p.author.isNotEmpty()) Text("  ·  ${p.author}", color = Palette.textMuted, fontSize = 12.sp)
                }
            }
            Switch(
                checked = p.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
                modifier = Modifier.semantics { contentDescription = if (p.enabled) "Enabled: ${p.name}" else "Disabled: ${p.name}" },
            )
            MoreMenu("More for ${p.name}", listOf(MenuAction("Uninstall…", SIcons.Trash, danger = true) { onUninstall() }), size = 40)
        }
        if (p.description.isNotEmpty()) Text(p.description, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 44.dp, top = 4.dp).alpha(if (p.enabled) 1f else 0.55f))
        if (open) PluginForm(p, model, onChanged)
    }
}

@Composable
private fun PluginForm(p: Plugin, model: ServerViewModel, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values = model.pluginForms[p.id].orEmpty()
    fun value(name: String, default: String) = values[name] ?: default
    fun set(name: String, v: String) { model.pluginForms[p.id] = values + (name to v) }
    Column(Modifier.padding(start = 8.dp, top = 8.dp)) {
        p.form.forEach { ff ->
            val shown = PluginRules.visible(ff, p.form.associate { it.field.name to value(it.field.name, PluginRules.formDefault(it.field)) })
            if (shown) {
                val v = value(ff.field.name, PluginRules.formDefault(ff.field))
                SettingsField(ff.field, v, v == "true", saved = false, onText = { set(ff.field.name, it) }, onChecked = { set(ff.field.name, it.toString()) }, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        if (p.actions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            p.actions.forEach { a ->
                val run = {
                    scope.launch {
                        model.toast("Executing action...", error = false)
                        val data = PluginRules.actionData(p.form, values)
                        model.call { it.json("POST", "plugins/${p.id}/action", buildJsonObject { put("action", a.id); put("data", data) }) }.onSuccess { r ->
                            val ok = (r["success"] as? JsonPrimitive)?.booleanOrNull == true
                            val msg = (r["message"] as? JsonPrimitive)?.content?.ifEmpty { null }
                            model.toast(msg ?: if (ok) "Action completed" else "Action failed", error = !ok)
                            if (r["themes"] is JsonArray || (r["themes_changed"] as? JsonPrimitive)?.booleanOrNull == true) model.refreshThemeList()
                            onChanged()
                        }.onFailure { model.toast(model.messageOf(it, "Failed to execute action"), error = true) }
                    }
                    Unit
                }
                if (a.primary) Button(onClick = run, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar)) { Text(a.label) }
                else OutlinedButton(onClick = run, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Palette.border)) { Text(a.label, color = Palette.text) }
            }
        }
        if (p.settingsFields.isNotEmpty()) PluginSettings(p, model, onChanged)
    }
}

/** `settings_schema`: each change saves that one setting; problems show in a line under the block. */
@Composable
private fun PluginSettings(p: Plugin, model: ServerViewModel, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val texts = remember(p.settings) { mutableStateMapOf<String, String>().apply { p.settingsFields.forEach { put(it.name, PluginRules.settingText(it, p.settings)) } } }
    var problem by remember { mutableStateOf<String?>(null) }
    fun save(name: String) {
        val f = p.settingsFields.first { it.name == name }
        val text = texts[name].orEmpty()
        PluginRules.settingProblem(f, text)?.let { problem = it; return }
        problem = null
        scope.launch {
            model.call { it.json("PUT", "plugins/${p.id}/settings", buildJsonObject { put("settings", JsonObject(mapOf(name to PluginRules.settingValue(f, text)))) }) }
                .onSuccess { onChanged() }
                .onFailure { problem = model.messageOf(it, "Not saved") }
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        FieldLabel("Settings")
        p.settingsFields.forEach { f ->
            var hadFocus by remember { mutableStateOf(false) }
            SettingsField(
                f, texts[f.name].orEmpty(), texts[f.name] == "true", saved = false,
                onText = { texts[f.name] = it; if (f.type == "select") save(f.name) },
                onChecked = { texts[f.name] = it.toString(); save(f.name) },
                // text fields save when they lose focus (the web's `change`)
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .onFocusChanged { st ->
                        if (hadFocus && !st.hasFocus && f.type != "boolean" && f.type != "select") save(f.name)
                        hadFocus = st.hasFocus
                    },
            )
        }
        problem?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
    }
}

@Composable
private fun CatalogRow(r: JsonObject, installing: String?, onInstall: (String, String) -> Unit) {
    fun s(k: String) = (r[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    fun b(k: String) = (r[k] as? JsonPrimitive)?.booleanOrNull == true
    val id = s("id")
    val installed = b("installed")
    val update = installed && b("update_available")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.card, RoundedCornerShape(8.dp)).border(1.dp, Palette.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s("name").ifEmpty { id }, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("v${s("version")}", color = Palette.textSecondary, fontSize = 12.sp)
                    if (update) Text(
                        "  Update", color = Palette.warning, fontSize = 12.sp,
                        modifier = Modifier.semantics { contentDescription = "Installed: v${s("installed_version")}" },
                    )
                    if (s("category").isNotEmpty()) Text("  ·  ${s("category")}", color = Palette.textMuted, fontSize = 12.sp)
                    if (s("author").isNotEmpty()) Text("  ·  ${s("author")}", color = Palette.textMuted, fontSize = 12.sp)
                }
            }
            when {
                installing == id -> OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(8.dp)) { Text("Installing…") }
                update -> Button(onClick = { onInstall(id, s("name")) }, enabled = installing == null, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar)) { Text("Update") }
                installed -> OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(8.dp)) { Text("Installed") }
                else -> Button(onClick = { onInstall(id, s("name")) }, enabled = installing == null, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar)) { Text("Install") }
            }
        }
        if (s("description").isNotEmpty()) Text(s("description"), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
