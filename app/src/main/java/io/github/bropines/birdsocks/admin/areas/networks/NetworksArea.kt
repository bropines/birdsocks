package io.github.bropines.birdsocks.admin.areas.networks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminRoute
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiNetwork
import io.github.bropines.birdsocks.admin.api.ApiNetworkRequest
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.api.ApiNetworkResourceRequest
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouterRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.routing.Badges
import io.github.bropines.birdsocks.admin.areas.routing.FormDialog
import io.github.bropines.birdsocks.admin.areas.routing.JsonTarget
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonEditor
import io.github.bropines.birdsocks.admin.areas.routing.SectionTitle
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.areas.routing.SwitchRow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.DataKey
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.IncompleteNote
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonObject

/** Networks: resources behind routing peers; [AreaScope.arg] opens one network by id. */
val NetworksArea = AdminArea(
    id = "networks",
    title = R.string.admin_area_networks,
    summary = R.string.admin_area_networks_summary,
    icon = Icons.Default.Hub,
    module = Capabilities.NETWORKS,
) { scope -> NetworksScreen(scope) }

/** A network being created or edited: its name and description. */
data class NetworkForm(val name: String = "", val description: String = "")

/**
 * The area's own state: which network is open, the open forms, the JSON
 * document, and per network the keys of its resources and routers (read
 * when it opens, never cached, refreshed after a change).
 */
class NetworksState(host: AdminHost) : AreaState(host) {
    var openId by mutableStateOf<String?>(null)
    var network by mutableStateOf<Pair<String?, NetworkForm>?>(null)
    var resource by mutableStateOf<Pair<String?, ResourceForm>?>(null)
    var router by mutableStateOf<Pair<String?, RouterForm>?>(null)
    var json by mutableStateOf<JsonTarget?>(null)

    private val resourceKeys = HashMap<String, DataKey<Listing<ApiNetworkResource>>>()
    private val routerKeys = HashMap<String, DataKey<Listing<ApiNetworkRouter>>>()

    fun resourcesKey(networkId: String) = resourceKeys.getOrPut(networkId) {
        DataKey.list("network_resources:$networkId", Capabilities.NETWORKS, ApiNetworkResource.serializer(), cache = false) { it.networkResources(networkId) }
    }

    fun routersKey(networkId: String) = routerKeys.getOrPut(networkId) {
        DataKey.list("network_routers:$networkId", Capabilities.NETWORKS, ApiNetworkRouter.serializer(), cache = false) { it.networkRouters(networkId) }
    }

    fun keys(networkId: String): List<DataKey<*>> = listOf(resourcesKey(networkId), routersKey(networkId))
}

@Composable
private fun NetworksScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("networks") { NetworksState(it) }
    LaunchedEffect(Unit) {
        host.load(AdminKeys.NETWORKS, AdminKeys.GROUPS, AdminKeys.PEERS, AdminKeys.POLICIES)
        scope.arg?.let { state.openId = it }
    }
    val openId = state.openId
    if (openId == null) {
        AreaScaffold(
            scope,
            refreshing = host.data[AdminKeys.NETWORKS].loading,
            onRefresh = { host.load(AdminKeys.NETWORKS, AdminKeys.POLICIES, force = true) },
            floatingActionButton = {
                if (host.can(Capabilities.NETWORKS, Op.CREATE)) ExtendedFloatingActionButton(
                    onClick = { state.network = null to NetworkForm() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.admin_net_new)) },
                )
            },
        ) { NetworksList(host, state) }
    } else {
        BackHandler { state.openId = null }
        LaunchedEffect(openId) { host.load(*state.keys(openId).toTypedArray()) }
        val network = host.data.items(AdminKeys.NETWORKS).firstOrNull { it.id == openId }
        val inner = remember(scope, openId) { AreaScope(host, scope.area, scope.arg, onBack = { state.openId = null }) }
        AreaScaffold(
            inner,
            subtitle = network?.name,
            refreshing = state.keys(openId).any { host.data[it].loading },
            onRefresh = { host.load(*(state.keys(openId) + AdminKeys.NETWORKS + AdminKeys.POLICIES).toTypedArray(), force = true) },
        ) {
            if (network == null) EmptyState(Icons.Default.Hub, stringResource(R.string.admin_net_gone))
            else NetworkDetail(host, state, network)
        }
    }
    NetworkDialogs(host, state)
}

@Composable
internal fun NetworksList(host: AdminHost, state: NetworksState) {
    val policies = host.data.items(AdminKeys.POLICIES)
    LoadableContent(host.data[AdminKeys.NETWORKS], onRetry = { host.load(AdminKeys.NETWORKS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { n -> listOf(n.name, n.description) + n.policies.mapNotNull { id -> policies.firstOrNull { it.id == id }?.name } },
            searchHint = stringResource(R.string.admin_net_search),
            emptyText = stringResource(R.string.admin_net_empty),
            noMatchText = stringResource(R.string.admin_net_no_match),
            emptyIcon = Icons.Default.Hub,
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy<ApiNetwork> { it.name.lowercase() }),
                ListSort("size", stringResource(R.string.admin_sort_size), compareByDescending { it.resources.size }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.NETWORKS, force = true) }) } },
        ) { n ->
            ListItem(
                modifier = Modifier.clickable { state.openId = n.id },
                leadingContent = { Icon(Icons.Default.Hub, null, tint = if (n.routingPeersCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                headlineContent = { Text(n.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        if (n.description.isNotEmpty()) Text(n.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(
                                pluralStringResource(R.plurals.admin_net_resources, n.resources.size, n.resources.size),
                                pluralStringResource(R.plurals.admin_net_routing_peers, n.routingPeersCount, n.routingPeersCount),
                                pluralStringResource(R.plurals.admin_net_policies, n.policies.size, n.policies.size),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                trailingContent = { if (n.routingPeersCount == 0) StatusBadge(stringResource(R.string.admin_net_no_router), MaterialTheme.colorScheme.errorContainer) },
            )
        }
    }
}

/** One network: what it is, its resources with who may reach them, its routing peers, its policies. */
@Composable
internal fun NetworkDetail(host: AdminHost, state: NetworksState, n: ApiNetwork) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val peers = host.data.items(AdminKeys.PEERS)
    val groups = host.data.items(AdminKeys.GROUPS)
    val policies = host.data.items(AdminKeys.POLICIES)
    val names: (String) -> String = { id -> groups.firstOrNull { it.id == id }?.name ?: peers.firstOrNull { it.id == id }?.name ?: id }
    val canCreate = host.can(Capabilities.NETWORKS, Op.CREATE)
    val canUpdate = host.can(Capabilities.NETWORKS, Op.UPDATE)
    val canDelete = host.can(Capabilities.NETWORKS, Op.DELETE)
    val resources = host.data[state.resourcesKey(n.id)]
    val routers = host.data[state.routersKey(n.id)]
    val also = state.keys(n.id)
    val selfRoutes = routers.value?.items.orEmpty().any { NetworkRules.isSelfRouter(it, self.peerId, self.groupIds) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (n.description.isNotEmpty()) Text(n.description, style = MaterialTheme.typography.bodyMedium)
                if (selfRoutes) StatusBadge(stringResource(R.string.admin_net_self_routes), MaterialTheme.colorScheme.primaryContainer, Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canUpdate) OutlinedButton(enabled = !host.changeBusy, onClick = { state.network = n.id to NetworkForm(n.name, n.description) }) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_edit))
                    }
                    OutlinedButton(onClick = {
                        state.json = JsonTarget(n.name, ctx.getString(R.string.admin_area_networks), NetworkChanges.networkPath(n.id),
                            AppJson.encodeToJsonElement(ApiNetwork.serializer(), n) as JsonObject, canUpdate) { b, a -> NetworkChanges.json(ctx, NetworkChanges.target(n), NetworkChanges.networkPath(n.id), b, a, emptyList()) }
                    }) { Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_routing_json)) }
                    if (canDelete) OutlinedButton(
                        enabled = !host.changeBusy,
                        onClick = { if (host.propose(NetworkChanges.delete(ctx, n, selfRoutes))) state.openId = null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }
                }
            }
        }

        // --- Resources ---
        item(key = "res-title") {
            SectionTitle(stringResource(R.string.admin_net_resources_title), onAdd = if (canCreate) ({ state.resource = null to ResourceForm() }) else null, addLabel = stringResource(R.string.admin_net_add))
        }
        sectionState(resources, onRetry = { host.load(state.resourcesKey(n.id), force = true) }, keyPrefix = "res")
        val resList = resources.value?.items.orEmpty().sortedBy { it.name.lowercase() }
        if (resources.value != null && resList.isEmpty()) item(key = "res-empty") { HelpText(stringResource(R.string.admin_net_resources_empty), Modifier.padding(horizontal = 16.dp)) }
        items(resList, key = { "res-" + it.id }) { r ->
            ResourceRow(r, NetworkRules.grants(r, policies), policiesKnown = host.data[AdminKeys.POLICIES].value != null,
                canToggle = canUpdate && !host.changeBusy,
                onToggle = { on -> host.propose(NetworkChanges.updateResource(ctx, n.id, r, ApiNetworkResourceRequest.of(r).copy(enabled = on), names, also)) },
                onOpenPolicy = { host.navigate(AdminRoute("policies", it)) },
                onClick = { state.resource = r.id to NetworkRules.resourceForm(r) })
        }

        // --- Routers ---
        item(key = "rt-title") {
            SectionTitle(stringResource(R.string.admin_net_routers_title), onAdd = if (canCreate) ({ state.router = null to RouterForm() }) else null, addLabel = stringResource(R.string.admin_net_add))
        }
        sectionState(routers, onRetry = { host.load(state.routersKey(n.id), force = true) }, keyPrefix = "rt")
        val rtList = routers.value?.items.orEmpty()
        if (routers.value != null && rtList.isEmpty()) item(key = "rt-empty") { HelpText(stringResource(R.string.admin_net_routers_empty), Modifier.padding(horizontal = 16.dp)) }
        items(rtList, key = { "rt-" + it.id }) { r ->
            val isSelf = NetworkRules.isSelfRouter(r, self.peerId, self.groupIds)
            RouterRow(r, names, isSelf, canToggle = canUpdate && !host.changeBusy,
                onToggle = { on -> host.propose(NetworkChanges.updateRouter(ctx, n.id, r, ApiNetworkRouterRequest.of(r).copy(enabled = on), isSelf, names, also)) },
                onClick = { state.router = r.id to NetworkRules.routerForm(r) })
        }

        // --- Policies ---
        item(key = "pol-title") { SectionTitle(stringResource(R.string.admin_net_policies_title)) }
        if (n.policies.isEmpty()) item(key = "pol-empty") { HelpText(stringResource(R.string.admin_net_policies_empty), Modifier.padding(horizontal = 16.dp)) }
        items(n.policies, key = { "pol-$it" }) { id ->
            val p = policies.firstOrNull { it.id == id }
            ListItem(
                modifier = Modifier.clickable { host.navigate(AdminRoute("policies", id)) },
                leadingContent = { Icon(Icons.Default.Policy, null, tint = if (p?.enabled != false) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                headlineContent = { Text(p?.name ?: id) },
                supportingContent = { if (p != null && !p.enabled) Text(stringResource(R.string.admin_routing_disabled), style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}

/** A loading line or an error card for one of a network's lists. */
private fun androidx.compose.foundation.lazy.LazyListScope.sectionState(l: Loadable<out Listing<*>>, onRetry: () -> Unit, keyPrefix: String) {
    if (l.loading && l.value == null) item(key = "$keyPrefix-loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
    l.error?.let { e -> item(key = "$keyPrefix-error") { ErrorCard(e, onRetry = onRetry) } }
    l.value?.let { v -> if (!v.isComplete) item(key = "$keyPrefix-incomplete") { IncompleteNote(v.issues.size) } }
}

@Composable
private fun ResourceRow(
    r: ApiNetworkResource,
    grants: List<ResourceGrant>,
    policiesKnown: Boolean,
    canToggle: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenPolicy: (String) -> Unit,
    onClick: () -> Unit,
) {
    val active = grants.filter { it.active }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                when (r.type) { NetworkRules.DOMAIN -> Icons.Default.Language; NetworkRules.SUBNET -> Icons.Default.Lan; else -> Icons.Default.Dns }, null,
                tint = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(r.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (r.groups.isNotEmpty()) Text(stringResource(R.string.admin_net_row_groups, r.groups.joinToString(", ") { it.name.ifEmpty { it.id } }), style = MaterialTheme.typography.bodySmall)
                when {
                    !policiesKnown -> Unit
                    active.isEmpty() -> Text(stringResource(R.string.admin_net_no_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> active.forEach { g ->
                        Text(
                            stringResource(R.string.admin_net_access_line, g.policyName, g.sources.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenPolicy(g.policyId) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Badges {
                    StatusBadge(typeLabel(r.type), MaterialTheme.colorScheme.surfaceVariant)
                    if (!r.enabled) StatusBadge(stringResource(R.string.admin_routing_disabled), MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        },
        trailingContent = { Switch(r.enabled, onToggle, enabled = canToggle) },
    )
}

@Composable
private fun typeLabel(type: String?): String = when (type) {
    NetworkRules.HOST -> stringResource(R.string.admin_net_type_host)
    NetworkRules.SUBNET -> stringResource(R.string.admin_net_type_subnet)
    NetworkRules.DOMAIN -> stringResource(R.string.admin_net_type_domain)
    else -> type.orEmpty()
}

@Composable
private fun RouterRow(r: ApiNetworkRouter, names: (String) -> String, self: Boolean, canToggle: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Default.Router, null, tint = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
        headlineContent = { Text(NetworkChanges.routerLabel(ApiNetworkRouterRequest.of(r), names), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        stringResource(if (r.peerGroups.isNullOrEmpty()) R.string.admin_net_router_peer else R.string.admin_net_router_groups),
                        stringResource(R.string.admin_net_row_metric, r.metric),
                        if (r.masquerade) stringResource(R.string.admin_routing_masquerade) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Badges {
                    if (self) StatusBadge(stringResource(R.string.admin_routing_this_device), MaterialTheme.colorScheme.primaryContainer)
                    if (!r.enabled) StatusBadge(stringResource(R.string.admin_routing_disabled), MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        },
        trailingContent = { Switch(r.enabled, onToggle, enabled = canToggle) },
    )
}

/** The forms and the JSON editor, wherever the area is. */
@Composable
private fun NetworkDialogs(host: AdminHost, state: NetworksState) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val peers = host.data.items(AdminKeys.PEERS)
    val groups = host.data.items(AdminKeys.GROUPS)
    val names: (String) -> String = { id -> groups.firstOrNull { it.id == id }?.name ?: peers.firstOrNull { it.id == id }?.name ?: id }
    val network = state.openId?.let { id -> host.data.items(AdminKeys.NETWORKS).firstOrNull { it.id == id } }
    val canUpdate = host.can(Capabilities.NETWORKS, Op.UPDATE)
    val canDelete = host.can(Capabilities.NETWORKS, Op.DELETE)

    state.network?.let { (id, form) ->
        val existing = id?.let { i -> host.data.items(AdminKeys.NETWORKS).firstOrNull { it.id == i } }
        NetworkFormDialog(host, form, existing, onDismiss = { state.network = null }) { f ->
            val request = ApiNetworkRequest(f.name.trim(), f.description.trim())
            val change = if (existing == null) NetworkChanges.create(ctx, request) else NetworkChanges.update(ctx, existing, request)
            if (host.propose(change)) state.network = null
        }
    }

    if (network != null) {
        val also = state.keys(network.id)
        state.resource?.let { (id, form) ->
            val existing = id?.let { i -> host.data[state.resourcesKey(network.id)].value?.items?.firstOrNull { it.id == i } }
            val grants = existing?.let { NetworkRules.grants(it, host.data.items(AdminKeys.POLICIES)) }.orEmpty()
            ResourceDialog(
                host, form, existing,
                onDismiss = { state.resource = null },
                onSave = { f ->
                    val request = NetworkRules.request(f)
                    val change = if (existing == null) NetworkChanges.createResource(ctx, network, request, names, also)
                    else NetworkChanges.updateResource(ctx, network.id, existing, request, names, also)
                    if (host.propose(change)) state.resource = null
                },
                onJson = existing?.let { r ->
                    {
                        state.resource = null
                        state.json = JsonTarget(r.name, network.name, NetworkChanges.resourcePath(network.id, r.id),
                            AppJson.encodeToJsonElement(ApiNetworkResource.serializer(), r) as JsonObject, canUpdate) { b, a ->
                            NetworkChanges.json(ctx, NetworkChanges.target(r), NetworkChanges.resourcePath(network.id, r.id), b, a, also)
                        }
                    }
                },
                onDelete = existing?.takeIf { canDelete }?.let { r -> { if (host.propose(NetworkChanges.deleteResource(ctx, network.id, r, grants.count { it.active }, also))) state.resource = null } },
            )
        }
        state.router?.let { (id, form) ->
            val existing = id?.let { i -> host.data[state.routersKey(network.id)].value?.items?.firstOrNull { it.id == i } }
            val isSelf = existing?.let { NetworkRules.isSelfRouter(it, self.peerId, self.groupIds) } == true
            RouterDialog(
                host, form, existing,
                onDismiss = { state.router = null },
                onSave = { f ->
                    val request = NetworkRules.request(f)
                    val change = if (existing == null) NetworkChanges.createRouter(ctx, network, request, names, also)
                    else NetworkChanges.updateRouter(ctx, network.id, existing, request, isSelf, names, also)
                    if (host.propose(change)) state.router = null
                },
                onJson = existing?.let { r ->
                    {
                        state.router = null
                        val label = NetworkChanges.routerLabel(ApiNetworkRouterRequest.of(r), names)
                        state.json = JsonTarget(label, network.name, NetworkChanges.routerPath(network.id, r.id),
                            AppJson.encodeToJsonElement(ApiNetworkRouter.serializer(), r) as JsonObject, canUpdate) { b, a ->
                            NetworkChanges.json(ctx, NetworkChanges.target(r, label), NetworkChanges.routerPath(network.id, r.id), b, a, also)
                        }
                    }
                },
                onDelete = existing?.takeIf { canDelete }?.let { r -> { if (host.propose(NetworkChanges.deleteRouter(ctx, network.id, r, isSelf, names, also))) state.router = null } },
            )
        }
    }

    state.json?.let { RawJsonEditor(host, it, onClose = { state.json = null }) }
}

@Composable
private fun NetworkFormDialog(host: AdminHost, initial: NetworkForm, existing: ApiNetwork?, onDismiss: () -> Unit, onSave: (NetworkForm) -> Unit) {
    var f by remember(initial) { mutableStateOf(initial) }
    FormDialog(
        title = existing?.name ?: stringResource(R.string.admin_net_new),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = f.name.isNotBlank() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = if (f.name.isBlank()) listOf(stringResource(R.string.admin_routing_problem_name)) else emptyList(),
    ) {
        OutlinedTextField(f.name, { f = f.copy(name = it) }, singleLine = true, label = { Text(stringResource(R.string.admin_net_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { f = f.copy(description = it) }, label = { Text(stringResource(R.string.admin_net_description)) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResourceDialog(host: AdminHost, initial: ResourceForm, existing: ApiNetworkResource?, onDismiss: () -> Unit, onSave: (ResourceForm) -> Unit, onJson: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var f by remember(initial) { mutableStateOf(initial) }
    val editable = host.can(Capabilities.NETWORKS, if (existing == null) Op.CREATE else Op.UPDATE)
    val problems = NetworkRules.validate(f).map {
        when (it) {
            ResourceProblem.NAME -> stringResource(R.string.admin_routing_problem_name)
            ResourceProblem.ADDRESS -> stringResource(R.string.admin_net_problem_address)
        }
    }
    val type = NetworkRules.resourceType(f.address)
    FormDialog(
        title = existing?.name ?: stringResource(R.string.admin_net_resource_new),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problems,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        OutlinedTextField(f.name, { f = f.copy(name = it) }, singleLine = true, enabled = editable, label = { Text(stringResource(R.string.admin_net_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { f = f.copy(description = it) }, enabled = editable, label = { Text(stringResource(R.string.admin_net_description)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            f.address, { f = f.copy(address = it.trim()) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_net_address)) },
            supportingText = { Text(if (type != null) stringResource(R.string.admin_net_address_is, typeLabel(type)) else stringResource(R.string.admin_net_address_hint)) },
            isError = f.address.isNotBlank() && type == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        MemberField(stringResource(R.string.admin_net_groups), MemberOptions.groups(host.data.items(AdminKeys.GROUPS), includeAll = false), f.groups, { f = f.copy(groups = it) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        HelpText(stringResource(R.string.admin_net_groups_help))
        SwitchRow(stringResource(R.string.admin_routing_enabled), f.enabled, { f = f.copy(enabled = it) }, enabled = editable)
    }
}

@Composable
private fun RouterDialog(host: AdminHost, initial: RouterForm, existing: ApiNetworkRouter?, onDismiss: () -> Unit, onSave: (RouterForm) -> Unit, onJson: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var f by remember(initial) { mutableStateOf(initial) }
    val editable = host.can(Capabilities.NETWORKS, if (existing == null) Op.CREATE else Op.UPDATE)
    val problems = NetworkRules.validate(f).map {
        when (it) {
            RouterProblem.NO_ROUTER -> stringResource(R.string.admin_net_problem_router)
            RouterProblem.METRIC -> stringResource(R.string.admin_routing_problem_metric)
        }
    }
    FormDialog(
        title = stringResource(if (existing == null) R.string.admin_net_router_new else R.string.admin_net_router_edit),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problems,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        SlidingSegmentedChips(
            options = listOf(stringResource(R.string.admin_net_router_peer), stringResource(R.string.admin_net_router_groups)),
            selectedIndex = if (f.useGroups) 1 else 0,
            onOptionSelected = { if (editable) f = f.copy(useGroups = it == 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (f.useGroups) MemberField(stringResource(R.string.admin_net_router_groups), MemberOptions.groups(host.data.items(AdminKeys.GROUPS)), f.peerGroups, { f = f.copy(peerGroups = it) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        else MemberField(stringResource(R.string.admin_net_router_peer), MemberOptions.peers(host.data.items(AdminKeys.PEERS)), listOfNotNull(f.peer), { f = f.copy(peer = it.lastOrNull()) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable, single = true)
        OutlinedTextField(f.metric, { f = f.copy(metric = it.filter(Char::isDigit).take(4)) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_routing_metric)) }, supportingText = { Text(stringResource(R.string.admin_routing_metric_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SwitchRow(stringResource(R.string.admin_routing_masquerade), f.masquerade, { f = f.copy(masquerade = it) }, enabled = editable, help = stringResource(R.string.admin_routing_masquerade_help))
        SwitchRow(stringResource(R.string.admin_routing_enabled), f.enabled, { f = f.copy(enabled = it) }, enabled = editable)
    }
}

/** A network for previews: two resources, one routing peer, one policy. */
internal fun previewNetworksHost(): PreviewAdminHost = PreviewAdminHost.demo().also { h ->
    h.data.set(AdminKeys.NETWORKS, Loadable(Listing(listOf(
        ApiNetwork(id = "n1", name = "Office", description = "The office LAN", routers = listOf("rt1"), routingPeersCount = 1, resources = listOf("res1", "res2"), policies = listOf("pol1")),
        ApiNetwork(id = "n2", name = "Lab", resources = listOf()),
    )), loadedAt = 1))
    val state = h.areaState("networks") { NetworksState(it) }
    h.data.set(state.resourcesKey("n1"), Loadable(Listing(listOf(
        ApiNetworkResource(id = "res1", type = NetworkRules.SUBNET, name = "LAN", address = "192.168.1.0/24", enabled = true, groups = listOf(ApiGroupMinimum("g-office", "office"))),
        ApiNetworkResource(id = "res2", type = NetworkRules.DOMAIN, name = "Wiki", address = "*.wiki.lan", enabled = false),
    )), loadedAt = 1))
    h.data.set(state.routersKey("n1"), Loadable(Listing(listOf(
        ApiNetworkRouter(id = "rt1", peer = "p-laptop", metric = 100, masquerade = true, enabled = true),
    )), loadedAt = 1))
}

@Preview
@Composable
private fun NetworksListPreview() = BirdSocksTheme {
    val host = remember { previewNetworksHost() }
    Surface { NetworksList(host, host.areaState("networks") { NetworksState(it) }) }
}

@Preview
@Composable
private fun NetworkDetailPreview() = BirdSocksTheme {
    val host = remember { previewNetworksHost() }
    Surface { NetworkDetail(host, host.areaState("networks") { NetworksState(it) }, host.data.items(AdminKeys.NETWORKS).first()) }
}
