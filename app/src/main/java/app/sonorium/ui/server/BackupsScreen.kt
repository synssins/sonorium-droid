package app.sonorium.ui.server

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.R
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import app.sonorium.ui.UiSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Settings › Backups (a Sonorium that keeps backups): back this phone up to the server while connected,
 * load phone presets into the server's themes, and the account's backups by phone, each restorable here.
 */
@Composable
fun BackupsPage(state: ServerState, model: ServerViewModel) {
    val backup by model.backup.collectAsState()
    var ask by remember { mutableStateOf<Ask?>(null) }
    LaunchedEffect(state.record?.id) { model.loadBackups() }
    val listing = backup.listing
    val settings = listing?.get("settings") as? JsonObject
    val storage = listing?.get("storage") as? JsonObject
    val devices = (listing?.get("devices") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun b(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.booleanOrNull == true
    fun n(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.longOrNull

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            PageHeader(stringResource(R.string.backups_title), null)
            // This phone's backups to this server
            Card {
                SwitchLine(
                    stringResource(R.string.backup_switch, state.record?.displayName.orEmpty()),
                    stringResource(R.string.backup_switch_tip), backup.on,
                ) { model.setBackupOn(it) }
                Row(Modifier.fillMaxWidth().heightIn(min = UiSize.ROW.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val last = when {
                            backup.running -> stringResource(R.string.backup_running)
                            backup.lastDone > 0 -> stringResource(R.string.backup_last, android.text.format.DateUtils.getRelativeTimeSpanString(backup.lastDone).toString())
                            else -> stringResource(R.string.backup_never)
                        }
                        Text(last, color = Palette.textSecondary, fontSize = 13.sp)
                        backup.problem?.let { Text(it, color = Palette.warning, fontSize = 12.sp) }
                    }
                    OutlinedButton(
                        onClick = { model.backUpNow() }, enabled = !backup.running && backup.restoring == null,
                        border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = UiSize.STANDARD.dp),
                    ) { Text(stringResource(R.string.backup_now), color = if (backup.running) Palette.textMuted else Palette.text) }
                }
                if (b(settings, "can_load") || b(settings, "load_into_themes")) {
                    SwitchLine(stringResource(R.string.backup_load), stringResource(R.string.backup_load_tip), b(settings, "load_into_themes")) {
                        model.setLoadIntoThemes(it)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.backup_devices).uppercase(), color = Palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
                storage?.let { st ->
                    val used = bytesText(n(st, "used_bytes") ?: 0)
                    val limit = n(st, "limit_bytes")
                    Text(
                        if (limit != null) stringResource(R.string.backup_space, used, bytesText(limit)) else stringResource(R.string.backup_space_nolimit, used),
                        color = if (b(st, "warning")) Palette.warning else Palette.textMuted, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            backup.restoring?.let { what ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_restoring, what), color = Palette.textSecondary, fontSize = 13.sp)
                }
            }
            when {
                listing == null && backup.loadFailed -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.backup_load_failed), color = Palette.text, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { model.loadBackups() }, border = BorderStroke(1.dp, Palette.border), modifier = Modifier.heightIn(min = UiSize.STANDARD.dp)) {
                        Text(stringResource(R.string.try_again), color = Palette.text)
                    }
                }
                listing == null -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Palette.accent)
                }
                devices.isEmpty() -> EmptyState(stringResource(R.string.backup_none), null)
                else -> Card {
                    devices.forEachIndexed { i, d ->
                        if (i > 0) androidx.compose.material3.HorizontalDivider(color = Palette.border)
                        val id = s(d, "device_id") ?: return@forEachIndexed
                        val name = s(d, "name")?.ifBlank { null } ?: id
                        val themes = (d["themes"] as? JsonArray)?.size ?: 0
                        val playlists = n(d, "playlists")
                        val notLinked = stringResource(R.string.backup_not_linked)
                        val parts = buildList {
                            add(pluralStringResource(R.plurals.backup_themes, themes, themes))
                            playlists?.let { add(pluralStringResource(R.plurals.backup_playlists, it.toInt(), it.toInt())) }
                            n(d, "size_bytes")?.let { add(bytesText(it)) }
                            s(d, "updated")?.let { u -> updatedText(u)?.let { add(it) } }
                            if (d["linked"] is JsonPrimitive && !b(d, "linked")) add(notLinked)
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = UiSize.ROW.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(SIcons.Device, null, tint = Palette.textSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (b(d, "this_device")) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.backup_this_device), color = Palette.accent, fontSize = 11.sp)
                                    }
                                }
                                Text(parts.joinToString(" · "), color = Palette.textSecondary, fontSize = 12.sp)
                            }
                            val restoreTitle = stringResource(R.string.backup_restore_title, name)
                            val restoreText = stringResource(R.string.backup_restore_text)
                            val restoreAction = stringResource(R.string.backup_restore_action)
                            val deleteTitle = stringResource(R.string.backup_delete_title, name)
                            val deleteText = stringResource(R.string.backup_delete_text)
                            val deleteAction = stringResource(R.string.backup_delete_action)
                            MoreMenu(name, listOf(
                                MenuAction(stringResource(R.string.backup_restore), SIcons.Download, enabled = backup.restoring == null && themes + (playlists ?: 0) > 0) {
                                    ask = Ask(restoreTitle, restoreText, restoreAction, danger = false) { model.restoreBackup(id) }
                                },
                                MenuAction(stringResource(R.string.backup_delete), SIcons.Trash, danger = true, separatorBefore = true) {
                                    ask = Ask(deleteTitle, deleteText, deleteAction) { model.deleteBackups(id) }
                                },
                            ), size = UiSize.STANDARD)
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
    ask?.let { a -> ConfirmDialog(a.title, a.sub, a.action, a.danger, onConfirm = a.onOk, onDismiss = { ask = null }) }
}

/** "3 h ago" from the server's ISO time, or null when it can't be read. */
private fun updatedText(iso: String): String? = runCatching {
    val ms = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    android.text.format.DateUtils.getRelativeTimeSpanString(ms).toString()
}.getOrNull()

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(12.dp)).border(1.dp, Palette.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

@Composable
private fun SwitchLine(label: String, tip: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = UiSize.ROW.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.text, fontSize = 14.sp)
            Text(tip, color = Palette.textSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar))
    }
}
