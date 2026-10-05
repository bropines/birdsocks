package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch

class NetworksActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { NetworksScreen(onBack = { finish() }) } }
    }
}

/**
 * The networks other peers route for: the exit node on top, then the ranges
 * and domains this device may reach through them, each one selectable.
 */
@Composable
fun NetworksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A preview hands in the networks to draw; see LocalDemo.
    val demo = LocalDemo.current
    val daemon by NetbirdState.daemon.collectAsStateOr { it.daemon }
    val status by NetbirdState.status.collectAsStateOr { it.status }
    // Everything ListNetworks gives, exit nodes included; null until it answers.
    var all by remember { mutableStateOf(demo?.networks) }
    // "Use networks" off: the daemon takes no routes, so the list stays empty.
    var routesOff by remember { mutableStateOf(demo?.config?.disableClientRoutes ?: false) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val revision = status?.fullStatus?.networksRevision
    val networks = all?.filter { !it.isExitNode }?.sortedBy { it.id }

    suspend fun reload() {
        all = runCatching { Netbird.networks() }.getOrElse { emptyList() }
    }

    fun change(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            reload()
            busy = false
        }
    }

    LaunchedEffect(daemon, revision) {
        if (demo != null) return@LaunchedEffect
        if (daemon == NetbirdState.Daemon.Running) reload() else all = null
    }
    // Back from Settings, where the switch lives: read it again.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(daemon, resumes) {
        if (demo != null || daemon != NetbirdState.Daemon.Running || resumes == 0) return@LaunchedEffect
        routesOff = runCatching { Netbird.config().disableClientRoutes }.getOrDefault(false)
        if (resumes > 1) reload()
    }

    Scaffold(topBar = {
        AppTopBar(
            title = stringResource(R.string.nb_menu_networks),
            subtitle = networks?.takeIf { it.isNotEmpty() }?.let {
                stringResource(R.string.nb_networks_selected, it.count { n -> n.selected }, it.size)
            },
            onBack = onBack,
            actions = {
                val list = networks.orEmpty()
                if (list.isNotEmpty()) {
                    val allOn = list.all { it.selected }
                    TextButton(enabled = !busy, onClick = {
                        change { if (allOn) Netbird.deselectNetworks(list.map { it.id }) else Netbird.selectNetworks(list.map { it.id }) }
                    }) { Text(stringResource(if (allOn) R.string.nb_networks_none else R.string.nb_networks_all)) }
                }
            }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            val list = networks?.filter { n ->
                query.isBlank() || n.id.contains(query, true) || n.range.contains(query, true) || n.domains.any { it.contains(query, true) }
            }
            when {
                daemon != NetbirdState.Daemon.Running -> DaemonStoppedState(onStarted = {})
                list == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (routesOff) {
                        item { NetworksOffBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    }
                    if (all.orEmpty().any { it.isExitNode }) {
                        item {
                            ExitNodeCard(
                                networks = all.orEmpty(),
                                status = status,
                                connected = status?.state == NbConnState.Connected,
                                onChanged = { all = it },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (networks.isNullOrEmpty()) {
                        item {
                            EmptyState(Icons.Default.Hub, stringResource(R.string.nb_networks_empty), Modifier.fillMaxWidth().padding(vertical = 48.dp))
                        }
                    } else {
                        item {
                            io.github.bropines.birdsocks.core.CompactSearchBar(query, { query = it }, stringResource(R.string.nb_networks_search), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    items(list, key = { it.id }) { net ->
                        val peer = status?.fullStatus?.peers?.firstOrNull { net.routedBy(it) }
                        ListItem(
                            leadingContent = { Icon(if (net.domains.isNotEmpty()) Icons.Default.Language else Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.primary) },
                            supportingContent = {
                                Column {
                                    Text(
                                        if (net.domains.isNotEmpty()) net.domains.joinToString(", ") else net.range,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    val resolved = net.resolvedIPs.values.flatMap { it.ips }
                                    if (resolved.isNotEmpty()) {
                                        Text(resolved.joinToString(", "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                    if (peer != null) Text(stringResource(R.string.nb_networks_via, peer.shortName), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = net.selected,
                                    enabled = !busy,
                                    onCheckedChange = { on ->
                                        change { if (on) Netbird.selectNetworks(listOf(net.id)) else Netbird.deselectNetworks(listOf(net.id)) }
                                    }
                                )
                            }
                        ) { Text(net.id) }
                    }
                }
            }
        }
    }
}

/** "Use networks" is off: nothing here applies until it is on, in Settings → Account. */
@Composable
private fun NetworksOffBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        onClick = { SettingsSections.open(context, SettingsSections.ACCOUNT) },
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.LinkOff, null) },
            supportingContent = { HelpText(stringResource(R.string.nb_networks_off_desc), tapToExpand = false) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(stringResource(R.string.nb_networks_off_title)) }
    }
}
