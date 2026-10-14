package app.sonorium

import app.sonorium.ui.darkEdgeToEdge
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.sonorium.ui.splash.SplashAnimation
import app.sonorium.ui.splash.SplashStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
        /** The system splash's icon box without an icon background (Android 12+): the start when it doesn't hand over. */
        private const val ICON_DP = 288f
        /** How long to wait for the system splash to hand over before starting anyway. */
        private const val HANDOVER_MS = 600L
    }

    private val linkModel: LinkViewModel by viewModels()

    /** Where the system splash showed the mark: the splash animation's first frame (null until it hands over). */
    private var splashStart by mutableStateOf<SplashStart?>(null)
    /** The splash animation showed its last frame. */
    private val splashDone = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The launcher icon (or a notification) while the app is open in the background: just bring it back
        // (R13; QA: a notification tap stacked a second main screen). Links and the Servers list still open.
        val isLink = intent?.action == Intent.ACTION_VIEW
        if (!isTaskRoot && !isLink && !intent.getBooleanExtra(EXTRA_SHOW_LIST, false)) {
            finish()
            return
        }
        darkEdgeToEdge(this)
        // A fresh start: the splash, then the main screen, always (with the last server, or none)
        val route = savedInstanceState == null && !handleLink(intent) && !intent.getBooleanExtra(EXTRA_SHOW_LIST, false)
        // First run: a guide over the main screen itself (coach marks), no separate wizard
        if (route) {
            handOver(systemSplash)
            openMain()
        }
        // Pro as last known (kept on the device, so it shows offline too)
        val pro = (application as SonoriumApp).entitlement.isPro.value
        setContent {
            SonoriumTheme {
                when {
                    route -> SplashAnimation(pro, splashStart) { splashDone.complete(Unit) }
                    else -> StartScreen(linkModel, onBack = if (isTaskRoot) null else ::finish)
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

    /**
     * The system splash (the mark alone) hands over to the splash animation, which starts on exactly its frame: the
     * mark's place and size are read from the system's icon. The system's splash goes once that frame is drawn.
     */
    private fun handOver(systemSplash: androidx.core.splashscreen.SplashScreen) {
        systemSplash.setOnExitAnimationListener { provider ->
            val icon = runCatching { provider.iconView }.getOrNull()
            if (icon == null || icon.width == 0) {
                provider.remove()
                return@setOnExitAnimationListener
            }
            val at = IntArray(2)
            icon.getLocationInWindow(at)
            splashStart = SplashStart(at[0] + icon.width / 2f, at[1] + icon.height / 2f, icon.width.toFloat())
            // Two frames: the animation's first one is drawn under the system's splash, then that goes
            icon.postOnAnimation { icon.postOnAnimation { provider.remove() } }
        }
        lifecycleScope.launch {
            delay(HANDOVER_MS)
            if (splashStart == null) {
                val d = window.decorView
                splashStart = SplashStart(d.width / 2f, d.height / 2f, ICON_DP * resources.displayMetrics.density)
            }
        }
    }

    /**
     * The main screen with the server used last, or with none (it still opens: Connect, local themes), once the
     * splash has shown its last frame (held there while the app is still getting ready).
     */
    private fun openMain() {
        val servers = (application as SonoriumApp).servers
        lifecycleScope.launch {
            // The one used last; else the first in the list (this device's own entry, with Pro)
            val id = servers.currentId.first()?.takeIf { servers.find(it) != null } ?: servers.servers.first().firstOrNull()?.id
            splashDone.await()
            startActivity(NativeServerActivity.intent(this@MainActivity, id))
            finish()
        }
    }}
