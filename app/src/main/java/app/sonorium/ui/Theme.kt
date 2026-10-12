package app.sonorium.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The web UI's palette (styles.css, UI_STYLE.md). Dark only; no new colours. */
object Palette {
    val background = Color(0xFF1A1A2E)
    val backgroundSecondary = Color(0xFF16213E)
    val card = Color(0xFF1F2937)
    val bar = Color(0xFF0F172A)
    val text = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)
    val textMuted = Color(0xFF64748B)
    val accent = Color(0xFF06D6A0)
    val accentSecondary = Color(0xFF8B5CF6)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
    val border = Color(0xFF334155)
}

@Composable
fun SonoriumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.bar,
            secondary = Palette.accentSecondary,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.card,
            onSurface = Palette.text,
            surfaceVariant = Palette.backgroundSecondary,
            onSurfaceVariant = Palette.textSecondary,
            outline = Palette.border,
            error = Palette.danger,
        ),
        content = content,
    )
}

/**
 * Sizes from the shared UI standard (sonorium-handoff `shared/ui-standards.md`), touch column, in dp.
 * Standard: header and top-bar controls, buttons, fields. Small: controls inside rows, cards, toolbars.
 */
object UiSize {
    // Touch minimum 48 (Chris, 2026-10-12; to-app/23)
    const val STANDARD = 48
    const val SMALL = 44
    /** List rows and the collapsed channel row, minimum. */
    const val ROW = 48
    /** Glyph in an icon-only Standard control / in Small icon buttons and text buttons. */
    const val GLYPH = 20
    const val GLYPH_SMALL = 16
    const val DISABLED_ALPHA = 0.5f
}
