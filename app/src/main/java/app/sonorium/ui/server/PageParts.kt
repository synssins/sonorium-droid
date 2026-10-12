package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.ui.Palette

/** A page title with its count badge and the header buttons (`spSetTitle`). */
@Composable
fun PageHeader(title: String, count: Int?, modifier: Modifier = Modifier, help: String? = null, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        count?.let {
            Spacer(Modifier.width(8.dp))
            CountBadge(it)
        }
        help?.let {
            Spacer(Modifier.width(6.dp))
            PageHelpButton(it)
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** The accent header button (`Add playlist`, `Add group`, …); icon-only on narrow screens. */
@Composable
fun AddButton(label: String, narrow: Boolean, onClick: () -> Unit) {
    if (narrow) {
        IconBox(SIcons.Plus, label, onClick, tint = Palette.bar, background = Palette.accent, border = Palette.accent, size = 44)
    } else {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = label },
        ) {
            Icon(SIcons.Plus, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

/** One item of a ⋯ menu; [separatorBefore] draws the web UI's menu separator. */
data class MenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val onClick: () -> Unit,
)

/** The ⋯ button and its menu. */
@Composable
fun MoreMenu(description: String, items: List<MenuAction>, size: Int = 44) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBox(SIcons.More, description, { open = true }, size = size)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card) {
            items.forEach { item ->
                if (item.separatorBefore) HorizontalDivider(color = Palette.border)
                DropdownMenuItem(
                    enabled = item.enabled,
                    leadingIcon = item.icon?.let { { Icon(it, null, tint = if (item.danger) Palette.danger else Palette.textSecondary, modifier = Modifier.size(16.dp)) } },
                    text = { Text(item.label, color = if (item.danger) Palette.danger else if (item.enabled) Palette.text else Palette.textMuted) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** The confirm popover (`spConfirm`): bold title, optional sub line, Cancel and the action. */
@Composable
fun ConfirmDialog(
    title: String,
    sub: String?,
    action: String,
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = sub?.let { { Text(it, color = Palette.textSecondary, fontSize = 14.sp) } },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (danger) Palette.danger else Palette.accent,
                    contentColor = if (danger) Palette.text else Palette.bar,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text(action) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, border = BorderStroke(1.dp, Palette.border), shape = RoundedCornerShape(8.dp)) {
                Text("Cancel", color = Palette.text)
            }
        },
    )
}

/** Our dialog for the web UI's `prompt()`: a message, one text field, OK/Cancel. */
@Composable
fun PromptDialog(message: String, initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        text = {
            Column {
                Text(message, color = Palette.text, fontSize = 15.sp)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
        },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onOk(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                shape = RoundedCornerShape(8.dp),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.textSecondary) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A labelled switch row (`.sw`). */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar),
        )
    }
}

/** A two-way segmented control (`In order` / `Shuffle`, `Server` / `As heard`). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(Palette.backgroundSecondary, RoundedCornerShape(8.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            TextButton(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = if (on) Palette.card else Color.Transparent),
                modifier = Modifier.heightIn(min = 38.dp),
            ) { Text(label, color = if (on) Palette.text else Palette.textSecondary, fontSize = 14.sp) }
        }
    }
}

/** A small round state dot. */
@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** An empty list: bold line and a sub line. */
@Composable
fun EmptyState(title: String, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        sub?.let { Text(it, color = Palette.textSecondary, fontSize = 14.sp) }
    }
}

/** A page's ? button: its `PAGE_HELP` text in a help panel. */
@Composable
fun PageHelpButton(view: String) {
    val (title, lines) = HelpTexts.PAGES[view] ?: return
    var open by remember { mutableStateOf(false) }
    IconBox(SIcons.Help, "Help: $title", { open = true }, size = 44)
    if (open) HelpPanel(title, lines) { open = false }
}
