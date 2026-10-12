package app.sonorium.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sonorium.SonoriumApp
import app.sonorium.server.NativeServerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tapping the playback or cast notification: back to the main screen that's already open (never a
 * second one on top), with the server used last when the app has to start again.
 */
object OpenApp {
    /** Which page the main screen should show (read by NativeServerActivity once wired): [PAGE_MY_CHANNEL]. */
    const val EXTRA_OPEN_PAGE = "app.sonorium.extra.OPEN_PAGE"
    const val PAGE_MY_CHANNEL = "personal"

    fun intent(context: Context, serverId: String?): Intent =
        NativeServerActivity.intent(context, serverId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_PAGE, PAGE_MY_CHANNEL)

    fun pendingIntent(context: Context, requestCode: Int, serverId: String? = null): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent(context, serverId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Adds the server used last to [requestCode]'s pending intent, in place, once it's read. */
    fun addLastServer(context: Context, requestCode: Int, scope: CoroutineScope) {
        val app = context.applicationContext as? SonoriumApp ?: return
        scope.launch {
            runCatching {
                val id = app.servers.currentId.first()?.takeIf { app.servers.find(it) != null }
                if (id != null) pendingIntent(app, requestCode, id)
            }.onFailure { Log.w("OpenApp", "Couldn't read the last server", it) }
        }
    }
}
