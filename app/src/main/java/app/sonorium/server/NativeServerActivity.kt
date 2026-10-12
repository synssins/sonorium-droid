package app.sonorium.server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        val id = intent.getStringExtra(EXTRA_SERVER_ID) ?: return finish()
        model.open(id)
        askForNotifications()
        setContent {
            SonoriumTheme {
                ServerScreen(
                    model = model,
                    onServers = { showServers(this) },
                    onWebPage = { view -> startActivity(ServerActivity.intent(this, id, view)) },
                )
            }
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

        fun intent(context: Context, serverId: String): Intent =
            Intent(context, NativeServerActivity::class.java).putExtra(EXTRA_SERVER_ID, serverId)

        /** Back to the server list, closing the server screens above it. */
        fun showServers(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SHOW_LIST, true),
            )
        }
    }
}
