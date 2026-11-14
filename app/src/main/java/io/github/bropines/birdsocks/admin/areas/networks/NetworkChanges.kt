package io.github.bropines.birdsocks.admin.areas.networks

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiNetwork
import io.github.bropines.birdsocks.admin.api.ApiNetworkRequest
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.api.ApiNetworkResourceRequest
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouterRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.routing.RawJson
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonChanges
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.DataKey
import kotlinx.serialization.json.JsonObject

/**
 * The Networks area's writes as [PlannedChange]s. [also] is the area's own
 * per-network data to read again once a change applied (a network's
 * resources and routers); [names] turns a peer or group id into its name.
 */
object NetworkChanges {
    val CREATE = ChangeKind("network.create", Capabilities.NETWORKS, Op.CREATE, ChangeClass.MEDIUM)
    val UPDATE = ChangeKind("network.update", Capabilities.NETWORKS, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("network.delete", Capabilities.NETWORKS, Op.DELETE, ChangeClass.HIGH)
    val RESOURCE_CREATE = ChangeKind("network_resource.create", Capabilities.NETWORKS, Op.CREATE, ChangeClass.MEDIUM)
    val RESOURCE_UPDATE = ChangeKind("network_resource.update", Capabilities.NETWORKS, Op.UPDATE, ChangeClass.MEDIUM)
    val RESOURCE_DELETE = ChangeKind("network_resource.delete", Capabilities.NETWORKS, Op.DELETE, ChangeClass.HIGH)
    val ROUTER_CREATE = ChangeKind("network_router.create", Capabilities.NETWORKS, Op.CREATE, ChangeClass.MEDIUM)
    val ROUTER_UPDATE = ChangeKind("network_router.update", Capabilities.NETWORKS, Op.UPDATE, ChangeClass.MEDIUM)
    val ROUTER_DELETE = ChangeKind("network_router.delete", Capabilities.NETWORKS, Op.DELETE, ChangeClass.HIGH)
    val JSON = ChangeKind("network.json", Capabilities.NETWORKS, Op.UPDATE, ChangeClass.MEDIUM)

    private fun refresh(also: List<DataKey<*>>) = listOf(AdminKeys.NETWORKS, AdminKeys.NETWORK_ROUTERS, AdminKeys.GROUPS) + also

    private fun onOff(ctx: Context, v: Boolean) = ctx.getString(if (v) R.string.admin_value_on else R.string.admin_value_off)

    private fun row(ctx: Context, label: Int, before: String?, after: String?, adding: Boolean) =
        if (!adding && before == after) null else DiffLine(ctx.getString(label), before?.ifEmpty { null }, after?.ifEmpty { null })

    fun target(n: ApiNetwork) = ChangeTarget(TargetType.NETWORK, n.id, n.name)
    fun target(r: ApiNetworkResource) = ChangeTarget(TargetType.NETWORK_RESOURCE, r.id, r.name)
    fun target(r: ApiNetworkRouter, label: String) = ChangeTarget(TargetType.NETWORK_ROUTER, r.id, label)

    // --- Networks ---

    fun create(ctx: Context, request: ApiNetworkRequest): PlannedChange = PlannedChange(
        change = AdminChange(
            CREATE, ChangeTarget(TargetType.NETWORK, "", request.name),
            title = ctx.getString(R.string.admin_net_create_title, request.name),
            effect = ctx.getString(R.string.admin_net_create_effect),
            diff = listOfNotNull(
                row(ctx, R.string.admin_net_name, null, request.name, true),
                request.description?.takeIf { it.isNotEmpty() }?.let { row(ctx, R.string.admin_net_description, null, it, true) },
            ),
        ),
        apply = { it.createNetwork(request) },
        refreshes = refresh(emptyList()),
    )

    fun update(ctx: Context, n: ApiNetwork, request: ApiNetworkRequest, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            UPDATE, target(n),
            title = ctx.getString(R.string.admin_net_update_title, n.name),
            effect = ctx.getString(R.string.admin_net_update_effect),
            diff = listOfNotNull(
                row(ctx, R.string.admin_net_name, n.name, request.name, false),
                row(ctx, R.string.admin_net_description, n.description, request.description.orEmpty(), false),
            ),
        ),
        apply = { it.updateNetwork(n.id, request) },
        verify = { api -> api.network(n.id).let { it.name == request.name && it.description == request.description.orEmpty() } },
        undo = if (isUndo) null else update(ctx, n.copy(name = request.name, description = request.description.orEmpty()), ApiNetworkRequest(n.name, n.description), isUndo = true),
        refreshes = refresh(emptyList()),
        isUndo = isUndo,
    )

    /** Its resources and routers go with it. HIGH, no way back. */
    fun delete(ctx: Context, n: ApiNetwork, selfRoutes: Boolean): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(n),
            title = ctx.getString(R.string.admin_net_delete_title, n.name),
            effect = ctx.getString(R.string.admin_net_delete_effect, n.resources.size, n.routers.size),
            warnings = listOfNotNull(
                if (n.policies.isNotEmpty()) ctx.getString(R.string.admin_net_delete_policies_warn, n.policies.size) else null,
                if (selfRoutes) ctx.getString(R.string.admin_net_router_self_warn) else null,
            ),
        ),
        apply = { it.deleteNetwork(n.id) },
        verify = { api -> try { api.network(n.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = refresh(emptyList()),
        done = ctx.getString(R.string.admin_done_deleted, n.name),
    )

    // --- Resources ---

    private fun resourceDiff(ctx: Context, a: ApiNetworkResourceRequest?, b: ApiNetworkResourceRequest, names: (String) -> String) = listOfNotNull(
        row(ctx, R.string.admin_net_name, a?.name, b.name, a == null),
        row(ctx, R.string.admin_net_description, a?.description, b.description, a == null && !b.description.isNullOrEmpty()),
        row(ctx, R.string.admin_net_address, a?.address, b.address, a == null),
        row(ctx, R.string.admin_net_groups, a?.groups?.joinToString(", ") { names(it) }, b.groups.joinToString(", ") { names(it) }, a == null && b.groups.isNotEmpty()),
        row(ctx, R.string.admin_routing_enabled, a?.let { onOff(ctx, it.enabled) }, onOff(ctx, b.enabled), a == null),
    )

    fun createResource(ctx: Context, network: ApiNetwork, request: ApiNetworkResourceRequest, names: (String) -> String, also: List<DataKey<*>>): PlannedChange = PlannedChange(
        change = AdminChange(
            RESOURCE_CREATE, ChangeTarget(TargetType.NETWORK_RESOURCE, "", request.name),
            title = ctx.getString(R.string.admin_net_resource_create_title, request.name, network.name),
            effect = ctx.getString(R.string.admin_net_resource_create_effect, request.address),
            diff = resourceDiff(ctx, null, request, names),
        ),
        apply = { it.createNetworkResource(network.id, request) },
        refreshes = refresh(also),
    )

    /** Taking groups away or switching it off may cut people off: still MEDIUM, the diff says it. */
    fun updateResource(ctx: Context, networkId: String, r: ApiNetworkResource, request: ApiNetworkResourceRequest, names: (String) -> String, also: List<DataKey<*>>, isUndo: Boolean = false): PlannedChange {
        val before = ApiNetworkResourceRequest.of(r)
        val cls = if (request == before.copy(enabled = true) && !r.enabled) ChangeClass.LOW else ChangeClass.MEDIUM
        return PlannedChange(
            change = AdminChange(
                RESOURCE_UPDATE, target(r),
                title = ctx.getString(R.string.admin_net_resource_update_title, r.name),
                effect = ctx.getString(if (r.enabled && !request.enabled) R.string.admin_net_resource_off_effect else R.string.admin_net_resource_update_effect),
                diff = resourceDiff(ctx, before, request, names),
                changeClass = cls,
            ),
            apply = { it.updateNetworkResource(networkId, r.id, request) },
            verify = { api -> api.networkResource(networkId, r.id).let { it.address == request.address && it.enabled == request.enabled && it.groups.map { g -> g.id }.toSet() == request.groups.toSet() } },
            undo = if (isUndo) null else updateResource(ctx, networkId, r.copy(name = request.name, description = request.description.orEmpty(), address = request.address, enabled = request.enabled,
                groups = request.groups.map { ApiGroupMinimum(it, names(it)) }), before, names, also, isUndo = true),
            refreshes = refresh(also),
            isUndo = isUndo,
        )
    }

    fun deleteResource(ctx: Context, networkId: String, r: ApiNetworkResource, grants: Int, also: List<DataKey<*>>): PlannedChange = PlannedChange(
        change = AdminChange(
            RESOURCE_DELETE, target(r),
            title = ctx.getString(R.string.admin_net_resource_delete_title, r.name),
            effect = ctx.getString(R.string.admin_net_resource_delete_effect, r.address),
            warnings = if (grants > 0) listOf(ctx.resources.getQuantityString(R.plurals.admin_net_resource_delete_grants, grants, grants)) else emptyList(),
        ),
        apply = { it.deleteNetworkResource(networkId, r.id) },
        verify = { api -> try { api.networkResource(networkId, r.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = refresh(also),
        done = ctx.getString(R.string.admin_done_deleted, r.name),
    )

    // --- Routers ---

    private fun routerDiff(ctx: Context, a: ApiNetworkRouterRequest?, b: ApiNetworkRouterRequest, names: (String) -> String) = listOfNotNull(
        row(ctx, R.string.admin_net_router_peer, a?.peer?.let(names), b.peer?.let(names), a == null && b.peer != null),
        row(ctx, R.string.admin_net_router_groups, a?.peerGroups?.joinToString(", ") { names(it) }, b.peerGroups?.joinToString(", ") { names(it) }, a == null && b.peerGroups != null),
        row(ctx, R.string.admin_routing_metric, a?.metric?.toString(), b.metric.toString(), a == null),
        row(ctx, R.string.admin_routing_masquerade, a?.let { onOff(ctx, it.masquerade) }, onOff(ctx, b.masquerade), a == null),
        row(ctx, R.string.admin_routing_enabled, a?.let { onOff(ctx, it.enabled) }, onOff(ctx, b.enabled), a == null),
    )

    fun routerLabel(r: ApiNetworkRouterRequest, names: (String) -> String): String =
        r.peer?.let(names) ?: r.peerGroups.orEmpty().joinToString(", ") { names(it) }

    fun createRouter(ctx: Context, network: ApiNetwork, request: ApiNetworkRouterRequest, names: (String) -> String, also: List<DataKey<*>>): PlannedChange = PlannedChange(
        change = AdminChange(
            ROUTER_CREATE, ChangeTarget(TargetType.NETWORK_ROUTER, "", routerLabel(request, names)),
            title = ctx.getString(R.string.admin_net_router_create_title, network.name),
            effect = ctx.getString(R.string.admin_net_router_create_effect, routerLabel(request, names)),
            diff = routerDiff(ctx, null, request, names),
        ),
        apply = { it.createNetworkRouter(network.id, request) },
        refreshes = refresh(also),
    )

    /** Switching off or replacing the router this device is: HIGH, with a warning. */
    fun updateRouter(ctx: Context, networkId: String, r: ApiNetworkRouter, request: ApiNetworkRouterRequest, self: Boolean, names: (String) -> String, also: List<DataKey<*>>, isUndo: Boolean = false): PlannedChange {
        val before = ApiNetworkRouterRequest.of(r)
        val label = routerLabel(before, names)
        val losingSelf = self && (r.enabled && !request.enabled || request.peer != before.peer || request.peerGroups != before.peerGroups)
        return PlannedChange(
            change = AdminChange(
                ROUTER_UPDATE, target(r, label),
                title = ctx.getString(R.string.admin_net_router_update_title, label),
                effect = ctx.getString(if (r.enabled && !request.enabled) R.string.admin_net_router_off_effect else R.string.admin_net_router_update_effect),
                diff = routerDiff(ctx, before, request, names),
                warnings = if (losingSelf) listOf(ctx.getString(R.string.admin_net_router_self_warn)) else emptyList(),
                changeClass = when {
                    losingSelf -> ChangeClass.HIGH
                    request == before.copy(enabled = true) && !r.enabled -> ChangeClass.LOW
                    else -> ChangeClass.MEDIUM
                },
            ),
            apply = { it.updateNetworkRouter(networkId, r.id, request) },
            verify = { api -> api.networkRouter(networkId, r.id).let { it.enabled == request.enabled && it.metric == request.metric && it.peer.orEmpty() == request.peer.orEmpty() } },
            undo = if (isUndo) null else updateRouter(ctx, networkId, r.copy(peer = request.peer, peerGroups = request.peerGroups, metric = request.metric, masquerade = request.masquerade, enabled = request.enabled),
                before, self, names, also, isUndo = true),
            refreshes = refresh(also),
            isUndo = isUndo,
        )
    }

    fun deleteRouter(ctx: Context, networkId: String, r: ApiNetworkRouter, self: Boolean, names: (String) -> String, also: List<DataKey<*>>): PlannedChange {
        val label = routerLabel(ApiNetworkRouterRequest.of(r), names)
        return PlannedChange(
            change = AdminChange(
                ROUTER_DELETE, target(r, label),
                title = ctx.getString(R.string.admin_net_router_delete_title, label),
                effect = ctx.getString(R.string.admin_net_router_delete_effect),
                warnings = if (self) listOf(ctx.getString(R.string.admin_net_router_self_warn)) else emptyList(),
            ),
            apply = { it.deleteNetworkRouter(networkId, r.id) },
            verify = { api -> try { api.networkRouter(networkId, r.id); false } catch (_: AdminApiException.NotFound) { true } },
            refreshes = refresh(also),
            done = ctx.getString(R.string.admin_done_deleted, label),
        )
    }

    // --- JSON ---

    /** A network, a resource (its groups go back as ids) or a router saved from the JSON editor. */
    fun json(ctx: Context, target: ChangeTarget, path: String, before: JsonObject, after: JsonObject, also: List<DataKey<*>>): PlannedChange =
        RawJsonChanges.edit(
            ctx, JSON, target, path, before, after, refresh(also),
            idFields = if (target.type == TargetType.NETWORK_RESOURCE) setOf("groups") else emptySet(),
        )

    fun networkPath(id: String) = RawJson.path("networks", id)
    fun resourcePath(networkId: String, id: String) = RawJson.path("networks", networkId, "resources", id)
    fun routerPath(networkId: String, id: String) = RawJson.path("networks", networkId, "routers", id)
}
