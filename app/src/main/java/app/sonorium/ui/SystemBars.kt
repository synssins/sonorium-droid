package app.sonorium.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge to edge with light status and navigation bar icons: the app is dark whatever the system's mode, so the
 * system's choice (dark icons in light mode) left them unreadable on the dark top bar.
 */
fun darkEdgeToEdge(activity: ComponentActivity) = activity.enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
)
