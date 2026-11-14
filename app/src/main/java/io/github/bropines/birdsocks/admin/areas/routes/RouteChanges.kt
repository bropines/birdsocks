package io.github.bropines.birdsocks.admin.areas.routes

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiRouteRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.routing.RawJson
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonChanges
import io.github.bropines.birdsocks.admin.areas.routing.RoutingInput
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeClassifier
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import kotlinx.serialization.json.JsonObject

/**
 * The Routes area's writes as [PlannedChange]s. [names] turns a peer or
 * group id into what the screen shows. A route this device uses or an exit
 * node raises the class: taking one away cuts traffic off.
 */
object RouteChanges {
    val CREATE = ChangeKind("route.create", Capabilities.ROUTES, Op.CREATE, ChangeClass.MEDIUM)
    val UPDATE = ChangeKind("route.update", Capabilities.ROUTES, Op.UPDATE, ChangeClass.MEDIUM)
    val TOGGLE = ChangeKind("route.toggle", Capabilities.ROUTES, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("route.delete", Capabilities.ROUTES, Op.DELETE, ChangeClass.MEDIUM)
    val JSON = ChangeKind("route.json", Capabilities.ROUTES, Op.UPDATE, ChangeClass.MEDIUM)

    private val REFRESH = listOf(AdminKeys.ROUTES)

    fun name(r: ApiRoute): String = r.networkId.ifEmpty { r.description.ifEmpty { r.id } }
    fun target(r: ApiRoute) = ChangeTarget(TargetType.ROUTE, r.id, name(r))

    private fun onOff(ctx: Context, v: Boolean) = ctx.getString(if (v) R.string.admin_value_on else R.string.admin_value_off)

    /** Field by field, what differs between two requests. */
    fun diff(ctx: Context, a: ApiRouteRequest?, b: ApiRouteRequest, names: (String) -> String): List<DiffLine> {
        fun ids(l: List<String>?) = l.orEmpty().joinToString(", ") { names(it) }.ifEmpty { null }
        fun row(label: Int, before: String?, after: String?) =
            if (a != null && before == after) null else DiffLine(ctx.getString(label), before?.ifEmpty { null }, after?.ifEmpty { null })
        return listOfNotNull(
            row(R.string.admin_route_network_id, a?.networkId, b.networkId),
            row(R.string.admin_route_description, a?.description, b.description),
            row(R.string.admin_route_network, a?.network, b.network),
            row(R.string.admin_route_domains, a?.domains?.joinToString(", "), b.domains?.joinToString(", ")),
            row(R.string.admin_route_peer, a?.peer?.let(names), b.peer?.let(names)),
            row(R.string.admin_route_peer_groups, a?.let { ids(it.peerGroups) }, ids(b.peerGroups)),
            row(R.string.admin_route_metric, a?.metric?.toString(), b.metric.toString()),
            row(R.string.admin_route_masquerade, a?.let { onOff(ctx, it.masquerade) }, onOff(ctx, b.masquerade)),
            row(R.string.admin_route_groups, a?.let { ids(it.groups) }, ids(b.groups)),
            row(R.string.admin_route_acl_groups, a?.let { ids(it.accessControlGroups) }, ids(b.accessControlGroups)),
            row(R.string.admin_route_enabled, a?.let { onOff(ctx, it.enabled) }, onOff(ctx, b.enabled)),
            row(R.string.admin_route_keep, a?.let { onOff(ctx, it.keepRoute) }, onOff(ctx, b.keepRoute)),
        )
    }

    /** What to say above the confirm about this device and exit nodes. */
    private fun warnings(ctx: Context, uses: Set<RouteUse>, exit: Boolean, losing: Boolean): List<String> = buildList {
        if (losing && RouteUse.RECEIVES in uses) add(ctx.getString(R.string.admin_route_warn_self_receives))
        if (losing && RouteUse.ROUTES in uses) add(ctx.getString(R.string.admin_route_warn_self_routes))
        if (losing && exit) add(ctx.getString(R.string.admin_route_warn_exit))
    }

    fun create(ctx: Context, request: ApiRouteRequest, names: (String) -> String): PlannedChange {
        val exit = RoutingInput.isExitNode(request.network)
        return PlannedChange(
            change = AdminChange(
                CREATE, ChangeTarget(TargetType.ROUTE, "", request.networkId),
                title = ctx.getString(R.string.admin_route_create_title, request.networkId),
                effect = ctx.getString(if (exit) R.string.admin_route_create_exit_effect else R.string.admin_route_create_effect),
                diff = diff(ctx, null, request, names),
                warnings = if (exit) listOf(ctx.getString(R.string.admin_route_warn_new_exit)) else emptyList(),
            ),
            apply = { it.createRoute(request) },
            refreshes = REFRESH,
        )
    }

    /**
     * A whole route saved from the form. HIGH when it takes an exit node away
     * or this device loses a route it uses (disabled, or its groups leave).
     */
    fun update(ctx: Context, route: ApiRoute, request: ApiRouteRequest, self: SelfUse, names: (String) -> String, isUndo: Boolean = false): PlannedChange {
        val before = ApiRouteRequest.of(route)
        val uses = RouteRules.uses(route, self)
        val after = route.copy(enabled = request.enabled, groups = request.groups, peer = request.peer, peerGroups = request.peerGroups, network = request.network, domains = request.domains)
        val stillUses = RouteRules.uses(after, self)
        val losing = (uses.isNotEmpty() && (route.enabled && !request.enabled || !stillUses.containsAll(uses)))
        val exitLost = ChangeClassifier.route(route.isExitNode, RoutingInput.isExitNode(request.network), route.enabled, request.enabled) == ChangeClass.HIGH
        return PlannedChange(
            change = AdminChange(
                UPDATE, target(route),
                title = ctx.getString(R.string.admin_route_update_title, name(route)),
                effect = ctx.getString(R.string.admin_route_update_effect),
                diff = diff(ctx, before, request, names),
                warnings = warnings(ctx, uses, route.isExitNode, losing || exitLost),
                changeClass = if (losing || exitLost) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { it.updateRoute(route.id, request) },
            verify = { api -> api.route(route.id).let { it.enabled == request.enabled && it.groups.toSet() == request.groups.toSet() && it.metric == request.metric } },
            undo = if (isUndo) null else update(ctx, route.copy(enabled = request.enabled, groups = request.groups, peer = request.peer, peerGroups = request.peerGroups,
                network = request.network, domains = request.domains, metric = request.metric, masquerade = request.masquerade, description = request.description,
                networkId = request.networkId, keepRoute = request.keepRoute, accessControlGroups = request.accessControlGroups), before, self, names, isUndo = true),
            refreshes = REFRESH,
            isUndo = isUndo,
        )
    }

    /** On: LOW (the switch was the confirm). Off: MEDIUM, HIGH for an exit node or a route this device uses. */
    fun setEnabled(ctx: Context, route: ApiRoute, on: Boolean, self: SelfUse, isUndo: Boolean = false): PlannedChange {
        val uses = RouteRules.uses(route, self)
        val cls = when {
            on -> ChangeClass.LOW
            route.isExitNode || uses.isNotEmpty() -> ChangeClass.HIGH
            else -> ChangeClass.MEDIUM
        }
        return PlannedChange(
            change = AdminChange(
                TOGGLE, target(route),
                title = ctx.getString(if (on) R.string.admin_route_enable_title else R.string.admin_route_disable_title, name(route)),
                effect = ctx.getString(if (on) R.string.admin_route_enable_effect else R.string.admin_route_disable_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_route_enabled), onOff(ctx, route.enabled), onOff(ctx, on))),
                warnings = warnings(ctx, uses, route.isExitNode, !on),
                changeClass = cls,
            ),
            apply = { it.updateRoute(route.id, ApiRouteRequest.of(it.route(route.id)).copy(enabled = on)) },
            verify = { it.route(route.id).enabled == on },
            undo = if (isUndo) null else setEnabled(ctx, route.copy(enabled = on), !on, self, isUndo = true),
            refreshes = REFRESH,
            isUndo = isUndo,
        )
    }

    /** HIGH when this device uses the route or it is an exit node; MEDIUM otherwise. No way back. */
    fun delete(ctx: Context, route: ApiRoute, self: SelfUse): PlannedChange {
        val uses = RouteRules.uses(route, self)
        val high = route.enabled && (uses.isNotEmpty() || route.isExitNode)
        return PlannedChange(
            change = AdminChange(
                DELETE, target(route),
                title = ctx.getString(R.string.admin_route_delete_title, name(route)),
                effect = ctx.getString(R.string.admin_route_delete_effect, RouteRules.destination(route)),
                warnings = if (route.enabled) warnings(ctx, uses, route.isExitNode, true) else emptyList(),
                changeClass = if (high) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { it.deleteRoute(route.id) },
            verify = { api -> try { api.route(route.id); false } catch (_: AdminApiException.NotFound) { true } },
            refreshes = REFRESH,
            done = ctx.getString(R.string.admin_done_deleted, name(route)),
        )
    }

    /** The route saved from the JSON editor; HIGH under the same conditions as a delete. */
    fun json(ctx: Context, route: ApiRoute, before: JsonObject, after: JsonObject, self: SelfUse): PlannedChange {
        val risky = route.enabled && (RouteRules.uses(route, self).isNotEmpty() || route.isExitNode)
        return RawJsonChanges.edit(
            ctx, JSON, target(route), RawJson.path("routes", route.id), before, after, REFRESH,
            changeClass = if (risky) ChangeClass.HIGH else ChangeClass.MEDIUM,
            warnings = if (risky) warnings(ctx, RouteRules.uses(route, self), route.isExitNode, true) else emptyList(),
        )
    }
}
