package app.sonorium

import android.app.Application
import android.os.Build
import android.provider.Settings
import app.sonorium.data.ServerStore
import app.sonorium.link.Linker
import app.sonorium.net.PinnedTls
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class SonoriumApp : Application() {
    /** One base client; per-server pinned clients derive from it and share its connection pool. */
    val baseClient: OkHttpClient by lazy { PinnedTls.baseClient() }
    val servers: ServerStore by lazy {
        // With Pro, this device is an entry of its own: its themes, playlists and playback
        // This device is an entry of its own for everyone (its sample theme plays without anything unlocked)
        ServerStore(this, device = kotlinx.coroutines.flow.flowOf(Unit).map { deviceRecord() })
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
    /** The paid features: Google Play in release builds; test builds grant them locally. */
    val entitlement: app.sonorium.local.ProEntitlement by lazy {
        app.sonorium.local.Entitlements.create(this, appScope) { shown?.get() }
    }

    /** Work that lives as long as the app. */
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** The activity showing now (the store's purchase screen opens over it). */
    @Volatile private var shown: java.lang.ref.WeakReference<android.app.Activity>? = null
    private var checkedAt = 0L

    companion object {
        /** The id of this device's own entry. */
        const val DEVICE_ID = "this-device"
        /** "Stop My Channel when switching between server and device" (on by default; Audio Settings). */
        const val KEY_STOP_ON_SWITCH = "stopMyChannelOnSwitch"
    }

    override fun onCreate() {
        super.onCreate()
        app.sonorium.ui.Txt.init(this)
        // The purchases read again when the app comes back (a refund, a promo code, a payment that went through)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                shown = java.lang.ref.WeakReference(activity)
                val now = System.currentTimeMillis()
                if (now - checkedAt > 60_000) {
                    checkedAt = now
                    appScope.launch { runCatching { entitlement.refresh() } }
                }
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (shown?.get() === activity) shown = null
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        CrashLog.install(this)
        // "Stop My Channel when switching between server and device": watched here, not in a screen, because
        // choosing an entry in the Servers list opens a new main screen (the old one never sees the switch)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
            var last = servers.currentId.first()
            servers.currentId.collect { now ->
                val left = last
                last = now
                if (left != null && now != null && left != now) runCatching { leftEntry(left) }
            }
        }
        // Home-screen widgets on this device's own channel follow its player
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
            app.sonorium.engine.PhonePlayer.state
                .map { s -> listOf(s.playing, s.sampleEnded, app.sonorium.engine.PhonePlayer.title(s), app.sonorium.engine.PhonePlayer.subtitle(s)) }
                .distinctUntilChanged()
                .collect { runCatching { app.sonorium.widget.WidgetUpdater.updateDevice(this@SonoriumApp) } }
        }
    }

    /** The switcher left [left] (a server or this device): with the setting on, My Channel stops there. */
    private fun leftEntry(left: String) {
        if (!getSharedPreferences("sonorium", MODE_PRIVATE).getBoolean(KEY_STOP_ON_SWITCH, true)) return
        if (left == DEVICE_ID) {
            if (app.sonorium.engine.PhonePlayer.state.value.playing || app.sonorium.engine.PhonePlayerService.isRunning) app.sonorium.engine.PhonePlayer.stop(this)
            return
        }
        val now = app.sonorium.listen.ListenController.state.value ?: return
        if (now.serverId != left) return
        app.sonorium.listen.ListenController.stop(this, now.sessionId)
        // The server lets go of the slot at once (else when its hold time runs out)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val api = app.sonorium.widget.WidgetUpdater.apiFor(this@SonoriumApp, left) ?: return@runCatching
                if (now.personal) api.stopListeningMyChannel() else api.stopListening(now.sessionId)
            }
        }
    }

    private fun deviceInfo(): Linker.DeviceInfo {
        val name = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return Linker.DeviceInfo(name = name, model = Build.MODEL)
    }
}
