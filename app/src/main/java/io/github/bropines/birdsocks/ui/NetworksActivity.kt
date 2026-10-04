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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbNetwork
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
 * The networks other peers route for: ranges and domains this device may
 * reach through them, each one selectable. Exit nodes are on the main screen.
 */
@Composable
fun NetworksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daemon by NetbirdState.daemon.collectAsState()
    val status by NetbirdState.status.collectAsState()
    var networks by remember { mutableStateOf<List<NbNetwork>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val revision = status?.fullStatus?.networksRevision

    suspend fun reload() {
        networks = runCatching { Netbird.networks() }.getOrElse { emptyList() }.filter { !it.isExitNode }.sortedBy { it.id }
    }

    fun change(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            reload()
            busy = false
        }
    }

    LaunchedEffect(daemon, revision) { if (daemon == NetbirdState.Daemon.Running) reload() else networks = null }

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
                daemon != NetbirdState.Daemon.Running -> EmptyState(Icons.Default.PowerSettingsNew, stringResource(R.string.nb_peers_daemon_off))
                list == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                networks.isNullOrEmpty() -> EmptyState(Icons.Default.Hub, stringResource(R.string.nb_networks_empty))
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        io.github.bropines.birdsocks.core.CompactSearchBar(query, { query = it }, stringResource(R.string.nb_networks_search), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    items(list, key = { it.id }) { net ->
                        val peer = status?.fullStatus?.peers?.firstOrNull { net.routedBy(it) }
                        ListItem(
                            leadingContent = { Icon(if (net.domains.isNotEmpty()) Icons.Default.Language else Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(net.id) },
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
                        )
                    }
                }
            }
        }
    }
}
