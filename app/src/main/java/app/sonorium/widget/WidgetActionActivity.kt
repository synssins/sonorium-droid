package app.sonorium.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import app.sonorium.engine.OpenApp
import app.sonorium.engine.PhonePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A widget's button, run in the foreground (no window shows): Play/Stop, previous, next, Retry, and opening
 * the app where a button can't help (Unlock, Choose a channel). Then the widget is drawn again.
 */
class WidgetActionActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val choice = WidgetStore.get(this, widgetId)
        when (intent.action) {
            UNLOCK -> {
                startActivity(OpenApp.intent(this, choice?.serverId))
                finish()
                return
            }
            CHOOSE -> {
                startActivity(
                    Intent(this, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                finish()
                return
            }
        }
        if (choice == null) {
            finish()
            return
        }
        val action = intent.action
        lifecycleScope.launch {
            runCatching { if (choice.device) device(action) else server(choice, action) }
            // Drawn again once the change is in (a server answers first)
            WidgetUpdater.update(applicationContext, widgetId)
            finish()
        }
    }

    private suspend fun device(action: String?) {
        WidgetUpdater.ensureDeviceTheme(this)
        val s = PhonePlayer.state.value
        when (action) {
            TOGGLE -> if (s.playing) PhonePlayer.stop(this) else PhonePlayer.play(this)
            NEXT -> if (s.playlistMode || s.sequenceId != null) PhonePlayer.next() else PhonePlayer.skip(true)
            PREVIOUS -> if (!s.playlistMode) PhonePlayer.skip(false)
        }
    }

    private suspend fun server(choice: WidgetChoice, action: String?) = withContext(Dispatchers.IO) {
        val api = WidgetUpdater.apiFor(applicationContext, choice.serverId) ?: return@withContext
        if (action == RETRY) return@withContext
        val session = api.sessions().firstOrNull { it.id == choice.channelId } ?: return@withContext
        when (action) {
            TOGGLE -> if (session.isPlaying) api.stop(session.id) else api.play(session.id)
            NEXT, PREVIOUS -> {
                val running = session.sequence?.running == true || session.playlistId != null
                if (running) {
                    if (action == NEXT) api.next(session.id)
                } else {
                    // Presets one way or the other (default settings first, as in the card's list)
                    val themeId = session.themeId ?: return@withContext
                    val ids = listOf("") + api.themePresets(themeId).presets.map { it.id }
                    if (ids.size < 2) return@withContext
                    val i = ids.indexOf(session.presetId.orEmpty()).coerceAtLeast(0)
                    val next = ids[(i + (if (action == NEXT) 1 else -1) + ids.size) % ids.size]
                    api.updateSession(session.id, kotlinx.serialization.json.buildJsonObject {
                        put("preset_id", kotlinx.serialization.json.JsonPrimitive(next))
                    })
                }
            }
        }
    }

    companion object {
        const val TOGGLE = "app.sonorium.widget.TOGGLE"
        const val PREVIOUS = "app.sonorium.widget.PREVIOUS"
        const val NEXT = "app.sonorium.widget.NEXT"
        const val RETRY = "app.sonorium.widget.RETRY"
        const val UNLOCK = "app.sonorium.widget.UNLOCK"
        const val CHOOSE = "app.sonorium.widget.CHOOSE"
        val ACTIONS = listOf(TOGGLE, PREVIOUS, NEXT, RETRY, UNLOCK, CHOOSE)
    }
}
