package app.sonorium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import app.sonorium.CrashLog
import app.sonorium.data.ServerRecord
import app.sonorium.web.ServerActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** The native start: linked servers, and linking a new one. A card is at most 420 dp wide (UI_STYLE.md). */
@Composable
fun StartScreen(model: LinkViewModel) {
    val servers by model.servers.collectAsState()
    val state by model.state.collectAsState()
    var typing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }

    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("Servers", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                crash?.let { report ->
                    Card { CrashReport(report) { CrashLog.clear(context); crash = null } }
                }
                when (val s = state) {
                    is LinkState.Working -> Card { Working(s.message) }
                    is LinkState.Failed -> Card {
                        Text(s.message, color = Palette.text)
                        Spacer(Modifier.height(16.dp))
                        val clipboard = LocalClipboardManager.current
                        Footer {
                            s.details?.let { details ->
                                SecondaryButton("Copy details") { clipboard.setText(AnnotatedString(details)) }
                            }
                            SecondaryButton("OK") { model.reset() }
                        }
                    }
                    is LinkState.Done -> Card {
                        Text("Linked to ${s.record.displayName}", color = Palette.text)
                        s.record.username?.let { Text("As $it", color = Palette.textSecondary, fontSize = 14.sp) }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Open") {
                            model.reset()
                            context.startActivity(ServerActivity.intent(context, s.record.id))
                        }
                    }
                    is LinkState.ConfirmWords -> Card { ConfirmWords(s, model) }
                    LinkState.Idle -> if (typing) {
                        Card { TypeAddress(onConnect = { typing = false; model.connectToAddress(it) }, onCancel = { typing = false }) }
                    } else {
                        ServerList(servers, model)
                        LinkButtons(model, onType = { typing = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerList(servers: List<ServerRecord>, model: LinkViewModel) {
    if (servers.isEmpty()) return
    val context = LocalContext.current
    val currentId by model.currentId.collectAsState()
    var renaming by remember { mutableStateOf<ServerRecord?>(null) }
    var removing by remember { mutableStateOf<ServerRecord?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 520.dp)) {
        items(servers, key = { it.id }) { server ->
            Card(Modifier.clickable { model.select(server); context.startActivity(ServerActivity.intent(context, server.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.displayName,
                        color = Palette.text,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (server.id == currentId) Chip("Connected")
                }
                Text(
                    listOfNotNull(
                        server.hosts.firstOrNull()?.takeIf { server.name != null },
                        server.username,
                        server.version.ifEmpty { null },
                    ).joinToString(" · "),
                    color = Palette.textSecondary,
                    fontSize = 12.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { renaming = server }) { Text("Rename", color = Palette.textSecondary) }
                    TextButton(onClick = { removing = server }) { Text("Remove", color = Palette.danger) }
                }
            }
        }
    }

    renaming?.let { server ->
        var name by remember(server.id) { mutableStateOf(server.customName ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Palette.card,
            title = { Text("Rename", color = Palette.text) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    placeholder = { Text(server.hosts.firstOrNull() ?: "") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { PrimaryButton("Save") { model.rename(server, name); renaming = null } },
            dismissButton = { SecondaryButton("Cancel") { renaming = null } },
        )
    }

    removing?.let { server ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Palette.card,
            title = { Text("Remove ${server.displayName}?", color = Palette.text) },
            text = { Text("This phone is unlinked from the server. Linking again needs a new code or the password.", color = Palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = { model.remove(server); removing = null },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.danger, contentColor = Palette.text),
                ) { Text("Remove", fontWeight = FontWeight.Medium) }
            },
            dismissButton = { SecondaryButton("Cancel") { removing = null } },
        )
    }
}

/** A pill in the accent colour (UI_STYLE.md chips). */
@Composable
private fun Chip(label: String) {
    Surface(
        color = Palette.accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Palette.accent.copy(alpha = 0.4f)),
    ) {
        Text(label, color = Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun LinkButtons(model: LinkViewModel, onType: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Scan Link App code", Modifier.fillMaxWidth()) {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            try {
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { code -> code.rawValue?.let(model::linkWithCode) }
                    .addOnFailureListener { model.scanFailed(it) }
            } catch (e: Exception) {
                model.scanFailed(e)
            }
        }
        SecondaryButton("Enter address", Modifier.fillMaxWidth(), onType)
    }
}

@Composable
private fun TypeAddress(onConnect: (String) -> Unit, onCancel: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Address") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Footer {
        PrimaryButton("Connect") { onConnect(address) }
        SecondaryButton("Cancel", onClick = onCancel)
    }
}

@Composable
private fun ConfirmWords(s: LinkState.ConfirmWords, model: LinkViewModel) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Text("Check that Sonorium shows the same words", color = Palette.textSecondary, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    Text(s.words.joinToString("  "), color = Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))
    if (!s.hasUsers) {
        Text("This server has no user accounts. Link it with a Link App code.", color = Palette.text)
        Spacer(Modifier.height(16.dp))
        SecondaryButton("OK") { model.reset() }
        return
    }
    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        password, { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Footer {
        PrimaryButton("Sign in") { model.signIn(s, username, password) }
        SecondaryButton("Cancel") { model.reset() }
    }
}

/** The last crash, to copy and send to the developer by hand. */
@Composable
private fun CrashReport(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Text("Sonorium closed unexpectedly last time", color = Palette.warning, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Text(
        report,
        color = Palette.textSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 12,
        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
    )
    Spacer(Modifier.height(16.dp))
    Footer {
        PrimaryButton("Copy") { clipboard.setText(AnnotatedString(report)) }
        SecondaryButton("Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun Working(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.height(20.dp).widthIn(max = 20.dp))
        Text(message, color = Palette.text)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Palette.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** Footer actions on the right: Primary · Cancel (UI_STYLE.md). */
@Composable
private fun Footer(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
        modifier = modifier.heightIn(min = 44.dp),
    ) { Text(label, fontWeight = FontWeight.Medium) }
}

@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Palette.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Palette.card, contentColor = Palette.text),
        modifier = modifier.heightIn(min = 44.dp),
    ) { Text(label, fontWeight = FontWeight.Medium) }
}
