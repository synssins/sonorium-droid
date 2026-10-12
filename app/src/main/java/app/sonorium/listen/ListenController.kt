package app.sonorium.listen

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Listen here" on the phone: the web UI's listen hooks start and stop the
 * [ListenService]; the service reports what it plays here, and the open web UI
 * follows it (APP_API.md 12.2.2, `listening` messages).
 */
object ListenController {
    data class Listening(
        val serverId: String,
        val sessionId: String,
        val name: String,
        val themeName: String,
        val playing: Boolean,
        val personal: Boolean = false,
    )

    private val _state = MutableStateFlow<Listening?>(null)
    val state: StateFlow<Listening?> = _state.asStateFlow()

    internal fun update(listening: Listening?) {
        _state.value = listening
    }

    /** The page pressed Listen here: it has already asked the server for the slot. */
    fun listen(
        context: Context,
        serverId: String,
        sessionId: String,
        url: String,
        name: String,
        themeName: String,
        volume: Int,
        personal: Boolean = false,
    ) {
        context.startService(
            Intent(context, ListenService::class.java).setAction(ListenService.ACTION_LISTEN)
                .putExtra(ListenService.EXTRA_SERVER_ID, serverId)
                .putExtra(ListenService.EXTRA_SESSION_ID, sessionId)
                .putExtra(ListenService.EXTRA_URL, url)
                .putExtra(ListenService.EXTRA_NAME, name)
                .putExtra(ListenService.EXTRA_THEME, themeName)
                .putExtra(ListenService.EXTRA_VOLUME, volume)
                .putExtra(ListenService.EXTRA_PERSONAL, personal),
        )
    }

    /** The page turned Listen here off; it tells the server itself. */
    fun stop(context: Context, sessionId: String) {
        if (_state.value?.sessionId != sessionId) return
        context.startService(
            Intent(context, ListenService::class.java).setAction(ListenService.ACTION_STOP)
                .putExtra(ListenService.EXTRA_SESSION_ID, sessionId),
        )
    }

    fun setVolume(context: Context, sessionId: String, volume: Int) {
        if (_state.value?.sessionId != sessionId) return
        context.startService(
            Intent(context, ListenService::class.java).setAction(ListenService.ACTION_VOLUME)
                .putExtra(ListenService.EXTRA_SESSION_ID, sessionId)
                .putExtra(ListenService.EXTRA_VOLUME, volume),
        )
    }
}
