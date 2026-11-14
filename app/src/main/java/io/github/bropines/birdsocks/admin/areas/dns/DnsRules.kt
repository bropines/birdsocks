package io.github.bropines.birdsocks.admin.areas.dns

import io.github.bropines.birdsocks.admin.api.ApiDnsRecord
import io.github.bropines.birdsocks.admin.api.ApiDnsRecordRequest
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiDnsZoneRequest
import io.github.bropines.birdsocks.admin.api.ApiNameserver
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroupRequest
import io.github.bropines.birdsocks.admin.areas.routing.RoutingInput
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse

/** One nameserver line of the form. */
data class NameserverForm(val ip: String = "", val port: String = "53", val type: String = "udp")

/** A nameserver group as its form holds it. Primary: every name; otherwise only [domains]. */
data class NsGroupForm(
    val name: String = "",
    val description: String = "",
    val nameservers: List<NameserverForm> = listOf(NameserverForm()),
    val primary: Boolean = false,
    val domains: String = "",
    val searchDomainsEnabled: Boolean = false,
    val groups: List<String> = emptyList(),
    val enabled: Boolean = true,
)

enum class NsProblemKind { NAME, NO_NAMESERVERS, TOO_MANY_NAMESERVERS, BAD_IP, BAD_PORT, DUPLICATE_NAMESERVER, NO_DOMAINS, BAD_DOMAIN, NO_GROUPS }

/** What is wrong with a nameserver group form; [index] is the nameserver line, [value] the bad domain. */
data class NsProblem(val kind: NsProblemKind, val index: Int = -1, val value: String = "")

/** A private zone as its form holds it. */
data class ZoneForm(
    val name: String = "",
    val domain: String = "",
    val enabled: Boolean = true,
    val searchDomain: Boolean = false,
    val groups: List<String> = emptyList(),
)

enum class ZoneProblem { NAME, DOMAIN, NO_GROUPS }

/** A record as its form holds it; [name] may be relative to the zone ("www", "@"). */
data class RecordForm(val name: String = "", val type: String = "A", val content: String = "", val ttl: String = "300")

enum class RecordProblem { NAME, TYPE, CONTENT, TTL }

/** What the DNS area decides without a screen. */
object DnsRules {
    const val MAX_NAMESERVERS = 3
    const val MAX_NAME = 40
    val RECORD_TYPES = listOf("A", "AAAA", "CNAME")

    // --- Nameserver groups ---

    fun form(g: ApiNameserverGroup) = NsGroupForm(
        name = g.name,
        description = g.description,
        nameservers = g.nameservers.map { NameserverForm(it.ip, it.port.toString(), it.nsType) }.ifEmpty { listOf(NameserverForm()) },
        primary = g.primary,
        domains = g.domains.joinToString("\n"),
        searchDomainsEnabled = g.searchDomainsEnabled,
        groups = g.groups,
        enabled = g.enabled,
    )

    fun domains(f: NsGroupForm): List<String> = RoutingInput.list(f.domains).map(RoutingInput::normalizeDomain)

    /** The nameserver lines that hold something: a blank last line is the "add one" line. */
    private fun filled(f: NsGroupForm) = f.nameservers.withIndex().filter { it.value.ip.isNotBlank() || it.value.port.trim() != "53" }

    fun validate(f: NsGroupForm): List<NsProblem> = buildList {
        val name = f.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME) add(NsProblem(NsProblemKind.NAME))
        val servers = filled(f)
        if (servers.isEmpty()) add(NsProblem(NsProblemKind.NO_NAMESERVERS))
        if (servers.size > MAX_NAMESERVERS) add(NsProblem(NsProblemKind.TOO_MANY_NAMESERVERS))
        val seen = mutableSetOf<String>()
        for ((i, ns) in servers) {
            val ip = ns.ip.trim().removePrefix("[").removeSuffix("]")
            if (!RoutingInput.isIp(ip)) add(NsProblem(NsProblemKind.BAD_IP, i, ns.ip.trim()))
            if (RoutingInput.port(ns.port) == null) add(NsProblem(NsProblemKind.BAD_PORT, i, ns.port.trim()))
            if (!seen.add(ip.lowercase() + ":" + ns.port.trim())) add(NsProblem(NsProblemKind.DUPLICATE_NAMESERVER, i, ns.ip.trim()))
        }
        if (!f.primary) {
            val d = domains(f)
            if (d.isEmpty()) add(NsProblem(NsProblemKind.NO_DOMAINS))
            d.firstOrNull { !RoutingInput.isDomain(it) }?.let { add(NsProblem(NsProblemKind.BAD_DOMAIN, value = it)) }
        }
        if (f.groups.isEmpty()) add(NsProblem(NsProblemKind.NO_GROUPS))
    }

    /**
     * The request for a valid [f]. A primary group has no domains and no
     * search domains, as the server requires; a match-domain group keeps both.
     */
    fun request(f: NsGroupForm) = ApiNameserverGroupRequest(
        name = f.name.trim(),
        description = f.description.trim(),
        nameservers = filled(f).map { (_, ns) -> ApiNameserver(ns.ip.trim().removePrefix("[").removeSuffix("]").lowercase(), ns.type.ifEmpty { "udp" }, RoutingInput.port(ns.port) ?: 53) },
        enabled = f.enabled,
        groups = f.groups,
        primary = f.primary,
        domains = if (f.primary) emptyList() else domains(f),
        searchDomainsEnabled = !f.primary && f.searchDomainsEnabled,
    )

    /** Whether this device gets [g]'s nameservers. */
    fun uses(g: ApiNameserverGroup, self: SelfUse): Boolean = self.known && g.enabled && self.inAny(g.groups)

    // --- Settings ---

    /** The groups of [after] that were not in [before] and hold this device. */
    fun selfGroupsTurnedOff(before: List<String>, after: List<String>, self: SelfUse): List<String> =
        after.filter { it !in before && it in self.groupIds }

    // --- Zones ---

    fun form(z: ApiDnsZone) = ZoneForm(z.name, z.domain, z.enabled, z.enableSearchDomain, z.distributionGroups)

    fun validate(f: ZoneForm): List<ZoneProblem> = buildList {
        if (f.name.isBlank() || f.name.trim().length > 255) add(ZoneProblem.NAME)
        if (!RoutingInput.isDomain(RoutingInput.normalizeDomain(f.domain))) add(ZoneProblem.DOMAIN)
        if (f.groups.isEmpty()) add(ZoneProblem.NO_GROUPS)
    }

    fun request(f: ZoneForm) = ApiDnsZoneRequest(
        name = f.name.trim(),
        domain = RoutingInput.normalizeDomain(f.domain),
        enabled = f.enabled,
        enableSearchDomain = f.searchDomain,
        distributionGroups = f.groups,
    )

    fun uses(z: ApiDnsZone, self: SelfUse): Boolean = self.known && z.enabled && self.inAny(z.distributionGroups)

    // --- Records ---

    fun form(r: ApiDnsRecord, zoneDomain: String) = RecordForm(relativeName(r.name, zoneDomain), r.type, r.content, r.ttl.toString())

    /** `www.example.com` in example.com → `www`; the apex → `@`. */
    fun relativeName(fqdn: String, zoneDomain: String): String {
        val n = RoutingInput.normalizeDomain(fqdn)
        val d = RoutingInput.normalizeDomain(zoneDomain)
        return when {
            n == d -> "@"
            n.endsWith(".$d") -> n.removeSuffix(".$d")
            else -> n
        }
    }

    /** The full name a record [name] stands for in [zoneDomain]: `@` or blank is the apex, a name already in the zone stays. */
    fun fqdn(name: String, zoneDomain: String): String {
        val n = RoutingInput.normalizeDomain(name)
        val d = RoutingInput.normalizeDomain(zoneDomain)
        return when {
            n.isEmpty() || n == "@" -> d
            n == d || n.endsWith(".$d") -> n
            else -> "$n.$d"
        }
    }

    fun validate(f: RecordForm, zoneDomain: String): List<RecordProblem> = buildList {
        if (!RoutingInput.isDomain(fqdn(f.name, zoneDomain), wildcard = true)) add(RecordProblem.NAME)
        val content = f.content.trim()
        when (f.type) {
            "A" -> if (!RoutingInput.isIpv4(content)) add(RecordProblem.CONTENT)
            "AAAA" -> if (!RoutingInput.isIpv6(content)) add(RecordProblem.CONTENT)
            "CNAME" -> if (RoutingInput.isIp(content) || !RoutingInput.isDomain(RoutingInput.normalizeDomain(content))) add(RecordProblem.CONTENT)
            else -> add(RecordProblem.TYPE)
        }
        val ttl = f.ttl.trim()
        if (ttl.isEmpty() || ttl.length > 9 || !ttl.all { it in '0'..'9' }) add(RecordProblem.TTL)
    }

    fun request(f: RecordForm, zoneDomain: String) = ApiDnsRecordRequest(
        name = fqdn(f.name, zoneDomain),
        type = f.type,
        content = if (f.type == "CNAME") RoutingInput.normalizeDomain(f.content) else f.content.trim().lowercase(),
        ttl = f.ttl.trim().toIntOrNull() ?: 300,
    )
}
