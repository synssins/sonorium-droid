package app.sonorium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.sonorium.ui.LinkViewModel
import app.sonorium.ui.SonoriumTheme
import app.sonorium.ui.StartScreen

class MainActivity : ComponentActivity() {
    private val linkModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
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
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        linkModel.linkWithCode(data)
    }
}
