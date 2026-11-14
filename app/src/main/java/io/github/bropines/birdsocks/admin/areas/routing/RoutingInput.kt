package io.github.bropines.birdsocks.admin.areas.routing

import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.state.AdminKeys
import java.util.Locale

/**
 * What the Networks, Routes and DNS areas accept as addresses, checked on the
 * phone before anything is proposed. Addresses are parsed by hand: an
 * InetAddress would resolve a name it was handed.
 */
object RoutingInput {
    const val METRIC_MIN = 1
    const val METRIC_MAX = 9999

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.length in 1..3 && p.all(::digit) && (p.length == 1 || p[0] != '0') && p.toInt() <= 255 }
    }

    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || '%' in s || ':' !in s) return false
        var str = s
        val lastColon = s.lastIndexOf(':')
        val tail = s.substring(lastColon + 1)
        if ('.' in tail) {
            if (!isIpv4(tail)) return false
            str = s.substring(0, lastColon + 1) + "0:0"
        }
        fun group(g: String) = g.length in 1..4 && g.all { digit(it) || it.lowercaseChar() in 'a'..'f' }
        val double = str.indexOf("::")
        if (double != str.lastIndexOf("::")) return false
        return if (double >= 0) {
            val left = str.substring(0, double).let { if (it.isEmpty()) emptyList() else it.split(':') }
            val right = str.substring(double + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
            (left + right).all(::group) && left.size + right.size <= 7
        } else {
            str.split(':').let { it.size == 8 && it.all(::group) }
        }
    }

    fun isIp(s: String): Boolean = isIpv4(s) || isIpv6(s)

    /** An address with a prefix length that fits it: 10.0.0.0/24, fd00::/64. */
    fun isCidr(s: String): Boolean {
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.lastIndex) return false
        val bits = s.substring(slash + 1)
        if (bits.length > 3 || !bits.all(::digit)) return false
        val ip = s.substring(0, slash)
        val n = bits.toInt()
        return when {
            isIpv4(ip) -> n in 0..32
            isIpv6(ip) -> n in 0..128
            else -> false
        }
    }

    /** 0.0.0.0/0 or ::/0: every address, an exit node. */
    fun isExitNode(network: String?): Boolean = network?.trim() == "0.0.0.0/0" || network?.trim() == "::/0"

    fun normalizeDomain(raw: String): String = raw.trim().trimEnd('.').lowercase(Locale.ROOT)

    /**
     * A domain name: dot-separated labels of letters, digits, hyphens (and
     * underscores, which service names use), at most 253 characters. With
     * [wildcard] a leading `*.` is allowed. Takes the normalized form.
     */
    fun isDomain(s: String, wildcard: Boolean = false): Boolean {
        val name = if (wildcard && s.startsWith("*.")) s.substring(2) else s
        if (name.isEmpty() || name.length > 253) return false
        return name.split('.').all { l ->
            l.length in 1..63 && l.first() != '-' && l.last() != '-' && l.all { it in 'a'..'z' || digit(it) || it == '-' || it == '_' }
        }
    }

    /** A list typed into one field: commas, semicolons, spaces or new lines between items; no blanks, no repeats. */
    fun list(raw: String): List<String> = raw.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** A route or router metric: 1 (preferred) to 9999. */
    fun metric(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && it.all(::digit) && it.length <= 4 }?.toInt()?.takeIf { it in METRIC_MIN..METRIC_MAX }

    fun port(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && it.all(::digit) && it.length <= 5 }?.toInt()?.takeIf { it in 1..65535 }

    private fun digit(c: Char) = c in '0'..'9'
}

/**
 * This device as the routing areas see it: its peer and the groups it is in,
 * from the peer list. A route or nameserver group that reaches one of these
 * groups is one this device uses.
 */
data class SelfUse(val peerId: String?, val groupIds: Set<String>) {
    val known: Boolean get() = peerId != null

    fun inAny(groups: Collection<String>?): Boolean = groups.orEmpty().any { it in groupIds }

    companion object {
        val NONE = SelfUse(null, emptySet())

        fun of(host: AdminHost): SelfUse {
            val peer = host.data.items(AdminKeys.PEERS).firstOrNull(host::isSelf) ?: return NONE
            return SelfUse(peer.id, peer.groups.map { it.id }.toSet())
        }
    }
}
