package io.github.bropines.birdsocks.admin.areas.networks

import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.api.ApiNetworkResourceRequest
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouterRequest
import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiPolicyRule
import io.github.bropines.birdsocks.admin.areas.routing.RoutingInput

/** A resource as its form holds it. */
data class ResourceForm(
    val name: String = "",
    val description: String = "",
    val address: String = "",
    val enabled: Boolean = true,
    val groups: List<String> = emptyList(),
)

enum class ResourceProblem { NAME, ADDRESS }

/** A routing peer or peer group as its form holds it. */
data class RouterForm(
    val useGroups: Boolean = false,
    val peer: String? = null,
    val peerGroups: List<String> = emptyList(),
    val metric: String = "9999",
    val masquerade: Boolean = true,
    val enabled: Boolean = true,
)

enum class RouterProblem { NO_ROUTER, METRIC }

/**
 * A policy rule that lets someone reach a resource: the policy, the rule,
 * who it lets in (group names, or the resource it starts from), and whether
 * it is in force (policy and rule both on).
 */
data class ResourceGrant(
    val policyId: String,
    val policyName: String,
    val ruleName: String,
    val sources: List<String>,
    val active: Boolean,
    /** Through the resource itself rather than one of its groups. */
    val direct: Boolean,
)

/** What the Networks area decides without a screen. */
object NetworkRules {
    const val HOST = "host"
    const val SUBNET = "subnet"
    const val DOMAIN = "domain"

    /**
     * What [address] would be on the server: a host (an IP, or one with a
     * /32 or /128), a subnet (any other CIDR), a domain (wildcards allowed);
     * null for neither.
     */
    fun resourceType(address: String): String? {
        val a = address.trim()
        if (RoutingInput.isIp(a)) return HOST
        if (RoutingInput.isCidr(a)) {
            val (ip, bits) = a.split('/')
            return if ((RoutingInput.isIpv4(ip) && bits == "32") || (RoutingInput.isIpv6(ip) && bits == "128")) HOST else SUBNET
        }
        if (RoutingInput.isDomain(RoutingInput.normalizeDomain(a), wildcard = true)) return DOMAIN
        return null
    }

    fun resourceForm(r: ApiNetworkResource) = ResourceForm(r.name, r.description, r.address, r.enabled, r.groups.map { it.id })

    fun validate(f: ResourceForm): List<ResourceProblem> = buildList {
        if (f.name.isBlank()) add(ResourceProblem.NAME)
        if (resourceType(f.address) == null) add(ResourceProblem.ADDRESS)
    }

    fun request(f: ResourceForm): ApiNetworkResourceRequest {
        val a = f.address.trim()
        return ApiNetworkResourceRequest(
            name = f.name.trim(),
            description = f.description.trim(),
            address = if (resourceType(a) == DOMAIN) RoutingInput.normalizeDomain(a) else a,
            enabled = f.enabled,
            groups = f.groups,
        )
    }

    fun routerForm(r: ApiNetworkRouter) = RouterForm(
        useGroups = !r.peerGroups.isNullOrEmpty(),
        peer = r.peer?.takeIf { it.isNotEmpty() },
        peerGroups = r.peerGroups.orEmpty(),
        metric = r.metric.toString(),
        masquerade = r.masquerade,
        enabled = r.enabled,
    )

    fun validate(f: RouterForm): List<RouterProblem> = buildList {
        if (if (f.useGroups) f.peerGroups.isEmpty() else f.peer.isNullOrEmpty()) add(RouterProblem.NO_ROUTER)
        if (RoutingInput.metric(f.metric) == null) add(RouterProblem.METRIC)
    }

    /** One of peer and peer groups, the other left out. */
    fun request(f: RouterForm) = ApiNetworkRouterRequest(
        peer = if (f.useGroups) null else f.peer,
        peerGroups = if (f.useGroups) f.peerGroups else null,
        metric = RoutingInput.metric(f.metric) ?: RoutingInput.METRIC_MAX,
        masquerade = f.masquerade,
        enabled = f.enabled,
    )

    /**
     * The accept rules among [policies] whose destination is [r] itself or
     * one of its groups. Drop rules grant nothing and are left out.
     */
    fun grants(r: ApiNetworkResource, policies: List<ApiPolicy>): List<ResourceGrant> {
        val groupIds = r.groups.map { it.id }.toSet()
        return policies.flatMap { p ->
            p.rules.filter { rule ->
                rule.action == ApiPolicyRule.ACTION_ACCEPT &&
                    (rule.destinationResource?.id == r.id || (rule.destinationResource == null && rule.destinations.any { it.id in groupIds }))
            }.map { rule ->
                ResourceGrant(
                    policyId = p.id,
                    policyName = p.name,
                    ruleName = rule.name,
                    sources = rule.sourceResource?.let { listOf(it.id) } ?: rule.sources.map { it.name.ifEmpty { it.id } },
                    active = p.enabled && rule.enabled,
                    direct = rule.destinationResource?.id == r.id,
                )
            }
        }.sortedWith(compareByDescending<ResourceGrant> { it.active }.thenBy { it.policyName.lowercase() })
    }

    /** Whether [r] is this device (its peer) or a group it is in. */
    fun isSelfRouter(r: ApiNetworkRouter, selfPeerId: String?, selfGroups: Set<String>): Boolean =
        (selfPeerId != null && r.peer == selfPeerId) || r.peerGroups.orEmpty().any { it in selfGroups }
}
