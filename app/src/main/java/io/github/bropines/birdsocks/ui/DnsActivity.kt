package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.CompactSearchBar
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.NetAddr
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.PredictiveBackContainer
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.models.NbDnsRecord
import io.github.bropines.birdsocks.models.NbDnsTable
import io.github.bropines.birdsocks.models.NbDnsZone
import io.github.bropines.birdsocks.models.NbNsGroup
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// DNS on the wire: enough to ask one question and read the answer. The app has no resolver of
// its own to lean on — Android's would go to the network's servers, not to NetBird's.
// ---------------------------------------------------------------------------------------------

private const val TYPE_A = 1
private const val TYPE_CNAME = 5
private const val TYPE_AAAA = 28
private const val DNS_TIMEOUT_MS = 2500

/** A [type] question for [domain] with recursion desired, as transaction [id]. */
private fun buildDnsQuery(domain: String, type: Int = TYPE_A, id: Int = Random.nextInt(0x10000)): ByteArray {
    val clean = domain.trim().trimEnd('.').ifBlank { "netbird.io" }
    val baos = java.io.ByteArrayOutputStream()
    val dos = java.io.DataOutputStream(baos)
    dos.writeShort(id)
    // Flags: a standard query, recursion desired.
    dos.writeShort(0x0100)
    // One question, no answer, authority or additional records.
    dos.writeShort(1)
    dos.writeShort(0)
    dos.writeShort(0)
    dos.writeShort(0)
    for (part in clean.split(".")) {
        if (part.isEmpty()) continue
        val bytes = part.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        dos.writeByte(bytes.size)
        dos.write(bytes)
    }
    dos.writeByte(0)
    dos.writeShort(type)
    // Class IN.
    dos.writeShort(1)
    return baos.toByteArray()
}

private data class DnsRecord(val type: String, val value: String)

/** What came back for one question: the server's verdict and its records, or why nothing did. */
private sealed interface DnsResult {
    data class Answer(val rcode: Int, val records: List<DnsRecord>, val millis: Long) : DnsResult
    /** [reason] null: nothing arrived in time. */
    data class NoAnswer(val reason: String?) : DnsResult
}

private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

/** The offset after the name at [start]; a compression pointer ends a name. */
private fun skipName(b: ByteArray, start: Int, end: Int): Int {
    var pos = start
    while (pos < end) {
        val len = b[pos].toInt() and 0xff
        when {
            len == 0 -> return pos + 1
            len and 0xc0 == 0xc0 -> return pos + 2
            else -> pos += len + 1
        }
    }
    throw IllegalArgumentException("truncated name")
}

/** The name at [start], following compression pointers (a bounded number of them). */
private fun readName(b: ByteArray, start: Int, end: Int): String {
    val labels = ArrayList<String>()
    var pos = start
    var jumps = 0
    while (pos < end && jumps < 16) {
        val len = b[pos].toInt() and 0xff
        when {
            len == 0 -> break
            len and 0xc0 == 0xc0 -> {
                if (pos + 1 >= end) break
                pos = ((len and 0x3f) shl 8) or (b[pos + 1].toInt() and 0xff)
                jumps++
            }
            else -> {
                if (pos + 1 + len > end) break
                labels += String(b, pos + 1, len, Charsets.US_ASCII)
                pos += len + 1
            }
        }
    }
    return labels.joinToString(".")
}

/** Reads the reply to the question with transaction [id]: its rcode and the A, AAAA and CNAME answers. */
private fun parseDnsReply(b: ByteArray, length: Int, id: Int, millis: Long): DnsResult {
    if (length < 12 || u16(b, 0) != id) return DnsResult.NoAnswer("malformed reply")
    val rcode = b[3].toInt() and 0x0f
    val questions = u16(b, 4)
    val answers = u16(b, 6)
    val records = ArrayList<DnsRecord>()
    runCatching {
        var pos = 12
        repeat(questions) { pos = skipName(b, pos, length) + 4 }
        for (i in 0 until answers) {
            pos = skipName(b, pos, length)
            if (pos + 10 > length) break
            val type = u16(b, pos)
            val rdLength = u16(b, pos + 8)
            val rData = pos + 10
            if (rData + rdLength > length) break
            when (type) {
                TYPE_A -> if (rdLength == 4) records += DnsRecord("A", InetAddress.getByAddress(b.copyOfRange(rData, rData + 4)).hostAddress.orEmpty())
                TYPE_AAAA -> if (rdLength == 16) records += DnsRecord("AAAA", InetAddress.getByAddress(b.copyOfRange(rData, rData + 16)).hostAddress.orEmpty())
                TYPE_CNAME -> records += DnsRecord("CNAME", readName(b, rData, length))
            }
            pos = rData + rdLength
        }
    }
    return DnsResult.Answer(rcode, records, millis)
}

/** One question to [host]:[port] over plain UDP: how a DNS client reaches the DNS proxy. */
private fun askUdp(host: String, port: Int, name: String, type: Int = TYPE_A): DnsResult = try {
    val id = Random.nextInt(0x10000)
    val query = buildDnsQuery(name, type, id)
    DatagramSocket().use { socket ->
        socket.soTimeout = DNS_TIMEOUT_MS
        val started = SystemClock.elapsedRealtime()
        socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
        val buf = ByteArray(4096)
        val reply = DatagramPacket(buf, buf.size)
        socket.receive(reply)
        parseDnsReply(buf, reply.length, id, SystemClock.elapsedRealtime() - started)
    }
} catch (e: SocketTimeoutException) {
    DnsResult.NoAnswer(null)
} catch (e: Exception) {
    DnsResult.NoAnswer(e.message ?: e.javaClass.simpleName)
}

/** A and AAAA for [name] from [host]:[port], read as one answer. */
private fun lookUp(host: String, port: Int, name: String): DnsResult {
    val a = askUdp(host, port, name, TYPE_A)
    val aaaa = askUdp(host, port, name, TYPE_AAAA)
    return when {
        a is DnsResult.Answer && aaaa is DnsResult.Answer -> DnsResult.Answer(
            rcode = if (a.rcode == 0 || aaaa.rcode == 0) 0 else a.rcode,
            records = (a.records + aaaa.records).distinct(),
            millis = a.millis
        )
        a is DnsResult.Answer -> a
        else -> aaaa
    }
}

/** host and port of a nameserver as NetBird lists it: 1.1.1.1:53, [2606:4700::1111]:53, or a bare address. */
private fun splitServer(server: String): Pair<String, Int> {
    val s = server.trim()
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end > 0) return s.substring(1, end) to (s.substring(end + 1).removePrefix(":").toIntOrNull() ?: 53)
    }
    if (s.count { it == ':' } == 1) return s.substringBefore(':') to (s.substringAfter(':').toIntOrNull() ?: 53)
    return s to 53
}

/**
 * One question to a nameserver through BirdSocks' own SOCKS5 proxy, by UDP
 * ASSOCIATE: the path an app that uses the proxy takes — over the mesh when a
 * peer routes the address, from the phone's own network otherwise. Asked
 * directly, a server behind a routing peer would look dead in proxy mode.
 */
private fun askThroughSocks(context: Context, server: String, name: String): DnsResult = try {
    val (host, port) = splitServer(server)
    val target = InetAddress.getByName(host)
    val proxy = GlobalSettings.getSocksAddress(context)
    val user = GlobalSettings.getSocksUser(context)
    val pass = GlobalSettings.getSocksPass(context)
    Socket().use { tcp ->
        tcp.connect(InetSocketAddress(NetAddr.dialableHost(proxy), NetAddr.port(proxy) ?: GlobalSettings.DEFAULT_SOCKS_PORT), DNS_TIMEOUT_MS)
        tcp.soTimeout = DNS_TIMEOUT_MS
        val out = tcp.getOutputStream()
        val inp = DataInputStream(tcp.getInputStream())
        // The proxy asks for a password only when both halves are set (patch 01).
        val auth = user.isNotEmpty() && pass.isNotEmpty()
        out.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        inp.readUnsignedByte()
        val method = inp.readUnsignedByte()
        if (method == 0xff) throw IllegalStateException("the proxy refused")
        if (method == 2) {
            val u = user.toByteArray()
            val p = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            inp.readUnsignedByte()
            if (inp.readUnsignedByte() != 0) throw IllegalStateException("the proxy refused the password")
        }
        // UDP ASSOCIATE, from any port of this address.
        out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        inp.readUnsignedByte()
        val reply = inp.readUnsignedByte()
        inp.readUnsignedByte()
        val bound = when (inp.readUnsignedByte()) {
            1 -> ByteArray(4).also { inp.readFully(it) }
            4 -> ByteArray(16).also { inp.readFully(it) }
            else -> throw IllegalStateException("the proxy answered oddly")
        }
        val relayPort = inp.readUnsignedShort()
        if (reply != 0) throw IllegalStateException("the proxy said no ($reply)")
        val relay = InetAddress.getByAddress(bound).let { if (it.isAnyLocalAddress) tcp.inetAddress else it }

        // The relay takes datagrams only from the address the association came from.
        DatagramSocket(InetSocketAddress(tcp.localAddress, 0)).use { udp ->
            udp.soTimeout = DNS_TIMEOUT_MS
            val id = Random.nextInt(0x10000)
            val query = buildDnsQuery(name, TYPE_A, id)
            val header = byteArrayOf(0, 0, 0, if (target is Inet4Address) 1 else 4) + target.address +
                byteArrayOf((port shr 8).toByte(), (port and 0xff).toByte())
            val packet = header + query
            val started = SystemClock.elapsedRealtime()
            udp.send(DatagramPacket(packet, packet.size, relay, relayPort))
            val buf = ByteArray(4096)
            val got = DatagramPacket(buf, buf.size)
            udp.receive(got)
            val millis = SystemClock.elapsedRealtime() - started
            val headerLength = when (buf[3].toInt()) {
                1 -> 10
                4 -> 22
                3 -> 7 + (buf[4].toInt() and 0xff)
                else -> throw IllegalStateException("the proxy answered oddly")
            }
            parseDnsReply(buf.copyOfRange(headerLength, got.length), got.length - headerLength, id, millis)
        }
    }
} catch (e: SocketTimeoutException) {
    DnsResult.NoAnswer(null)
} catch (e: Exception) {
    DnsResult.NoAnswer(e.message ?: e.javaClass.simpleName)
}

/** The name a nameserver group is tested with: its first domain, or a public one for a group that takes every name. */
private fun testNameFor(group: NbNsGroup): String =
    group.domains.firstNotNullOfOrNull { it.removePrefix("*.").trimEnd('.').ifEmpty { null } } ?: "netbird.io"

// ---------------------------------------------------------------------------------------------

class DnsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { DnsScreen(onBack = { finish() }) } }
    }
}

/**
 * NetBird's DNS on this device: the DNS proxy other apps ask and a lookup
 * through it, what NetBird's DNS is set to here, and the nameservers the
 * network hands out, each one testable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val daemon by NetbirdState.daemon.collectAsState()
    val streamed by NetbirdState.status.collectAsState()
    val streamedProfile by NetbirdState.profile.collectAsState()
    val running = daemon == NetbirdState.Daemon.Running

    var polled by remember { mutableStateOf<NbStatus?>(null) }
    var config by remember { mutableStateOf<NbConfig?>(null) }
    var readProfile by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // Settings may change them while this is in the background; read again on every return.
    var proxyOn by remember { mutableStateOf(GlobalSettings.isDnsProxyEnabled(context)) }
    var proxyAddress by remember { mutableStateOf(GlobalSettings.getDnsProxyAddress(context)) }

    var proxyTest by remember { mutableStateOf<DnsResult?>(null) }
    var proxyTesting by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var lookup by remember { mutableStateOf<DnsResult?>(null) }
    var looking by remember { mutableStateOf(false) }
    var serverTests by remember { mutableStateOf<Map<String, DnsResult>>(emptyMap()) }
    var serversTesting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var table by remember { mutableStateOf<NbDnsTable?>(null) }
    var tableRead by remember { mutableStateOf(false) }
    var recordFilter by rememberSaveable { mutableStateOf("") }

    fun refresh(manual: Boolean = false) {
        proxyOn = GlobalSettings.isDnsProxyEnabled(context)
        proxyAddress = GlobalSettings.getDnsProxyAddress(context)
        if (!NetbirdState.isRunning) return
        scope.launch {
            if (manual) refreshing = true
            runCatching { Netbird.status() }.onSuccess { polled = it }
            runCatching { Netbird.config() }.onSuccess { config = it }
            table = Netbird.dnsTable(context)
            tableRead = true
            if (NetbirdState.profile.value == null) runCatching { Netbird.activeProfile() }.onSuccess { readProfile = it }
            refreshing = false
        }
    }
    LifecycleResumeEffect(running) {
        refresh()
        onPauseOrDispose { }
    }

    // The table comes and goes with the engine: read again when the connection changes.
    val connState = streamed?.state
    LaunchedEffect(connState) {
        if (NetbirdState.isRunning) { table = Netbird.dnsTable(context); tableRead = true }
    }

    // The stream is live; the read is there until it has spoken.
    val fs = (streamed ?: polled)?.fullStatus
    val fqdn = fs?.localPeerState?.fqdn.orEmpty().trimEnd('.')
    val profile = streamedProfile ?: readProfile
    val proxyHost = NetAddr.dialableHost(proxyAddress)
    val proxyPort = NetAddr.port(proxyAddress) ?: 53

    fun testProxy() {
        proxyTesting = true
        // This device's own name: only NetBird's resolver knows it.
        val name = fqdn.ifEmpty { "netbird.io" }
        scope.launch {
            proxyTest = withContext(Dispatchers.IO) { askUdp(proxyHost, proxyPort, name) }
            proxyTesting = false
        }
    }

    fun runLookup() {
        val name = query.trim()
        if (name.isEmpty()) return
        focusManager.clearFocus()
        looking = true
        scope.launch {
            lookup = withContext(Dispatchers.IO) { lookUp(proxyHost, proxyPort, name) }
            looking = false
        }
    }

    fun testServer(key: String, server: String, name: String) {
        serversTesting = serversTesting + key
        scope.launch {
            val result = withContext(Dispatchers.IO) { askThroughSocks(context, server, name) }
            serverTests = serverTests + (key to result)
            serversTesting = serversTesting - key
        }
    }

    PredictiveBackContainer(onBack = onBack, popsInAppState = false) {
        Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_menu_dns), onBack = onBack) }) { padding ->
            // The proxy, the lookups and NetBird's servers all live in the daemon.
            if (!running) {
                DaemonStoppedState(onStarted = {}, modifier = Modifier.padding(padding).fillMaxSize())
                return@Scaffold
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refresh(manual = true) },
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().readableWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        SettingsCard(stringResource(R.string.nb_settings_dns_proxy)) {
                            if (!proxyOn) {
                                SettingsClickableItem(stringResource(R.string.dns_proxy_off_title), stringResource(R.string.dns_proxy_off_desc), Icons.Default.Dns) {
                                    SettingsSections.open(context, SettingsSections.DNS)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        proxyAddress,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { clipboard.copyText(scope, proxyAddress) }) {
                                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy), modifier = Modifier.size(18.dp))
                                    }
                                    TestButton(busy = proxyTesting, onClick = ::testProxy)
                                }
                                HelpText(stringResource(R.string.dns_proxy_test_desc))
                                proxyTest?.let { Spacer(Modifier.height(8.dp)); DnsResultLine(it, strict = true) }
                            }
                        }
                    }

                    if (proxyOn) item {
                        SettingsCard(stringResource(R.string.dns_lookup_title)) {
                            HelpText(stringResource(R.string.dns_lookup_desc))
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactSearchBar(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholderText = stringResource(R.string.dns_lookup_placeholder),
                                    modifier = Modifier.weight(1f),
                                    onSearch = ::runLookup
                                )
                                Spacer(Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = ::runLookup,
                                    enabled = !looking && query.isNotBlank(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.size(height = 40.dp, width = 50.dp)
                                ) {
                                    if (looking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Search, stringResource(R.string.dns_cd_query), modifier = Modifier.size(18.dp))
                                }
                            }
                            lookup?.let { result ->
                                Spacer(Modifier.height(12.dp))
                                LookupResult(result)
                            }
                        }
                    }

                    item {
                        SettingsCard(stringResource(R.string.nb_settings_dns)) {
                            config?.let { c ->
                                InfoRow(stringResource(R.string.dns_state)) { StatePill(on = !c.disableDns) }
                                if (c.disableDns) HelpText(stringResource(R.string.dns_netbird_off_desc), Modifier.padding(bottom = 8.dp))
                            }
                            if (fqdn.isNotEmpty()) {
                                InfoRow(stringResource(R.string.dns_device_name)) {
                                    Text(fqdn, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    IconButton(onClick = { clipboard.copyText(scope, fqdn) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                fqdn.substringAfter('.', "").takeIf { it.isNotEmpty() }?.let { domain ->
                                    InfoRow(stringResource(R.string.dns_domain)) { Text(domain, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                                }
                            }
                            val labels = profile?.let { GlobalSettings.getDnsLabels(context, it) }.orEmpty()
                            InfoRow(stringResource(R.string.nb_settings_dns_labels)) {
                                Text(
                                    labels.ifEmpty { stringResource(R.string.nb_details_none) },
                                    fontFamily = if (labels.isNotEmpty()) FontFamily.Monospace else null,
                                    fontSize = 13.sp,
                                    color = if (labels.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            SettingsClickableItem(stringResource(R.string.dns_settings_link), stringResource(R.string.dns_settings_link_desc), Icons.Default.Settings) {
                                SettingsSections.open(context, SettingsSections.DNS)
                            }
                        }
                    }

                    item {
                        SettingsCard(stringResource(R.string.dns_records_title)) {
                            HelpText(stringResource(R.string.dns_records_desc))
                            // Reverse zones are the client's own, made from the peers': nothing new to read.
                            val zones = table?.zones.orEmpty().filterNot { it.domain.endsWith(".arpa") }
                            when {
                                !tableRead -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                                zones.all { it.records.isEmpty() } -> {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        stringResource(if (table == null) R.string.dns_records_not_connected else R.string.dns_records_none),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                else -> {
                                    // A long table gets a filter: by name or by address.
                                    if (zones.sumOf { it.records.size } > 12) {
                                        Spacer(Modifier.height(10.dp))
                                        CompactSearchBar(
                                            value = recordFilter,
                                            onValueChange = { recordFilter = it },
                                            placeholderText = stringResource(R.string.dns_records_filter),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    val filter = recordFilter.trim()
                                    zones.filter { it.records.isNotEmpty() }.forEach { zone ->
                                        val shown = if (filter.isEmpty()) zone.records
                                            else zone.records.filter { it.name.contains(filter, true) || it.value.contains(filter, true) }
                                        if (shown.isNotEmpty()) {
                                            Spacer(Modifier.height(10.dp))
                                            DnsZone(zone, shown, filtering = filter.isNotEmpty(), onCopy = { clipboard.copyText(scope, it) })
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsCard(stringResource(R.string.dns_servers_title)) {
                            HelpText(stringResource(R.string.dns_servers_desc))
                            val groups = fs?.dnsServers.orEmpty()
                            if (fs == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                            } else if (groups.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.dns_servers_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            groups.forEach { group ->
                                Spacer(Modifier.height(10.dp))
                                NameserverGroup(
                                    group = group,
                                    results = serverTests,
                                    testing = serversTesting,
                                    onCopy = { clipboard.copyText(scope, it) },
                                    onTest = ::testServer
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TestButton(busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dns_test), maxLines = 1, softWrap = false)
        }
    }
}

/** A label on the left, its value on the right. */
@Composable
private fun InfoRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, content = value)
    }
}

@Composable
private fun StatePill(on: Boolean) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(if (on) R.string.nb_on else R.string.nb_off),
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * One line for a result. [strict]: only records count as success — the DNS
 * proxy asked for this device's name has failed if it does not know it. Else
 * any verdict from the server shows it is there.
 */
@Composable
private fun DnsResultLine(result: DnsResult, strict: Boolean = false, withRecords: Boolean = true) {
    val (text, ok) = when (result) {
        is DnsResult.Answer -> {
            val ms = result.millis.toInt()
            val what = when {
                result.rcode == 0 && result.records.isNotEmpty() -> if (withRecords) result.records.joinToString(", ") { it.value } else null
                result.rcode == 0 -> stringResource(R.string.dns_rcode_nodata)
                else -> rcodeText(result.rcode)
            }
            val line = if (what == null) stringResource(R.string.dns_answered, ms) else stringResource(R.string.dns_answered_with, ms, what)
            line to if (strict) result.rcode == 0 && result.records.isNotEmpty() else result.rcode == 0 || result.rcode == 3
        }
        is DnsResult.NoAnswer -> stringResource(R.string.dns_no_answer, result.reason ?: stringResource(R.string.dns_timeout)) to false
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun rcodeText(rcode: Int): String = when (rcode) {
    2 -> stringResource(R.string.dns_rcode_servfail)
    3 -> stringResource(R.string.dns_rcode_nxdomain)
    5 -> stringResource(R.string.dns_rcode_refused)
    else -> stringResource(R.string.dns_rcode_other, rcode)
}

/** The verdict, then each record on its own line. */
@Composable
private fun LookupResult(result: DnsResult) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DnsResultLine(result, withRecords = false)
            if (result is DnsResult.Answer) result.records.forEach { r ->
                Row {
                    Text(r.type, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                    Text(r.value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * One zone of the DNS table: its domain, whose it is and how many names, and
 * when open, every name with its records; a tap on a name copies it. The
 * peers' zone starts closed (Peers lists them), an admin's short zone open.
 */
@Composable
private fun DnsZone(zone: NbDnsZone, records: List<NbDnsRecord>, filtering: Boolean, onCopy: (String) -> Unit) {
    var open by rememberSaveable(zone.domain) { mutableStateOf(zone.custom && zone.records.size <= 8) }
    val names = remember(records) { records.groupBy { it.name } }
    val total = remember(zone.records) { zone.records.distinctBy { it.name }.size }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !filtering) { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    if (zone.custom) Icons.Default.Dns else Icons.Default.Devices,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(zone.domain, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            stringResource(if (zone.custom) R.string.dns_zone_custom else R.string.dns_zone_peers),
                            pluralStringResource(R.plurals.dns_zone_names, total, total),
                            if (zone.search) null else stringResource(R.string.dns_zone_no_search)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!filtering) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open || filtering) {
                names.forEach { (name, recs) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().clickable { onCopy(name) }.padding(start = 42.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text(
                            zoneLabel(name, zone.domain),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.45f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(0.55f)) {
                            recs.forEach { r ->
                                Text(
                                    if (r.type == "A" || r.type == "AAAA") r.value else "${r.type} ${r.value}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** A record's name inside its zone: "grafana" for grafana.example.com, "@" for the zone itself. */
private fun zoneLabel(name: String, zone: String): String = when {
    name.equals(zone, true) -> "@"
    name.endsWith(".$zone", true) -> name.dropLast(zone.length + 1)
    else -> name
}

/** A nameserver group: the names it answers, whether NetBird uses it, and its servers. */
@Composable
private fun NameserverGroup(
    group: NbNsGroup,
    results: Map<String, DnsResult>,
    testing: Set<String>,
    onCopy: (String) -> Unit,
    onTest: (key: String, server: String, name: String) -> Unit
) {
    val ok = group.enabled && group.error.isEmpty()
    val name = testNameFor(group)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    when {
                        ok -> Icons.Default.CheckCircle
                        group.error.isNotEmpty() -> Icons.Default.Error
                        else -> Icons.Default.RemoveCircleOutline
                    },
                    null,
                    tint = when {
                        ok -> MaterialTheme.colorScheme.primary
                        group.error.isNotEmpty() -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group.domains.isEmpty()) stringResource(R.string.nb_details_dns_all) else group.domains.joinToString(", ") { it.trimEnd('.') },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!ok) Text(
                        group.error.ifEmpty { stringResource(R.string.dns_group_off) },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (group.error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            group.servers.forEach { server ->
                val key = group.domains.joinToString(",") + "|" + server
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp, top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(server, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        results[key]?.let { DnsResultLine(it) }
                    }
                    IconButton(onClick = { onCopy(splitServer(server).first) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { onTest(key, server, name) }, enabled = key !in testing, modifier = Modifier.size(32.dp)) {
                        if (key in testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.PlayArrow, stringResource(R.string.dns_cd_test_server), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
