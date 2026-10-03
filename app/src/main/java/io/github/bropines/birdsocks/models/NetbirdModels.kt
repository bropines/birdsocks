package io.github.bropines.birdsocks.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The daemon's answers, as the bridge hands them over: daemon.proto's
 * messages in protobuf JSON (appctr/rpc.go). Names are protobuf's JSON names —
 * lowerCamelCase, except fields the .proto spells in capitals (IP, URL, ID).
 * 64-bit integers arrive quoted, timestamps as RFC 3339 strings, durations as
 * "0.0123s". Only what the app reads is declared; AppJson ignores the rest.
 */

/** StatusResponse: [status] is the daemon's connection state (see [NbConnState]). */
@Serializable
data class NbStatus(
    val status: String = "",
    val fullStatus: NbFullStatus = NbFullStatus(),
    val daemonVersion: String = "",
    /** RFC 3339, absent while the session has no expiry (setup-key peers). */
    val sessionExpiresAt: String? = null
) {
    val state: NbConnState get() = NbConnState.of(status)
}

/** The daemon's internal.StatusType values. */
enum class NbConnState {
    Idle, Connecting, Connected, NeedsLogin, LoginFailed, SessionExpired, Unknown;

    /** The user has to sign in again before anything connects. */
    val needsLogin: Boolean get() = this == NeedsLogin || this == LoginFailed || this == SessionExpired

    companion object {
        fun of(raw: String): NbConnState = entries.firstOrNull { it.name == raw } ?: Unknown
    }
}

@Serializable
data class NbFullStatus(
    val managementState: NbServerState = NbServerState(),
    val signalState: NbServerState = NbServerState(),
    val localPeerState: NbLocalPeer = NbLocalPeer(),
    val peers: List<NbPeer> = emptyList(),
    val relays: List<NbRelay> = emptyList(),
    val dnsServers: List<NbNsGroup> = emptyList(),
    val events: List<NbEvent> = emptyList(),
    val lazyConnectionEnabled: Boolean = false,
    /** Moves whenever the set of networks changes; ListNetworks is worth re-reading then. */
    val networksRevision: Long = 0
)

/** ManagementState and SignalState. */
@Serializable
data class NbServerState(
    @SerialName("URL") val url: String = "",
    val connected: Boolean = false,
    val error: String = ""
)

@Serializable
data class NbLocalPeer(
    @SerialName("IP") val ip: String = "",
    val pubKey: String = "",
    val fqdn: String = "",
    val ipv6: String = "",
    val rosenpassEnabled: Boolean = false,
    val rosenpassPermissive: Boolean = false,
    val networks: List<String> = emptyList(),
    val wgPort: Int = 0
) {
    /** The overlay address without its prefix length: 100.92.1.2/16 → 100.92.1.2. */
    val address: String get() = ip.substringBefore('/')
}

@Serializable
data class NbPeer(
    @SerialName("IP") val ip: String = "",
    val ipv6: String = "",
    val pubKey: String = "",
    val fqdn: String = "",
    /** Connected, Connecting or Idle (lazy connections stay Idle until used). */
    val connStatus: String = "",
    val connStatusUpdate: String? = null,
    val relayed: Boolean = false,
    val relayAddress: String = "",
    val localIceCandidateType: String = "",
    val remoteIceCandidateType: String = "",
    val localIceCandidateEndpoint: String = "",
    val remoteIceCandidateEndpoint: String = "",
    val lastWireguardHandshake: String? = null,
    val bytesRx: Long = 0,
    val bytesTx: Long = 0,
    val rosenpassEnabled: Boolean = false,
    /** Ranges this peer routes for: the networks it is a routing peer of. */
    val networks: List<String> = emptyList(),
    /** A protobuf Duration, "0.012345s". */
    val latency: String? = null
) {
    val connected: Boolean get() = connStatus == "Connected"
    val connecting: Boolean get() = connStatus == "Connecting"
    val address: String get() = ip.substringBefore('/')

    /** The name before the first dot: laptop.netbird.cloud → laptop. */
    val shortName: String get() = fqdn.substringBefore('.').ifEmpty { address }

    /** Latency in milliseconds, or null when the daemon has not measured one. */
    val latencyMs: Double?
        get() = latency?.removeSuffix("s")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { it * 1000 }

    /** Routes the whole internet: an exit node. */
    val isExitNode: Boolean get() = networks.any { it == "0.0.0.0/0" || it == "::/0" }
}

@Serializable
data class NbRelay(
    @SerialName("URI") val uri: String = "",
    val available: Boolean = false,
    val error: String = "",
    val transport: String = ""
)

@Serializable
data class NbNsGroup(
    val servers: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val enabled: Boolean = false,
    val error: String = ""
)

@Serializable
data class NbEvent(
    val id: String = "",
    /** INFO, WARNING, ERROR, CRITICAL. */
    val severity: String = "INFO",
    /** NETWORK, DNS, AUTHENTICATION, CONNECTIVITY, SYSTEM. */
    val category: String = "",
    val message: String = "",
    val userMessage: String = "",
    val timestamp: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/** One entry of ListNetworks: a network route, a domain route, or an exit node. */
@Serializable
data class NbNetwork(
    @SerialName("ID") val id: String = "",
    val range: String = "",
    val selected: Boolean = false,
    val domains: List<String> = emptyList(),
    val resolvedIPs: Map<String, NbIpList> = emptyMap()
) {
    val isExitNode: Boolean
        get() = range.split(',').map { it.trim() }.any { it == "0.0.0.0/0" || it == "::/0" }
}

@Serializable
data class NbIpList(val ips: List<String> = emptyList())

@Serializable
data class NbNetworks(val routes: List<NbNetwork> = emptyList())

@Serializable
data class NbLoginResponse(
    val needsSSOLogin: Boolean = false,
    val userCode: String = "",
    val verificationURI: String = "",
    val verificationURIComplete: String = ""
)

@Serializable
data class NbWaitSsoResponse(val email: String = "")

/** GetConfigResponse: the active profile's settings. */
@Serializable
data class NbConfig(
    val managementUrl: String = "",
    val adminURL: String = "",
    /** Masked by the daemon when set. */
    val preSharedKey: String = "",
    val wireguardPort: Long = 0,
    val mtu: Long = 0,
    val disableAutoConnect: Boolean = false,
    val serverSSHAllowed: Boolean = false,
    val rosenpassEnabled: Boolean = false,
    val rosenpassPermissive: Boolean = false,
    val lazyConnectionEnabled: Boolean = false,
    val blockInbound: Boolean = false,
    val disableDns: Boolean = false,
    val disableClientRoutes: Boolean = false,
    val disableServerRoutes: Boolean = false,
    val blockLanAccess: Boolean = false,
    val disableIpv6: Boolean = false
)
