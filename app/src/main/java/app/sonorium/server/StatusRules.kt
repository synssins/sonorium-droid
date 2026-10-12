package app.sonorium.server

import app.sonorium.net.NowChannel
import app.sonorium.net.NowPlaying
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** System › Status: the web UI's client-side timing (`npRender`, `npAt`; native-api.md Phase 2). */
object StatusRules {

    /** A poll answer and the local monotonic time (ms) it arrived. */
    data class Snap(val t: Long, val data: NowPlaying)

    /** `npTime(s)`: `h:mm:ss` when h > 0 else `m:ss`. */
    fun time(seconds: Double): String {
        val s = max(0.0, ceil(seconds)).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** The Now text: `{t}` → `—` without seconds, else the countdown minus [past]. */
    fun nowText(now: String, seconds: Double?, past: Double): String =
        if (seconds == null) now.replace("{t}", "—") else now.replace("{t}", time(seconds - past))

    /** The "plays" line under the channel name. */
    fun plays(c: NowChannel): String {
        val step = c.presetName?.takeIf { it.isNotEmpty() } ?: "Default settings"
        val what = c.sequence?.let { s ->
            if (s.step != null && s.step != 0) "${s.name} · Step ${s.step} of ${s.steps}: $step" else "${s.name} · $step"
        } ?: step
        return listOfNotNull(c.themeName, what).joinToString(" · ")
    }

    fun percent(level: Double?): String = level?.let { "${(it * 100).roundToInt()}%" } ?: "—"

    /** After each push: drop the oldest while the second-oldest is older than 20 s. */
    fun trim(snaps: MutableList<Snap>, now: Long) {
        while (snaps.size > 1 && snaps[1].t < now - 20_000) snaps.removeAt(0)
    }

    /** A channel as shown, with the seconds since its snapshot. */
    data class Shown(val channel: NowChannel, val past: Double)

    /**
     * Server mode: the newest snapshot. As heard: per channel, the snapshot from about its delay ago
     * (`max(buffer_seconds, localBuffer)`); [localBuffer] applies to the channel this device plays.
     */
    fun shown(snaps: List<Snap>, now: Long, heard: Boolean, localChannel: String?, localBuffer: Double): List<Shown> {
        val newest = snaps.lastOrNull() ?: return emptyList()
        if (!heard) return newest.data.channels.map { Shown(it, (now - newest.t) / 1000.0) }
        return newest.data.channels.map { c ->
            val delay = max(c.bufferSeconds, if (c.id == localChannel) localBuffer else 0.0)
            val target = now - (delay * 1000).toLong()
            var pick: Pair<Snap, NowChannel>? = null
            for (s in snaps) {
                val version = s.data.channels.firstOrNull { it.id == c.id } ?: continue
                if (pick == null) pick = s to version
                else if (s.t <= target) pick = s to version
                else break
            }
            val (snap, version) = pick ?: (newest to c)
            Shown(version, max(0.0, (target - snap.t) / 1000.0))
        }
    }
}
