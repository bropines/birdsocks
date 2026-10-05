package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.TunRoutes
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.models.NbDnsNameserver
import io.github.bropines.birdsocks.models.NbDnsRecord
import io.github.bropines.birdsocks.models.NbDnsTable
import io.github.bropines.birdsocks.models.NbDnsZone
import io.github.bropines.birdsocks.models.NbFullStatus
import io.github.bropines.birdsocks.models.NbIpList
import io.github.bropines.birdsocks.models.NbLocalPeer
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbNsGroup
import io.github.bropines.birdsocks.models.NbPeer
import io.github.bropines.birdsocks.models.NbProfile
import io.github.bropines.birdsocks.models.NbRelay
import io.github.bropines.birdsocks.models.NbServerState
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The made-up network the README and store screenshots are taken in: a small
 * company, Acme, on a self-hosted NetBird server, seen from one phone. Nothing
 * here is anyone's real network — the names, the keys and the domains are
 * invented, and the addresses are NetBird's range and the documentation ones.
 */
object DemoNet {
    private const val SERVER = "https://nb.acme.example:443"
    private const val DOMAIN = "mesh.acme.example"

    /** An RFC 3339 stamp [minutes] before now, as the daemon writes one. */
    private fun ago(minutes: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - minutes * 60_000L))

    private fun inDays(days: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + days * 86_400_000L))

    private fun peer(
        name: String, ip: String, key: String,
        state: String = "Connected", relayed: Boolean = false,
        localIce: String = "", remoteIce: String = "",
        localEp: String = "", remoteEp: String = "",
        latencyMs: Double? = null, rx: Long = 0, tx: Long = 0,
        networks: List<String> = emptyList(), changedMinutesAgo: Long = 95,
    ) = NbPeer(
        ip = "$ip/16",
        pubKey = key,
        fqdn = "$name.$DOMAIN",
        connStatus = state,
        connStatusUpdate = ago(changedMinutesAgo),
        relayed = relayed,
        relayAddress = if (relayed) "rels://nb.acme.example:443" else "",
        localIceCandidateType = localIce,
        remoteIceCandidateType = remoteIce,
        localIceCandidateEndpoint = localEp,
        remoteIceCandidateEndpoint = remoteEp,
        lastWireguardHandshake = if (state == "Connected") ago(1) else null,
        bytesRx = rx,
        bytesTx = tx,
        networks = networks,
        latency = latencyMs?.let { "${it / 1000}s" },
    )

    const val VPS_FRA_KEY = "oJXyD5OVZQz5OAuO2yJKaySKHpJOj9CuLhqUkqMwXxg="

    val peers = listOf(
        peer("vps-fra", "100.92.8.20", VPS_FRA_KEY,
            localIce = "srflx", remoteIce = "host", localEp = "203.0.113.58:51820", remoteEp = "198.51.100.17:51820",
            latencyMs = 38.4, rx = 1_284_000_000, tx = 214_500_000,
            networks = listOf("0.0.0.0/0", "portal.partner.example"), changedMinutesAgo = 182),
        peer("vps-ams", "100.92.8.21", "jLYQkA+eNH+uiG3GUHeV7HRcTD/LLrLHPhSTTIZ+4Fc=", state = "Idle",
            networks = listOf("0.0.0.0/0"), changedMinutesAgo = 26 * 60),
        peer("nas", "100.92.21.5", "unJJm/oSHoNrKsFXJu59awr2qxPDjpLK4NFQV7FZmH8=",
            localIce = "host", remoteIce = "host", localEp = "192.168.1.23:51820", remoteEp = "192.168.1.40:51820",
            latencyMs = 1.2, rx = 3_420_000_000, tx = 96_000_000,
            networks = listOf("192.168.50.0/24")),
        peer("office-gw", "100.92.3.1", "lMx0EdcX8UV5sqoQD7uzT6WT/q7Scki3YuOrWAXwdlo=",
            localIce = "srflx", remoteIce = "srflx", localEp = "203.0.113.58:51820", remoteEp = "198.51.100.62:51820",
            latencyMs = 12.6, rx = 182_400_000, tx = 24_100_000,
            networks = listOf("172.20.0.0/16")),
        peer("desktop-alex", "100.92.12.30", "K5wdfg83xEkhvT9lZOrffxQqcmaMR+Ij0W7djEe0avw=", relayed = true,
            latencyMs = 47.0, rx = 12_800_000, tx = 3_900_000),
        peer("laptop-maria", "100.92.40.12", "W67iYfU7JhUtJjuoOwN81JYuQ0gBJWuIXpyQUfMgsNs=", relayed = true,
            latencyMs = 64.2, rx = 5_600_000, tx = 1_200_000),
        peer("ci-runner", "100.92.33.9", "g/Oep629DXTm3sfz367Mj2RlZmQae6JmDzAR/DVwKRw=", state = "Idle",
            networks = listOf("172.31.8.0/22"), changedMinutesAgo = 140),
        peer("ipad-mini", "100.92.44.2", "V5kNGgCRJokZ8l2dBhLfNZ1gJqJA9FiaXXkfHdl8/vo=", state = "Idle",
            changedMinutesAgo = 3 * 24 * 60),
    )

    val self = NbLocalPeer(
        ip = "100.92.14.7/16",
        pubKey = "UvImZaYMEtKJGF2VDuiBNgkWb2sRPReNbA/TkB/yOaE=",
        fqdn = "pixel-8a.$DOMAIN",
        wgPort = 51820,
    )

    val status = NbStatus(
        status = "Connected",
        daemonVersion = "0.80.0",
        sessionExpiresAt = inDays(21),
        fullStatus = NbFullStatus(
            managementState = NbServerState(SERVER, connected = true),
            signalState = NbServerState(SERVER, connected = true),
            localPeerState = self,
            peers = peers,
            relays = listOf(
                NbRelay("rels://nb.acme.example:443", available = true, transport = "ws"),
                NbRelay("rels://relay-ams.acme.example:443", available = true, transport = "quic"),
                NbRelay("stun:nb.acme.example:3478", available = true),
            ),
            dnsServers = listOf(
                NbNsGroup(servers = listOf("172.20.0.53:53"), domains = listOf("corp.acme.example"), enabled = true),
                NbNsGroup(servers = listOf("1.1.1.1:53", "9.9.9.9:53"), enabled = true),
            ),
            networksRevision = 4,
            forwardingRules = 2,
        ),
    )

    val networks = listOf(
        NbNetwork(id = "exit-frankfurt", range = "0.0.0.0/0", selected = true),
        NbNetwork(id = "exit-amsterdam", range = "0.0.0.0/0"),
        NbNetwork(id = "office-lan", range = "172.20.0.0/16", selected = true),
        NbNetwork(id = "homelab", range = "192.168.50.0/24", selected = true),
        NbNetwork(
            id = "partner-portal", range = "", selected = true,
            domains = listOf("portal.partner.example"),
            resolvedIPs = mapOf("portal.partner.example" to NbIpList(listOf("192.0.2.80", "192.0.2.81"))),
        ),
        NbNetwork(id = "staging", range = "172.31.8.0/22"),
    )

    val config = NbConfig(managementUrl = SERVER, adminURL = SERVER, wireguardPort = 51820, mtu = 1280)

    private fun a(name: String, ip: String) = NbDnsRecord("$name.$DOMAIN", "A", 300, ip)

    val dnsTable = NbDnsTable(
        zones = listOf(
            NbDnsZone(
                domain = "corp.acme.example", custom = true, search = true,
                records = listOf(
                    NbDnsRecord("gitlab.corp.acme.example", "A", 300, "172.20.4.21"),
                    NbDnsRecord("grafana.corp.acme.example", "A", 300, "172.20.4.30"),
                    NbDnsRecord("vault.corp.acme.example", "A", 300, "172.20.4.31"),
                    NbDnsRecord("wiki.corp.acme.example", "A", 300, "172.20.4.22"),
                    NbDnsRecord("ci.corp.acme.example", "CNAME", 300, "gitlab.corp.acme.example"),
                    NbDnsRecord("printer.corp.acme.example", "A", 300, "172.20.9.15"),
                ),
            ),
            NbDnsZone(
                domain = DOMAIN, search = true,
                records = listOf(a("pixel-8a", "100.92.14.7")) +
                    peers.map { NbDnsRecord(it.fqdn, "A", 300, it.address) },
            ),
        ),
        nameservers = listOf(
            NbDnsNameserver(servers = listOf("172.20.0.53:53"), domains = listOf("corp.acme.example")),
            NbDnsNameserver(servers = listOf("1.1.1.1:53", "9.9.9.9:53"), primary = true),
        ),
    )

    val accounts = listOf(
        Account(NbProfile("acme", isActive = true), "nb.acme.example"),
        Account(NbProfile("home"), "netbird.example.org"),
    )

    /** Everything a connected phone shows, in proxy mode. */
    val data = DemoData(
        daemon = NetbirdState.Daemon.Running,
        status = status,
        profile = "acme",
        accounts = accounts,
        networks = networks,
        config = config,
        dnsTable = dnsTable,
        byeDpiAddress = "127.38.201.7" to 41873,
    )

    /** The same with the VPN up and everything routed to the exit node. */
    val vpnData = data.copy(
        vpn = true,
        vpnRoutes = TunRoutes.RouteSet(
            v4 = listOf("0.0.0.0/0", "100.64.0.0/10", TunRoutes.DNS_IP + "/32"),
            v6 = emptyList(),
            defaultV4 = true,
            defaultV6 = false,
            defaultReason = "exit node",
        ),
    )

    /** The app's own preferences on that phone. */
    val prefs: Map<String, Any> = mapOf(
        "device_name" to "pixel-8a",
        "account_email_acme" to "alex@acme.example",
        "dns_labels_acme" to "alex-phone",
        "cp_mode" to "byedpi",
    )

    val vpnPrefs: Map<String, Any> = prefs + ("tun_mode_enabled" to true)
}

/**
 * SharedPreferences that hold [values] and take no writes. The renderer's own
 * keep nothing (and its editor returns null), so the app's settings reach the
 * screens through this.
 */
private class DemoPrefs(private val values: Map<String, Any>) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = NoEdits
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private object NoEdits : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() {}
    }
}

private class DemoContext(base: Context, private val prefs: SharedPreferences) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

/**
 * The look the screenshots are taken in: the amber preset, which matches the
 * orange bird, without the wallpaper's colours. Dark unless [light].
 */
@Composable
fun Showcase(
    data: DemoData = DemoNet.data,
    prefs: Map<String, Any> = DemoNet.prefs,
    light: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = LocalContext.current
    val context = remember(base, prefs) { DemoContext(base, DemoPrefs(prefs)) }
    CompositionLocalProvider(LocalContext provides context, LocalDemo provides data) {
        BirdSocksTheme(
            appTheme = if (light) "light" else "dark",
            themePreset = "amber",
            dynamicColorEnabled = false,
            amoledModeEnabled = false,
            content = content,
        )
    }
}
