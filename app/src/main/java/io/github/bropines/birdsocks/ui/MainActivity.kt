package io.github.bropines.birdsocks.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Changelog
import io.github.bropines.birdsocks.core.Automation
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.LoginFlow
import io.github.bropines.birdsocks.core.MainItem
import io.github.bropines.birdsocks.core.MainLayout
import io.github.bropines.birdsocks.core.MainLayoutStore
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.Updater
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import io.github.bropines.birdsocks.ui.theme.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {
    companion object {
        /** Opened from the "session expiring" notice: start renewing at once. */
        const val EXTRA_EXTEND = "extend"
        /** Opened from Settings → Appearance → Main screen: the main screen with its editor. */
        private const val EXTRA_EDIT_LAYOUT = "edit_layout"

        fun editLayoutIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_EDIT_LAYOUT, true)

        /** Set by [EXTRA_EDIT_LAYOUT]; the main screen takes it and turns into its editor. */
        internal val editRequest = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** "What's new" for this launch: once after an update (see [checkWhatsNew]). */
    private val showChangelog = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // On but not running: an update or a task killer took it down, never
        // the user — a manual stop clears the flag first. An activity in the
        // foreground may always start a foreground service.
        if (savedInstanceState == null && GlobalSettings.wasRunning(this) &&
            NetbirdState.daemon.value == NetbirdState.Daemon.Stopped
        ) {
            NetbirdService.start(this)
        }
        handleIntent(intent)
        // Every creation: one recreated before the dialog was closed (a rotation) shows it again.
        checkWhatsNew()
        // A newer release on GitHub, once per launch: said in a toast and by a
        // badge on the Info button; nothing is downloaded until asked.
        if (savedInstanceState == null && GlobalSettings.isUpdateCheckOnLaunch(this)) {
            lifecycleScope.launch {
                val found = Updater.check(this@MainActivity)
                val release = (found as? Updater.State.Available)?.release ?: (found as? Updater.State.Ready)?.release
                if (release != null) {
                    Toast.makeText(this@MainActivity, getString(R.string.update_available_toast, release.version), Toast.LENGTH_LONG).show()
                }
            }
        } else if (savedInstanceState == null) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { Updater.prune(this@MainActivity) }
        }
        setContent {
            BirdSocksTheme {
                MainScreen()
                ActionLinkDialogs()
                if (showChangelog.value) ChangelogDialog(onDismiss = {
                    showChangelog.value = false
                    GlobalSettings.setLastSeenChangelogVersion(this, Changelog.currentVersion())
                })
            }
        }
    }

    /**
     * Whether this launch shows "What's new": the first one after an update. A fresh
     * install only records the version — it has nothing to compare with — and so does
     * an install of the same version again (a debug rebuild). An update from a build
     * older than this check has no version stored; Android's own install and update
     * times tell it from a fresh install.
     */
    private fun checkWhatsNew() {
        val current = Changelog.currentVersion()
        val seen = GlobalSettings.getLastSeenChangelogVersion(this)
        val updated = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.lastUpdateTime != info.firstInstallTime
        }.getOrDefault(false)
        when {
            seen == current -> Unit
            seen.isEmpty() && !updated -> GlobalSettings.setLastSeenChangelogVersion(this, current)
            else -> showChangelog.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_EXTEND, false) == true) {
            intent.removeExtra(EXTRA_EXTEND)
            io.github.bropines.birdsocks.core.ExtendFlow.start(this)
        }
        if (intent?.getBooleanExtra(EXTRA_EDIT_LAYOUT, false) == true) {
            intent.removeExtra(EXTRA_EDIT_LAYOUT)
            editRequest.value = true
        }
        // The installer session's answer (Updater.installWithSession).
        if (intent?.action == Updater.ACTION_INSTALL_STATUS) {
            Updater.onInstallStatus(this, intent)
            intent.action = null
        }
        // birdsocks:// links (DeepLinks): a screen opens on top of this one,
        // an action asks here first (ActionLinks).
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == DeepLinks.SCHEME) {
            val uri = intent.data!!
            intent.data = null
            val target = DeepLinks.intentFor(this, uri)
            if (target != null) startActivity(target)
            else DeepLinks.requestFor(uri)?.let { ActionLinks.receive(this, it, runCatching { uri.getQueryParameter("secret") }.getOrNull()) }
        }
    }
}

/** What the status card shows: the service and the daemon's connection in one word. */
enum class CardState { Stopped, Starting, Connecting, Connected, NeedsLogin, Idle, Stopping, Offline }

fun cardStateOf(daemon: NetbirdState.Daemon, status: NbStatus?, network: Boolean = true): CardState = when (daemon) {
    NetbirdState.Daemon.Stopped -> CardState.Stopped
    NetbirdState.Daemon.Starting -> CardState.Starting
    NetbirdState.Daemon.Stopping -> CardState.Stopping
    NetbirdState.Daemon.Running -> if (!network && status?.state?.needsLogin != true) CardState.Offline else when (status?.state) {
        null -> CardState.Connecting
        NbConnState.Connected -> CardState.Connected
        NbConnState.Connecting -> CardState.Connecting
        NbConnState.NeedsLogin, NbConnState.LoginFailed, NbConnState.SessionExpired -> CardState.NeedsLogin
        else -> CardState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A preview may hand in a whole state to draw; see LocalDemo.
    val demo = LocalDemo.current
    val daemon by NetbirdState.daemon.collectAsStateOr { it.daemon }
    val status by NetbirdState.status.collectAsStateOr { it.status }
    val error by NetbirdState.error.collectAsStateOr { null }
    val login by LoginFlow.state.collectAsState()
    val network by NetbirdState.network.collectAsStateOr { true }
    val card = cardStateOf(daemon, status, network)

    // Exit nodes come from ListNetworks; re-read when the daemon says the set moved.
    var networks by remember { mutableStateOf(demo?.networks ?: emptyList()) }
    val revision = status?.fullStatus?.networksRevision
    LaunchedEffect(card == CardState.Connected, revision) {
        if (demo != null) return@LaunchedEffect
        networks = if (card == CardState.Connected) runCatching { Netbird.networks() }.getOrDefault(emptyList()) else emptyList()
    }
    val sessionShown = sessionNeedsAttention(status?.sessionExpiresAt)
    var showAccounts by remember { mutableStateOf(false) }
    val profile by NetbirdState.profile.collectAsStateOr { it.profile }
    val accounts by rememberAccounts(profile)
    val accountLabel = accounts.firstOrNull { it.profile.name == profile }?.label
        ?: profile?.takeIf { it != "default" }

    // The active account's dashboard, from its profile's config — the address the
    // management server row in Settings opens. Unknown while the daemon is down.
    val running = daemon == NetbirdState.Daemon.Running
    var config by remember { mutableStateOf(demo?.config) }
    LaunchedEffect(running, profile, card == CardState.Connected) {
        if (demo != null) return@LaunchedEffect
        config = if (running) runCatching { Netbird.config() }.getOrNull() else null
    }
    val dashboardUrl = config?.dashboardUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    var showAbout by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsStateOr { Updater.State.Idle }
    val updateWaiting = update is Updater.State.Available || update is Updater.State.Ready

    // The arrangement (core/MainLayout.kt), read again on every return: Settings may have put
    // something back on the screen, a restore may have brought another arrangement.
    var layout by remember { mutableStateOf(MainLayoutStore.load(context)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var vpnEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                layout = MainLayoutStore.load(context)
                vpnEnabled = GlobalSettings.isTunModeEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val editRequested by MainActivity.editRequest.collectAsState()
    LaunchedEffect(editRequested) {
        if (editRequested) {
            editing = true
            MainActivity.editRequest.value = false
        }
    }
    BackHandler(enabled = editing) { editing = false }
    fun edit(change: (MainLayout) -> MainLayout) {
        val next = change(layout)
        if (next != layout) {
            layout = next
            MainLayoutStore.save(context, next)
        }
    }
    val addable = layout.addable(mainItemsOffered)
    // The exit-node row is there only with an exit node to pick; arranging, it stands in by name.
    val exitShown = card == CardState.Connected && networks.any { it.isExitNode }
    val cells = layout.shown(mainItemsOffered).filter { editing || it.item != MainItem.EXIT_NODE || exitShown }

    fun open(item: MainItem) {
        when (item) {
            MainItem.ACCOUNTS -> showAccounts = true
            MainItem.VPN -> {
                val activity = context.findActivity() ?: return
                Automation.runFromActivity(activity, Automation.Command.Tun(!vpnEnabled))
                vpnEnabled = !vpnEnabled
            }
            else -> item.screen(context)?.let { context.startActivity(it) }
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // On its side a phone has no height to spare for a bar under the canvas: the controls stand beside it.
    val sideways = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp < 480
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            if (editing) {
                AppTopBar(title = stringResource(R.string.main_layout_title), onBack = { editing = false })
            } else AppTopBar(
                title = stringResource(R.string.app_name),
                subtitle = accountLabel,
                // Stopped too: the sheet starts the daemon for what it does.
                onTitleClick = { showAccounts = true },
                actions = {
                    if (dashboardUrl != null) {
                        IconButton(onClick = { openUrl(context, dashboardUrl) }) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = stringResource(R.string.main_cd_dashboard))
                        }
                    }
                    IconButton(onClick = { showAbout = true }) {
                        BadgedBox(badge = { if (updateWaiting) Badge() }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(if (updateWaiting) R.string.about_cd_update else R.string.about_open)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (editing && !sideways) MainEditBar(
                columns = layout.columns,
                onColumns = { c -> edit { it.withColumns(c) } },
                canAdd = addable.isNotEmpty(),
                onAdd = { showPalette = true },
                onReset = { confirmReset = true },
                onDone = { editing = false },
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .readableWidth()
                    .padding(16.dp)
            ) {
                MainCanvas(
                    cells = cells,
                    columns = layout.effectiveColumns,
                    cardHeight = 96.dp,
                    editing = editing,
                    scroll = scroll,
                    inserts = listOf(
                        // The banners stand under the status card, wherever it is: only when something needs the user.
                        MainCanvasInsert(MainItem.STATUS) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                error?.let { message -> ErrorCard(message) { NetbirdState.dismissError() } }
                                // The server refuses extra DNS names to a device not added with a key that allows them.
                                val mgmtError = status?.fullStatus?.managementState?.error.orEmpty()
                                if ("extra DNS labels" in mgmtError) DnsLabelsRefusedCard()
                                if (card == CardState.NeedsLogin || login !is LoginFlow.State.Idle) {
                                    LoginCard(login, status?.fullStatus?.managementState?.error.orEmpty())
                                }
                                // The session lives in Settings → Account; here only when it needs a hand.
                                if (card == CardState.Connected && sessionShown) status?.sessionExpiresAt?.let { expiry -> SessionRow(expiry) }
                            }
                        }
                    ),
                    title = { stringResource(it.spec().title) },
                    // A long press anywhere on the screen turns it into its own editor.
                    onLongPress = { editing = true; true },
                    onMove = { item, target -> edit { it.move(item, target) } },
                    onStep = { item, by -> edit { it.step(item, by, mainItemsOffered) } },
                    onRemove = { item -> edit { it.remove(item) } },
                    onSetWide = { item, wide -> edit { it.setWide(item, wide) } },
                ) { placed ->
                    when (val item = placed.item) {
                        MainItem.STATUS -> StatusCard(
                            state = card,
                            status = status,
                            onToggle = {
                                if (card == CardState.Stopped) NetbirdService.start(context) else NetbirdService.stop(context)
                            },
                            onConnect = { scope.launch { runCatching { Netbird.up() }.onFailure { toast(context, it) } } }
                        )
                        MainItem.EXIT_NODE ->
                            if (exitShown) ExitNodeCard(networks, status, connected = true, onChanged = { networks = it })
                            else MainBlockPlaceholder(item, note = R.string.main_layout_exit_when)
                        MainItem.PROXY -> ProxyAddressCard()
                        MainItem.VPN -> {
                            val spec = item.spec()
                            val stateWord = stringResource(if (vpnEnabled) R.string.main_status_state_on else R.string.main_status_state_off)
                            MenuCard(
                                stringResource(spec.title),
                                if (vpnEnabled) Icons.Default.VpnKey else Icons.Default.VpnKeyOff,
                                Modifier.semantics { role = Role.Switch; stateDescription = stateWord },
                                highlighted = vpnEnabled,
                            ) { open(item) }
                        }
                        else -> {
                            val spec = item.spec()
                            MenuCard(stringResource(spec.title), spec.icon) { open(item) }
                        }
                    }
                }
                if (editing && addable.isNotEmpty()) {
                    Spacer(Modifier.height(MainTileGap))
                    MainAddCell(onClick = { showPalette = true })
                }
            }
            if (editing && sideways) MainEditRail(
                columns = layout.columns,
                onColumns = { c -> edit { it.withColumns(c) } },
                canAdd = addable.isNotEmpty(),
                onAdd = { showPalette = true },
                onReset = { confirmReset = true },
                onDone = { editing = false },
            )
        }
    }

    if (showPalette) MainPaletteSheet(addable, onAdd = { item -> edit { it.add(item) } }, onDismiss = { showPalette = false })
    if (confirmReset) {
        // Resolved here: a dialog is a window of its own, outside the app's language.
        val resetTitle = stringResource(R.string.main_layout_reset_title)
        val resetText = stringResource(R.string.main_layout_reset_text)
        val resetLabel = stringResource(R.string.main_layout_reset)
        val cancelLabel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(resetTitle) },
            text = { Text(resetText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    MainLayoutStore.reset(context)
                    layout = MainLayoutStore.load(context)
                }) { Text(resetLabel) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(cancelLabel) } }
        )
    }
    if (showAccounts) AccountSheet(onDismiss = { showAccounts = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

/** The SOCKS5 proxy's address, as apps are to be set: a tap copies it. */
@Composable
private fun ProxyAddressCard() {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val address = remember { GlobalSettings.getSocksAddress(context) }
    val copied = stringResource(R.string.settings_lan_copied, address)
    Card(
        onClick = {
            clipboard.copyText(scope, address)
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.primary) },
            overlineContent = { Text(stringResource(R.string.nb_socks_title)) },
            trailingContent = { Icon(Icons.Default.ContentCopy, stringResource(R.string.main_proxy_copy_cd)) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(address, fontFamily = FontFamily.Monospace) }
    }
}

/**
 * Whether the session row belongs on the main screen: the session ends
 * within the hour, or a renewal is under way or has just failed.
 */
@Composable
private fun sessionNeedsAttention(expiresAt: String?): Boolean {
    val extend by io.github.bropines.birdsocks.core.ExtendFlow.state.collectAsState()
    val until = expiresAt?.let { parseRfc3339Millis(it) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (until != null) { now = System.currentTimeMillis(); delay(60_000) }
    }
    return until != null && (extend !is io.github.bropines.birdsocks.core.ExtendFlow.State.Idle || until - now < 60 * 60_000)
}

private fun toast(context: Context, e: Throwable) {
    Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusCard(
    state: CardState,
    status: NbStatus?,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onConnect: () -> Unit
) {
    // Progress reads calm (secondary), a pending sign-in warm (tertiary).
    val scheme = MaterialTheme.colorScheme
    val on = state != CardState.Stopped
    val background by animateColorAsState(
        when (state) {
            CardState.Connected -> scheme.primaryContainer
            CardState.Starting, CardState.Connecting, CardState.Stopping -> scheme.secondaryContainer
            CardState.NeedsLogin, CardState.Idle, CardState.Offline -> scheme.tertiaryContainer
            CardState.Stopped -> scheme.surfaceContainerHigh
        }, label = "status_bg"
    )
    val content by animateColorAsState(
        when (state) {
            CardState.Connected -> scheme.onPrimaryContainer
            CardState.Starting, CardState.Connecting, CardState.Stopping -> scheme.onSecondaryContainer
            CardState.NeedsLogin, CardState.Idle, CardState.Offline -> scheme.onTertiaryContainer
            CardState.Stopped -> scheme.onSurfaceVariant
        }, label = "status_fg"
    )
    val haptics = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.extraLarge
    val stateWord = stringResource(if (on) R.string.main_status_state_on else R.string.main_status_state_off)
    val actionWord = stringResource(if (on) R.string.main_status_action_stop else R.string.main_status_action_start)
    val busy = state == CardState.Starting || state == CardState.Connecting || state == CardState.Stopping

    Surface(
        shape = shape,
        color = background,
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp)
            .animateContentSize()
            .clip(shape)
            .semantics { role = Role.Switch; stateDescription = stateWord }
            .combinedClickable(
                enabled = state != CardState.Stopping,
                onClickLabel = actionWord,
                onClick = {
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle()
                }
            )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(targetState = if (busy) null else state, label = "status_icon") { shown ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (shown) {
                        null -> LoadingIndicator(color = content, modifier = Modifier.size(40.dp))
                        CardState.Connected -> Icon(Icons.Default.CheckCircle, null, tint = content, modifier = Modifier.size(32.dp))
                        CardState.NeedsLogin -> Icon(Icons.AutoMirrored.Filled.Login, null, tint = content, modifier = Modifier.size(32.dp))
                        CardState.Idle -> Icon(Icons.Default.LinkOff, null, tint = content, modifier = Modifier.size(32.dp))
                        CardState.Offline -> Icon(Icons.Default.SignalWifiOff, null, tint = content, modifier = Modifier.size(32.dp))
                        else -> Icon(Icons.Default.PowerSettingsNew, null, tint = content, modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val title = stringResource(
                when (state) {
                    CardState.Connected -> R.string.main_status_active
                    CardState.Starting -> R.string.main_status_starting
                    CardState.Connecting -> R.string.main_status_connecting
                    CardState.NeedsLogin -> R.string.main_status_needs_login
                    CardState.Idle -> R.string.nb_status_idle
                    CardState.Stopping -> R.string.nb_status_stopping
                    CardState.Offline -> R.string.nb_status_offline
                    CardState.Stopped -> R.string.status_stopped
                }
            )
            AnimatedContent(targetState = title, label = "status_title") { t ->
                Text(t, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = content, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            val peers = status?.fullStatus?.peers.orEmpty()
            val subtitle = when (state) {
                CardState.Connected -> stringResource(R.string.nb_status_connected_desc, peers.size, peers.count { it.connected })
                CardState.Starting -> stringResource(R.string.main_status_starting_desc)
                CardState.Connecting -> stringResource(R.string.nb_status_connecting_desc)
                CardState.NeedsLogin -> stringResource(R.string.nb_status_login_desc)
                CardState.Idle -> stringResource(R.string.nb_status_idle_desc)
                CardState.Stopping -> stringResource(R.string.main_status_please_wait)
                CardState.Offline -> stringResource(R.string.nb_status_offline_desc)
                CardState.Stopped -> stringResource(R.string.tap_to_start)
            }
            // The VPN mode says itself, as the notification and the tile do.
            val vpn by io.github.bropines.birdsocks.core.TunVpnService.running.collectAsStateOr { it.vpn }
            // Root says so: it takes no VPN slot, which is why one would pick it.
            val rootState by io.github.bropines.birdsocks.core.RootTun.state.collectAsState()
            val how = if (vpn && io.github.bropines.birdsocks.core.GlobalSettings.isTunRoot(LocalContext.current)) {
                when (rootState?.mode) {
                    io.github.bropines.birdsocks.core.RootTun.Mode.Shared -> stringResource(R.string.nb_main_root_shared)
                    io.github.bropines.birdsocks.core.RootTun.Mode.Yield -> stringResource(R.string.nb_main_root_yield)
                    else -> "Root"
                }
            } else "VPN"
            val line = if (vpn && state == CardState.Connected) "$subtitle · $how" else subtitle
            Text(line, textAlign = TextAlign.Center, color = content, modifier = Modifier.alpha(0.8f))
            if (state == CardState.Idle) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.nb_action_connect)) }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

/**
 * Signing in: NetBird Cloud or a server of the user's own, and a setup key or
 * the browser. A browser login shows its code and waits here; the wait lives
 * in [LoginFlow], so the browser taking over the screen does not end it.
 */
@Composable
private fun LoginCard(login: LoginFlow.State, serverMessage: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A server that did not answer the probe: the next tap signs in anyway.
    var unreachable by remember { mutableStateOf<String?>(null) }
    var selfHosted by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    // The server the profile already points at: a re-login is to that one.
    // Read again when a login fails: one started with a server (a new
    // account) has stored it by then.
    LaunchedEffect(login is LoginFlow.State.Failed) {
        val url = runCatching { Netbird.config().managementUrl }.getOrNull()?.trimEnd('/') ?: return@LaunchedEffect
        if (server.isEmpty() && url.isNotEmpty() && !url.startsWith(GlobalSettings.CLOUD_MANAGEMENT_URL.substringBeforeLast(':'))) {
            selfHosted = true
            server = url.removeSuffix(":443")
        }
    }
    var setupKey by rememberSaveable { mutableStateOf("") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    val busy = login is LoginFlow.State.Working || login is LoginFlow.State.Browser

    // The page opens by itself once per login; the button below opens it again.
    var openedFor by remember { mutableStateOf<String?>(null) }
    if (login is LoginFlow.State.Browser && openedFor != login.userCode) {
        LaunchedEffect(login.userCode) {
            openedFor = login.userCode
            openUrl(context, login.url)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.nb_login_title), style = MaterialTheme.typography.titleMedium)
            // What the server said when it refused: "pending approval", an expired session.
            if (serverMessage.isNotBlank() && login !is LoginFlow.State.Browser) {
                HelpText(serverMessage, color = MaterialTheme.colorScheme.error)
            }
            when (login) {
                is LoginFlow.State.Browser -> {
                    if (login.userCode.isNotEmpty()) {
                        HelpText(stringResource(R.string.nb_login_browser_desc))
                        Text(
                            login.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    } else {
                        HelpText(stringResource(R.string.nb_login_browser_pkce_desc))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.nb_login_waiting), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { openUrl(context, login.url) }) { Text(stringResource(R.string.nb_login_open_again)) }
                    }
                    TextButton(onClick = { LoginFlow.reset() }) { Text(stringResource(R.string.action_cancel)) }
                }
                else -> {
                    SlidingSegmentedChips(
                        items = listOf(
                            SegmentedChipItem(stringResource(R.string.nb_login_cloud), Icons.Default.Cloud),
                            SegmentedChipItem(stringResource(R.string.nb_login_self_hosted), Icons.Default.Dns)
                        ),
                        selectedIndex = if (selfHosted) 1 else 0,
                        onOptionSelected = { if (!busy) selfHosted = it == 1 },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selfHosted) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it.trim() },
                            label = { Text(stringResource(R.string.nb_login_server)) },
                            placeholder = { Text("https://netbird.example.com") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useKey, onCheckedChange = { useKey = it }, enabled = !busy)
                        Text(stringResource(R.string.nb_login_use_setup_key))
                    }
                    if (useKey) {
                        OutlinedTextField(
                            value = setupKey,
                            onValueChange = { setupKey = it.trim() },
                            label = { Text(stringResource(R.string.nb_login_setup_key)) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    unreachable?.let {
                        Text(stringResource(R.string.nb_login_unreachable, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (login is LoginFlow.State.Failed) {
                        Text(login.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = !busy && (!selfHosted || server.isNotBlank()) && (!useKey || setupKey.isNotBlank()),
                        onClick = {
                            val url = if (selfHosted) normalizeServer(server) else GlobalSettings.CLOUD_MANAGEMENT_URL
                            if (!selfHosted || unreachable != null) {
                                unreachable = null
                                LoginFlow.start(context, url, setupKey.takeIf { useKey })
                            } else scope.launch {
                                // A typo in an own server's address should not end in a 30 s timeout.
                                val problem = probeServer(context, url)
                                if (problem == null) LoginFlow.start(context, url, setupKey.takeIf { useKey })
                                else unreachable = problem
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(when {
                            unreachable != null -> R.string.nb_login_anyway
                            useKey -> R.string.nb_login_action_key
                            else -> R.string.nb_login_action_browser
                        }))
                    }
                }
            }
        }
    }
}

/** netbird.example.com → https://netbird.example.com; a scheme the user typed is kept. */
fun normalizeServer(raw: String): String {
    val s = raw.trim().trimEnd('/')
    return if ("://" in s) s else "https://$s"
}

/**
 * Opens [url] in a Custom Tab: over the app, with Chrome's cookies, so a
 * signed-in identity provider asks for one tap. A plain browser when no
 * Custom Tabs provider is installed.
 */
fun openUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        val tab = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
        if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, uri)
    }.recoverCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

/** Sign-in refused because of the extra DNS names asked for: the way out is to drop them. */
@Composable
private fun DnsLabelsRefusedCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        ListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Label, null) },
            supportingContent = { HelpText(stringResource(R.string.nb_dns_labels_refused_desc), tapToExpand = false) },
            trailingContent = {
                TextButton(onClick = {
                    GlobalSettings.setDnsLabels(context, NetbirdState.profile.value ?: "default", "")
                    scope.launch {
                        runCatching {
                            Netbird.setConfig {
                                put("cleanDNSLabels", true)
                            }
                            Netbird.reconnect()
                        }.onFailure { toast(context, it) }
                    }
                }) { Text(stringResource(R.string.nb_dns_labels_remove)) }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(stringResource(R.string.nb_dns_labels_refused)) }
    }
}

@Composable
fun MenuCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    icon2: ImageVector? = null,
    /** A tile that is a switch, while it is on. */
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = if (highlighted) scheme.primaryContainer else scheme.surfaceContainer, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                if (icon2 != null) Icon(icon2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Null when [url] answers at all (any status counts), the reason otherwise.
 * An http:// server is not probed: the app may not speak cleartext, the
 * daemon may, so a refusal here would say nothing about the server. Nor is
 * one behind a control proxy or ByeDPI: the daemon goes that way, this
 * probe would go direct, where the server may well be blocked.
 */
suspend fun probeServer(context: Context, url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (url.startsWith("http://", ignoreCase = true)) return@withContext null
    if (GlobalSettings.getControlMode(context) != GlobalSettings.CONTROL_DIRECT) return@withContext null
    runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().close()
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}

