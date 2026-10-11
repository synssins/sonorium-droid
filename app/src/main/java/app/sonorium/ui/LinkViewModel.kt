package app.sonorium.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sonorium.SonoriumApp
import app.sonorium.data.ServerRecord
import app.sonorium.link.LinkOutcome
import app.sonorium.link.LinkUri
import app.sonorium.link.Reached
import app.sonorium.net.ApiException
import app.sonorium.net.PinnedTls
import app.sonorium.net.SecurityWords
import app.sonorium.net.SonoriumApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class LinkState {
    data object Idle : LinkState()
    data class Working(val message: String) : LinkState()
    /** A typed address answered: show its words, then ask for username and password. */
    data class ConfirmWords(
        val reached: Reached,
        val httpsPort: Int,
        val fingerprint: String,
        val words: List<String>,
        val hasUsers: Boolean,
    ) : LinkState()
    data class Done(val record: ServerRecord) : LinkState()
    data class Failed(val message: String) : LinkState()
}

class LinkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SonoriumApp

    val servers: StateFlow<List<ServerRecord>> =
        app.servers.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    fun reset() {
        _state.value = LinkState.Idle
    }

    /** A Link App code from an App Link, the scanner, or pasted text. */
    fun linkWithCode(raw: String) {
        when (val parsed = LinkUri.parse(raw)) {
            is LinkUri.Result.Invalid -> _state.value = LinkState.Failed("That isn't a Sonorium link code.")
            is LinkUri.Result.UnsupportedVersion -> _state.value = LinkState.Failed("Update the Sonorium app to use this code.")
            is LinkUri.Result.Ok -> viewModelScope.launch {
                val link = parsed.link
                _state.value = LinkState.Working(link.username?.let { "Linking as $it" } ?: "Linking")
                finish(app.linker.link(link))
            }
        }
    }

    /** First contact with a typed address (APP_API.md 1.3, 2.3). */
    fun connectToAddress(input: String) {
        val (host, port) = splitHostPort(input.trim()) ?: run {
            _state.value = LinkState.Failed("Enter an address such as 192.168.1.20 or sonorium.local.")
            return
        }
        viewModelScope.launch {
            _state.value = LinkState.Working("Connecting to $host")
            val (client, recorder) = PinnedTls.recordingClient(app.baseClient)
            val httpsPort = port ?: DEFAULT_HTTPS_PORT
            var reached = app.linker.reach(client, listOf(host), httpsPort)
            var usedPort = httpsPort
            if (reached == null && port == null) {
                // The HTTPS port may have been moved; the public /api/info on HTTP says where.
                val viaHttp = runCatching {
                    val url = okhttp3.HttpUrl.Builder().scheme("http").host(host).port(DEFAULT_HTTP_PORT).build()
                    SonoriumApi(app.baseClient, url).info()
                }.getOrNull()
                viaHttp?.ports?.https?.let { p ->
                    usedPort = p
                    reached = app.linker.reach(client, listOf(host), p)
                }
                if (viaHttp != null && viaHttp.ports.https == null) {
                    _state.value = LinkState.Failed("HTTPS is off on this server.")
                    return@launch
                }
            }
            val found = reached
            val fingerprint = recorder.fingerprint
            if (found == null || fingerprint == null) {
                _state.value = LinkState.Failed("No Sonorium answered at $host.")
                return@launch
            }
            _state.value = LinkState.ConfirmWords(
                reached = found,
                httpsPort = usedPort,
                fingerprint = fingerprint,
                words = SecurityWords.forFingerprint(fingerprint),
                hasUsers = found.info.hasUsers,
            )
        }
    }

    fun signIn(confirm: LinkState.ConfirmWords, username: String, password: String) {
        viewModelScope.launch {
            _state.value = LinkState.Working("Signing in")
            finish(app.linker.linkWithPassword(confirm.reached, confirm.httpsPort, confirm.fingerprint, username, password))
        }
    }

    fun select(record: ServerRecord) {
        viewModelScope.launch { app.servers.select(record.id) }
    }

    fun forget(record: ServerRecord) {
        viewModelScope.launch { app.servers.remove(record.id) }
    }

    private suspend fun finish(outcome: LinkOutcome) {
        _state.value = when (outcome) {
            is LinkOutcome.Linked -> {
                app.servers.save(outcome.record, outcome.token)
                LinkState.Done(outcome.record)
            }
            LinkOutcome.HttpsOff -> LinkState.Failed("HTTPS is off on this server.")
            LinkOutcome.Unreachable -> LinkState.Failed("Can't reach the server. Check that this phone is on the same network.")
            LinkOutcome.WrongServer -> LinkState.Failed("A different server answered at that address.")
            is LinkOutcome.TooOld -> LinkState.Failed(outcome.message)
            is LinkOutcome.TooNew -> LinkState.Failed("Update the Sonorium app to use this server.")
            is LinkOutcome.Refused -> LinkState.Failed(outcome.error.userMessage())
        }
    }

    private fun ApiException.userMessage(): String = when {
        status == 429 && retryAfterSeconds != null -> "$detail (${retryAfterSeconds}s)"
        else -> detail
    }

    companion object {
        const val DEFAULT_HTTPS_PORT = 8808
        const val DEFAULT_HTTP_PORT = 8008

        /** `host`, `host:port`, `[v6]:port`, or a pasted URL. */
        internal fun splitHostPort(input: String): Pair<String, Int?>? {
            if (input.isEmpty()) return null
            val withScheme = if ("://" in input) input else "https://$input"
            val url = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
            val host = url.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            return host to url.port.takeIf { it in 1..65535 }
        }
    }
}
