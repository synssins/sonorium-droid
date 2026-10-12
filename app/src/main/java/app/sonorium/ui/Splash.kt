package app.sonorium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.sonorium.R

/** The Sonorium splash: the logo on the app background, while the main screen gets ready. */
@Composable
fun Splash() {
    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.sonorium_logo), contentDescription = "Sonorium",
                contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}
