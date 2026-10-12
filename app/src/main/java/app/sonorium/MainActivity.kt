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
        private const val SPLASH_MS = 700L
    }

    private val linkModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A fresh start: the splash, then the main screen, always (with the last server, or none)
        val route = savedInstanceState == null && !handleLink(intent) && !intent.getBooleanExtra(EXTRA_SHOW_LIST, false)
        // The first-run wizard (a framework, off until approved: FirstRun.ENABLED)
        val wizard = route && app.sonorium.ui.wizard.FirstRun.shouldShow(this)
        if (route && !wizard) openMain()
        setContent {
            SonoriumTheme {
                when {
                    wizard -> app.sonorium.ui.wizard.FirstRunWizard(onFinish = {
                        app.sonorium.ui.wizard.FirstRun.markDone(this)
                        openMain()
                    })
                    route -> app.sonorium.ui.Splash()
                    else -> StartScreen(linkModel)
                }
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

    /** The main screen with the server used last, or with none (it still opens: Connect, local themes). */
    private fun openMain() {
        val servers = (application as SonoriumApp).servers
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val id = servers.currentId.first()?.takeIf { servers.find(it) != null }
            kotlinx.coroutines.delay((SPLASH_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            startActivity(NativeServerActivity.intent(this@MainActivity, id))
            finish()
        }
    }}
