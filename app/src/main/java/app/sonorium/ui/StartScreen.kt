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
import app.sonorium.data.ServerRecord
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** The native start: linked servers, and linking a new one. A card is at most 420 dp wide (UI_STYLE.md). */
@Composable
fun StartScreen(model: LinkViewModel) {
    val servers by model.servers.collectAsState()
    val state by model.state.collectAsState()
    var typing by rememberSaveable { mutableStateOf(false) }

    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("Sonorium", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                when (val s = state) {
                    is LinkState.Working -> Card { Working(s.message) }
                    is LinkState.Failed -> Card {
                        Text(s.message, color = Palette.text)
                        Spacer(Modifier.height(16.dp))
                        SecondaryButton("OK") { model.reset() }
                    }
                    is LinkState.Done -> Card {
                        Text("Linked to ${s.record.label}", color = Palette.text)
                        s.record.username?.let { Text("As $it", color = Palette.textSecondary, fontSize = 14.sp) }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("OK") { model.reset() }
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 480.dp)) {
        items(servers, key = { it.id }) { server ->
            Card(Modifier.clickable { model.select(server) }) {
                Text(server.label, color = Palette.text, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(server.hosts.firstOrNull(), server.username, server.version.ifEmpty { null }).joinToString(" · "),
                    color = Palette.textSecondary,
                    fontSize = 12.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { model.forget(server) }) { Text("Forget", color = Palette.danger) }
                }
            }
        }
    }
}

@Composable
private fun LinkButtons(model: LinkViewModel, onType: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Scan Link App code", Modifier.fillMaxWidth()) {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(model::linkWithCode) }
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
