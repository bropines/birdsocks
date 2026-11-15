package io.github.bropines.birdsocks.admin.areas.routes

import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiRouteRequest
import io.github.bropines.birdsocks.admin.areas.routing.RoutingInput
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse

/** A route as its form holds it, before it is checked. */
data class RouteForm(
    val description: String = "",
    val networkId: String = "",
    /** Domains instead of a CIDR. */
    val domainMode: Boolean = false,
    val network: String = "",
    val domains: String = "",
    /** A group of routing peers instead of one peer. */
    val useGroups: Boolean = false,
    val peer: String? = null,
    val peerGroups: List<String> = emptyList(),
    val metric: String = "9999",
    val masquerade: Boolean = true,
    /** Distribution groups: the peers that get the route. */
    val groups: List<String> = emptyList(),
    val accessControlGroups: List<String> = emptyList(),
    val enabled: Boolean = true,
    val keepRoute: Boolean = true,
    val skipAutoApply: Boolean? = null,
) {
    val isExitNode: Boolean get() = !domainMode && RoutingInput.isExitNode(network)
}

enum class RouteProblem { NETWORK_ID, NETWORK, NO_DOMAINS, BAD_DOMAIN, TOO_MANY_DOMAINS, NO_ROUTER, METRIC, NO_GROUPS }

/** How this device is involved in a route. */
enum class RouteUse {
    /** It is in a distribution group: the route is handed to it. */
    RECEIVES,
    /** It is the routing peer or in the routing peer group. */
    ROUTES,
}

/** What the Routes area decides without a screen. */
object RouteRules {
    const val MAX_DOMAINS = 32
    const val MAX_NETWORK_ID = 40

    fun form(r: ApiRoute) = RouteForm(
        description = r.description,
        networkId = r.networkId,
        domainMode = !r.domains.isNullOrEmpty(),
        network = r.network.orEmpty(),
        domains = r.domains.orEmpty().joinToString("\n"),
        useGroups = !r.peerGroups.isNullOrEmpty(),
        peer = r.peer?.takeIf { it.isNotEmpty() },
        peerGroups = r.peerGroups.orEmpty(),
        metric = r.metric.toString(),
        masquerade = r.masquerade,
        groups = r.groups,
        accessControlGroups = r.accessControlGroups.orEmpty(),
        enabled = r.enabled,
        keepRoute = r.keepRoute,
        skipAutoApply = r.skipAutoApply,
    )

    /** A new exit node: every address, through the routing peer. */
    fun exitNodeForm() = RouteForm(networkId = "exit-node", network = "0.0.0.0/0", masquerade = true)

    fun domains(f: RouteForm): List<String> = RoutingInput.list(f.domains).map(RoutingInput::normalizeDomain)

    fun validate(f: RouteForm): List<RouteProblem> = buildList {
        val id = f.networkId.trim()
        if (id.isEmpty() || id.length > MAX_NETWORK_ID) add(RouteProblem.NETWORK_ID)
        if (f.domainMode) {
            val d = domains(f)
            when {
                d.isEmpty() -> add(RouteProblem.NO_DOMAINS)
                d.size > MAX_DOMAINS -> add(RouteProblem.TOO_MANY_DOMAINS)
                d.any { !RoutingInput.isDomain(it, wildcard = true) } -> add(RouteProblem.BAD_DOMAIN)
            }
        } else if (!RoutingInput.isCidr(f.network.trim())) add(RouteProblem.NETWORK)
        if (if (f.useGroups) f.peerGroups.isEmpty() else f.peer.isNullOrEmpty()) add(RouteProblem.NO_ROUTER)
        if (RoutingInput.metric(f.metric) == null) add(RouteProblem.METRIC)
        if (f.groups.isEmpty()) add(RouteProblem.NO_GROUPS)
    }

    /** The request for a valid [f]: one of network/domains and one of peer/peer groups, the other left out. */
    fun request(f: RouteForm) = ApiRouteRequest(
        description = f.description.trim(),
        networkId = f.networkId.trim(),
        enabled = f.enabled,
        peer = if (f.useGroups) null else f.peer,
        peerGroups = if (f.useGroups) f.peerGroups else null,
        network = if (f.domainMode) null else f.network.trim(),
        domains = if (f.domainMode) domains(f) else null,
        metric = RoutingInput.metric(f.metric) ?: RoutingInput.METRIC_MAX,
        masquerade = f.masquerade,
        groups = f.groups,
        keepRoute = f.keepRoute,
        accessControlGroups = f.accessControlGroups.takeIf { it.isNotEmpty() },
        skipAutoApply = if (f.isExitNode) f.skipAutoApply else null,
    )

    /** How this device takes part in [r]; empty when it does not, or when it is not known. */
    fun uses(r: ApiRoute, self: SelfUse): Set<RouteUse> {
        if (!self.known) return emptySet()
        return buildSet {
            if (self.inAny(r.groups)) add(RouteUse.RECEIVES)
            if (r.peer == self.peerId || self.inAny(r.peerGroups)) add(RouteUse.ROUTES)
        }
    }

    /** What [r] routes, for a list row: the CIDR or the domains. */
    fun destination(r: ApiRoute): String = r.network?.takeIf { it.isNotEmpty() } ?: r.domains.orEmpty().joinToString(", ")
}
