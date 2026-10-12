package app.sonorium.web

import android.content.Context
import androidx.webkit.WebViewCompat

/**
 * The server's web UI needs a recent WebView: its CSS uses :has() and container
 * queries (Chrome 105-106) and its scripts use ?. and ??. Phones get WebView
 * updates from the Play Store (Android 8 and 9 up to Chrome 138); one that was
 * never updated can't show the page.
 */
object WebViewSupport {
    const val MIN_MAJOR_VERSION = 106

    data class Provider(val packageName: String, val versionName: String, val major: Int)

    fun provider(context: Context): Provider? {
        val info = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull() ?: return null
        val version = info.versionName ?: return null
        val major = version.substringBefore('.').toIntOrNull() ?: return null
        return Provider(info.packageName, version, major)
    }

    /** The provider when it's too old for the web UI; null when it's fine or unknown. */
    fun outdated(context: Context): Provider? = provider(context)?.takeIf { it.major < MIN_MAJOR_VERSION }
}
