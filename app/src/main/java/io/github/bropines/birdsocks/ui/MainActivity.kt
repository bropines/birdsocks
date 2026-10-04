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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
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
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.EgressProbe
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.LoginFlow
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {
    companion object {
        /** Opened from the "session expiring" notice: start renewing at once. */
        const val EXTRA_EXTEND = "extend"
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        setContent { BirdSocksTheme { MainScreen() } }
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
        // birdsocks:// links (DeepLinks): the screen opens on top of this one.
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == DeepLinks.SCHEME) {
            val target = DeepLinks.intentFor(this, intent.data!!)
            intent.data = null
            if (target != null) startActivity(target)
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
    val daemon by NetbirdState.daemon.collectAsState()
    val status by NetbirdState.status.collectAsState()
    val error by NetbirdState.error.collectAsState()
    val login by LoginFlow.state.collectAsState()
    val network by NetbirdState.network.collectAsState()
    val card = cardStateOf(daemon, status, network)

    // Exit nodes come from ListNetworks; re-read when the daemon says the set moved.
    var networks by remember { mutableStateOf<List<NbNetwork>>(emptyList()) }
    val revision = status?.fullStatus?.networksRevision
    LaunchedEffect(card == CardState.Connected, revision) {
        networks = if (card == CardState.Connected) runCatching { Netbird.networks() }.getOrDefault(emptyList()) else emptyList()
    }
    var showExitPicker by remember { mutableStateOf(false) }
    // An exit node can be up as a peer and forward nothing: check the internet
    // through the proxy while one is selected, sooner again after a failure.
    val selectedExit = networks.firstOrNull { it.isExitNode && it.selected }
    var exitWorks by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(selectedExit?.id, card == CardState.Connected) {
        exitWorks = null
        if (selectedExit == null || card != CardState.Connected) return@LaunchedEffect
        while (true) {
            exitWorks = EgressProbe.internetThroughProxy(context)
            delay(if (exitWorks == true) 120_000L else 20_000L)
        }
    }
    var showAccounts by remember { mutableStateOf(false) }
    val profile by NetbirdState.profile.collectAsState()
    val accounts by rememberAccounts(profile)
    val accountLabel = accounts.firstOrNull { it.profile.name == profile }?.label

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                subtitle = listOfNotNull(accountLabel, status?.fullStatus?.localPeerState?.fqdn?.substringBefore('.')?.takeIf { it.isNotEmpty() })
                    .joinToString(" · ").ifEmpty { null },
                // Stopped too: the sheet starts the daemon for what it does.
                onTitleClick = { showAccounts = true },
                actions = {
                    IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.menu_settings))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).readableWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatusCard(
                    state = card,
                    status = status,
                    onToggle = {
                        if (card == CardState.Stopped) NetbirdService.start(context) else NetbirdService.stop(context)
                    },
                    onConnect = { scope.launch { runCatching { Netbird.up() }.onFailure { toast(context, it) } } }
                )
            }
            error?.let { message ->
                item { ErrorCard(message) { NetbirdState.dismissError() } }
            }
            // The server refuses extra DNS names to a device not added with a key that allows them.
            val mgmtError = status?.fullStatus?.managementState?.error.orEmpty()
            if ("extra DNS labels" in mgmtError) {
                item { DnsLabelsRefusedCard() }
            }
            if (card == CardState.NeedsLogin || login !is LoginFlow.State.Idle) {
                item { LoginCard(login, status?.fullStatus?.managementState?.error.orEmpty()) }
            }
            if (card == CardState.Connected) {
                status?.sessionExpiresAt?.let { expiry -> item { SessionRow(expiry) } }
                status?.let { s -> item { AddressCard(s) } }
                item { SocksCard() }
                val exitNodes = networks.filter { it.isExitNode }
                if (exitNodes.isNotEmpty()) {
                    item {
                        ExitNodeRow(
                            current = selectedExit,
                            via = selectedExit?.let { routingPeerOf(it, status) }?.substringBefore('.'),
                            works = exitWorks,
                            onTurnOff = {
                                scope.launch {
                                    runCatching {
                                        selectedExit?.let { Netbird.deselectNetworks(listOf(it.id)) }
                                        networks = Netbird.networks()
                                    }.onFailure { toast(context, it) }
                                }
                            },
                            onClick = { showExitPicker = true }
                        )
                    }
                }
            }
            item {
                val peers = status?.fullStatus?.peers.orEmpty()
                BoxWithConstraints {
                    val entries = listOf(
                        MenuEntry(
                            if (peers.isEmpty()) stringResource(R.string.nb_menu_peers)
                            else stringResource(R.string.nb_menu_peers_count, peers.size),
                            Icons.Default.Devices
                        ) { context.startActivity(Intent(context, PeersActivity::class.java)) },
                        MenuEntry(stringResource(R.string.nb_menu_networks), Icons.Default.Hub) {
                            context.startActivity(Intent(context, NetworksActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_expose_title), Icons.Default.Public) {
                            context.startActivity(Intent(context, ExposeActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_events_title), Icons.Default.EventNote) {
                            context.startActivity(Intent(context, EventsActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_details_title), Icons.Default.MonitorHeart) {
                            context.startActivity(Intent(context, StatusDetailsActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_trace_title), Icons.Default.Policy) {
                            context.startActivity(TraceActivity.intent(context))
                        },
                        MenuEntry(stringResource(R.string.menu_logs), Icons.AutoMirrored.Filled.Article) {
                            context.startActivity(Intent(context, LogsActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.menu_settings), Icons.Default.Settings) {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    )
                    MenuGrid(menuColumnsFor(maxWidth, entries.size), entries)
                }
            }
        }
    }

    if (showAccounts) AccountSheet(onDismiss = { showAccounts = false })

    if (showExitPicker) {
        val exitNodes = networks.filter { it.isExitNode }
        val current = exitNodes.firstOrNull { it.selected }?.id ?: ""
        PickerSheet(
            title = stringResource(R.string.nb_exit_node),
            options = listOf(PickerOption("", stringResource(R.string.nb_exit_node_none), Icons.Default.Block)) +
                exitNodes.map { PickerOption(it.id, it.id, Icons.Default.Public, supporting = routingPeerOf(it, status)) },
            selected = current,
            onPick = { id ->
                scope.launch {
                    runCatching {
                        if (id.isEmpty()) Netbird.deselectNetworks(listOfNotNull(current.ifEmpty { null }))
                        else Netbird.selectNetworks(listOf(id))
                        networks = Netbird.networks()
                    }.onFailure { toast(context, it) }
                }
            },
            onDismiss = { showExitPicker = false }
        )
    }
}

/** The peer that routes [network], by its name, as far as the status shows it. */
private fun routingPeerOf(network: NbNetwork, status: NbStatus?): String? {
    return status?.fullStatus?.peers?.firstOrNull { network.routedBy(it) }?.fqdn
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
            Text(subtitle, textAlign = TextAlign.Center, color = content, modifier = Modifier.alpha(0.8f))
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
    LaunchedEffect(Unit) {
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
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!selfHosted, { selfHosted = false }, SegmentedButtonDefaults.itemShape(0, 2), enabled = !busy) {
                            Text(stringResource(R.string.nb_login_cloud))
                        }
                        SegmentedButton(selfHosted, { selfHosted = true }, SegmentedButtonDefaults.itemShape(1, 2), enabled = !busy) {
                            Text(stringResource(R.string.nb_login_self_hosted))
                        }
                    }
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
                                val problem = probeServer(url)
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

/** This device on the network: its name and addresses, each copied on tap. */
@Composable
private fun AddressCard(status: NbStatus) {
    val local = status.fullStatus.localPeerState
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            CopyRow(Icons.Default.Badge, stringResource(R.string.nb_address_name), local.fqdn)
            CopyRow(Icons.Default.Lan, "IPv4", local.address)
            if (local.ipv6.isNotEmpty()) CopyRow(Icons.Default.Lan, "IPv6", local.ipv6.substringBefore('/'))
        }
    }
}

/** The proxy apps point at, as a URI they can paste. */
@Composable
private fun SocksCard() {
    val context = LocalContext.current
    val address = GlobalSettings.getSocksAddress(context)
    val user = GlobalSettings.getSocksUser(context)
    val pass = GlobalSettings.getSocksPass(context)
    val creds = if (user.isNotEmpty() && pass.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(pass)}@" else ""
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column {
            CopyRow(Icons.Default.SettingsEthernet, stringResource(R.string.nb_socks_title), "socks5://$creds$address", shown = "socks5://$address")
            if (GlobalSettings.isDnsProxyEnabled(context)) {
                CopyRow(Icons.Default.Dns, stringResource(R.string.nb_settings_dns_proxy), GlobalSettings.getDnsProxyAddress(context))
            }
        }
    }
}

@Composable
private fun CopyRow(icon: ImageVector, label: String, value: String, shown: String = value) {
    if (value.isEmpty()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(label) },
        headlineContent = { Text(shown, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = { clipboard.copyText(scope, value) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy)) }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

/** Sign-in refused because of the extra DNS names asked for: the way out is to drop them. */
@Composable
private fun DnsLabelsRefusedCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Label, null) },
            headlineContent = { Text(stringResource(R.string.nb_dns_labels_refused)) },
            supportingContent = { HelpText(stringResource(R.string.nb_dns_labels_refused_desc)) },
            trailingContent = {
                TextButton(onClick = {
                    GlobalSettings.setDnsLabels(context, "")
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
        )
    }
}

/** The exit node in use; [works] false when the internet does not answer through it. */
@Composable
private fun ExitNodeRow(current: NbNetwork?, via: String?, works: Boolean?, onTurnOff: () -> Unit, onClick: () -> Unit) {
    val dead = current != null && works == false
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            leadingContent = {
                Icon(
                    if (dead) Icons.Default.PublicOff else Icons.Default.Public, null,
                    tint = if (dead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            overlineContent = { Text(stringResource(R.string.nb_exit_node)) },
            headlineContent = { Text(current?.id ?: stringResource(R.string.nb_exit_node_none)) },
            supportingContent = when {
                dead -> { { HelpText(stringResource(R.string.nb_exit_node_dead), color = MaterialTheme.colorScheme.error) } }
                current != null && via != null -> { { Text(stringResource(R.string.nb_exit_node_via, via)) } }
                else -> null
            },
            trailingContent = {
                if (dead) TextButton(onClick = onTurnOff) { Text(stringResource(R.string.nb_exit_node_off)) }
                else Icon(Icons.Default.ChevronRight, null)
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

/** A destination on the main screen; [icon2], when set, stands beside [icon]. */
data class MenuEntry(val title: String, val icon: ImageVector, val icon2: ImageVector? = null, val onClick: () -> Unit)

/** How many menu columns [width] holds, dividing [entries] evenly. */
fun menuColumnsFor(width: Dp, entries: Int = 4): Int {
    val fit = ((width + 16.dp) / 116.dp).toInt().coerceIn(2, 4)
    return (fit downTo 2).firstOrNull { entries % it == 0 } ?: 2
}

@Composable
fun MenuGrid(columns: Int, entries: List<MenuEntry>, cardHeight: Dp = 96.dp, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        entries.chunked(columns).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, entry ->
                    if (i > 0) Spacer(Modifier.width(16.dp))
                    MenuCard(entry.title, entry.icon, Modifier.weight(1f).fillMaxHeight().heightIn(min = cardHeight), entry.icon2, entry.onClick)
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.width(16.dp))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, icon2: ImageVector? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
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
 * daemon may, so a refusal here would say nothing about the server.
 */
suspend fun probeServer(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (url.startsWith("http://", ignoreCase = true)) return@withContext null
    runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().close()
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}

/**
 * When the session ends, and renewing it: the server signs SSO peers out
 * after its expiry (24 h by default), and renewal keeps the connection up.
 */
@Composable
private fun SessionRow(expiresAt: String) {
    val context = LocalContext.current
    val extend by io.github.bropines.birdsocks.core.ExtendFlow.state.collectAsState()
    val until = parseRfc3339Millis(expiresAt) ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) }
    }
    if (extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser) {
        val url = (extend as io.github.bropines.birdsocks.core.ExtendFlow.State.Browser).url
        LaunchedEffect(url) { openUrl(context, url) }
    }
    val left = until - now
    val soon = left < 60 * 60_000
    val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
    Card(
        onClick = { io.github.bropines.birdsocks.core.ExtendFlow.start(context) },
        colors = CardDefaults.cardColors(containerColor = if (soon) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer)
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary) },
            overlineContent = { Text(stringResource(R.string.nb_session_title)) },
            headlineContent = { Text(stringResource(R.string.nb_session_until, clock, durationWords(context, left))) },
            supportingContent = {
                when (val e = extend) {
                    is io.github.bropines.birdsocks.core.ExtendFlow.State.Failed -> Text(e.message, color = MaterialTheme.colorScheme.error)
                    is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser -> Text(stringResource(R.string.nb_login_waiting))
                    else -> Text(stringResource(R.string.nb_session_extend_hint))
                }
            },
            trailingContent = {
                if (extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Working || extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else Icon(Icons.Default.Refresh, stringResource(R.string.nb_session_extend))
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

/** "19 h", "45 min": how long until a moment. */
fun durationWords(context: Context, millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) context.getString(R.string.nb_hours, minutes / 60) else context.getString(R.string.nb_minutes, minutes)
}
