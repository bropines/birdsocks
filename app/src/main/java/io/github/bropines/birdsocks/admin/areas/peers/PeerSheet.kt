package io.github.bropines.birdsocks.admin.areas.peers

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiAccessiblePeer
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.DetailSheetBody
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch

/** The cached policies this device would stop matching if its groups went from [before] to [after]. */
internal fun lostPolicies(host: AdminHost, before: Collection<String>, after: Collection<String>): List<String> =
    SelfReach.policiesLost(before.toSet(), after.toSet(), host.data.items(AdminKeys.POLICIES))

/**
 * A peer's details and what may be changed: name, groups, SSH, login and
 * inactivity expiration, approval, deletion; who it can reach, read on
 * demand. Every change goes through the gates ([PeerChanges]); deleting this
 * device's own peer is not offered, and the runner would refuse it anyway.
 */
@Composable
internal fun PeerSheet(host: AdminHost, peer: ApiPeer, onDismiss: () -> Unit) {
    val self = host.isSelf(peer)
    var renaming by remember { mutableStateOf(false) }
    AdminDetailSheet(
        title = peer.name,
        subtitle = peer.dnsLabel.takeIf { it.isNotEmpty() },
        onDismiss = onDismiss,
        badges = { PeerBadges(peer, self) },
    ) { PeerDetails(host, peer, onRename = { renaming = true }, onDeleted = onDismiss) }

    if (renaming) {
        val ctx = LocalContext.current
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

@Composable
private fun PeerBadges(peer: ApiPeer, self: Boolean) {
    if (self) StatusBadge(stringResource(R.string.admin_peer_this_device), MaterialTheme.colorScheme.primaryContainer)
    if (peer.approvalRequired) StatusBadge(stringResource(R.string.admin_peer_pending), MaterialTheme.colorScheme.tertiaryContainer)
    if (peer.loginExpired) StatusBadge(stringResource(R.string.admin_peer_expired), MaterialTheme.colorScheme.errorContainer)
}

/** The sheet's body; apart so a preview draws it without a sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerDetails(host: AdminHost, peer: ApiPeer, onRename: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val self = host.isSelf(peer)
    val canUpdate = host.can(Capabilities.PEERS, Op.UPDATE)
    val canGroups = host.can(Capabilities.GROUPS, Op.UPDATE)
    val canDelete = host.can(Capabilities.PEERS, Op.DELETE) && !self
    val groups = host.data.items(AdminKeys.GROUPS)
    val idle = !host.changeBusy

    SelectionContainer {
        Column {
            DetailField(stringResource(R.string.admin_peer_address), listOf(peer.ip, peer.ipv6).filter { it.isNotEmpty() }.joinToString("\n"), mono = true)
            DetailField(
                stringResource(R.string.admin_peer_status),
                stringResource(if (peer.connected) R.string.admin_peer_online else R.string.admin_peer_offline) +
                    (formatApiDate(peer.lastSeen).takeIf { !peer.connected && it.isNotEmpty() }?.let { " · " + ctx.getString(R.string.admin_peer_seen, it) } ?: ""),
            )
            DetailField(stringResource(R.string.admin_peer_os), listOf(peer.os, peer.kernelVersion).filter { it.isNotEmpty() }.joinToString(" · "))
            DetailField(stringResource(R.string.admin_peer_version), listOfNotNull(
                peer.version.takeIf { it.isNotEmpty() },
                peer.uiVersion.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.admin_peer_ui_version, it) },
            ).joinToString(" · "))
            DetailField(stringResource(R.string.admin_peer_public_ip), peer.connectionIp, mono = true)
            DetailField(stringResource(R.string.admin_peer_location), listOf(peer.cityName, peer.countryCode).filter { it.isNotEmpty() }.joinToString(", "))
            DetailField(stringResource(R.string.admin_peer_local_addresses), peer.networkAddresses.map { it.netIp }.filter { it.isNotEmpty() }.joinToString("\n"), mono = true)
            DetailField(stringResource(R.string.admin_peer_hostname), peer.hostname, mono = true)
            DetailField(stringResource(R.string.admin_peer_extra_dns), peer.extraDnsLabels.joinToString(", "), mono = true)
            DetailField(stringResource(R.string.admin_peer_serial), peer.serialNumber, mono = true)
            DetailField(stringResource(R.string.admin_peer_created), formatApiDate(peer.createdAt))
            DetailField(stringResource(R.string.admin_peer_last_login), formatApiDate(peer.lastLogin))
            DetailField(stringResource(R.string.admin_peer_flags), localFlags(peer))
            DetailField(stringResource(R.string.admin_peer_disapproval), peer.disapprovalReason)
        }
    }

    // Groups: membership lives on the groups, "All" is everyone's and not shown as editable.
    val allIds = groups.filter { it.isAll }.map { it.id }
    val current = peer.groups.map { it.id }.filter { it !in allIds }
    MemberField(
        label = stringResource(R.string.admin_peer_groups),
        options = MemberOptions.groups(groups, includeAll = false),
        selected = current,
        enabled = canGroups && idle && groups.isNotEmpty(),
        emptyText = stringResource(R.string.admin_peer_groups_none),
        onChange = { after ->
            val full = after + allIds
            val lost = if (self) lostPolicies(host, peer.groups.map { it.id }, full) else emptyList()
            host.propose(PeerChanges.setGroups(ctx, peer, full, groups, self, lost))
        },
    )

    Spacer(Modifier.height(8.dp))
    SettingsSwitchItem(
        stringResource(R.string.admin_peer_ssh),
        stringResource(R.string.admin_peer_ssh_desc),
        Icons.Default.Terminal,
        checked = peer.sshEnabled,
        enabled = canUpdate && idle,
    ) { host.propose(PeerChanges.setSsh(ctx, peer, it, self)) }
    SettingsSwitchItem(
        stringResource(R.string.admin_peer_login_expiration),
        stringResource(if (peer.loginExpired) R.string.admin_peer_login_expired_desc else R.string.admin_peer_login_expiration_desc),
        Icons.Default.Timer,
        checked = peer.loginExpirationEnabled,
        enabled = canUpdate && idle,
    ) { host.propose(PeerChanges.setLoginExpiration(ctx, peer, it, self)) }
    SettingsSwitchItem(
        stringResource(R.string.admin_peer_inactivity),
        stringResource(R.string.admin_peer_inactivity_desc),
        Icons.Default.HourglassBottom,
        checked = peer.inactivityExpirationEnabled,
        enabled = canUpdate && idle,
    ) { host.propose(PeerChanges.setInactivityExpiration(ctx, peer, it, self)) }

    AccessiblePeers(host, peer)

    Spacer(Modifier.height(8.dp))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (peer.approvalRequired && canUpdate) OutlinedButton(enabled = idle, onClick = { host.propose(PeerChanges.approve(ctx, peer)) }) {
            Icon(Icons.Default.HowToReg, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_approve))
        }
        if (canUpdate) OutlinedButton(enabled = idle, onClick = onRename) {
            Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_rename))
        }
        if (canDelete) OutlinedButton(
            enabled = idle,
            onClick = { if (host.propose(PeerChanges.delete(ctx, peer, self))) onDeleted() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_delete))
        }
    }
    if (self) HelpText(stringResource(R.string.admin_peer_self_note), Modifier.padding(top = 8.dp))
    else if (!host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
}

@Composable
private fun localFlags(peer: ApiPeer): String {
    val f = peer.localFlags ?: return ""
    return listOfNotNull(
        if (f.rosenpassEnabled) "Rosenpass" else null,
        if (f.serverSshAllowed) stringResource(R.string.admin_peer_flag_ssh) else null,
        if (f.blockInbound) stringResource(R.string.admin_peer_flag_block_inbound) else null,
        if (f.blockLanAccess) stringResource(R.string.admin_peer_flag_block_lan) else null,
        if (f.disableDns) stringResource(R.string.admin_peer_flag_no_dns) else null,
        if (f.disableFirewall) stringResource(R.string.admin_peer_flag_no_firewall) else null,
        if (f.lazyConnectionEnabled) stringResource(R.string.admin_peer_flag_lazy) else null,
    ).joinToString(", ")
}

/**
 * The peers this one can reach under the policies now, read on demand: one
 * request the list does not need. Read-only, so it is a plain read.
 */
@Composable
private fun AccessiblePeers(host: AdminHost, peer: ApiPeer) {
    val scope = rememberCoroutineScope()
    var loading by remember(peer.id) { mutableStateOf(false) }
    var error by remember(peer.id) { mutableStateOf<Throwable?>(null) }
    var result by remember(peer.id) { mutableStateOf<List<ApiAccessiblePeer>?>(null) }
    fun load() {
        val api = host.api ?: return
        loading = true; error = null
        scope.launch {
            try { result = api.accessiblePeers(peer.id).items.sortedWith(compareByDescending<ApiAccessiblePeer> { it.connected }.thenBy { it.name.lowercase() }) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e }
            finally { loading = false }
        }
    }
    Spacer(Modifier.height(8.dp))
    val list = result
    when {
        loading -> Row { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.admin_peer_accessible_loading)) }
        error != null -> ErrorCard(error!!, onRetry = ::load)
        list == null -> TextButton(onClick = ::load, enabled = host.api != null) {
            Icon(Icons.Default.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(peer.accessiblePeersCount?.let { stringResource(R.string.admin_peer_accessible_show_n, it) } ?: stringResource(R.string.admin_peer_accessible_show))
        }
        else -> {
            Text(stringResource(R.string.admin_peer_accessible, list.size), style = MaterialTheme.typography.labelLarge)
            if (list.isEmpty()) HelpText(stringResource(R.string.admin_peer_accessible_none))
            list.forEach { p ->
                ListItem(
                    leadingContent = {
                        Icon(if (p.connected) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked, null, Modifier.size(10.dp),
                            tint = if (p.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    },
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(listOf(p.ip, p.os).filter { it.isNotEmpty() }.joinToString(" · "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
    }
}

/**
 * Groups added to and removed from the selected peers at once — one PUT per
 * group, previewed here and reviewed at the gates. "All" is not offered.
 */
@Composable
internal fun BulkGroupsSheet(host: AdminHost, peers: List<ApiPeer>, onDismiss: () -> Unit, onProposed: () -> Unit) {
    val ctx = LocalContext.current
    val groups = host.data.items(AdminKeys.GROUPS)
    var add by rememberSaveable { mutableStateOf(listOf<String>()) }
    var remove by rememberSaveable { mutableStateOf(listOf<String>()) }
    val edits = remember(peers, add, remove, groups) { PeerGroups.bulk(peers, add, remove, groups) }
    val options = MemberOptions.groups(groups, includeAll = false)
    val count = ctx.resources.getQuantityString(R.plurals.admin_peers_count, peers.size, peers.size)
    AdminDetailSheet(title = stringResource(R.string.admin_bulk_groups_title, count), onDismiss = onDismiss) {
        HelpText(peers.joinToString(", ") { it.name }, lines = 3)
        MemberField(stringResource(R.string.admin_bulk_groups_add), options, add, onChange = { add = it }, emptyText = stringResource(R.string.admin_bulk_groups_none))
        MemberField(stringResource(R.string.admin_bulk_groups_remove), options, remove, onChange = { remove = it }, emptyText = stringResource(R.string.admin_bulk_groups_none))
        Spacer(Modifier.height(8.dp))
        if (add.isNotEmpty() || remove.isNotEmpty()) {
            Text(stringResource(R.string.admin_bulk_groups_preview), style = MaterialTheme.typography.labelLarge)
            if (edits.isEmpty()) HelpText(stringResource(R.string.admin_bulk_groups_nothing))
            edits.forEach { e ->
                Text(
                    if (e.add.isNotEmpty()) stringResource(R.string.admin_bulk_groups_line_add, e.groupName, e.add.size)
                    else stringResource(R.string.admin_bulk_groups_line_remove, e.groupName, e.remove.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if ((add.toSet() intersect remove.toSet()).isNotEmpty()) HelpText(stringResource(R.string.admin_bulk_groups_both))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = edits.isNotEmpty() && !host.changeBusy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val selfPeer = peers.firstOrNull { host.isSelf(it) }
                val lost = selfPeer?.let { p ->
                    val before = p.groups.map { it.id }
                    lostPolicies(host, before, PeerGroups.groupsAfter(p.id, before, edits))
                }.orEmpty()
                if (host.propose(PeerChanges.bulkGroups(ctx, peers, add, remove, groups, selfPeer?.id, lost))) onProposed()
            },
        ) { Text(stringResource(R.string.admin_bulk_groups_review)) }
    }
}

@Preview
@Composable
private fun PeerDetailsPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    val peer = host.data.items(AdminKeys.PEERS).first()
    Surface { DetailSheetBody(peer.name, peer.dnsLabel, badges = { PeerBadges(peer, true) }) { PeerDetails(host, peer, {}, {}) } }
}
