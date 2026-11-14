package io.github.bropines.birdsocks.admin.areas.peers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
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
    LaunchedEffect(Unit) { host.load(AdminKeys.PEERS, AdminKeys.GROUPS) }
    val loadable = host.data[AdminKeys.PEERS]
    AreaScaffold(scope, refreshing = loadable.loading, onRefresh = { host.load(AdminKeys.PEERS, force = true) }) {
        PeersContent(host, initialOpenId = scope.arg)
    }
}

/** The list and the open peer's sheet. */
@Composable
internal fun PeersContent(host: AdminHost, initialOpenId: String? = null) {
    val loadable = host.data[AdminKeys.PEERS]
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    LoadableContent(loadable, onRetry = { host.load(AdminKeys.PEERS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { p -> listOf(p.name, p.ip, p.dnsLabel, p.hostname, p.os, p.connectionIp) + p.groups.map { it.name } },
            searchHint = stringResource(R.string.admin_peers_search),
            emptyText = stringResource(R.string.admin_peers_empty),
            noMatchText = stringResource(R.string.admin_peers_no_match),
            emptyIcon = Icons.Default.Devices,
            filters = listOf(
                ListFilter("online", stringResource(R.string.admin_peer_online)) { it.connected },
                ListFilter("offline", stringResource(R.string.admin_peer_offline)) { !it.connected },
                ListFilter("pending", stringResource(R.string.admin_peer_pending)) { it.approvalRequired },
                ListFilter("expired", stringResource(R.string.admin_peer_expired)) { it.loginExpired },
            ),
            sorts = listOf(
                ListSort("attention", stringResource(R.string.admin_sort_attention),
                    compareByDescending<ApiPeer> { it.approvalRequired }.thenByDescending { host.isSelf(it) }.thenByDescending { it.connected }.thenBy { it.name.lowercase() }),
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() }),
                ListSort("seen", stringResource(R.string.admin_sort_last_seen), compareByDescending<ApiPeer> { it.connected }.thenByDescending { it.lastSeen.orEmpty() }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.PEERS, force = true) }) } },
        ) { peer -> PeerRow(peer, host.isSelf(peer)) { openId = peer.id } }
        listing.items.firstOrNull { it.id == openId }?.let { peer -> PeerSheet(host, peer, onDismiss = { openId = null }) }
    }
}

@Composable
private fun PeerRow(peer: ApiPeer, self: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
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

/**
 * A peer's details and what may be changed: the name, login expiration,
 * approval, deletion. Every change goes through the gates
 * ([PeerChanges]); deleting this device's own peer is not offered, and the
 * runner would refuse it anyway.
 */
@Composable
internal fun PeerSheet(host: AdminHost, peer: ApiPeer, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val self = host.isSelf(peer)
    val canUpdate = host.can(Capabilities.PEERS, Op.UPDATE)
    val canDelete = host.can(Capabilities.PEERS, Op.DELETE) && !self
    var renaming by remember { mutableStateOf(false) }

    AdminDetailSheet(
        title = peer.name,
        onDismiss = onDismiss,
        badges = { if (self) StatusBadge(stringResource(R.string.admin_peer_this_device), MaterialTheme.colorScheme.primaryContainer) },
    ) {
        SelectionContainer {
            androidx.compose.foundation.layout.Column {
                DetailField(stringResource(R.string.admin_peer_address), listOf(peer.ip, peer.ipv6).filter { it.isNotEmpty() }.joinToString("\n"), mono = true)
                DetailField(stringResource(R.string.admin_peer_dns), peer.dnsLabel, mono = true)
                DetailField(
                    stringResource(R.string.admin_peer_status),
                    stringResource(if (peer.connected) R.string.admin_peer_online else R.string.admin_peer_offline) +
                        (formatApiDate(peer.lastSeen).takeIf { !peer.connected && it.isNotEmpty() }?.let { " · $it" } ?: ""),
                )
                DetailField(stringResource(R.string.admin_peer_os), listOf(peer.os, peer.version).filter { it.isNotEmpty() }.joinToString(" · "))
                DetailField(stringResource(R.string.admin_peer_public_ip), listOf(peer.connectionIp, peer.cityName, peer.countryCode).filter { it.isNotEmpty() }.joinToString(" · "), mono = true)
                DetailField(stringResource(R.string.admin_peer_groups), peer.groups.joinToString(", ") { it.name })
                DetailField(stringResource(R.string.admin_peer_last_login), formatApiDate(peer.lastLogin))
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsSwitchItem(
            stringResource(R.string.admin_peer_login_expiration),
            stringResource(if (peer.loginExpired) R.string.admin_peer_login_expired_desc else R.string.admin_peer_login_expiration_desc),
            Icons.Default.Timer,
            checked = peer.loginExpirationEnabled,
            enabled = canUpdate && !host.changeBusy,
        ) { host.propose(PeerChanges.setLoginExpiration(ctx, peer, it, self)) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (peer.approvalRequired && canUpdate) OutlinedButton(enabled = !host.changeBusy, onClick = { host.propose(PeerChanges.approve(ctx, peer)) }) {
                Icon(Icons.Default.HowToReg, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_approve))
            }
            if (canUpdate) OutlinedButton(enabled = !host.changeBusy, onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_rename))
            }
            if (canDelete) OutlinedButton(
                enabled = !host.changeBusy,
                onClick = { if (host.propose(PeerChanges.delete(ctx, peer, self))) onDismiss() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_delete))
            }
        }
        if (self) HelpText(stringResource(R.string.admin_peer_self_note), Modifier.padding(top = 8.dp))
        else if (!host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
    }

    if (renaming) {
        // Strings read here, in the sheet's caller: the dialog is a window of its own.
        val title = stringResource(R.string.admin_peer_rename)
        val label = stringResource(R.string.admin_peer_name)
        val save = stringResource(R.string.action_save)
        val cancel = stringResource(R.string.action_cancel)
        var name by remember { mutableStateOf(peer.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(title) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(label) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != peer.name, onClick = { renaming = false; host.propose(PeerChanges.rename(ctx, peer, name, self)) }) { Text(save) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(cancel) } },
        )
    }
}

@Preview
@Composable
private fun PeersContentPreview() = BirdSocksTheme {
    Surface { PeersContent(PreviewAdminHost.demo()) }
}
