package app.sonorium.ui.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import app.sonorium.R
import app.sonorium.listen.ListenController
import app.sonorium.net.Session
import app.sonorium.server.ServerState
import app.sonorium.server.ServerViewModel
import app.sonorium.ui.Palette
import kotlinx.coroutines.launch

/** A menu item: native pages have a screen here; the rest open the web UI at that page for now. */
enum class Page(val title: String, val icon: ImageVector, val webView: String?, val perm: String? = null) {
    Channels("Channels", SIcons.Grid, null),
    Personal("My channel", SIcons.Headphones, null),
    Playlists("Playlists", SIcons.Playlist, null),
    Themes("Themes", SIcons.Music, null),
    Speakers("Speakers", SIcons.Speakers, null, "sp.set"),
    Groups("Speaker Groups", SIcons.Speakers, null, "sp.set"),
    Spaces("Floors & Areas", SIcons.Grid, null, "st.change"),
    Audio("Audio Settings", SIcons.Volume, "settings-audio", "st.change"),
    Advanced("Advanced", SIcons.Settings, "settings-advanced", "st.change"),
    Logs("Logs", SIcons.Activity, "settings-logs", "st.view"),
    Plugins("Plugins", SIcons.Settings, "settings-plugins", "admin.plugins"),
    Connection("Connection", SIcons.Link, "settings-connection", "admin.connection"),
    Status("Status", SIcons.Activity, null),
}

private val SETTINGS = listOf(Page.Speakers, Page.Groups, Page.Spaces, Page.Audio, Page.Advanced, Page.Logs, Page.Plugins, Page.Connection)

@Composable
fun ServerScreen(
    model: ServerViewModel,
    onServers: () -> Unit,
    onWebPage: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    val toast by model.toasts.collectAsState()
    val listenVolume by model.listenVolume.collectAsState()
    val listening by ListenController.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.Channels) }
    var editing by remember { mutableStateOf<Session?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Link channels (Zones, admin): null = closed; a link = change it; NEW_LINK = a new one
    var linking by remember { mutableStateOf<app.sonorium.net.ChannelLink?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun open(p: Page) {
        scope.launch { drawer.close() }
        if (p.webView != null) onWebPage(p.webView) else page = p
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalToast provides toast) {
    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Tablets in landscape keep the menu open as a sidebar; everything else slides it in
            val permanent = maxWidth >= 1000.dp && maxHeight >= 600.dp
            val panelWidth = minOf(320.dp, maxWidth * 0.85f)
            val menu: @Composable () -> Unit = { Menu(state, page, permanent, ::open, onServers) }
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        state = state,
                        showMenuButton = !permanent,
                        onMenu = { scope.launch { drawer.open() } },
                        onServers = onServers,
                        personalOn = listening?.let { it.personal && it.playing && it.serverId == state.record?.id } == true,
                        onPersonal = { page = Page.Personal },
                    )
                    Box(Modifier.weight(1f)) {
                        when {
                            state.connecting && state.sessions.isEmpty() -> Centered {
                                CircularProgressIndicator(color = Palette.accent)
                                Spacer(Modifier.height(12.dp))
                                Text("Connecting to ${state.record?.displayName.orEmpty()}", color = Palette.textSecondary)
                            }
                            state.failure != null -> Centered {
                                Text(state.failure.orEmpty(), color = Palette.text)
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.OutlinedButton(onClick = onServers, border = BorderStroke(1.dp, Palette.border)) {
                                        Text("Servers", color = Palette.text)
                                    }
                                    if (!state.unlinked) {
                                        Button(onClick = { model.connect() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar)) {
                                            Text("Try again")
                                        }
                                    }
                                }
                            }
                            page == Page.Personal -> PersonalChannelScreen(model, state, listenVolume, listening)
                            page == Page.Playlists -> PlaylistsPage(state, model)
                            page == Page.Groups -> GroupsPage(state, model)
                            page == Page.Speakers -> SpeakersPage(state, model)
                            page == Page.Spaces -> SpacesPage(state, model)
                            page == Page.Status -> StatusPage(state, model)
                            // The Theme Editor is Phase 3: Edit opens the web UI's Themes page for now
                            page == Page.Themes -> ThemesPage(state, model, onEdit = { onWebPage("themes") })
                            else -> ChannelsPage(
                                state = state,
                                model = model,
                                listenVolume = listenVolume,
                                onNew = { creating = true },
                                onEdit = { editing = it },
                                onLink = { linking = it ?: NEW_LINK },
                            )
                        }
                        ToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                }
            }
            if (permanent) {
                Row(Modifier.fillMaxSize()) {
                    Surface(color = Palette.bar, modifier = Modifier.width(260.dp).fillMaxHeight()) { menu() }
                    Box(Modifier.weight(1f)) { content() }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = Palette.bar, modifier = Modifier.width(panelWidth)) { menu() }
                    },
                    content = content,
                )
            }
        }
    }

    if (creating || editing != null) {
        ChannelEditor(
            edit = editing,
            state = state,
            onLoadPresets = { id -> scope.launch { model.loadPresets(id) } },
            onError = { model.toast(it, error = true) },
            onSave = { body, playlistId, clearFirst, zoneChanges ->
                model.saveChannel(editing, body, playlistId, clearFirst, zoneChanges) { creating = false; editing = null }
            },
            onClose = { creating = false; editing = null },
        )
    }
    linking?.let { link ->
        LinkChannelsDialog(
            edit = link.takeIf { it !== NEW_LINK },
            state = state,
            onLoadPresets = model::loadPresetsAsync,
            onSubmit = { id, body, onError, onDone -> model.saveLink(id, body, onError, onDone) },
            onClose = { linking = null },
        )
    }
    }
}

@Composable
private fun TopBar(
    state: ServerState,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onServers: () -> Unit,
    personalOn: Boolean,
    onPersonal: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Palette.bar).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenuButton) {
            IconBox(SIcons.Menu, "Menu", onMenu, tint = Palette.text, size = 44)
            Spacer(Modifier.width(10.dp))
        }
        Image(
            painterResource(R.drawable.sonorium_logo), contentDescription = "Sonorium",
            contentScale = ContentScale.Fit, modifier = Modifier.height(26.dp).widthIn(max = 110.dp),
        )
        Spacer(Modifier.weight(1f))
        // The server button: the name given in the app, else the address (1.8)
        val label = state.record?.displayName.orEmpty()
        Surface(
            color = Palette.accent,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 40.dp).widthIn(max = 200.dp).semantics { contentDescription = "Servers: $label" }.clickable(onClick = onServers),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Server, null, tint = Palette.bar, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = Palette.bar, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The headphone button: the account's personal channel (native only), when the server has it
        if (state.personalChannel) {
            Spacer(Modifier.width(8.dp))
            IconBox(
                SIcons.Headphones, "My channel", onPersonal,
                tint = if (personalOn) Palette.bar else Palette.accent,
                background = if (personalOn) Palette.accent else Palette.backgroundSecondary,
                border = Palette.accent,
                size = 40,
            )
        }
    }
}

@Composable
private fun Menu(state: ServerState, current: Page, permanent: Boolean, onOpen: (Page) -> Unit, onServers: () -> Unit) {
    val perms = state.me?.perms.orEmpty().toSet()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (permanent) {
            Image(
                painterResource(R.drawable.sonorium_logo), contentDescription = "Sonorium",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).height(34.dp),
            )
            HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 8.dp))
        }
        Section("Playback")
        MenuItem(Page.Channels, current, onOpen, badge = state.playingCount.takeIf { it > 0 })
        if (state.personalChannel) MenuItem(Page.Personal, current, onOpen)
        MenuItem(Page.Playlists, current, onOpen)
        Section("Configuration")
        MenuItem(Page.Themes, current, onOpen)
        val settings = SETTINGS.filter { it.perm == null || it.perm in perms }
        MenuRow(SIcons.Settings, "Settings", selected = false, trailing = if (settingsOpen) SIcons.ChevronDown else SIcons.ChevronRight) { settingsOpen = !settingsOpen }
        if (settingsOpen) {
            settings.forEach { MenuItem(it, current, onOpen, indent = true) }
            MenuRow(SIcons.Server, "Servers", selected = false, indent = true, onClick = onServers)
        }
        Section("System")
        MenuItem(Page.Status, current, onOpen)
        Spacer(Modifier.weight(1f, fill = false))
        HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.version.isNotEmpty()) Text("v${state.version}", color = Palette.textMuted, fontSize = 12.sp)
            if (state.installLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.background(Palette.card, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(state.installLabel, color = Palette.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), color = Palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun MenuItem(page: Page, current: Page, onOpen: (Page) -> Unit, indent: Boolean = false, badge: Int? = null) {
    MenuRow(page.icon, page.title, selected = page == current && page.webView == null, indent = indent, badge = badge) { onOpen(page) }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    indent: Boolean = false,
    badge: Int? = null,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) Palette.card else Palette.bar)
            .clickable(onClick = onClick)
            .padding(start = if (indent) 44.dp else 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Palette.accent else Palette.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = if (selected) Palette.accent else Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        badge?.let { CountBadge(it) }
        trailing?.let { Icon(it, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        content()
    }
}

@Composable
private fun ChannelsPage(
    state: ServerState,
    model: ServerViewModel,
    listenVolume: Int,
    onNew: () -> Unit,
    onEdit: (Session) -> Unit,
    onLink: (app.sonorium.net.ChannelLink?) -> Unit,
) {
    val listening by ListenController.state.collectAsState()
    val expanded by model.expanded.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = remember(model) {
        CardActions(
            togglePlay = model::togglePlay,
            setTheme = model::setTheme,
            setPreset = model::setPreset,
            setVolume = model::setVolume,
            toggleMuted = model::toggleSpeakersMuted,
            toggleListen = { model.toggleListen(it) },
            setListenVolume = model::setListenVolume,
            next = model::next,
            themeMode = model::enterThemeMode,
            playlistMode = model::enterPlaylistMode,
            pickPlaylist = { s, id -> state.playlists.firstOrNull { it.id == id }?.let { model.pickPlaylist(s, it) } },
            edit = onEdit,
            delete = model::delete,
            stopCasting = model::stopCasting,
            leaveLink = model::leaveLink,
            rejoinLink = model::rejoinLink,
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (maxWidth < 600.dp) 12.dp else 24.dp
        // Phones: every channel collapsed until opened; wide screens: open until collapsed
        val openByDefault = maxWidth >= 840.dp
        val narrow = maxWidth < 600.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = side, end = side, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Page header: Channels [count] + New Channel
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Channels", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    CountBadge(state.sessions.size)
                    Spacer(Modifier.weight(1f))
                    if (state.zones?.admin == true && narrow) {
                        // Phones: icon only, so the header fits next to New Channel
                        IconBox(SIcons.Link, "Link channels", { onLink(null) }, tint = Palette.text, size = 44)
                        Spacer(Modifier.width(8.dp))
                    } else if (state.zones?.admin == true) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onLink(null) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Palette.border),
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) {
                            Icon(SIcons.Link, null, tint = Palette.text, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Link channels", color = Palette.text)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onNew,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Icon(SIcons.Plus, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Channel", fontWeight = FontWeight.Medium)
                    }
                }
            }
            // Link bars (Zones): one per link, above the cards (1.6)
            state.zones?.links?.forEach { link ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "link-${link.id}") {
                    LinkBar(state, link, admin = state.zones.admin, onChange = { onLink(link) }, onUnlink = { model.unlink(link.id) })
                }
            }
            if (state.sessionsLoaded && state.sessions.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(SIcons.Music, null, tint = Palette.textMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No channels yet", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Create a new channel to start playing ambient sounds", color = Palette.textSecondary, fontSize = 14.sp)
                    }
                }
            }
            items(state.sessions, key = { it.id }) { session ->
                val open = expanded[session.id] ?: openByDefault
                Box(contentAlignment = Alignment.TopCenter) {
                    if (!open) {
                        ChannelRow(
                            session = session,
                            state = state,
                            onExpand = { model.setExpanded(session.id, true) },
                            onTogglePlay = { model.togglePlay(session) },
                            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                        )
                        return@Box
                    }
                    ChannelCard(
                        session = session,
                        state = state,
                        listening = listening?.let { it.serverId == state.record?.id && it.sessionId == session.id && it.playing && !it.personal } == true,
                        listenVolume = listenVolume,
                        actions = actions,
                        onCountdownZero = { scope.launch { model.refreshSessions() } },
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                        onCollapse = { model.setExpanded(session.id, false) },
                    )
                }
            }
        }
    }
}

private val NEW_LINK = app.sonorium.net.ChannelLink(id = "")

@Composable
private fun LinkBar(state: ServerState, link: app.sonorium.net.ChannelLink, admin: Boolean, onChange: () -> Unit, onUnlink: () -> Unit) {
    val plays = link.playlistId?.let { state.playlist(it)?.name ?: "Playlist" } ?: state.theme(link.themeId)?.name.orEmpty()
    fun names(ids: List<String>) = ids.mapNotNull { id -> state.sessions.firstOrNull { it.id == id }?.name }.joinToString(", ")
    Surface(color = Palette.accentSecondary.copy(alpha = 0.1f), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Palette.accentSecondary.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SIcons.Link, null, tint = Palette.accentSecondary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) { append(link.name) }
                        if (plays.isNotEmpty()) append(" · $plays")
                    },
                    color = Palette.text, fontSize = 14.sp,
                )
                val left = names(link.left)
                Text(names(link.channels) + if (left.isNotEmpty()) " · $left left" else "", color = Palette.textSecondary, fontSize = 12.sp)
            }
            if (admin) {
                androidx.compose.material3.TextButton(onClick = onChange) { Text("Change", color = Palette.text) }
                androidx.compose.material3.TextButton(onClick = onUnlink) { Text("Unlink", color = Palette.text) }
            }
        }
    }
}
