package app.sonorium.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sonorium.SonoriumApp
import app.sonorium.data.ServerRecord
import app.sonorium.listen.ListenController
import app.sonorium.server.NativeServerActivity
import app.sonorium.net.PinnedTls
import app.sonorium.net.SonoriumApi
import app.sonorium.ui.Card
import app.sonorium.ui.Palette
import app.sonorium.ui.PrimaryButton
import app.sonorium.ui.SecondaryButton
import app.sonorium.ui.SonoriumTheme
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/** One linked server's web UI, through the [LoopbackProxy]. */
class ServerActivity : ComponentActivity() {
    private sealed class Screen {
        data class Connecting(val label: String) : Screen()
        data class Failed(val message: String) : Screen()
        data object Ready : Screen()
        data class OldWebView(val provider: WebViewSupport.Provider) : Screen()
    }

    private val app get() = application as SonoriumApp
    private var screen by mutableStateOf<Screen>(Screen.Connecting(""))
    private var proxy: LoopbackProxy? = null
    private var webView: WebView? = null
    private var reply: JavaScriptReplyProxy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonoriumTheme {
                Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
                    when (val s = screen) {
                        is Screen.OldWebView -> Centered {
                            Card {
                                Text(
                                    "This phone's web view is too old for Sonorium (version ${s.provider.versionName}). " +
                                        "Update Android System WebView, then open Sonorium again.",
                                    color = Palette.text,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    SecondaryButton("Servers") { finish() }
                                    PrimaryButton("Update") { openStore(s.provider.packageName) }
                                }
                            }
                        }
                        Screen.Ready -> AndroidView(
                            factory = { webView!! },
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                        )
                        is Screen.Connecting -> Centered {
                            Card {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp)
                                    Text("Connecting to ${s.label}", color = Palette.text)
                                }
                            }
                        }
                        is Screen.Failed -> Centered {
                            Card {
                                Text(s.message, color = Palette.text)
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    SecondaryButton("Servers") { finish() }
                                    PrimaryButton("Try again") { connect() }
                                }
                            }
                        }
                    }
                }
            }
        }
        connect()
    }

    private fun connect() {
        val id = intent.getStringExtra(EXTRA_SERVER_ID) ?: return finish()
        WebViewSupport.outdated(this)?.let {
            screen = Screen.OldWebView(it)
            return
        }
        lifecycleScope.launch {
            val record = app.servers.find(id) ?: return@launch finish()
            screen = Screen.Connecting(record.displayName)
            val token = app.servers.token(id)
            if (token == null) {
                screen = Screen.Failed("This phone isn't linked to ${record.displayName}. Link it again.")
                return@launch
            }
            val pinned = PinnedTls.pinnedClient(app.baseClient, record.fingerprint)
            val reached = app.linker.reach(pinned, record.hosts, record.httpsPort)
            if (reached == null) {
                screen = Screen.Failed("Can't reach ${record.displayName}. Check that this phone is on the same network.")
                return@launch
            }
            if (reached.info.id != record.id) {
                screen = Screen.Failed("A different server answered at ${reached.host}.")
                return@launch
            }
            val updated = record.preferring(reached.host).copy(version = reached.info.version)
            if (updated != record) app.servers.update(updated)
            start(updated, reached.host, reached.info.ports.http, token)
        }
    }

    private suspend fun start(record: ServerRecord, host: String, httpPort: Int, token: String) {
        stopProxy()
        val apiClient = PinnedTls.pinnedClient(app.baseClient, record.fingerprint).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS).build()
        val streamClient = app.baseClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
        val streamBase = HttpUrl.Builder().scheme("http").host(host).port(httpPort).build()
        val started = withContext(Dispatchers.IO) {
            LoopbackProxy(
                api = apiClient,
                apiBase = SonoriumApi.baseUrl(host, record.httpsPort),
                streams = streamClient,
                streamBase = streamBase,
                token = token,
                secret = newSecret(),
                onUnlinked = { runOnUiThread { unlinked(record) } },
            ).also { it.start() }
        }
        proxy = started
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(started.origin, "${LoopbackProxy.COOKIE}=${started.secret}; Path=/; SameSite=Strict")
            flush()
        }
        val view = webView ?: createWebView().also { webView = it }
        installHost(view, started.origin, streamBase.toString().trimEnd('/'), record, host)
        screen = Screen.Ready
        // Load once the view is on screen with its real size.
        view.doOnLayoutWithSize { view.loadUrl("${started.origin}/") }
    }

    /** Runs [action] once the view is attached and has a non-zero size. */
    private fun WebView.doOnLayoutWithSize(action: () -> Unit) {
        if (isAttachedToWindow && width > 0 && height > 0) {
            action()
            return
        }
        addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: android.view.View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int,
            ) {
                if (r - l > 0 && b - t > 0) {
                    v.removeOnLayoutChangeListener(this)
                    v.post { action() }
                }
            }
        })
    }

    private fun openStore(packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
        val web = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        runCatching { startActivity(market) }.onFailure { runCatching { startActivity(web) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        // A fixed size, not wrap_content: with wrap_content WebView gives the page
        // 100vh = 0, and the menu and sidebar (sized from vh) get no height.
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.parseColor("#1A1A2E"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                openRequestedView(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val origin = proxy?.origin ?: return true
                if (request.url.toString().startsWith(origin)) return false
                // Anything else (help links, the website) opens in the browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
    }

    private var hostInstalledFor: String? = null
    private var viewOpened = false

    /** The page the native menu asked for: wait for the page's showView(), then call it once. */
    private fun openRequestedView(view: WebView, attempt: Int = 0) {
        val target = intent.getStringExtra(EXTRA_VIEW) ?: return
        if (viewOpened || attempt > 40 || !target.matches(Regex("[a-z-]+"))) return
        view.evaluateJavascript(
            "(function(){if(typeof showView==='function'&&typeof sessions!=='undefined'){showView('$target');return true}return false})()",
        ) { result ->
            if (result == "true") viewOpened = true else view.postDelayed({ openRequestedView(view, attempt + 1) }, 250)
        }
    }

    private fun installHost(view: WebView, origin: String, streamBase: String, record: ServerRecord, host: String) {
        if (hostInstalledFor == origin) return
        val origins = setOf(origin)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(view, HostScript.BRIDGE) }
            WebViewCompat.addWebMessageListener(view, HostScript.BRIDGE, origins) { _, message, _, _, replyProxy ->
                reply = replyProxy
                onPageMessage(message.data ?: return@addWebMessageListener)
            }
        }
        // Without these features (a very old WebView) the page still works through
        // the proxy, as it would in a browser.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, HostScript.build(streamBase, record.id, record.name, host), origins)
        }
        hostInstalledFor = origin
    }

    private fun onPageMessage(raw: String) {
        val message = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        fun text(key: String) = (message[key] as? JsonPrimitive)?.content
        fun number(key: String) = (message[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        when (text("type")) {
            // The page loaded (or reloaded): tell it what the app is playing.
            "ready" -> sendListening(ListenController.state.value)
            "listen" -> {
                val sessionId = text("sessionId") ?: return
                val url = text("url") ?: return
                askForNotifications()
                ListenController.listen(
                    this, serverId ?: return, sessionId, url,
                    name = text("name").orEmpty(), themeName = text("themeName").orEmpty(), volume = number("volume") ?: 100,
                )
            }
            "stopListening" -> text("sessionId")?.let { ListenController.stop(this, it) }
            "setListenVolume" -> {
                val sessionId = text("sessionId") ?: return
                ListenController.setVolume(this, sessionId, number("volume") ?: return)
            }
            // The page's Servers button: back to the server list.
            "showServers" -> NativeServerActivity.showServers(this)
            // The page is ready: open the page the native menu asked for.
            "ready" -> Unit
            "unlinked" -> lifecycleScope.launch {
                val id = intent.getStringExtra(EXTRA_SERVER_ID) ?: return@launch
                app.servers.find(id)?.let { unlinked(it) }
            }
        }
    }

    /** Tells the page something, e.g. `refresh` after the app comes back. */
    private fun sendToPage(type: String, data: JsonObject? = null) {
        val proxyReply = reply ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val message = buildJsonObject {
                put("type", type)
                data?.let { put("data", it) }
            }
            runCatching { proxyReply.postMessage(message.toString()) }
        }
    }

    /** The native player's state, so the card's Listen button follows the lock screen. */
    private var lastSentListening: ListenController.Listening? = null

    private fun sendListening(listening: ListenController.Listening?) {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val previous = lastSentListening
        lastSentListening = listening
        val target = listening?.takeIf { it.serverId == serverId }
            ?: previous?.takeIf { it.serverId == serverId }?.copy(playing = false)
            ?: return
        sendToPage("listening", buildJsonObject {
            put("sessionId", target.sessionId)
            put("playing", target.playing)
        })
    }

    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private var handledUnlink = false

    private fun unlinked(record: ServerRecord) {
        if (handledUnlink) return
        handledUnlink = true
        lifecycleScope.launch {
            app.servers.remove(record.id)
            stopProxy()
            screen = Screen.Failed("This phone was unlinked from ${record.displayName}. Link it again.")
        }
    }

    override fun onStart() {
        super.onStart()
        listenWatch = lifecycleScope.launch { ListenController.state.collect { sendListening(it) } }
    }

    override fun onStop() {
        listenWatch?.cancel()
        super.onStop()
    }

    private var listenWatch: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()
        sendToPage("refresh")
    }

    override fun onDestroy() {
        stopProxy()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun stopProxy() {
        proxy?.stop()
        proxy = null
    }

    private fun newSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val EXTRA_SERVER_ID = "server_id"

        private const val EXTRA_VIEW = "view"

        fun intent(context: Context, serverId: String, view: String? = null): Intent =
            Intent(context, ServerActivity::class.java).putExtra(EXTRA_SERVER_ID, serverId).putExtra(EXTRA_VIEW, view)
    }
}

@androidx.compose.runtime.Composable
private fun Centered(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) { content() }
    }
}
