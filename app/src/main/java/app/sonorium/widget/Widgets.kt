package app.sonorium.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.sonorium.R
import app.sonorium.SonoriumApp
import app.sonorium.engine.PhonePlayer
import app.sonorium.local.SampleTheme
import app.sonorium.net.PinnedTls
import app.sonorium.net.Session
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a widget shows: [serverId] (or this device's [SonoriumApp.DEVICE_ID]) and its channel. */
data class WidgetChoice(val serverId: String, val channelId: String) {
    val device: Boolean get() = serverId == SonoriumApp.DEVICE_ID
}

/** Each widget's channel, kept on the phone. */
object WidgetStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(context: Context, widgetId: Int): WidgetChoice? {
        val p = prefs(context)
        val server = p.getString("server:$widgetId", null) ?: return null
        return WidgetChoice(server, p.getString("channel:$widgetId", null) ?: return null)
    }

    fun set(context: Context, widgetId: Int, choice: WidgetChoice) {
        prefs(context).edit().putString("server:$widgetId", choice.serverId).putString("channel:$widgetId", choice.channelId).apply()
    }

    fun remove(context: Context, widgetId: Int) {
        prefs(context).edit().remove("server:$widgetId").remove("channel:$widgetId").apply()
    }
}

/** What a widget shows now. [problem]: why it can't show the channel (with what to do about it). */
data class WidgetSnapshot(
    val source: String,
    val title: String,
    val status: String,
    val playing: Boolean = false,
    /** Previous / next do something now. */
    val canPrevious: Boolean = true,
    val canNext: Boolean = true,
    val problem: Problem? = null,
) {
    enum class Problem { NOT_SET, UNREACHABLE, GONE, SAMPLE_LIMIT }
}

/** Reads what widgets show and draws them; also run by their buttons. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val DEVICE_CHANNEL = "my"

    private val providers = listOf(SmallChannelWidget::class.java, LargeChannelWidget::class.java)

    /** Every widget again (reading servers as needed). */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { p -> manager.getAppWidgetIds(ComponentName(context, p)).forEach { update(context, it) } }
    }

    /** The widgets on this device's own channel, from the player's state (no reading). */
    fun updateDevice(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { p ->
            manager.getAppWidgetIds(ComponentName(context, p)).forEach { id ->
                if (WidgetStore.get(context, id)?.device == true) draw(context, id, deviceSnapshot(context))
            }
        }
    }

    /** The widgets on [serverId]'s channels, from sessions the app just read (no reading). */
    fun updateServer(context: Context, serverId: String, serverName: String, sessions: List<Session>, themeName: (String?) -> String?, presetName: (Session) -> String?) {
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { p ->
            manager.getAppWidgetIds(ComponentName(context, p)).forEach { id ->
                val choice = WidgetStore.get(context, id)?.takeIf { it.serverId == serverId } ?: return@forEach
                val session = sessions.firstOrNull { it.id == choice.channelId }
                draw(context, id, if (session == null) gone(context, serverName) else sessionSnapshot(context, serverName, session, themeName(session.themeId), presetName(session)))
            }
        }
    }

    /** One widget: what it shows is read (a server's channel over the network), then drawn. */
    fun update(context: Context, widgetId: Int, done: (() -> Unit)? = null) {
        scope.launch {
            try {
                draw(context, widgetId, load(context, WidgetStore.get(context, widgetId)))
            } finally {
                done?.invoke()
            }
        }
    }

    suspend fun load(context: Context, choice: WidgetChoice?): WidgetSnapshot {
        if (choice == null) return WidgetSnapshot("", context.getString(R.string.widget_choose), "", problem = WidgetSnapshot.Problem.NOT_SET)
        if (choice.device) {
            ensureDeviceTheme(context)
            return deviceSnapshot(context)
        }
        val app = context.applicationContext as SonoriumApp
        val record = app.servers.find(choice.serverId) ?: return gone(context, "")
        val api = apiFor(context, choice.serverId) ?: return WidgetSnapshot(
            record.displayName, record.displayName, context.getString(R.string.widget_unreachable, record.displayName),
            problem = WidgetSnapshot.Problem.UNREACHABLE,
        )
        return runCatching {
            val session = api.sessions().firstOrNull { it.id == choice.channelId } ?: return gone(context, record.displayName)
            val theme = session.themeId?.let { id -> runCatching { api.themes().firstOrNull { it.id == id }?.name }.getOrNull() }
            val preset = session.presetId?.takeIf { it.isNotEmpty() }?.let { pid ->
                session.themeId?.let { tid -> runCatching { api.themePresets(tid).presets.firstOrNull { it.id == pid }?.name }.getOrNull() }
            }
            sessionSnapshot(context, record.displayName, session, theme, preset)
        }.getOrElse {
            WidgetSnapshot(record.displayName, record.displayName, context.getString(R.string.widget_unreachable, record.displayName), problem = WidgetSnapshot.Problem.UNREACHABLE)
        }
    }

    /** A linked server's API, reached as the app reaches it; null when it can't be reached. */
    suspend fun apiFor(context: Context, serverId: String): SonoriumApi? {
        val app = context.applicationContext as SonoriumApp
        val record = app.servers.find(serverId) ?: return null
        val token = app.servers.token(serverId) ?: return null
        val pinned = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
        val reached = withTimeoutOrNull(REACH_MS) { app.linker.reach(pinned, record.hosts, record.httpsPort) } ?: return null
        if (reached.info.id != record.id) return null
        return SonoriumApi(pinned, SonoriumApi.baseUrl(reached.host, record.httpsPort), token)
    }

    /** A cold start (the app not open since): the player's theme as the app would choose it. */
    suspend fun ensureDeviceTheme(context: Context) {
        if (PhonePlayer.state.value.theme != null) return
        val app = context.applicationContext as SonoriumApp
        val themes = runCatching { if (app.entitlement.isPro.value) app.themeStore.list() else emptyList() }.getOrDefault(emptyList())
        PhonePlayer.sync(app, SampleTheme.with(app, themes))
    }

    private fun gone(context: Context, source: String) =
        WidgetSnapshot(source, context.getString(R.string.widget_gone), "", problem = WidgetSnapshot.Problem.GONE)

    fun deviceSnapshot(context: Context): WidgetSnapshot {
        val app = context.applicationContext as SonoriumApp
        val s = PhonePlayer.state.value
        val source = app.deviceRecord().displayName
        if (s.sampleEnded) return WidgetSnapshot(source, context.getString(R.string.widget_my_channel), context.getString(R.string.widget_sample_limit), problem = WidgetSnapshot.Problem.SAMPLE_LIMIT)
        val title = PhonePlayer.title(s) ?: context.getString(R.string.widget_my_channel)
        val sub = PhonePlayer.subtitle(s)
        return WidgetSnapshot(
            source, title, if (s.playing) sub else listOf(context.getString(R.string.widget_stopped), sub).filter { it.isNotBlank() }.joinToString(" · "),
            playing = s.playing,
            canPrevious = !s.playlistMode && (s.sequenceId != null || s.presets.isNotEmpty()),
            canNext = s.playlistMode || s.sequenceId != null || s.presets.isNotEmpty(),
        )
    }

    fun sessionSnapshot(context: Context, source: String, session: Session, theme: String?, preset: String?): WidgetSnapshot {
        val seq = session.sequence?.takeIf { it.running }
        val pl = session.playlist?.takeIf { it.running || session.playlistId != null }
        val what = when {
            pl != null && pl.entries != null && pl.position != null ->
                context.getString(R.string.widget_entry, pl.position, pl.entries, pl.themeName ?: theme.orEmpty())
            seq != null && seq.step != null && seq.steps != null ->
                listOfNotNull(theme, context.getString(R.string.widget_step, seq.step, seq.steps, seq.currentPresetName.orEmpty())).joinToString(" · ")
            else -> listOfNotNull(theme, preset ?: context.getString(R.string.widget_default_settings)).joinToString(" · ")
        }
        return WidgetSnapshot(
            source, session.name, if (session.isPlaying) what else listOf(context.getString(R.string.widget_stopped), what).filter { it.isNotBlank() }.joinToString(" · "),
            playing = session.isPlaying,
            // A server has Next for sequences and playlists, no Previous; presets go both ways
            canPrevious = seq == null && pl == null && session.themeId != null,
            canNext = session.themeId != null || pl != null,
        )
    }

    /** Draws [widgetId] with [s]. */
    fun draw(context: Context, widgetId: Int, s: WidgetSnapshot) {
        val manager = AppWidgetManager.getInstance(context)
        val info = manager.getAppWidgetInfo(widgetId) ?: return
        val large = info.provider.className == LargeChannelWidget::class.java.name
        val views = RemoteViews(context.packageName, if (large) R.layout.widget_large else R.layout.widget_small)
        views.setTextViewText(R.id.widget_title, s.title)
        views.setTextViewText(R.id.widget_status, s.status)
        views.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_widget_stop else R.drawable.ic_widget_play)
        views.setContentDescription(R.id.widget_play, context.getString(if (s.playing) R.string.widget_stop else R.string.widget_play))
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, widgetId))
        val problem = s.problem
        val action = when (problem) {
            WidgetSnapshot.Problem.UNREACHABLE -> WidgetActionActivity.RETRY
            WidgetSnapshot.Problem.SAMPLE_LIMIT -> WidgetActionActivity.UNLOCK
            WidgetSnapshot.Problem.NOT_SET, WidgetSnapshot.Problem.GONE -> WidgetActionActivity.CHOOSE
            null -> WidgetActionActivity.TOGGLE
        }
        views.setOnClickPendingIntent(R.id.widget_play, act(context, widgetId, action))
        if (large) {
            views.setTextViewText(R.id.widget_source, s.source)
            views.setViewVisibility(R.id.widget_controls, if (problem == null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_action, if (problem == null) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_action, when (problem) {
                WidgetSnapshot.Problem.UNREACHABLE -> context.getString(R.string.widget_retry)
                WidgetSnapshot.Problem.SAMPLE_LIMIT -> context.getString(R.string.widget_unlock)
                WidgetSnapshot.Problem.NOT_SET, WidgetSnapshot.Problem.GONE -> context.getString(R.string.widget_choose)
                null -> ""
            })
            views.setOnClickPendingIntent(R.id.widget_action, act(context, widgetId, action))
            views.setOnClickPendingIntent(R.id.widget_previous, act(context, widgetId, WidgetActionActivity.PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_next, act(context, widgetId, WidgetActionActivity.NEXT))
            views.setInt(R.id.widget_previous, "setAlpha", if (s.canPrevious) 255 else 90)
            views.setInt(R.id.widget_next, "setAlpha", if (s.canNext) 255 else 90)
        } else {
            // 2x1: the problem's action on the button (Retry, Unlock, Choose), its text as the state
            if (problem != null) views.setContentDescription(R.id.widget_play, s.status.ifEmpty { s.title })
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun act(context: Context, widgetId: Int, action: String): PendingIntent =
        PendingIntent.getActivity(
            context, widgetId * 10 + WidgetActionActivity.ACTIONS.indexOf(action),
            Intent(context, WidgetActionActivity::class.java).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context, widgetId * 10 + 9,
            Intent(context, app.sonorium.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REACH_MS = 8_000L
}

/** 2x1: a chosen channel's Play/Stop. */
class SmallChannelWidget : ChannelWidget()

/** 4x2: where, the channel, what plays, previous / Play / next. */
class LargeChannelWidget : ChannelWidget()

open class ChannelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        var left = ids.size
        if (left == 0) pending.finish()
        ids.forEach { id -> WidgetUpdater.update(context, id) { synchronized(this) { if (--left == 0) pending.finish() } } }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.remove(context, it) }
    }
}
