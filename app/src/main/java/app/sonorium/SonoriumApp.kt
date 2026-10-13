package app.sonorium

import android.app.Application
import android.os.Build
import android.provider.Settings
import app.sonorium.data.ServerStore
import app.sonorium.link.Linker
import app.sonorium.net.PinnedTls
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

class SonoriumApp : Application() {
    /** One base client; per-server pinned clients derive from it and share its connection pool. */
    val baseClient: OkHttpClient by lazy { PinnedTls.baseClient() }
    val servers: ServerStore by lazy {
        // With Pro, this device is an entry of its own: its themes, playlists and playback
        ServerStore(this, device = entitlement.isPro.map { pro -> if (pro) deviceRecord() else null })
    }

    /** This device's entry in the list (its name as Android shows it). */
    fun deviceRecord(): app.sonorium.data.ServerRecord = app.sonorium.data.ServerRecord(
        id = DEVICE_ID, label = deviceInfo().name, fingerprint = "", hosts = emptyList(), httpPort = 0, httpsPort = 0,
        deviceId = "", device = true,
    )
    val linker: Linker by lazy { Linker(baseClient, deviceInfo()) }
    /** Themes kept on this phone (a folder the user picked). */
    val themeStore: app.sonorium.local.ThemeStore by lazy { app.sonorium.local.ThemeStore(this) }
    /** This device's own entry: the app's API answered in the app (its themes, playlists, playback). */
    val deviceApi: app.sonorium.local.DeviceApi by lazy { app.sonorium.local.DeviceApi(this) }
    /** The paid features; test builds grant them locally. */
    val entitlement: app.sonorium.local.ProEntitlement by lazy { app.sonorium.local.LocalProEntitlement(this) }

    companion object {
        /** The id of this device's own entry. */
        const val DEVICE_ID = "this-device"
    }

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
