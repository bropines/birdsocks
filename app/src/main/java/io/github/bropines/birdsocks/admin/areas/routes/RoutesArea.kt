package io.github.bropines.birdsocks.admin.areas.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.routing.Badges
import io.github.bropines.birdsocks.admin.areas.routing.FormDialog
import io.github.bropines.birdsocks.admin.areas.routing.JsonTarget
import io.github.bropines.birdsocks.admin.areas.routing.RawJson
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonEditor
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.areas.routing.SwitchRow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonObject

/** Routes: the legacy network routes and exit nodes; [AreaScope.arg] opens one by id. */
val RoutesArea = AdminArea(
    id = "routes",
    title = R.string.admin_area_routes,
    summary = R.string.admin_area_routes_summary,
    icon = Icons.AutoMirrored.Filled.AltRoute,
    module = Capabilities.ROUTES,
) { scope -> RoutesScreen(scope) }

/** The open form (null id for a new route) and the open JSON document, across rotations. */
class RoutesState(host: AdminHost) : AreaState(host) {
    var editing by mutableStateOf<Pair<String?, RouteForm>?>(null)
    var json by mutableStateOf<String?>(null)
}

@Composable
private fun RoutesScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("routes") { RoutesState(it) }
    LaunchedEffect(Unit) {
        host.load(AdminKeys.ROUTES, AdminKeys.GROUPS, AdminKeys.PEERS)
        scope.arg?.let { id -> host.data.items(AdminKeys.ROUTES).firstOrNull { it.id == id }?.let { state.editing = id to RouteRules.form(it) } }
    }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.ROUTES].loading,
        onRefresh = { host.load(AdminKeys.ROUTES, AdminKeys.PEERS, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.ROUTES, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { state.editing = null to RouteForm() },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_route_new)) },
            )
        },
    ) { RoutesContent(host, state) }
}

@Composable
internal fun RoutesContent(host: AdminHost, state: RoutesState = host.areaState("routes") { RoutesState(it) }) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val peers = host.data.items(AdminKeys.PEERS)
    val groups = host.data.items(AdminKeys.GROUPS)
    val names: (String) -> String = { id -> groups.firstOrNull { it.id == id }?.name ?: peers.firstOrNull { it.id == id }?.name ?: id }
    val canUpdate = host.can(Capabilities.ROUTES, Op.UPDATE)

    LoadableContent(host.data[AdminKeys.ROUTES], onRetry = { host.load(AdminKeys.ROUTES, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { r -> listOf(r.networkId, r.description, RouteRules.destination(r)) + r.groups.map(names) + listOfNotNull(r.peer?.let(names)) + r.peerGroups.orEmpty().map(names) },
            searchHint = stringResource(R.string.admin_route_search),
            emptyText = stringResource(R.string.admin_route_empty),
            noMatchText = stringResource(R.string.admin_route_no_match),
            emptyIcon = Icons.AutoMirrored.Filled.AltRoute,
            filters = listOf(
                ListFilter("exit", stringResource(R.string.admin_route_exit_node)) { it.isExitNode },
                ListFilter("self", stringResource(R.string.admin_routing_this_device)) { RouteRules.uses(it, self).isNotEmpty() },
                ListFilter("off", stringResource(R.string.admin_routing_disabled)) { !it.enabled },
            ),
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareByDescending<ApiRoute> { it.isExitNode }.thenBy { it.networkId.lowercase() }),
                ListSort("metric", stringResource(R.string.admin_route_sort_metric), compareBy<ApiRoute> { it.networkId.lowercase() }.thenBy { it.metric }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.ROUTES, force = true) }) } },
        ) { r ->
            RouteRow(r, RouteRules.uses(r, self), names, canToggle = canUpdate && !host.changeBusy,
                onToggle = { host.propose(RouteChanges.setEnabled(ctx, r, it, self)) },
                onClick = { state.editing = r.id to RouteRules.form(r) })
        }
    }

    state.editing?.let { (id, form) ->
        val route = id?.let { i -> host.data.items(AdminKeys.ROUTES).firstOrNull { it.id == i } }
        RouteDialog(
            host, form, route,
            onDismiss = { state.editing = null },
            onSave = { f ->
                val request = RouteRules.request(f)
                val change = if (route == null) RouteChanges.create(ctx, request, names) else RouteChanges.update(ctx, route, request, self, names)
                if (host.propose(change)) state.editing = null
            },
            onJson = route?.let { { state.json = it.id } },
            onDelete = route?.takeIf { host.can(Capabilities.ROUTES, Op.DELETE) }?.let { r -> { if (host.propose(RouteChanges.delete(ctx, r, self))) state.editing = null } },
        )
    }
    state.json?.let { id ->
        val route = host.data.items(AdminKeys.ROUTES).firstOrNull { it.id == id } ?: return@let
        val target = remember(route) {
            JsonTarget(
                title = RouteChanges.name(route), subtitle = ctx.getString(R.string.admin_area_routes),
                path = RawJson.path("routes", route.id),
                fallback = AppJson.encodeToJsonElement(ApiRoute.serializer(), route) as JsonObject,
                editable = canUpdate,
            ) { before, after -> RouteChanges.json(ctx, route, before, after, self) }
        }
        RawJsonEditor(host, target, onClose = { state.json = null })
    }
}

@Composable
private fun RouteRow(r: ApiRoute, uses: Set<RouteUse>, names: (String) -> String, canToggle: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val router = r.peer?.takeIf { it.isNotEmpty() }?.let(names) ?: r.peerGroups.orEmpty().joinToString(", ") { names(it) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (r.isExitNode) Icons.Default.Public else Icons.AutoMirrored.Filled.AltRoute, null,
                tint = if (!r.enabled) MaterialTheme.colorScheme.outline else if (r.isExitNode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(RouteRules.destination(r), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOf(r.networkId, r.description).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.admin_route_row_router, router, r.metric), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.groups.isNotEmpty()) Text(stringResource(R.string.admin_route_row_groups, r.groups.joinToString(", ") { names(it) }), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Badges {
                    if (r.isExitNode) StatusBadge(stringResource(R.string.admin_route_exit_node), MaterialTheme.colorScheme.tertiaryContainer)
                    if (RouteUse.ROUTES in uses) StatusBadge(stringResource(R.string.admin_route_self_routes), MaterialTheme.colorScheme.primaryContainer)
                    else if (RouteUse.RECEIVES in uses) StatusBadge(stringResource(R.string.admin_routing_this_device), MaterialTheme.colorScheme.primaryContainer)
                    if (!r.enabled) StatusBadge(stringResource(R.string.admin_routing_disabled), MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        },
        trailingContent = { Switch(r.enabled, onToggle, enabled = canToggle) },
    )
}

@Composable
private fun problemText(p: RouteProblem): String = stringResource(
    when (p) {
        RouteProblem.NETWORK_ID -> R.string.admin_route_problem_network_id
        RouteProblem.NETWORK -> R.string.admin_route_problem_network
        RouteProblem.NO_DOMAINS -> R.string.admin_route_problem_no_domains
        RouteProblem.BAD_DOMAIN -> R.string.admin_route_problem_bad_domain
        RouteProblem.TOO_MANY_DOMAINS -> R.string.admin_route_problem_too_many_domains
        RouteProblem.NO_ROUTER -> R.string.admin_route_problem_no_router
        RouteProblem.METRIC -> R.string.admin_routing_problem_metric
        RouteProblem.NO_GROUPS -> R.string.admin_route_problem_no_groups
    }
)

/** The whole route in one form; [route] null for a new one. */
@Composable
private fun RouteDialog(
    host: AdminHost,
    initial: RouteForm,
    route: ApiRoute?,
    onDismiss: () -> Unit,
    onSave: (RouteForm) -> Unit,
    onJson: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var f by remember(initial) { mutableStateOf(initial) }
    val problems = RouteRules.validate(f)
    val editable = host.can(Capabilities.ROUTES, if (route == null) Op.CREATE else Op.UPDATE)
    val peerOptions = MemberOptions.peers(host.data.items(AdminKeys.PEERS))
    val groupOptions = MemberOptions.groups(host.data.items(AdminKeys.GROUPS))
    val problemTexts = problems.map { problemText(it) }
    FormDialog(
        title = if (route == null) stringResource(R.string.admin_route_new) else RouteChanges.name(route),
        confirmLabel = stringResource(if (route == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && !host.changeBusy && (route == null || f != initial),
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problemTexts,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        OutlinedTextField(f.networkId, { f = f.copy(networkId = it.take(RouteRules.MAX_NETWORK_ID)) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_route_network_id)) }, supportingText = { Text(stringResource(R.string.admin_route_network_id_hint)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { f = f.copy(description = it) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_route_description)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            options = listOf(stringResource(R.string.admin_route_network), stringResource(R.string.admin_route_domains)),
            selectedIndex = if (f.domainMode) 1 else 0,
            onOptionSelected = { if (editable) f = f.copy(domainMode = it == 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (f.domainMode) {
            OutlinedTextField(f.domains, { f = f.copy(domains = it) }, enabled = editable, minLines = 2,
                label = { Text(stringResource(R.string.admin_route_domains)) }, supportingText = { Text(stringResource(R.string.admin_route_domains_hint)) }, modifier = Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.admin_route_keep), f.keepRoute, { f = f.copy(keepRoute = it) }, enabled = editable, help = stringResource(R.string.admin_route_keep_help))
        } else {
            OutlinedTextField(f.network, { f = f.copy(network = it.trim()) }, singleLine = true, enabled = editable,
                label = { Text(stringResource(R.string.admin_route_network)) }, supportingText = { Text(stringResource(R.string.admin_route_network_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            if (!f.isExitNode && editable) TextButton(onClick = { f = f.copy(network = "0.0.0.0/0") }) { Icon(Icons.Default.Public, null); Text(" " + stringResource(R.string.admin_route_make_exit)) }
        }
        if (f.isExitNode) {
            StatusBadge(stringResource(R.string.admin_route_exit_node), MaterialTheme.colorScheme.tertiaryContainer)
            HelpText(stringResource(R.string.admin_route_exit_help))
            SwitchRow(stringResource(R.string.admin_route_skip_auto_apply), f.skipAutoApply == true, { f = f.copy(skipAutoApply = it) }, enabled = editable,
                help = stringResource(R.string.admin_route_skip_auto_apply_help))
        }
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            options = listOf(stringResource(R.string.admin_route_peer), stringResource(R.string.admin_route_peer_groups)),
            selectedIndex = if (f.useGroups) 1 else 0,
            onOptionSelected = { if (editable) f = f.copy(useGroups = it == 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (f.useGroups) {
            MemberField(stringResource(R.string.admin_route_peer_groups), groupOptions, f.peerGroups, { f = f.copy(peerGroups = it) },
                emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        } else {
            MemberField(stringResource(R.string.admin_route_peer), peerOptions, listOfNotNull(f.peer), { f = f.copy(peer = it.lastOrNull()) },
                emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable, single = true)
        }
        OutlinedTextField(f.metric, { f = f.copy(metric = it.filter(Char::isDigit).take(4)) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_route_metric)) }, supportingText = { Text(stringResource(R.string.admin_routing_metric_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SwitchRow(stringResource(R.string.admin_route_masquerade), f.masquerade, { f = f.copy(masquerade = it) }, enabled = editable, help = stringResource(R.string.admin_routing_masquerade_help))
        MemberField(stringResource(R.string.admin_route_groups), groupOptions, f.groups, { f = f.copy(groups = it) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        HelpText(stringResource(R.string.admin_route_groups_help))
        MemberField(stringResource(R.string.admin_route_acl_groups), groupOptions, f.accessControlGroups, { f = f.copy(accessControlGroups = it) },
            emptyText = stringResource(R.string.admin_route_acl_none), enabled = editable)
        HelpText(stringResource(R.string.admin_route_acl_help))
        SwitchRow(stringResource(R.string.admin_route_enabled), f.enabled, { f = f.copy(enabled = it) }, enabled = editable)
    }
}

@Preview
@Composable
private fun RoutesContentPreview() = BirdSocksTheme {
    val host = remember { PreviewAdminHost.demo().also { it.data.set(AdminKeys.ROUTES, PREVIEW_ROUTES) } }
    Surface { RoutesContent(host) }
}

/** Routes for previews: an exit node through the laptop and an office subnet. */
internal val PREVIEW_ROUTES = Loadable(Listing(listOf(
    ApiRoute(id = "r1", networkId = "exit-node", network = "0.0.0.0/0", networkType = "IPv4", peer = "p-laptop", groups = listOf("g-office"), enabled = true, masquerade = true),
    ApiRoute(id = "r2", networkId = "office-lan", description = "printers", network = "192.168.1.0/24", networkType = "IPv4", peerGroups = listOf("g-office"), groups = listOf("g-all"), enabled = false),
)), loadedAt = 1)
