package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbServerState
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.delay

class StatusDetailsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { StatusDetailsScreen(onBack = { finish() }) } }
    }
}

/**
 * Everything the daemon reports about the connection itself: the servers it
 * talks to, the relays and how, NetBird's DNS servers, and this device as
 * the network sees it.
 */
@Composable
fun StatusDetailsScreen(onBack: () -> Unit) {
    val daemon by NetbirdState.daemon.collectAsState()
    val streamed by NetbirdState.status.collectAsState()
    var polled by remember { mutableStateOf(streamed) }
    LaunchedEffect(daemon) {
        while (daemon == NetbirdState.Daemon.Running) {
            runCatching { Netbird.status() }.onSuccess { polled = it }
            delay(3000)
        }
    }
    val status = polled ?: streamed
    val fs = status?.fullStatus
    val on = stringResource(R.string.nb_on)
    val off = stringResource(R.string.nb_off)

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_details_title), onBack = onBack) }) { padding ->
        if (fs == null) {
            EmptyState(icon = Icons.Default.CloudOff, text = stringResource(R.string.nb_details_not_running), modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).readableWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Section(stringResource(R.string.nb_details_servers)) {
                    ServerRow("Management", fs.managementState)
                    ServerRow("Signal", fs.signalState)
                }
            }
            item {
                Section(stringResource(R.string.nb_details_relays)) {
                    if (fs.relays.isEmpty()) Line(stringResource(R.string.nb_details_none))
                    fs.relays.forEach { r ->
                        StateRow(
                            ok = r.available,
                            title = r.uri,
                            detail = listOf(r.transport.ifEmpty { null }, r.error.ifEmpty { null }).filterNotNull().joinToString(" · ")
                        )
                    }
                }
            }
            item {
                Section(stringResource(R.string.nb_details_dns)) {
                    if (fs.dnsServers.isEmpty()) Line(stringResource(R.string.nb_details_none))
                    fs.dnsServers.forEach { g ->
                        StateRow(
                            ok = g.enabled && g.error.isEmpty(),
                            title = g.servers.joinToString(", "),
                            detail = listOf(
                                if (g.domains.isEmpty()) stringResource(R.string.nb_details_dns_all) else g.domains.joinToString(", "),
                                g.error.ifEmpty { null }
                            ).filterNotNull().joinToString("\n")
                        )
                    }
                }
            }
            item {
                val me = fs.localPeerState
                Section(stringResource(R.string.nb_details_device)) {
                    Field(stringResource(R.string.nb_address_name), me.fqdn)
                    Field("IPv4", me.ip)
                    Field("IPv6", me.ipv6)
                    Field(stringResource(R.string.nb_peer_pubkey), me.pubKey, mono = true)
                    if (me.wgPort > 0) Field(stringResource(R.string.nb_details_wg_port), me.wgPort.toString())
                    Field(stringResource(R.string.nb_details_serves), me.networks.joinToString(", ").ifEmpty { stringResource(R.string.nb_details_none) })
                    Field(stringResource(R.string.nb_details_lazy), if (fs.lazyConnectionEnabled) on else off)
                    Field("Rosenpass", if (me.rosenpassEnabled) on + if (me.rosenpassPermissive) " (permissive)" else "" else off)
                    if (fs.forwardingRules > 0) Field(stringResource(R.string.nb_details_forwarding), fs.forwardingRules.toString())
                    Field(stringResource(R.string.nb_details_ssh), if (fs.sshServerState.enabled) on else off)
                    fs.sshServerState.sessions.forEach { s ->
                        Line("${s.jwtUsername.ifEmpty { s.username }}@${s.remoteAddress}  ${s.command}".trim())
                    }
                    Field(stringResource(R.string.nb_details_version), status.daemonVersion)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ServerRow(name: String, s: NbServerState) {
    StateRow(ok = s.connected, title = name, detail = listOf(s.url, s.error.ifEmpty { null }).filterNotNull().joinToString("\n"))
}

@Composable
private fun StateRow(ok: Boolean, title: String, detail: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    if (value.isEmpty()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
