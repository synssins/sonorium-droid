package app.sonorium.ui.server

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.server.Toast
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay

/** A select's option: marks are ★ (default), ⚭ (intrusion) and the sequence mark, in that order (1.2). */
data class PickOption(
    val value: String,
    val label: String,
    val isDefault: Boolean = false,
    val intrusion: Boolean = false,
    val sequence: Boolean = false,
    val separatorBefore: Boolean = false,
    val note: String? = null,
    val icon: ImageVector? = null,
)

@Composable
fun Marks(o: PickOption) {
    if (o.isDefault) Text("★", color = Palette.warning, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    if (o.intrusion) Text("⚭", color = Palette.accentSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
    if (o.sequence) Icon(SIcons.Sequence, "Sequence", tint = Palette.textSecondary, modifier = Modifier.padding(start = 4.dp).size(13.dp))
}

/**
 * The web UI's select field (`.psel-btn`): the current value, its marks and a chevron; a list
 * with a check on the current option. [placeholder] shows dimmed when nothing is chosen.
 */
@Composable
fun PickerField(
    label: String,
    options: List<PickOption>,
    current: String?,
    onPick: (PickOption) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == current }
    Box(modifier) {
        Surface(
            color = Palette.backgroundSecondary,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Palette.border),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .semantics { contentDescription = label }
                .clickable(enabled = enabled) { open = true },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.label ?: placeholder,
                    color = if (selected != null) Palette.text else Palette.textMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                selected?.let { Marks(it) }
                Spacer(Modifier.weight(1f))
                Icon(SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Palette.card,
            modifier = Modifier.widthIn(min = 220.dp, max = 420.dp),
        ) {
            options.forEach { o ->
                if (o.separatorBefore) HorizontalDivider(color = Palette.border)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(22.dp)) {
                                if (o.value == current) Icon(SIcons.Check, null, tint = Palette.accent, modifier = Modifier.size(14.dp))
                            }
                            o.icon?.let { Icon(it, null, tint = Palette.textSecondary, modifier = Modifier.padding(end = 8.dp).size(16.dp)) }
                            Text(o.label, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Marks(o)
                            o.note?.let { Text("  $it", color = Palette.textMuted, fontSize = 12.sp) }
                        }
                    },
                    onClick = {
                        open = false
                        onPick(o)
                    },
                )
            }
        }
    }
}

/** A square icon button (`.icon-btn sm`); [tint] for on/accent/danger states. */
@Composable
fun IconBox(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.textSecondary,
    background: Color = Palette.backgroundSecondary,
    border: Color = Palette.border,
    size: Int = 40,
    enabled: Boolean = true,
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .size(size.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
    }
}

/** A count badge next to a title (`.badge`). */
@Composable
fun CountBadge(count: Int) {
    Box(
        Modifier
            .background(Palette.card, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) { Text("$count", color = Palette.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

/** A short slider with the value as a % (UI_STYLE.md); [onRelease] saves once, on release. */
@Composable
fun PercentSlider(
    value: Int,
    description: String,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    if (!dragging) local = value.toFloat()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
                onChange(it.toInt())
            },
            onValueChangeFinished = {
                dragging = false
                onRelease(local.toInt())
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.border,
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        Text("${local.toInt()}%", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.width(48.dp).padding(start = 8.dp))
    }
}

/** A field label (`.ch-lbl`): small caps, secondary text. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = modifier)
}

/** The current toast, for full-screen dialogs: they sit above the page's own toast. */
val LocalToast = androidx.compose.runtime.compositionLocalOf<Toast?> { null }

/** A toast host for inside a full-screen dialog (editors); place it at the bottom of the dialog. */
@Composable
fun DialogToast(modifier: Modifier = Modifier) {
    ToastHost(LocalToast.current, modifier)
}

/** A full-screen editor's background, with the toast shown above it. */
@Composable
fun EditorSurface(content: @Composable () -> Unit) {
    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            content()
            DialogToast(Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = 16.dp, end = 16.dp))
        }
    }
}

/** The bottom toast (0.4): icon + text, 3 s, red for errors. */
@Composable
fun ToastHost(toast: Toast?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            shown = toast
            delay(3_000)
            if (shown == toast) shown = null
        }
    }
    AnimatedVisibility(visible = shown != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val t = shown ?: return@AnimatedVisibility
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, if (t.error) Palette.danger else Palette.accent),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (t.error) SIcons.Close else SIcons.Check, null, tint = if (t.error) Palette.danger else Palette.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(t.text, color = Palette.text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = content)
}
