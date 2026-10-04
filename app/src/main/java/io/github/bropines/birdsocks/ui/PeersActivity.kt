package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.CompactSearchBar
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.formatFileSize
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbPeer
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.delay

class PeersActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { PeersScreen(onBack = { finish() }) } }
    }
}

private enum class PeerFilter { All, Connected, Connecting, Idle }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit) {
    val status by NetbirdState.status.collectAsState()
    val daemon by NetbirdState.daemon.collectAsState()
    // The stream speaks when a peer's state moves; byte counters and latency
    // move without it, so a visible list re-reads them every few seconds.
    var polled by remember { mutableStateOf(status) }
    LaunchedEffect(daemon) {
        while (daemon == NetbirdState.Daemon.Running) {
            runCatching { Netbird.status() }.onSuccess { polled = it }
            delay(3000)
        }
    }
    val shown = polled ?: status
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PeerFilter.All) }
    var openKey by remember { mutableStateOf<String?>(null) }

    val peers = shown?.fullStatus?.peers.orEmpty()
        .filter {
            when (filter) {
                PeerFilter.All -> true
                PeerFilter.Connected -> it.connected
                PeerFilter.Connecting -> it.connecting
                PeerFilter.Idle -> !it.connected && !it.connecting
            }
        }
        .filter { query.isBlank() || it.fqdn.contains(query, true) || it.ip.contains(query) || it.ipv6.contains(query, true) }
        .sortedWith(compareByDescending<NbPeer> { it.connected }.thenBy { it.fqdn })

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_menu_peers), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            if (daemon != NetbirdState.Daemon.Running) {
                EmptyState(Icons.Default.PowerSettingsNew, stringResource(R.string.nb_peers_daemon_off))
                return@Column
            }
            CompactSearchBar(query, { query = it }, stringResource(R.string.nb_peers_search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (shown?.fullStatus?.lazyConnectionEnabled == true) {
                HelpText(stringResource(R.string.nb_peers_lazy), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            SlidingSegmentedChips(
                options = listOf(
                    stringResource(R.string.nb_peers_filter_all),
                    stringResource(R.string.nb_peers_filter_connected),
                    stringResource(R.string.nb_peers_filter_connecting),
                    stringResource(R.string.nb_peers_filter_idle)
                ),
                selectedIndex = filter.ordinal,
                onOptionSelected = { filter = PeerFilter.entries[it] },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (peers.isEmpty()) {
                EmptyState(Icons.Default.Devices, stringResource(R.string.nb_peers_empty))
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(peers, key = { it.pubKey }) { peer ->
                        PeerRow(peer) { openKey = peer.pubKey }
                    }
                }
            }
        }
    }

    val open = shown?.fullStatus?.peers?.firstOrNull { it.pubKey == openKey }
    if (open != null) {
        ModalBottomSheet(onDismissRequest = { openKey = null }, sheetState = rememberFullSheetState()) {
            PeerDetails(open)
        }
    }
}

@Composable
private fun PeerRow(peer: NbPeer, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickableRow(onClick),
        leadingContent = { StatusDot(peer) },
        headlineContent = { Text(peer.shortName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(peer.address, pathWord(peer), peer.latencyMs?.let { "%.0f ms".format(it) }).joinToString(" · "),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        },
        trailingContent = {
            if (peer.isExitNode) Icon(Icons.Default.Public, stringResource(R.string.nb_exit_node), tint = MaterialTheme.colorScheme.primary)
        }
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(onClick = onClick)

@Composable
private fun StatusDot(peer: NbPeer) {
    val color = when {
        peer.connected -> Color(0xFF43A047)
        peer.connecting -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun pathWord(peer: NbPeer): String? = when {
    !peer.connected -> null
    peer.relayed -> stringResource(R.string.nb_peer_relayed)
    else -> stringResource(R.string.nb_peer_direct)
}

@Composable
private fun PeerDetails(peer: NbPeer) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(peer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(peer.shortName, style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        peer.connected -> stringResource(R.string.nb_peers_filter_connected)
                        peer.connecting -> stringResource(R.string.main_status_connecting)
                        else -> stringResource(R.string.nb_peers_filter_idle)
                    } + (parseRfc3339Millis(peer.connStatusUpdate)?.let { " · " + agoText(context, it, System.currentTimeMillis()) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DetailRow(stringResource(R.string.nb_address_name), peer.fqdn)
        DetailRow("IPv4", peer.address)
        DetailRow("IPv6", peer.ipv6.substringBefore('/'))
        if (peer.connected) {
            DetailRow(stringResource(R.string.nb_peer_path), pathWord(peer) ?: "")
            if (peer.relayed) DetailRow(stringResource(R.string.nb_peer_relay), peer.relayAddress)
            DetailRow(
                stringResource(R.string.nb_peer_ice),
                listOf(peer.localIceCandidateType, peer.remoteIceCandidateType).filter { it.isNotEmpty() }.joinToString(" → ")
            )
            DetailRow(stringResource(R.string.nb_peer_endpoint), peer.remoteIceCandidateEndpoint)
            peer.latencyMs?.let { DetailRow(stringResource(R.string.nb_peer_latency), "%.1f ms".format(it)) }
            parseRfc3339Millis(peer.lastWireguardHandshake)?.let {
                DetailRow(stringResource(R.string.nb_peer_handshake), agoText(context, it, System.currentTimeMillis()))
            }
            if (peer.bytesRx > 0 || peer.bytesTx > 0) {
                DetailRow(stringResource(R.string.nb_peer_transfer), "↓ ${formatFileSize(peer.bytesRx)}  ↑ ${formatFileSize(peer.bytesTx)}")
            }
        }
        if (peer.networks.isNotEmpty()) DetailRow(stringResource(R.string.nb_menu_networks), peer.networks.joinToString(", "))
        if (peer.rosenpassEnabled) DetailRow("Rosenpass", stringResource(R.string.nb_on))
        DetailRow(stringResource(R.string.nb_peer_pubkey), peer.pubKey)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            IconButton(onClick = { clipboard.copyText(scope, value) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy)) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
