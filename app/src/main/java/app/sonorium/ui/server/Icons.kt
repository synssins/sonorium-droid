package app.sonorium.ui.server

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The web UI's icons (its inline SVGs, 24x24, stroke 2), so native screens look the same. */
object SIcons {
    private fun icon(name: String, stroke: List<String> = emptyList(), fill: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroke.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            fill.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private const val DOT = "a1 1 0 1 0 0 2a1 1 0 1 0 0-2z"

    val Menu = icon("menu", listOf("M3 12h18M3 6h18M3 18h18"))
    val Server = icon(
        "server",
        listOf(
            "M4 3h16a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M4 14h16a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
            "M6 6.5h.01M6 17.5h.01",
        ),
    )
    val Headphones = icon(
        "headphones",
        listOf(
            "M3 18v-6a9 9 0 0 1 18 0v6",
            "M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z",
        ),
    )
    val Play = icon("play", fill = listOf("M8 5v14l11-7z"))
    /** This device (a phone or tablet): its own entry in the list. */
    val Device = icon("device", listOf("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01"))
    val Stop = icon("stop", fill = listOf("M7.5 6h9A1.5 1.5 0 0 1 18 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6z"))
    val Edit = icon("edit", listOf("M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"))
    val More = icon("more", fill = listOf("M5 11$DOT", "M12 11$DOT", "M19 11$DOT"))
    val Volume = icon("volume", listOf("M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07"))
    val Muted = icon("muted", listOf("M11 5L6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M3 3l18 18"))
    val Palette = icon(
        "palette",
        listOf("M12 2a10 10 0 0 0 0 20c1.1 0 1.8-.9 1.8-1.9 0-.5-.2-.9-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1 .8-1.8 1.8-1.8H17a5 5 0 0 0 5-5C22 6 17.5 2 12 2z"),
        listOf("M7.5 9.3a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4z", "M10.5 5.3a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4z", "M15.5 5.8a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4z"),
    )
    val Playlist = icon("playlist", listOf("M3 6h13M3 12h9M3 18h7", "M17 18V10l4-1.5", "M15 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"))
    val Next = icon("next", listOf("M19 5v14"), listOf("M5 4l10 8-10 8V4z"))
    val Plus = icon("plus", listOf("M12 5v14M5 12h14"))
    val Check = icon("check", listOf("M20 6L9 17l-5-5"))
    val ChevronDown = icon("chevron", listOf("M6 9l6 6 6-6"))
    val ChevronRight = icon("chevron-right", listOf("M9 6l6 6-6 6"))
    val Back = icon("arrow-left", listOf("M19 12H5", "M12 19l-7-7 7-7"))
    val Link = icon(
        "link",
        listOf(
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        ),
    )
    val Cast = icon(
        "cast",
        listOf("M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 20h.01"),
    )
    val Close = icon("close", listOf("M18 6L6 18M6 6l12 12"))
    val Grid = icon("grid", listOf("M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z"))
    val Music = icon("music", listOf("M9 18V5l12-2v13", "M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM18 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"))
    val Settings = icon(
        "settings",
        listOf(
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z",
        ),
    )
    val Activity = icon("activity", listOf("M22 12h-4l-3 9L9 3l-3 9H2"))
    val Help = icon("help", listOf("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01"))
    val Trash = icon("trash", listOf("M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"))
    val Search = icon("search", listOf("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M21 21l-4.35-4.35"))
    val Sequence = icon("sequence", listOf("M3 14h6v6H3z", "M17 3l4 7h-8z", "M17 14a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"))
    val Speakers = icon("speakers", listOf("M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 10a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 6h.01"))
    val Pause = icon("pause", fill = listOf("M6 4h4v16H6z", "M14 4h4v16h-4z"))
    val Copy = icon("copy", listOf("M9 9h11a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V9z", "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"))
    val Shuffle = icon("shuffle", listOf("M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5"))
    val InOrder = icon("in-order", listOf("M10 6h11M10 12h11M10 18h11", "M4 6h1v4M4 10h2", "M6 18H4c0-1 2-2 2-3s-1-1.5-2-1"))
    val Repeat = icon("repeat", listOf("M17 1l4 4-4 4", "M3 11V9a4 4 0 0 1 4-4h14", "M7 23l-4-4 4-4", "M21 13v2a4 4 0 0 1-4 4H3"))
    val Folder = icon("folder", listOf("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"))
    val Star = icon("star", listOf("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"))
    val StarFilled = icon("star-filled", fill = listOf("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"))
    val Refresh = icon("refresh", listOf("M23 4v6h-6", "M1 20v-6h6", "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"))
    val Download = icon("download", listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3"))
    val Upload = icon("upload", listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12"))
    val Tag = icon("tag", listOf("M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z", "M7 7h.01"))
    val Layers = icon("layers", listOf("M12 2L2 7l10 5 10-5-10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5"))
    val MapPin = icon("map-pin", listOf("M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z", "M12 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"))
}
