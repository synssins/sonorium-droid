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
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset
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
import app.sonorium.ui.coach.coachTarget
import kotlinx.coroutines.launch

/** A menu item: native pages have a screen here; the rest open the web UI at that page for now. */
enum class Page(val title: String, val icon: ImageVector, val webView: String?, val perm: String? = null, val cap: String? = null) {
    Channels("Channels", SIcons.Grid, null),
    Personal("My Channel", SIcons.Headphones, null),
    Playlists("Playlists", SIcons.Playlist, null),
    Themes("Themes", SIcons.Music, null),
    Speakers("Speakers", SIcons.Speakers, null, "sp.set", "speakers"),
    Groups("Speaker Groups", SIcons.Speakers, null, "sp.set", "speaker_groups"),
    Spaces("Floors & Areas", SIcons.Grid, null, "st.change", "spaces"),
    Audio("Audio Settings", SIcons.Volume, null, "st.change", "audio_settings"),
    Backups("Backups", SIcons.Upload, null, cap = "theme_backup"),
    Advanced("Advanced", SIcons.Settings, null, "st.change", "advanced"),
    Logs("Logs", SIcons.Activity, null, "st.view", "logs"),
    Plugins("Plugins", SIcons.Settings, null, "admin.plugins", "plugins"),
    Connection("Connection", SIcons.Link, null, "admin.connection", "connection"),
    Status("Status", SIcons.Activity, null, cap = "status"),
}

/** Every control in the top bar is this tall (dp): Standard, so they line up (ui-standards: top bar 56, controls 48). */
private const val TOP_BAR_CONTROL = app.sonorium.ui.UiSize.STANDARD

// The web UI's order (slots.js): Connection, Audio Settings, Floors & Areas, Speakers, Speaker Groups, Plugins, Logs, Advanced
private val SETTINGS = listOf(Page.Connection, Page.Audio, Page.Backups, Page.Spaces, Page.Speakers, Page.Groups, Page.Plugins, Page.Logs, Page.Advanced)

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
    val themeEditor by model.editor.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.Channels) }
    val localFolder by model.localFolder.collectAsState()
    // This device's entry: its themes folder decides what Themes shows
    LaunchedEffect(state.onDevice) { if (state.onDevice) model.refreshLocalThemes() }
    // A server that can't be reached: Themes opens on the themes on this phone (R19)
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

    // Asked from outside (a notification): My Channel
    val requested by model.requestedPage.collectAsState()
    LaunchedEffect(requested) {
        if (requested == app.sonorium.engine.OpenApp.PAGE_MY_CHANNEL) {
            page = Page.Personal
            model.requestedPage.value = null
        }
    }
    // The volume buttons act on a channel only while Channels shows (item 15)
    LaunchedEffect(page) { model.channelsShown = page == Page.Channels }
    // A page this account can't open (e.g. restored after Android closed the app): back to Channels (QA 20)
    LaunchedEffect(page, state.me) {
        val perm = page.perm
        if ((perm != null && state.me != null && perm !in state.me?.perms.orEmpty()) || !state.offers(page.cap)) page = Page.Channels
    }
    // Back from any other page returns to Channels (R6), not to the Servers list
    androidx.activity.compose.BackHandler(enabled = page != Page.Channels) { page = Page.Channels }
    // Registered later, so it goes first: Back with the menu open just closes the menu (R10)
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen || drawer.targetValue == DrawerValue.Open) { scope.launch { drawer.close() } }
    val folderPicker = rememberOwnThemesFolderPicker(model)
    val themeImport = rememberOwnThemeImport(model, folderPicker)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val coach = remember { app.sonorium.ui.coach.CoachState(appContext) }
    val serverButtonRight = remember { mutableStateOf<Float?>(null) }
    // First run: the guide over the main screen, once the first page has settled (item 12)
    val linkedServers by model.servers.collectAsState(initial = emptyList())
    val firstRun = app.sonorium.ui.coach.Guides.firstRun(hasServer = linkedServers.any { !it.device })
    LaunchedEffect(state.connecting, page) {
        // A real first run only: not after an update (Chris)
        if (!state.connecting && page == Page.Channels && !coach.upgraded) {
            kotlinx.coroutines.delay(800)
            coach.showOnce(firstRun)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalToast provides toast, LocalThemesFolderPicker provides folderPicker, LocalThemeImport provides themeImport, LocalOnDevice provides state.onDevice, app.sonorium.ui.coach.LocalCoach provides coach, LocalServerButtonRight provides serverButtonRight) {
    themeEditor?.let { ThemeEditorScreen(it, model, state) }
    // An imported zip: the user's own file, so asked before it's deleted
    model.importProblem.collectAsState().value?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { model.importProblemSeen() },
            containerColor = Palette.card,
            text = { Text(text, color = Palette.text, fontSize = 14.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { model.importProblemSeen() }) { Text("OK", color = Palette.accent) } },
        )
    }
    model.zipToDelete.collectAsState().value?.let { (_, name) ->
        ConfirmDialog(
            title = "Delete “$name”?",
            sub = "The theme is imported. Its music stays; only the zip file is deleted.",
            action = "Delete",
            onConfirm = { model.answerZip(true) },
            onDismiss = { model.answerZip(false) },
        )
    }
    // What goes beyond the sample on this device's own entry: the unlock screen
    val unlockAsked by model.unlockAsked.collectAsState()
    if (unlockAsked) UnlockScreen(model, onClose = model::unlockClosed, onUnlocked = { model.unlockClosed(); model.refreshLocalThemes() })
    Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Tablets in landscape keep the menu open as a sidebar; everything else slides it in
            // From the window's size, not the space left by the keyboard: the layout (and the dialogs a page has
            // open) must not change when a text field brings the keyboard up (tablet: forms closed themselves)
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val permanent = config.screenWidthDp >= 1000 && config.screenHeightDp >= 600
            val panelWidth = minOf(320.dp, maxWidth * 0.85f)
            val menu: @Composable () -> Unit = { Menu(state, page, permanent, ::open, onServers, onPluginPage = { v -> scope.launch { drawer.close() }; onWebPage(v) }) }
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        state = state,
                        showMenuButton = !permanent,
                        onMenu = { scope.launch { drawer.open() } },
                        onServers = onServers,
                    )
                    // A server that can't be reached: a line above the page, which stays browsable
                    val serverPage = page != Page.Personal && page != Page.Themes
                    state.failure?.takeIf { !serverPage }?.let { failure ->
                        Row(
                            Modifier.fillMaxWidth().background(Palette.backgroundSecondary).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(failure, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(
                                onClick = { if (state.unlinked) onServers() else model.connect() },
                                modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp),
                            ) { Text(if (state.unlinked) "Servers" else "Try again", color = Palette.accent) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when {
                            serverPage && state.connecting && state.sessions.isEmpty() -> Centered {
                                CircularProgressIndicator(color = Palette.accent)
                                Spacer(Modifier.height(12.dp))
                                Text(state.record?.let { "Connecting to ${it.displayName}…" } ?: "Connecting…", color = Palette.textSecondary)
                            }
                            // Pages that show the server's data: nothing stale when there's no server or it can't be
                            // reached, only why and what to do (QA: no old data presented as live)
                            serverPage && (state.record == null || state.failure != null) -> NoConnection(
                                state, page,
                                onServers = onServers,
                                onRetry = { model.connect() },
                                onPhone = { if (!model.useDevice()) page = Page.Personal },
                            )
                            page == Page.Personal -> PersonalChannelScreen(model, state, listenVolume, listening, onPhoneThemes = { if (!model.useDevice()) page = Page.Themes })
                            page == Page.Playlists -> PlaylistsPage(state, model)
                            page == Page.Groups -> GroupsPage(state, model)
                            page == Page.Speakers -> SpeakersPage(state, model)
                            page == Page.Spaces -> SpacesPage(state, model)
                            page == Page.Status -> StatusPage(state, model)
                            page == Page.Connection -> ConnectionPage(state, model)
                            page == Page.Audio -> AudioPage(model)
                            page == Page.Backups -> BackupsPage(state, model)
                            page == Page.Plugins -> PluginsPage(model)
                            page == Page.Logs -> LogsPage(model)
                            page == Page.Advanced -> AdvancedSettingsPage(state, model)
                            // Themes: the server's, or the ones on this phone (the only ones without a server)
                            // Themes: the active entry's (this device's need its themes folder first)
                            page == Page.Themes && state.record == null ->
                                LocalThemesPage(model, hasServer = false)
                            page == Page.Themes && state.failure != null -> NoConnection(state, page, onServers = onServers, onRetry = { model.connect() }, onPhone = { model.useDevice() })
                            page == Page.Themes -> Column(Modifier.fillMaxSize()) {
                                // A theme that just arrived on this device getting ready, with its progress
                                if (state.onDevice) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ReadyingLine() }
                                // Unlocked but no themes folder yet: the sample still shows, with the way to a folder (R6)
                                if (state.onDevice && model.entitlement.isPro.collectAsState().value && localFolder != app.sonorium.local.ThemeStore.FolderState.OK) {
                                    ChooseFolderLine(missing = localFolder == app.sonorium.local.ThemeStore.FolderState.MISSING) { folderPicker.ask(changing = false, then = null) }
                                }
                                ThemesPage(state, model, onEdit = { model.openThemeEditor(it) })
                            }
                            else -> ChannelsPage(
                                state = state,
                                model = model,
                                listenVolume = listenVolume,
                                onNew = { if (model.reachableOrSay()) creating = true },
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
    // Over everything: a guide, when one shows
    app.sonorium.ui.coach.CoachOverlay(coach)
    }
}

@Composable
private fun TopBar(
    state: ServerState,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onServers: () -> Unit,
) {
    val serverButtonRight = LocalServerButtonRight.current
    Row(
        // Tagged for the device tests (no headphones button in any state: Chris, item 11)
        Modifier.fillMaxWidth().height(56.dp).background(Palette.bar).semantics { testTagsAsResourceId = true }.testTag("top-bar").padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenuButton) {
            IconBox(SIcons.Menu, "Menu", onMenu, tint = Palette.text, size = TOP_BAR_CONTROL)
            Spacer(Modifier.width(10.dp))
        }
        Image(
            painterResource(R.drawable.sonorium_logo), contentDescription = "Sonorium",
            contentScale = ContentScale.Fit, modifier = Modifier.height(26.dp).widthIn(max = 110.dp),
        )
        Spacer(Modifier.weight(1f))
        // The server button: the name given in the app (else the address), with its connection status;
        // "Connect" when there's none (QA: a removed or unreachable server never looks connected)
        val record = state.record?.takeIf { !state.unlinked }
        val label = record?.displayName ?: "Connect"
        val status = when {
            record == null -> null
            state.connecting -> "connecting"
            state.failure != null -> "can't reach"
            else -> "connected"
        }
        val live = status == "connected"
        Surface(
            color = if (live || record == null) Palette.accent else Palette.backgroundSecondary,
            shape = RoundedCornerShape(8.dp),
            border = if (live || record == null) null else androidx.compose.foundation.BorderStroke(1.dp, Palette.border),
            modifier = Modifier.height(TOP_BAR_CONTROL.dp).widthIn(max = 200.dp)
                .coachTarget(app.sonorium.ui.coach.Targets.SERVERS)
                .onGloballyPositioned { c -> serverButtonRight?.value = c.boundsInRoot().right }
                .semantics { contentDescription = if (record?.device == true) "$label, this device" else "Servers: $label" + (status?.let { ", $it" } ?: "") }
                .clickable(onClick = onServers),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val fg = if (live || record == null) Palette.bar else Palette.text
                Icon(if (record?.device == true) SIcons.Device else SIcons.Server, null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (record != null && !live) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).background(if (state.connecting) Palette.warning else Palette.danger, androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
    }
}


@Composable
private fun Menu(state: ServerState, current: Page, permanent: Boolean, onOpen: (Page) -> Unit, onServers: () -> Unit, onPluginPage: (String) -> Unit) {
    val perms = state.me?.perms.orEmpty().toSet()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxHeight().semantics { testTagsAsResourceId = true }.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (permanent) {
            Image(
                painterResource(R.drawable.sonorium_logo), contentDescription = "Sonorium",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).height(34.dp),
            )
            HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 8.dp))
        }
        Section("Playback")
        MenuItem(Page.Channels, current, onOpen, badge = state.playingCount.takeIf { it > 0 })
        MenuItem(Page.Personal, current, onOpen)
        MenuItem(Page.Playlists, current, onOpen)
        Section("Configuration")
        MenuItem(Page.Themes, current, onOpen)
        val settings = SETTINGS.filter { (it.perm == null || it.perm in perms) && state.offers(it.cap) && (it != Page.Connection || state.connectionPage) &&
            (it != Page.Backups || state.info?.capabilities?.contains("theme_backup") == true) }
        MenuRow(SIcons.Settings, "Settings", selected = false, trailing = if (settingsOpen) SIcons.ChevronDown else SIcons.ChevronRight) { settingsOpen = !settingsOpen }
        if (settingsOpen) {
            settings.forEach { MenuItem(it, current, onOpen, indent = true) }
            MenuRow(SIcons.Server, "Servers", selected = false, indent = true, onClick = onServers)
        }
        if (state.pluginPages.isNotEmpty()) {
            Section("Plugins")
            state.pluginPages.forEach { p -> MenuRow(SIcons.Layers, p.title, selected = false) { onPluginPage(p.view) } }
        }
        if (state.offers(Page.Status.cap)) {
            Section("System")
            MenuItem(Page.Status, current, onOpen)
        }
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
            .testTag("menu:$title") // device tests find menu items by this (page text behind the menu has the same words)
            .heightIn(min = 48.dp)
            .background(if (selected) Palette.card else Palette.bar)
            .clickable(onClick = onClick)
            .padding(start = if (indent) 44.dp else 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Palette.accent else Palette.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = if (selected) Palette.accent else Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        badge?.let { CountBadge(it) }
        trailing?.let { Icon(it, null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp)) }
    }
}

/**
 * A server page with no server to show: never connected, removed or unlinked (Connect / Servers), or
 * unreachable (Try again), and the way to the phone's own player. Nothing from the server is shown.
 */
@Composable
private fun NoConnection(state: ServerState, page: Page, onServers: () -> Unit, onRetry: () -> Unit, onPhone: () -> Unit) {
    val what = when (page) {
        Page.Channels -> "its channels"
        Page.Playlists -> "its playlists"
        Page.Themes -> "its themes"
        Page.Status -> "what's playing"
        Page.Speakers, Page.Groups -> "its speakers"
        Page.Spaces -> "its floors and areas"
        Page.Logs -> "its log"
        else -> "its settings"
    }
    val record = state.record?.takeIf { !state.unlinked }
    Centered {
        Icon(SIcons.Server, null, tint = Palette.textMuted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                record == null && state.unlinked -> "Not linked"
                record == null -> "No server connected"
                else -> "Can't reach ${record.displayName}"
            },
            color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                record == null && state.unlinked -> state.failure ?: "Open Servers to link this phone again."
                record == null -> "Connect to your Sonorium server to see $what."
                else -> "Check that this phone is on the same network. It reconnects by itself when the server is back."
            },
            color = Palette.textSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (record != null) {
                OutlinedButton(
                    onClick = onServers, border = androidx.compose.foundation.BorderStroke(1.dp, Palette.border),
                    modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                ) { Text("Servers", color = Palette.text) }
            }
            Button(
                onClick = if (record != null) onRetry else onServers,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
            ) { Text(if (record != null) "Try again" else if (state.unlinked) "Servers" else "Connect") }
        }
        if (page != Page.Themes) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onPhone, modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.SMALL.dp)) {
                Text("Play on this phone", color = Palette.accent)
            }
        }
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
            // The playlists as they are now (these actions are remembered across recompositions)
            pickPlaylist = { s, id -> model.state.value.playlists.firstOrNull { it.id == id }?.let { model.pickPlaylist(s, it) } },
            edit = onEdit,
            delete = model::delete,
            stopCasting = model::stopCasting,
            leaveLink = model::leaveLink,
            rejoinLink = model::rejoinLink,
            mix = { s -> s.themeId?.let { id -> model.state.value.theme(id)?.let(model::openThemeEditor) } },
            sleep = model::setSleep,
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
                    Text("Channels", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    CountBadge(state.sessions.size)
                    Spacer(Modifier.weight(1f))
                    if (state.zones?.admin == true && narrow) {
                        // Phones: icon only, so the header fits next to New Channel
                        IconBox(SIcons.Link, "Link channels", { onLink(null) }, tint = Palette.text, size = app.sonorium.ui.UiSize.STANDARD)
                        Spacer(Modifier.width(8.dp))
                    } else if (state.zones?.admin == true) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onLink(null) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Palette.border),
                            modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                        ) {
                            Icon(SIcons.Link, null, tint = Palette.text, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Link channels", color = Palette.text)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (state.offers(CAP_CHANNELS)) Button(
                        onClick = onNew,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
                        modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
                    ) {
                        Icon(SIcons.Plus, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Channel", fontWeight = FontWeight.Medium)
                    }
                    // Help at the far right, its right edge under the top bar's server button (Chris, item 11)
                    Spacer(Modifier.width(8.dp))
                    PageHelpButton("sessions")
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
                        Text("No channels yet", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                        extra = if (state.onDevice) ({ DeviceOutput() }) else null,
                    )
                }
            }
        }
    }
}

private val NEW_LINK = app.sonorium.net.ChannelLink(id = "")

/** This device unlocked, its themes folder not chosen (or gone): a line with Choose folder above its themes. */
@Composable
private fun ChooseFolderLine(missing: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).background(Palette.card, RoundedCornerShape(12.dp))
            .then(Modifier.border(1.dp, Palette.border, RoundedCornerShape(12.dp))).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            androidx.compose.ui.res.stringResource(if (missing) R.string.folder_missing_line else R.string.folder_choose_line),
            color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onChoose, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = app.sonorium.ui.UiSize.STANDARD.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bar),
        ) { Text(androidx.compose.ui.res.stringResource(R.string.folder_choose), fontWeight = FontWeight.Medium) }
    }
}

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
