package io.github.bropines.birdsocks.core

import io.github.bropines.birdsocks.models.NbNetwork
import java.net.Inet6Address
import java.net.InetAddress

/**
 * What the VPN routes into the tunnel, from NetBird's state alone (pure: no
 * Android, so it can be checked without a device).
 *
 * The proxy behind the tunnel splits per destination anyway — the overlay
 * and peer-routed ranges through the netstack, the rest directly — so any
 * route works; the choice is about overhead (hev, SOCKS and Go for every
 * packet, no ICMP) and about rebuilds, since Android fixes routes at
 * establish(). By default only NetBird's ranges go in; the default route
 * joins when an exit node is selected, when a domain route is (its addresses
 * are known only after DNS and move constantly), or when the user asks.
 */
object TunRoutes {
    /** The one DNS server the VPN announces, answered inside the proxy (netstack/tundns.go). */
    const val DNS_IP = "198.18.0.2"
    /** The overlay when nothing better is known: NetBird's default range, which CGNAT shares. */
    const val FALLBACK_OVERLAY = "100.64.0.0/10"

    data class RouteSet(
        /** IPv4 prefixes, "a.b.c.d/n", the DNS address included. */
        val v4: List<String>,
        /** IPv6 prefixes. */
        val v6: List<String>,
        val defaultV4: Boolean,
        val defaultV6: Boolean,
        /** Why the default route is in, for the log and the settings screen; null without it. */
        val defaultReason: String?,
    ) {
        /** Equal keys need no rebuild. */
        val key: String get() = listOf(v4.sorted(), v6.sorted(), defaultV4, defaultV6).toString()
    }

    /**
     * @param localIp the peer's overlay address with its prefix, "100.92.1.2/16", or empty
     * @param localIpv6 the same for IPv6, or empty
     * @param cachedOverlay prefixes remembered from an earlier status, for before the first one
     * @param networks ListNetworks: what is selected decides
     */
    fun compute(
        localIp: String,
        localIpv6: String,
        cachedOverlay: List<String>,
        networks: List<NbNetwork>,
        routeAll: Boolean,
        ipv6Default: Boolean,
    ): RouteSet {
        val v4 = linkedSetOf("$DNS_IP/32")
        val v6 = linkedSetOf<String>()
        val overlay = overlayPrefixes(localIp, localIpv6).ifEmpty { cachedOverlay.mapNotNull(::normalize) }
        overlay.forEach { if (':' in it) v6 += it else v4 += it }
        if (overlay.none { ':' !in it }) v4 += FALLBACK_OVERLAY

        val selected = networks.filter { it.selected }
        val exit = selected.any { it.isExitNode }
        val domainRoute = selected.any { !it.isExitNode && it.domains.isNotEmpty() }
        for (n in selected) {
            if (n.isExitNode || n.domains.isNotEmpty()) continue
            for (range in n.range.split(',')) {
                val p = normalize(range.trim()) ?: continue
                if (':' in p) v6 += p else v4 += p
            }
        }
        val reason = when {
            exit -> "exit node"
            domainRoute -> "domain route"
            routeAll -> "route all"
            else -> null
        }
        val defaultV4 = reason != null
        return RouteSet(v4.toList(), v6.toList(), defaultV4, defaultV4 && ipv6Default, reason)
    }

    /** The networks the overlay addresses sit in: "100.92.1.2/16" → "100.92.0.0/16". */
    fun overlayPrefixes(localIp: String, localIpv6: String): List<String> = buildList {
        if (localIp.isNotBlank()) normalize(if ('/' in localIp) localIp else "$localIp/16")?.let(::add)
        if (localIpv6.isNotBlank()) normalize(if ('/' in localIpv6) localIpv6 else "$localIpv6/64")?.let(::add)
    }

    /** A CIDR masked to its network address, or null when it is not one (a domain route's placeholder). */
    fun normalize(cidr: String): String? {
        val (host, bitsText) = cidr.split('/').takeIf { it.size == 2 } ?: return null
        val bits = bitsText.toIntOrNull() ?: return null
        // Literals only: getByName would look a name up.
        if (!V4_LITERAL.matches(host) && !(':' in host && V6_LITERAL.matches(host))) return null
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val bytes = addr.address
        if (bits < 0 || bits > bytes.size * 8) return null
        for (i in bytes.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep)) and 0xff).toByte()
        }
        val masked = InetAddress.getByAddress(bytes)
        val text = if (masked is Inet6Address) masked.hostAddress!!.substringBefore('%').let(::compressV6) else masked.hostAddress!!
        return "$text/$bits"
    }

    private val V4_LITERAL = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    private val V6_LITERAL = Regex("^[0-9a-fA-F:.]+$")

    /** Java prints IPv6 uncompressed; Android's VpnService takes either, the key wants one form. */
    private fun compressV6(full: String): String {
        val groups = full.split(':').map { it.trimStart('0').ifEmpty { "0" } }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] == "0") {
                var j = i
                while (j < groups.size && groups[j] == "0") j++
                if (j - i > bestLen && j - i > 1) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestStart < 0) return groups.joinToString(":")
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return "$head::$tail"
    }
}
