package app.sonorium.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.ConnectionRules.Field
import app.sonorium.ui.Palette

/**
 * One field of the settings vocabulary (`ext/core.js` `field()`). Text-like fields edit [text];
 * boolean edits [checked]; multiselect keeps a comma list in [text].
 */
@Composable
fun SettingsField(
    f: Field,
    text: String,
    checked: Boolean,
    saved: Boolean,
    onText: (String) -> Unit,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = f.label + if (f.type == "secret" && saved) " · saved" else ""
    when (f.type) {
        "boolean" -> Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked, onChecked, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bar), modifier = Modifier.semantics { contentDescription = f.label })
        }
        "select" -> {
            var open by remember { mutableStateOf(false) }
            Column(modifier) {
                FieldLabel(label)
                Box {
                    Text(
                        f.options.firstOrNull { it.first == text }?.second ?: text,
                        color = Palette.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }.padding(12.dp).semantics { contentDescription = f.label },
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.card) {
                        f.options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l, color = Palette.text) }, onClick = { open = false; onText(v) }) }
                    }
                }
            }
        }
        "multiselect" -> Column(modifier) {
            FieldLabel(label)
            val on = text.split(',').filter { it.isNotEmpty() }.toSet()
            f.options.forEach { (v, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v in on, { c -> onText((if (c) on + v else on - v).joinToString(",")) }, colors = CheckboxDefaults.colors(checkedColor = Palette.accent))
                    Text(l, color = Palette.text, fontSize = 14.sp)
                }
            }
        }
        else -> Column(modifier) {
            FieldLabel(label)
            SField(
                text, onText,
                singleLine = f.type != "textarea",
                minLines = if (f.type == "textarea") 2 else 1,
                placeholder = if (f.placeholder.isNotEmpty()) ({ Text(f.placeholder, color = Palette.textMuted) }) else null,
                supportingText = if (f.help.isNotEmpty()) ({ Text(f.help, fontSize = 11.sp) }) else null,
                visualTransformation = if (f.type == "secret") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (f.type) {
                        "number" -> KeyboardType.Decimal
                        "url" -> KeyboardType.Uri
                        "secret" -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = f.label },
            )
        }
    }
}
