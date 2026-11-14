package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* Routing and naming: networks with their resources and routers, legacy routes, DNS. See ApiModels.kt. */

// --- Networks ---

/** GET /api/networks: [routers], [resources] and [policies] are ids. */
@Serializable
data class ApiNetwork(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val routers: List<String> = emptyList(),
    @SerialName("routing_peers_count") val routingPeersCount: Int = 0,
    val resources: List<String> = emptyList(),
    val policies: List<String> = emptyList(),
)

@Serializable
data class ApiNetworkRequest(
    val name: String,
    val description: String? = null,
)

/** GET /api/networks/{id}/resources. */
@Serializable
data class ApiNetworkResource(
    val id: String = "",
    /** host, subnet or domain: the server decides from [address]. */
    val type: String = "",
    val name: String = "",
    val description: String = "",
    /** An IP, a CIDR or a domain (wildcards allowed). */
    val address: String = "",
    val enabled: Boolean = false,
    val groups: List<ApiGroupMinimum> = emptyList(),
)

@Serializable
data class ApiNetworkResourceRequest(
    val name: String,
    val description: String? = null,
    val address: String,
    val enabled: Boolean,
    /** Group ids. */
    val groups: List<String>,
) {
    companion object {
        fun of(r: ApiNetworkResource) = ApiNetworkResourceRequest(r.name, r.description, r.address, r.enabled, r.groups.map { it.id })
    }
}

/** GET /api/networks/{id}/routers: one routing [peer] or a set of [peerGroups]. */
@Serializable
data class ApiNetworkRouter(
    val id: String = "",
    val peer: String? = null,
    @SerialName("peer_groups") val peerGroups: List<String>? = null,
    val metric: Int = 9999,
    val masquerade: Boolean = false,
    val enabled: Boolean = false,
    /** Only GET /api/networks/routers says which network a router is in. */
    @SerialName("network_id") val networkId: String? = null,
)

@Serializable
data class ApiNetworkRouterRequest(
    val peer: String? = null,
    @SerialName("peer_groups") val peerGroups: List<String>? = null,
    val metric: Int,
    val masquerade: Boolean,
    val enabled: Boolean,
) {
    companion object {
        fun of(r: ApiNetworkRouter) = ApiNetworkRouterRequest(r.peer, r.peerGroups, r.metric, r.masquerade, r.enabled)
    }
}

// --- Routes (legacy) ---

/** GET /api/routes. A [network] CIDR or a list of [domains]; one [peer] or [peerGroups]. */
@Serializable
data class ApiRoute(
    val id: String = "",
    /** IPv4, IPv6 or Domain. */
    @SerialName("network_type") val networkType: String = "",
    val description: String = "",
    @SerialName("network_id") val networkId: String = "",
    val enabled: Boolean = false,
    val peer: String? = null,
    @SerialName("peer_groups") val peerGroups: List<String>? = null,
    val network: String? = null,
    val domains: List<String>? = null,
    val metric: Int = 9999,
    val masquerade: Boolean = false,
    /** Distribution groups: the peers that get the route. */
    val groups: List<String> = emptyList(),
    @SerialName("keep_route") val keepRoute: Boolean = false,
    @SerialName("access_control_groups") val accessControlGroups: List<String>? = null,
    @SerialName("skip_auto_apply") val skipAutoApply: Boolean? = null,
) {
    val isExitNode: Boolean get() = network == "0.0.0.0/0" || network == "::/0"
}

@Serializable
data class ApiRouteRequest(
    val description: String,
    @SerialName("network_id") val networkId: String,
    val enabled: Boolean,
    val peer: String? = null,
    @SerialName("peer_groups") val peerGroups: List<String>? = null,
    val network: String? = null,
    val domains: List<String>? = null,
    val metric: Int,
    val masquerade: Boolean,
    val groups: List<String>,
    @SerialName("keep_route") val keepRoute: Boolean,
    @SerialName("access_control_groups") val accessControlGroups: List<String>? = null,
    @SerialName("skip_auto_apply") val skipAutoApply: Boolean? = null,
) {
    companion object {
        fun of(r: ApiRoute) = ApiRouteRequest(
            r.description, r.networkId, r.enabled, r.peer, r.peerGroups, r.network, r.domains,
            r.metric, r.masquerade, r.groups, r.keepRoute, r.accessControlGroups, r.skipAutoApply,
        )
    }
}

// --- DNS ---

@Serializable
data class ApiNameserver(
    val ip: String = "",
    /** udp. */
    @SerialName("ns_type") val nsType: String = "udp",
    val port: Int = 53,
)

/** GET /api/dns/nameservers: nameservers for [domains] (all names when [primary]) handed to [groups]. */
@Serializable
data class ApiNameserverGroup(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val nameservers: List<ApiNameserver> = emptyList(),
    val enabled: Boolean = false,
    val groups: List<String> = emptyList(),
    val primary: Boolean = false,
    val domains: List<String> = emptyList(),
    @SerialName("search_domains_enabled") val searchDomainsEnabled: Boolean = false,
)

/** POST and PUT /api/dns/nameservers: every field required. */
@Serializable
data class ApiNameserverGroupRequest(
    val name: String,
    val description: String,
    val nameservers: List<ApiNameserver>,
    val enabled: Boolean,
    val groups: List<String>,
    val primary: Boolean,
    val domains: List<String>,
    @SerialName("search_domains_enabled") val searchDomainsEnabled: Boolean,
) {
    companion object {
        fun of(g: ApiNameserverGroup) = ApiNameserverGroupRequest(
            g.name, g.description, g.nameservers, g.enabled, g.groups, g.primary, g.domains, g.searchDomainsEnabled,
        )
    }
}

/** GET and PUT /api/dns/settings: the groups whose peers' DNS management is off. */
@Serializable
data class ApiDnsSettings(
    @SerialName("disabled_management_groups") val disabledManagementGroups: List<String> = emptyList(),
)

/** GET /api/dns/zones/{id}/records. [type] A, AAAA or CNAME. */
@Serializable
data class ApiDnsRecord(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val content: String = "",
    val ttl: Int = 0,
)

@Serializable
data class ApiDnsRecordRequest(
    val name: String,
    val type: String,
    val content: String,
    val ttl: Int,
) {
    companion object {
        fun of(r: ApiDnsRecord) = ApiDnsRecordRequest(r.name, r.type, r.content, r.ttl)
    }
}

/** GET /api/dns/zones: a private zone served to [distributionGroups]. */
@Serializable
data class ApiDnsZone(
    val id: String = "",
    val name: String = "",
    val domain: String = "",
    val enabled: Boolean = true,
    @SerialName("enable_search_domain") val enableSearchDomain: Boolean = false,
    @SerialName("distribution_groups") val distributionGroups: List<String> = emptyList(),
    val records: List<ApiDnsRecord> = emptyList(),
)

@Serializable
data class ApiDnsZoneRequest(
    val name: String,
    val domain: String,
    val enabled: Boolean? = null,
    @SerialName("enable_search_domain") val enableSearchDomain: Boolean,
    @SerialName("distribution_groups") val distributionGroups: List<String>,
) {
    companion object {
        fun of(z: ApiDnsZone) = ApiDnsZoneRequest(z.name, z.domain, z.enabled, z.enableSearchDomain, z.distributionGroups)
    }
}
