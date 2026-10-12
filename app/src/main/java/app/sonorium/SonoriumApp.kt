package app.sonorium

import android.app.Application
import android.os.Build
import android.provider.Settings
import app.sonorium.data.ServerStore
import app.sonorium.link.Linker
import app.sonorium.net.PinnedTls
import okhttp3.OkHttpClient

class SonoriumApp : Application() {
    /** One base client; per-server pinned clients derive from it and share its connection pool. */
    val baseClient: OkHttpClient by lazy { PinnedTls.baseClient() }
    val servers: ServerStore by lazy { ServerStore(this) }
    val linker: Linker by lazy { Linker(baseClient, deviceInfo()) }
    /** Themes kept on this phone (a folder the user picked). */
    val themeStore: app.sonorium.local.ThemeStore by lazy { app.sonorium.local.ThemeStore(this) }
    /** The paid features; test builds grant them locally. */
    val entitlement: app.sonorium.local.ProEntitlement by lazy { app.sonorium.local.LocalProEntitlement(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    private fun deviceInfo(): Linker.DeviceInfo {
        val name = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return Linker.DeviceInfo(name = name, model = Build.MODEL)
    }
}
