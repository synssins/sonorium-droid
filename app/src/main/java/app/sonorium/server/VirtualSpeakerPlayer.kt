package app.sonorium.server

import android.content.Context
import android.util.Log
import app.sonorium.listen.ListenController
import app.sonorium.net.ApiException
import app.sonorium.net.SonoriumApi
import app.sonorium.net.VirtualSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The account's virtual speaker on a demo Sonorium (APP_API.md 9.6, plugin `virtual_speakers`): while this
 * device holds it, a channel playing on it plays here. The status poll is the heartbeat; the last client to
 * claim the speaker gets it. On a server without virtual speakers this never starts.
 */
class VirtualSpeakerPlayer(private val context: Context, private val scope: CoroutineScope) {
    /** [speaker] as last read; [here]: this device holds it and wants to hear it. */
    data class State(val speaker: VirtualSpeaker, val here: Boolean)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var job: Job? = null
    private var api: SonoriumApi? = null
    private var client = ""
    /** This device wants to hear the speaker (null: not decided yet, the first status decides). */
    @Volatile private var wanted: Boolean? = null

    /** Starts polling [api] for the server [serverId]; [url] makes the stream's address from its path. */
    fun start(api: SonoriumApi, serverId: String, client: String, url: (String) -> String?) {
        stop()
        this.api = api
        this.client = client
        job = scope.launch {
            while (isActive) {
                var st = try {
                    api.virtualSpeaker(client)
                } catch (e: ApiException) {
                    if (e.status == 404 || e.status == 403) break // no account here, or not allowed: nothing to play
                    null
                } catch (e: Exception) {
                    null
                }
                if (st == null) {
                    delay(POLL_MS)
                    continue
                }
                // First look: take it when nobody holds it (or this device did); later, only when this device
                // held it and nobody else has claimed it since (a server restart forgets claims)
                val want = wanted ?: (!st.listening || st.thisClient)
                if (want && !st.thisClient) {
                    if (!st.listening) st = runCatching { api.claimVirtualSpeaker(client) }.getOrDefault(st) else wanted = false
                }
                if (wanted == null) wanted = want
                val here = wanted == true && st.thisClient
                _state.value = State(st, here)
                val path = st.streamPath
                if (here && st.playing && path != null) {
                    val now = ListenController.state.value
                    if (now?.sessionId != st.id) {
                        url(path)?.let { u ->
                            ListenController.listen(context, serverId, st.id, u, st.name, st.channel?.name.orEmpty(), 100)
                        }
                    }
                } else if (ListenController.state.value?.sessionId == st.id) {
                    ListenController.stop(context, st.id)
                }
                delay((st.pollSeconds.coerceIn(1, 30) * 1000L))
            }
        }
    }

    /** Play here (claim it) or stop here (let it go). */
    fun toggle() {
        val api = api ?: return
        val now = _state.value
        scope.launch {
            runCatching {
                if (now?.here == true) {
                    wanted = false
                    ListenController.stop(context, now.speaker.id)
                    api.releaseVirtualSpeaker(client)
                    _state.value = now.copy(here = false)
                } else {
                    wanted = true
                    val st = api.claimVirtualSpeaker(client)
                    _state.value = State(st, st.thisClient)
                }
            }.onFailure { Log.w(TAG, "Virtual speaker change failed", it) }
        }
    }

    /** Stops polling (the server was left); the stream itself stops as the switch setting says. */
    fun stop() {
        job?.cancel()
        job = null
        api = null
        wanted = null
        _state.value = null
    }

    companion object {
        private const val TAG = "VirtualSpeaker"
        private const val POLL_MS = 3_000L

        /** This install's client id for the server: `app-` + the id the server gave it when linking (8–64 safe characters). */
        fun clientId(deviceId: String): String =
            ("app-" + deviceId.filter { it.isLetterOrDigit() || it in "._:-" }).take(64).padEnd(8, '0')
    }
}
