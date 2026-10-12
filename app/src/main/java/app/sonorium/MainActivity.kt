package app.sonorium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.sonorium.server.NativeServerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.sonorium.ui.LinkViewModel
import app.sonorium.ui.SonoriumTheme
import app.sonorium.ui.StartScreen

class MainActivity : ComponentActivity() {
    companion object {
        /** From a server screen's Servers button: show the list, don't reopen the last server. */
        const val EXTRA_SHOW_LIST = "show_list"
    }

    private val linkModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && !handleLink(intent) && !intent.getBooleanExtra(EXTRA_SHOW_LIST, false)) openLastServer()
        setContent {
            SonoriumTheme {
                StartScreen(linkModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A Link App code opened from the camera or a browser (App Link or `sonorium://pair`). */
    private fun handleLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.dataString ?: return false
        linkModel.linkWithCode(data)
        return true
    }

    /** On a fresh start, go straight to the server used last; Back returns to the list. */
    private fun openLastServer() {
        val servers = (application as SonoriumApp).servers
        lifecycleScope.launch {
            val id = servers.currentId.first() ?: return@launch
            if (servers.find(id) != null) startActivity(NativeServerActivity.intent(this@MainActivity, id))
        }
    }
}
