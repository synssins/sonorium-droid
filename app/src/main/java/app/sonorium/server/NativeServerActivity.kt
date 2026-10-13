package app.sonorium.server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.sonorium.MainActivity
import app.sonorium.ui.SonoriumTheme
import app.sonorium.ui.server.ServerScreen
import app.sonorium.web.ServerActivity

/** One server, native (Phase 1). Pages not native yet open the web UI at that page. */
class NativeServerActivity : ComponentActivity() {
    private val model: ServerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No server: the main screen still opens (Connect button, browse, local themes)
        val id = intent.getStringExtra(EXTRA_SERVER_ID)
        model.open(id)
        pageFrom(intent)
        askForNotificationsOnFirstPlay()
        setContent {
            SonoriumTheme {
                ServerScreen(
                    model = model,
                    onServers = { showServers(this) },
                    onWebPage = { view -> id?.let { startActivity(ServerActivity.intent(this, it, view)) } },
                )
            }
        }
    }

    /** Volume buttons: with the setting on, they nudge the open server channel's speakers (item 15). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val delta = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return super.onKeyDown(keyCode, event)
        }
        if (model.nudgeVolume(delta)) {
            handledVolumeKey = keyCode
            return true
        }
        handledVolumeKey = null
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (handledVolumeKey == keyCode) {
            handledVolumeKey = null
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private var handledVolumeKey: Int? = null

    /** A notification of the phone's player or cast opens My Channel. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageFrom(intent)
    }

    private fun pageFrom(intent: Intent?) {
        if (intent?.getStringExtra(app.sonorium.engine.OpenApp.EXTRA_OPEN_PAGE) == app.sonorium.engine.OpenApp.PAGE_MY_CHANNEL) {
            model.requestedPage.value = app.sonorium.engine.OpenApp.PAGE_MY_CHANNEL
        }
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
    }

    override fun onPause() {
        model.onPause()
        super.onPause()
    }

    /**
     * Asked when something first plays (on the phone, Listen here or Cast), not on the first screen:
     * the playback controls then explain why.
     */
    private fun askForNotificationsOnFirstPlay() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                app.sonorium.engine.PhonePlayer.state, app.sonorium.listen.ListenController.state, app.sonorium.cast.CastController.state,
            ) { phone, listen, cast -> phone.playing || phone.preparing != null || listen != null || cast.speaker != null }
                .first { it }
            withResumed { askForNotifications() }
        }
    }

    /** Listen here's playback controls need notifications (Android 13+). */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        private const val EXTRA_SERVER_ID = "server_id"
        /** A volume button moves the channel's speaker volume this much (%). */
        private const val VOLUME_STEP = 5

        fun intent(context: Context, serverId: String?): Intent =
            Intent(context, NativeServerActivity::class.java).apply { serverId?.let { putExtra(EXTRA_SERVER_ID, it) } }

        /** Opens [serverId]'s main screen as the only screen (from the Servers page or a new link). */
        fun open(context: Context, serverId: String) {
            context.startActivity(intent(context, serverId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        /** The Servers page, over the main screen: Back returns to it. */
        fun showServers(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_LIST, true))
        }
    }
}
