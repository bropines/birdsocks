package io.github.bropines.birdsocks.admin.areas.peers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListQuery
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberAdminListState
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Peers: the account's machines; [AreaScope.arg] opens one by id. */
val PeersArea = AdminArea(
    id = "peers",
    title = R.string.admin_area_peers,
    summary = R.string.admin_area_peers_summary,
    icon = Icons.Default.Devices,
    module = Capabilities.PEERS,
) { scope -> PeersScreen(scope) }

@Composable
private fun PeersScreen(scope: AreaScope) {
    val host = scope.host
    // Policies feed the "may cut this device off" check; read only if the role may.
    LaunchedEffect(Unit) { host.load(AdminKeys.PEERS, AdminKeys.GROUPS, AdminKeys.POLICIES) }
    val loadable = host.data[AdminKeys.PEERS]
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var bulk by rememberSaveable { mutableStateOf(false) }
    val canGroups = host.can(Capabilities.GROUPS, Op.UPDATE)
    AreaScaffold(
        scope,
        subtitle = if (selected.isNotEmpty()) pluralStringResource(R.plurals.admin_peers_selected, selected.size, selected.size) else null,
        refreshing = loadable.loading,
        onRefresh = { host.load(AdminKeys.PEERS, AdminKeys.GROUPS, force = true) },
        actions = {
            if (selected.isNotEmpty()) {
                if (canGroups) IconButton(onClick = { bulk = true }, enabled = !host.changeBusy) {
                    Icon(Icons.Default.Workspaces, stringResource(R.string.admin_bulk_groups))
                }
                IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Default.Close, stringResource(R.string.admin_peers_selection_clear)) }
            }
        },
    ) {
        PeersContent(host, initialOpenId = scope.arg, selected = selected, onSelectedChange = { selected = it })
    }
    if (bulk) {
        val peers = host.data.items(AdminKeys.PEERS).filter { it.id in selected }
        BulkGroupsSheet(host, peers, onDismiss = { bulk = false }, onProposed = { bulk = false; selected = emptyList() })
    }
}

/**
 * The list and the open peer's sheet. A long press starts a selection (for
 * bulk group changes); while one is on, a tap adds or removes.
 */
@Composable
internal fun PeersContent(
    host: AdminHost,
    initialOpenId: String? = null,
    selected: List<String> = emptyList(),
    onSelectedChange: (List<String>) -> Unit = {},
) {
    val loadable = host.data[AdminKeys.PEERS]
    val groups = host.data.items(AdminKeys.GROUPS)
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    val listState = rememberAdminListState()
    LoadableContent(loadable, onRetry = { host.load(AdminKeys.PEERS, force = true) }) { listing, stale ->
        val peers = listing.items
        val fixedLabels = mapOf(
            PeerQuery.ONLINE to stringResource(R.string.admin_peer_online),
            PeerQuery.OFFLINE to stringResource(R.string.admin_peer_offline),
            PeerQuery.PENDING to stringResource(R.string.admin_peer_pending),
            PeerQuery.EXPIRATION_ON to stringResource(R.string.admin_peers_filter_expiration),
            PeerQuery.EXPIRED to stringResource(R.string.admin_peer_expired),
            PeerQuery.SSH to stringResource(R.string.admin_peers_filter_ssh),
        )
        val filters = PeerQuery.fixed.map { (id, test) -> ListFilter(id, fixedLabels.getValue(id), test) } +
            PeerQuery.groupChips(peers, groups).map { g -> ListFilter(PeerQuery.groupFilterId(g.id), g.name, PeerQuery.inGroup(g.id)) } +
            PeerQuery.osFamilies(peers).map { f -> ListFilter(PeerQuery.osFilterId(f), PeerQuery.osLabel(f), PeerQuery.hasOs(f)) }
        val sorts = listOf(
            ListSort("attention", stringResource(R.string.admin_sort_attention), PeerQuery.attention(host::isSelf)),
            ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() }),
            ListSort("seen", stringResource(R.string.admin_sort_last_seen), compareByDescending<ApiPeer> { it.connected }.thenByDescending { it.lastSeen.orEmpty() }),
            ListSort("os", stringResource(R.string.admin_peers_sort_os), compareBy<ApiPeer> { PeerQuery.osFamily(it.os) }.thenBy { it.name.lowercase() }),
        )
        val searchText: (ApiPeer) -> List<String> = { p -> listOf(p.name, p.ip, p.dnsLabel, p.hostname, p.os, p.connectionIp, p.cityName, p.version) + p.groups.map { it.name } }
        val selecting = selected.isNotEmpty()
        AdminList(
            items = peers,
            key = { it.id },
            searchText = searchText,
            searchHint = stringResource(R.string.admin_peers_search),
            emptyText = stringResource(R.string.admin_peers_empty),
            noMatchText = stringResource(R.string.admin_peers_no_match),
            emptyIcon = Icons.Default.Devices,
            filters = filters,
            sorts = sorts,
            state = listState,
            issues = listing.issues.size,
            header = {
                if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.PEERS, force = true) }) }
                if (selecting) item(key = "selection") {
                    val sort = sorts.firstOrNull { it.id == listState.sortId } ?: sorts.first()
                    val shown = ListQuery.apply(peers, listState.query, searchText, filters, listState.active, sort).map { it.id }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onSelectedChange((selected + shown).distinct()) }) { Text(stringResource(R.string.admin_peers_select_shown, shown.size)) }
                        TextButton(onClick = { onSelectedChange(emptyList()) }) { Text(stringResource(R.string.admin_peers_selection_clear)) }
                    }
                }
            },
        ) { peer ->
            val on = peer.id in selected
            PeerRow(
                peer, host.isSelf(peer),
                selecting = selecting, selected = on,
                onClick = { if (selecting) onSelectedChange(if (on) selected - peer.id else selected + peer.id) else openId = peer.id },
                onLongClick = { onSelectedChange(if (on) selected - peer.id else selected + peer.id) },
            )
        }
        peers.firstOrNull { it.id == openId }?.let { peer -> PeerSheet(host, peer, onDismiss = { openId = null }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(peer: ApiPeer, self: Boolean, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            else Icon(
                if (peer.connected) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked, null,
                Modifier.size(12.dp),
                tint = if (peer.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        supportingContent = {
            Text(
                listOf(peer.ip, peer.os).filter { it.isNotEmpty() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (self) StatusBadge(stringResource(R.string.admin_peer_this_device), MaterialTheme.colorScheme.primaryContainer)
                if (peer.approvalRequired) StatusBadge(stringResource(R.string.admin_peer_pending), MaterialTheme.colorScheme.tertiaryContainer)
                if (peer.loginExpired) StatusBadge(stringResource(R.string.admin_peer_expired), MaterialTheme.colorScheme.errorContainer)
                if (peer.ephemeral) StatusBadge(stringResource(R.string.admin_peer_ephemeral), MaterialTheme.colorScheme.surfaceVariant)
            }
        },
        headlineContent = { Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Preview
@Composable
private fun PeersContentPreview() = BirdSocksTheme {
    Surface { PeersContent(PreviewAdminHost.demo()) }
}

@Preview
@Composable
private fun PeersSelectingPreview() = BirdSocksTheme {
    Surface { PeersContent(PreviewAdminHost.demo(), selected = listOf("p-laptop", "p-nas")) }
}
