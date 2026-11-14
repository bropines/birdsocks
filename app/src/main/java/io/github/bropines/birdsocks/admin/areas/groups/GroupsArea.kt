package io.github.bropines.birdsocks.admin.areas.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.api.ApiResource
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.peers.SelfReach
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.DataKey
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.DetailSheetBody
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberKind
import io.github.bropines.birdsocks.admin.ui.MemberOption
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch

/** Groups: create, rename, members (peers and network resources), delete with where it is used. */
val GroupsArea = AdminArea(
    id = "groups",
    title = R.string.admin_area_groups,
    summary = R.string.admin_area_groups_summary,
    icon = Icons.Default.Workspaces,
    module = Capabilities.GROUPS,
) { scope -> GroupsScreen(scope) }

/** What deleting a group is checked against. */
internal val USAGE_KEYS: List<DataKey<*>> = listOf(
    AdminKeys.POLICIES, AdminKeys.ROUTES, AdminKeys.NAMESERVERS, AdminKeys.DNS_ZONES, AdminKeys.DNS_SETTINGS,
    AdminKeys.SETUP_KEYS, AdminKeys.USERS, AdminKeys.NETWORK_ROUTERS,
)

/** The usage of [groupId] from what [host] holds now; a list the role cannot read, or not read yet, is unchecked. */
internal fun usageOf(host: AdminHost, groupId: String): GroupUsage {
    val d = host.data
    return GroupUsage.of(
        groupId,
        policies = d[AdminKeys.POLICIES].value?.items,
        routes = d[AdminKeys.ROUTES].value?.items,
        nameservers = d[AdminKeys.NAMESERVERS].value?.items,
        dnsZones = d[AdminKeys.DNS_ZONES].value?.items,
        dnsSettings = d[AdminKeys.DNS_SETTINGS].value,
        setupKeys = d[AdminKeys.SETUP_KEYS].value?.items,
        users = d[AdminKeys.USERS].value?.items,
        routers = d[AdminKeys.NETWORK_ROUTERS].value?.items,
    )
}

/**
 * The network resources a group may hold, read on demand (every network's
 * resources is one request per network — not worth it until someone edits
 * a group's resources).
 */
internal class GroupsState(host: AdminHost) : AreaState(host) {
    var resources by mutableStateOf<List<ApiNetworkResource>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)

    fun loadResources() {
        val api = host.api ?: return
        if (loading) return
        loading = true; error = null
        host.scope.launch {
            try {
                val nets = api.networks().items
                resources = nets.flatMap { api.networkResources(it.id).items }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e
            } finally {
                loading = false
            }
        }
    }
}

@Composable
private fun GroupsScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.GROUPS, AdminKeys.PEERS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.GROUPS].loading,
        onRefresh = { host.load(AdminKeys.GROUPS, AdminKeys.PEERS, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.GROUPS, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_group_create)) },
            )
        },
    ) { GroupsContent(host, initialOpenId = scope.arg) }
    if (creating) CreateGroupDialog(host, onDismiss = { creating = false })
}

@Composable
internal fun GroupsContent(host: AdminHost, initialOpenId: String? = null) {
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    LoadableContent(host.data[AdminKeys.GROUPS], onRetry = { host.load(AdminKeys.GROUPS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { g -> listOf(g.name, g.issued) + g.peers.map { it.name } },
            searchHint = stringResource(R.string.admin_groups_search),
            emptyText = stringResource(R.string.admin_groups_empty),
            noMatchText = stringResource(R.string.admin_groups_no_match),
            emptyIcon = Icons.Default.Workspaces,
            filters = listOf(
                ListFilter("with_peers", stringResource(R.string.admin_groups_filter_peers)) { it.peersCount > 0 },
                ListFilter("with_resources", stringResource(R.string.admin_groups_filter_resources)) { it.resourcesCount > 0 },
                ListFilter("empty", stringResource(R.string.admin_groups_filter_empty)) { it.peersCount == 0 && it.resourcesCount == 0 },
                ListFilter("synced", stringResource(R.string.admin_groups_filter_synced)) { it.issued.isNotEmpty() && it.issued != "api" },
            ),
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy<ApiGroup> { !it.isAll }.thenBy { it.name.lowercase() }),
                ListSort("size", stringResource(R.string.admin_sort_size), compareByDescending { it.peersCount + it.resourcesCount }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.GROUPS, force = true) }) } },
        ) { g ->
            ListItem(
                modifier = Modifier.clickable { openId = g.id },
                leadingContent = { Icon(if (g.isAll) Icons.Default.Lock else Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = { Text(groupSummary(g), style = MaterialTheme.typography.bodySmall) },
                headlineContent = { Text(g.name) },
            )
        }
        listing.items.firstOrNull { it.id == openId }?.let { g -> GroupSheet(host, g, onDismiss = { openId = null }) }
    }
}

@Composable
private fun groupSummary(g: ApiGroup): String = listOfNotNull(
    pluralStringResource(R.plurals.admin_group_peers, g.peersCount, g.peersCount),
    if (g.resourcesCount > 0) pluralStringResource(R.plurals.admin_group_resources, g.resourcesCount, g.resourcesCount) else null,
    g.issued.takeIf { it.isNotEmpty() && it != "api" },
).joinToString(" · ")

@Composable
internal fun GroupSheet(host: AdminHost, g: ApiGroup, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    AdminDetailSheet(
        title = g.name,
        subtitle = groupSummary(g),
        onDismiss = onDismiss,
        badges = { if (g.isAll) StatusBadge(stringResource(R.string.admin_group_builtin), MaterialTheme.colorScheme.surfaceVariant) },
    ) { GroupDetails(host, g, onRename = { renaming = true }, onDeleted = onDismiss) }
    if (renaming) {
        val ctx = LocalContext.current
        val title = stringResource(R.string.admin_group_rename)
        val label = stringResource(R.string.admin_group_name)
        val save = stringResource(R.string.action_save)
        val cancel = stringResource(R.string.action_cancel)
        var name by remember { mutableStateOf(g.name) }
        val taken = host.data.items(AdminKeys.GROUPS).any { it.id != g.id && it.name.equals(name.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(title) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(label) }, isError = taken || name.trim() == ApiGroup.ALL) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != g.name && !taken && name.trim() != ApiGroup.ALL,
                    onClick = { renaming = false; host.propose(GroupChanges.rename(ctx, g, name)) },
                ) { Text(save) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(cancel) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupDetails(host: AdminHost, g: ApiGroup, onRename: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val state = host.areaState("groups") { GroupsState(it) }
    val editable = !g.isAll && host.can(Capabilities.GROUPS, Op.UPDATE)
    val canDelete = !g.isAll && host.can(Capabilities.GROUPS, Op.DELETE)
    val idle = !host.changeBusy
    val peers = host.data.items(AdminKeys.PEERS)
    val selfPeer = peers.firstOrNull { host.isSelf(it) }
    val peerNames = remember(peers, g) { (peers.map { it.id to it.name } + g.peers.map { it.id to it.name }).toMap() }

    if (g.isAll) HelpText(stringResource(R.string.admin_group_all_note), lines = 3)
    else if (g.issued.isNotEmpty() && g.issued != "api") HelpText(stringResource(R.string.admin_group_synced_note, g.issued), lines = 3)

    // Peers. An unread PEERS list still shows the group's own names.
    val peerOptions = MemberOptions.peers(peers).ifEmpty { g.peers.map { MemberOption(MemberKind.PEER, it.id, it.name) } }
    MemberField(
        label = stringResource(R.string.admin_group_peers_label),
        options = peerOptions,
        selected = g.peers.map { it.id },
        enabled = editable && idle,
        emptyText = stringResource(R.string.admin_group_no_peers),
        onChange = { after ->
            val selfOut = selfPeer != null && g.peers.any { it.id == selfPeer.id } && selfPeer.id !in after
            val lost = if (selfOut) {
                val before = selfPeer!!.groups.map { it.id }
                SelfReach.policiesLost(before.toSet(), (before - g.id).toSet(), host.data.items(AdminKeys.POLICIES))
            } else emptyList()
            host.propose(GroupChanges.setPeers(ctx, g, after, { peerNames[it] ?: it }, selfPeer?.id, lost))
        },
    )

    // Network resources: names only once they are read.
    val known = state.resources
    val resourceLabel: (ApiResource) -> String = { r -> known?.firstOrNull { it.id == r.id }?.let { "${it.name} (${it.address})" } ?: "${r.type} ${r.id}" }
    if (known == null) {
        if (g.resources.isNotEmpty()) DetailField(stringResource(R.string.admin_group_resources_label), g.resources.joinToString("\n", transform = resourceLabel))
        if (editable && host.canRead(Capabilities.NETWORKS)) {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.admin_group_resources_loading)) }
                state.error != null -> ErrorCard(state.error!!, onRetry = state::loadResources)
                else -> TextButton(onClick = state::loadResources) { Text(stringResource(R.string.admin_group_resources_edit)) }
            }
        }
    } else {
        val options = known.sortedBy { it.name.lowercase() }.map { MemberOption(MemberKind.OTHER, it.id, it.name, listOf(it.address, it.type).filter(String::isNotEmpty).joinToString(" · ")) }
        MemberField(
            label = stringResource(R.string.admin_group_resources_label),
            options = options,
            selected = g.resources.map { it.id },
            enabled = editable && idle,
            emptyText = stringResource(R.string.admin_group_no_resources),
            onChange = { ids ->
                val after = ids.map { id -> g.resources.firstOrNull { it.id == id } ?: ApiResource(id, known.firstOrNull { it.id == id }?.type.orEmpty()) }
                host.propose(GroupChanges.setResources(ctx, g, after, resourceLabel))
            },
        )
    }

    // Where it is used: from what is cached; the delete reads the rest first.
    var deleting by remember { mutableStateOf(false) }
    val usage = usageOf(host, g.id)
    val usageLines = GroupChanges.usageLines(ctx, usage).filter { usage.isUsed || it != ctx.getString(R.string.admin_group_used_unchecked) }
    if (!g.isAll && usageLines.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.admin_group_usage), style = MaterialTheme.typography.labelLarge)
        usageLines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
    val settled = USAGE_KEYS.all { k -> !host.canRead(k.module) || host.data[k].let { !it.loading && (it.value != null || it.error != null) } }
    LaunchedEffect(deleting, settled) {
        if (deleting && settled) {
            deleting = false
            val selfIn = selfPeer != null && g.peers.any { it.id == selfPeer.id }
            if (host.propose(GroupChanges.delete(ctx, g, usageOf(host, g.id), selfIn))) onDeleted()
        }
    }

    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editable) OutlinedButton(enabled = idle, onClick = onRename) {
            Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_group_rename))
        }
        if (canDelete) OutlinedButton(
            enabled = idle && !deleting,
            onClick = { host.load(*USAGE_KEYS.toTypedArray()); deleting = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            if (deleting) CircularProgressIndicator(Modifier.size(18.dp)) else Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_group_delete))
        }
    }
    if (!g.isAll && !host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
}

/** A new group: a name (unique, not "All") and, optionally, its peers. */
@Composable
private fun CreateGroupDialog(host: AdminHost, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var name by rememberSaveable { mutableStateOf("") }
    var members by rememberSaveable { mutableStateOf(listOf<String>()) }
    val peers = host.data.items(AdminKeys.PEERS)
    val taken = host.data.items(AdminKeys.GROUPS).any { it.name.equals(name.trim(), ignoreCase = true) } || name.trim() == ApiGroup.ALL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_group_create)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        name, { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_group_name)) },
                        isError = taken,
                        supportingText = if (taken) ({ Text(stringResource(R.string.admin_group_name_taken)) }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MemberField(
                        label = stringResource(R.string.admin_group_peers_label),
                        options = MemberOptions.peers(peers),
                        selected = members,
                        onChange = { members = it },
                        emptyText = stringResource(R.string.admin_group_no_peers),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !taken && !host.changeBusy, onClick = {
                val names = members.map { id -> peers.firstOrNull { it.id == id }?.name ?: id }
                if (host.propose(GroupChanges.create(ctx, name, members, names))) onDismiss()
            }) { Text(ctx.getString(R.string.admin_group_create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun GroupsContentPreview() = BirdSocksTheme {
    Surface { GroupsContent(PreviewAdminHost.demo()) }
}

@Preview
@Composable
private fun GroupDetailsPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    val g = host.data.items(AdminKeys.GROUPS).last()
    Surface { DetailSheetBody(g.name, null) { GroupDetails(host, g, {}, {}) } }
}
