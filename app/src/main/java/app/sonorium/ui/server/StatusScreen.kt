package app.sonorium.ui.server

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sonorium.listen.ListenController
import app.sonorium.net.NowRow
import app.sonorium.server.ChannelRules
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.server.StatusRules
import app.sonorium.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun stateColor(state: String): Color = when (state) {
    "on" -> Palette.accent
    "fade" -> Palette.accentSecondary
    "wait" -> Palette.warning
    "rest" -> Palette.textSecondary
    else -> Palette.textMuted // off = Muted
}

/** System › Status (native-api.md Phase 2): polls `GET /now-playing` every 200 ms while shown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusPage(state: ServerState, model: ServerViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sonorium", Context.MODE_PRIVATE) }
    var heard by remember { mutableStateOf(prefs.getString("statusTiming", "server") == "heard") }
    val snaps = remember { mutableStateListOf<StatusRules.Snap>() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val collapsed = remember { mutableStateListOf<String>() }
    val listening by ListenController.state.collectAsState()
    var ema by remember { mutableStateOf<Double?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Poll only while the page is shown and the app is in front; 200 ms after each answer
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                model.nowPlaying()?.let { data ->
                    val t = SystemClock.elapsedRealtime()
                    snaps.add(StatusRules.Snap(t, data))
                    val list = snaps.toMutableList()
                    StatusRules.trim(list, t)
                    if (list.size != snaps.size) {
                        snaps.clear()
                        snaps.addAll(list)
                    }
                }
                // The local player's buffer, smoothed (ema*0.92 + x*0.08); unknown resets it
                ema = ListenController.bufferedAhead()?.let { x -> ema?.let { it * 0.92 + x * 0.08 } ?: x }
                delay(200)
            }
        }
    }
    // The countdowns move between polls
    LaunchedEffect(Unit) {
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }

    val local = listening?.takeIf { it.playing && it.serverId == state.record?.id && !it.personal }?.sessionId
    val shown = StatusRules.shown(snaps, now, heard, local, ema ?: 0.0)
    val count = snaps.lastOrNull()?.data?.channels?.size ?: 0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (narrow) 12.dp else 24.dp)) {
            item {
                PageHeader("Status", null, help = "status") {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Timing", color = Palette.textMuted, fontSize = 11.sp)
                        Segmented(listOf("Server", "As heard"), if (heard) 1 else 0, {
                            heard = it == 1
                            prefs.edit().putString("statusTiming", if (heard) "heard" else "server").apply()
                        })
                    }
                }
                Surface(
                    color = Palette.card, shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (count > 0) Palette.accent else Palette.border),
                    modifier = Modifier.padding(bottom = 12.dp).semantics { contentDescription = "Channels playing now" },
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("$count", color = if (count > 0) Palette.accent else Palette.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("Active Channels", color = Palette.textSecondary, fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Playing", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    CountBadge(count)
                }
                if (count > 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        listOf("on" to "Playing", "fade" to "Fading", "wait" to "Waiting", "rest" to "Paused", "off" to "Muted").forEach { (s, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(stateColor(s))
                                Spacer(Modifier.width(5.dp))
                                Text(label, color = Palette.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                } else if (snaps.isNotEmpty()) {
                    EmptyState("Nothing playing", null)
                }
            }
            items(shown, key = { it.channel.id }) { s ->
                val c = s.channel
                Surface(color = Palette.card, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Palette.border), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ChannelRules.themeIcon(c.themeId, state.theme(c.themeId)), fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(StatusRules.plays(c), color = Palette.textSecondary, fontSize = 13.sp, maxLines = 2)
                            }
                        }
                        // Where: the speakers, and this phone when it listens
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (c.speakersPlaying) {
                                val active = !c.speakersMuted
                                Icon(SIcons.Volume, null, tint = if (active) Palette.accent else Palette.textMuted, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(c.speakers.orEmpty(), color = if (active) Palette.text else Palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(12.dp))
                            }
                            if (c.id == local) {
                                Icon(SIcons.Headphones, null, tint = Palette.accent, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("This phone", color = Palette.text, fontSize = 12.sp)
                            }
                        }
                        c.sections.forEach { section ->
                            Text(section.title, color = Palette.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                            HorizontalDivider(color = Palette.border)
                            section.rows.forEach { row ->
                                val key = "${c.id}|${row.key}"
                                if (row.type == "group") {
                                    val closed = key in collapsed
                                    NowRowView(row, s.past, narrow, group = true, closed = closed) { if (closed) collapsed.remove(key) else collapsed.add(key) }
                                    if (!closed) row.tracks.forEach { NowRowView(it, s.past, narrow, indent = true) }
                                } else {
                                    NowRowView(row, s.past, narrow)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NowRowView(row: NowRow, past: Double, narrow: Boolean, group: Boolean = false, closed: Boolean = false, indent: Boolean = false, onToggle: (() -> Unit)? = null) {
    val now = StatusRules.nowText(row.now, row.seconds, past)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp)
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(start = if (indent) 22.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group) {
            Icon(if (closed) SIcons.ChevronRight else SIcons.ChevronDown, null, tint = Palette.textSecondary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Icon(SIcons.Folder, null, tint = Palette.textSecondary, modifier = Modifier.size(13.dp))
        } else {
            Box(Modifier.semantics { contentDescription = now }) { Dot(stateColor(row.state)) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, color = Palette.text, fontSize = 13.sp, fontWeight = if (group) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Phones: mode and Now under the name; wide screens: their own columns
            if (narrow) Text(listOf(row.mode, now).filter { it.isNotEmpty() }.joinToString(" · "), color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!narrow) {
            Text(row.mode, color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(120.dp))
            Text(now, color = Palette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(200.dp))
        }
        Level(row.level, row.band)
    }
}

@Composable
private fun Level(level: Double?, band: List<Double>?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Box(Modifier.width(56.dp).height(6.dp).background(Palette.backgroundSecondary, RoundedCornerShape(3.dp))) {
            if (band != null && band.size == 2) {
                val lo = band[0].toFloat().coerceIn(0f, 1f)
                val w = (band[1] - band[0]).toFloat().coerceIn(0f, 1f - lo)
                Row(Modifier.fillMaxHeight()) {
                    if (lo > 0f) Spacer(Modifier.weight(lo))
                    if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(Palette.accentSecondary.copy(alpha = 0.3f)))
                    if (1f - lo - w > 0.001f) Spacer(Modifier.weight(1f - lo - w))
                }
            }
            level?.let { l ->
                val f = l.toFloat().coerceIn(0f, 1f)
                if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Palette.accent, RoundedCornerShape(3.dp)))
            }
        }
        Text(StatusRules.percent(level), color = Palette.textSecondary, fontSize = 11.sp, modifier = Modifier.width(38.dp).padding(start = 6.dp))
    }
}
