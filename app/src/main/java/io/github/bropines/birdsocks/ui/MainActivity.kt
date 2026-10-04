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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.LoginFlow
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
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
    val sessionShown = sessionNeedsAttention(status?.sessionExpiresAt)
    var showAccounts by remember { mutableStateOf(false) }
    val profile by NetbirdState.profile.collectAsState()
    val accounts by rememberAccounts(profile)
    val accountLabel = accounts.firstOrNull { it.profile.name == profile }?.label
        ?: profile?.takeIf { it != "default" }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                subtitle = accountLabel,
                // Stopped too: the sheet starts the daemon for what it does.
                onTitleClick = { showAccounts = true }
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
                // The session lives in Settings → Account; here only when it needs a hand.
                if (sessionShown) status?.sessionExpiresAt?.let { expiry -> item { SessionRow(expiry) } }
                if (networks.any { it.isExitNode }) {
                    item { ExitNodeCard(networks, status, connected = true, onChanged = { networks = it }) }
                }
            }
            item {
                BoxWithConstraints {
                    val entries = listOf(
                        MenuEntry(stringResource(R.string.nb_menu_peers), Icons.Default.Devices) {
                            context.startActivity(Intent(context, PeersActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_menu_networks), Icons.Default.Hub) {
                            context.startActivity(Intent(context, NetworksActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_menu_dns), Icons.Default.Dns) {
                            context.startActivity(Intent(context, DnsActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_expose_title), Icons.Default.Public) {
                            context.startActivity(Intent(context, ExposeActivity::class.java))
                        },
                        MenuEntry(stringResource(R.string.nb_menu_diagnostics), Icons.Default.MonitorHeart) {
                            context.startActivity(DiagnosticsActivity.intent(context))
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
            val vpn by io.github.bropines.birdsocks.core.TunVpnService.running.collectAsState()
            val line = if (vpn && state == CardState.Connected) "$subtitle · VPN" else subtitle
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

/** Sign-in refused because of the extra DNS names asked for: the way out is to drop them. */
@Composable
private fun DnsLabelsRefusedCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        ListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Label, null) },
            supportingContent = { HelpText(stringResource(R.string.nb_dns_labels_refused_desc)) },
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

/** A destination on the main screen; [icon2], when set, stands beside [icon]. */
data class MenuEntry(val title: String, val icon: ImageVector, val icon2: ImageVector? = null, val onClick: () -> Unit)

/** Menu tiles go two to a row, whatever the width (the author's call: never three). */
@Suppress("UNUSED_PARAMETER")
fun menuColumnsFor(width: Dp, entries: Int = 4): Int = 2

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

