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

/**
 * A player whose ⏮ ⏭ are always offered (presets, steps, themes: not media items), on the lock screen, the
 * notification and controllers. The session takes the available commands from the player's change events,
 * which carry the wrapped player's own; listeners here get this player's instead, so ⏮ ⏭ stay.
 */
@OptIn(UnstableApi::class)
abstract class SkippablePlayer(player: androidx.media3.common.Player) : androidx.media3.common.ForwardingPlayer(player) {
    private val wrapped = java.util.IdentityHashMap<androidx.media3.common.Player.Listener, androidx.media3.common.Player.Listener>()

    override fun getAvailableCommands(): androidx.media3.common.Player.Commands = super.getAvailableCommands().buildUpon()
        .removeAll(
            androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, androidx.media3.common.Player.COMMAND_SEEK_BACK,
            androidx.media3.common.Player.COMMAND_SEEK_FORWARD, androidx.media3.common.Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        )
        .addAll(
            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT, androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun addListener(listener: androidx.media3.common.Player.Listener) {
        // Every callback passed on as it is (a proxy: Player.Listener's methods are all Java defaults, which
        // Kotlin's `by` doesn't pass on), but the available commands, which are this player's
        val mine = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.common.Player.Listener::class.java.classLoader,
            arrayOf(androidx.media3.common.Player.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(this@SkippablePlayer.availableCommands)
                "equals" -> return@newProxyInstance args?.getOrNull(0) === listener
                "hashCode" -> return@newProxyInstance System.identityHashCode(listener)
                "toString" -> return@newProxyInstance "SkippablePlayer listener for $listener"
                else -> try {
                    method.invoke(listener, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
            null
        } as androidx.media3.common.Player.Listener
        synchronized(wrapped) { wrapped[listener] = mine }
        super.addListener(mine)
    }

    override fun removeListener(listener: androidx.media3.common.Player.Listener) {
        val mine = synchronized(wrapped) { wrapped.remove(listener) } ?: listener
        super.removeListener(mine)
    }
}
