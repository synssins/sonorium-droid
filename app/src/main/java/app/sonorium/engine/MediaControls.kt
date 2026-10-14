package app.sonorium.engine

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import java.io.ByteArrayOutputStream

/**
 * What every playback notification and lock screen of the app shows, the same for a theme played on this
 * device and a server channel heard here: the buttons beside ⏮ ▶ ⏭ (Next theme, Stop) and the artwork.
 */
@OptIn(UnstableApi::class)
object MediaControls {
    const val COMMAND_NEXT_THEME = "app.sonorium.NEXT_THEME"
    const val COMMAND_STOP = "app.sonorium.STOP"

    /** The buttons beside ⏮ ▶ ⏭: Next theme and Stop (same icons, same order everywhere). */
    fun buttons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Next theme")
            .setCustomIconResId(app.sonorium.R.drawable.ic_next_theme)
            .setSessionCommand(SessionCommand(COMMAND_NEXT_THEME, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop")
            .setSessionCommand(SessionCommand(COMMAND_STOP, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /** The session commands a controller may send: the defaults plus the buttons'. */
    fun sessionCommands(): SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        .add(SessionCommand(COMMAND_NEXT_THEME, Bundle.EMPTY))
        .add(SessionCommand(COMMAND_STOP, Bundle.EMPTY))
        .build()

    /** Accepts a controller with the buttons and their commands. */
    fun accept(session: MediaSession): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands())
            .setMediaButtonPreferences(buttons())
            .build()

    private const val SIZE = 512
    private const val BACKGROUND = 0xFF1A1A2E.toInt()

    @Volatile private var appArt: ByteArray? = null

    /** The app's icon on the Sonorium background, square. */
    fun appArtwork(resources: Resources): ByteArray? = appArt ?: runCatching {
        val icon = android.graphics.BitmapFactory.decodeResource(resources, app.sonorium.R.mipmap.ic_launcher)
        square { canvas ->
            val inset = SIZE / 6
            canvas.drawBitmap(icon, null, Rect(inset, inset, SIZE - inset, SIZE - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }.getOrNull().also { appArt = it }

    private val iconArt = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** A theme's icon (an emoji) on the Sonorium background, square; null when it can't be drawn. */
    fun iconArtwork(icon: String): ByteArray? {
        if (icon.isBlank()) return null
        iconArt[icon]?.let { return it }
        return runCatching {
            square { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SIZE * 0.55f; textAlign = Paint.Align.CENTER }
                val bounds = Rect().also { paint.getTextBounds(icon, 0, icon.length, it) }
                canvas.drawText(icon, SIZE / 2f, SIZE / 2f - bounds.exactCenterY(), paint)
            }
        }.getOrNull()?.also { iconArt[icon] = it }
    }

    private fun square(draw: (Canvas) -> Unit): ByteArray {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(BACKGROUND); draw(this) }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
