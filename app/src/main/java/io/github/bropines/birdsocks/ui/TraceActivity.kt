package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbTrace
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch

class TraceActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PEER_IP = "peer_ip"
        fun intent(context: Context, peerIp: String? = null) =
            Intent(context, TraceActivity::class.java).apply { peerIp?.let { putExtra(EXTRA_PEER_IP, it) } }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val peer = intent.getStringExtra(EXTRA_PEER_IP).orEmpty()
        setContent { BirdSocksTheme { TraceScreen(peer, onBack = { finish() }) } }
    }
}

/**
 * Asks NetBird's firewall what it would do with one packet between this
 * device and a peer: each step it takes, and which ACL rule decides.
 */
@Composable
fun TraceScreen(initialPeer: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by NetbirdState.status.collectAsState()
    val self = status?.fullStatus?.localPeerState?.address.orEmpty()
    val peers = status?.fullStatus?.peers.orEmpty()
    var peer by remember { mutableStateOf(initialPeer) }
    var inbound by remember { mutableStateOf(true) }
    var protocol by remember { mutableStateOf("tcp") }
    var port by remember { mutableStateOf("22") }
    var result by remember { mutableStateOf<NbTrace?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickPeer by remember { mutableStateOf(false) }
    val peerName = peers.firstOrNull { it.ip.substringBefore('/') == peer }?.fqdn?.substringBefore('.')

    fun run() {
        val p = port.toIntOrNull() ?: 0
        busy = true; error = null; result = null
        scope.launch {
            runCatching {
                if (inbound) Netbird.trace(peer, self, protocol, 40000, p, "in")
                else Netbird.trace(self, peer, protocol, 40000, p, "out")
            }.onSuccess { result = it }.onFailure { error = it.message }
            busy = false
        }
    }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_trace_title), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).readableWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HelpText(stringResource(R.string.nb_trace_desc)) }
            item {
                OutlinedTextField(
                    value = peer, onValueChange = { peer = it.trim() },
                    label = { Text(stringResource(R.string.nb_trace_peer)) },
                    supportingText = peerName?.let { { Text(it) } },
                    trailingIcon = { IconButton(onClick = { pickPeer = true }) { Icon(Icons.Default.Devices, stringResource(R.string.nb_trace_pick)) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = inbound, onClick = { inbound = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.nb_trace_in)) }
                    SegmentedButton(selected = !inbound, onClick = { inbound = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.nb_trace_out)) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    listOf("tcp", "udp", "icmp").forEach { p ->
                        FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p.uppercase()) })
                    }
                    if (protocol != "icmp") {
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.nb_trace_port)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            item {
                Button(onClick = ::run, enabled = !busy && peer.isNotEmpty() && self.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nb_trace_run))
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            result?.let { r ->
                item {
                    val ok = r.finalDisposition || r.stages.lastOrNull()?.allowed == true
                    Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                        ListItem(
                            leadingContent = { Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Block, null) },
                            headlineContent = { Text(stringResource(if (ok) R.string.nb_trace_allowed else R.string.nb_trace_denied)) },
                            supportingContent = { Text(r.stages.lastOrNull()?.message.orEmpty()) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
                items(r.stages) { s ->
                    ListItem(
                        leadingContent = { Icon(if (s.allowed) Icons.Default.Check else Icons.Default.Remove, null, tint = if (s.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(listOfNotNull(s.message, s.forwardingDetails).joinToString("\n")) }
                    )
                }
            }
        }
    }

    if (pickPeer) {
        PickerSheet(
            title = stringResource(R.string.nb_trace_pick),
            options = peers.sortedBy { it.fqdn }.map { PickerOption(it.ip.substringBefore('/'), it.fqdn.substringBefore('.'), Icons.Default.Computer, supporting = it.ip.substringBefore('/')) },
            selected = peer,
            onPick = { peer = it },
            onDismiss = { pickPeer = false }
        )
    }
}
