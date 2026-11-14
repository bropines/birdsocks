package io.github.bropines.birdsocks.admin.areas.groups

import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser

/**
 * Where a group is referenced, computed on the phone from the cached lists —
 * what deleting it would break (the server refuses to delete a linked group,
 * but saying why beforehand beats a raw error). A null list was not read
 * (no permission, not loaded yet): it lands in [unchecked].
 */
data class GroupUsage(
    val policies: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val nameservers: List<String> = emptyList(),
    val dnsZones: List<String> = emptyList(),
    val setupKeys: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    val networkRouters: Int = 0,
    val dnsManagementDisabled: Boolean = false,
    /** Which of the lists could not be checked: "policies", "routes", … */
    val unchecked: List<String> = emptyList(),
) {
    val isUsed: Boolean
        get() = policies.isNotEmpty() || routes.isNotEmpty() || nameservers.isNotEmpty() || dnsZones.isNotEmpty() ||
            setupKeys.isNotEmpty() || users.isNotEmpty() || networkRouters > 0 || dnsManagementDisabled

    companion object {
        fun of(
            groupId: String,
            policies: List<ApiPolicy>?,
            routes: List<ApiRoute>?,
            nameservers: List<ApiNameserverGroup>?,
            dnsZones: List<ApiDnsZone>?,
            dnsSettings: ApiDnsSettings?,
            setupKeys: List<ApiSetupKey>?,
            users: List<ApiUser>?,
            routers: List<ApiNetworkRouter>?,
        ): GroupUsage {
            val unchecked = buildList {
                if (policies == null) add("policies")
                if (routes == null) add("routes")
                if (nameservers == null) add("nameservers")
                if (dnsZones == null) add("dns_zones")
                if (dnsSettings == null) add("dns_settings")
                if (setupKeys == null) add("setup_keys")
                if (users == null) add("users")
                if (routers == null) add("network_routers")
            }
            return GroupUsage(
                policies = policies.orEmpty().filter { p ->
                    p.rules.any { r ->
                        r.sources.any { it.id == groupId } || r.destinations.any { it.id == groupId } || groupId in r.authorizedGroups.keys
                    }
                }.map { it.name.ifEmpty { it.id } },
                routes = routes.orEmpty().filter { r ->
                    groupId in r.groups || r.peerGroups.orEmpty().contains(groupId) || r.accessControlGroups.orEmpty().contains(groupId)
                }.map { r -> r.networkId.ifEmpty { r.description.ifEmpty { r.id } } },
                nameservers = nameservers.orEmpty().filter { groupId in it.groups }.map { it.name.ifEmpty { it.id } },
                dnsZones = dnsZones.orEmpty().filter { groupId in it.distributionGroups }.map { it.name.ifEmpty { it.domain } },
                setupKeys = setupKeys.orEmpty().filter { groupId in it.autoGroups && !it.revoked }.map { it.name.ifEmpty { it.id } },
                users = users.orEmpty().filter { groupId in it.autoGroups }.map { it.name.ifEmpty { it.email.ifEmpty { it.id } } },
                networkRouters = routers.orEmpty().count { it.peerGroups.orEmpty().contains(groupId) },
                dnsManagementDisabled = dnsSettings?.disabledManagementGroups?.contains(groupId) == true,
                unchecked = unchecked,
            )
        }
    }
}
