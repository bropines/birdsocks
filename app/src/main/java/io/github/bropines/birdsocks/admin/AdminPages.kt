package io.github.bropines.birdsocks.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard

// --- Overview ---

@Composable
internal fun OverviewPage(model: AdminViewModel) {
    val user = model.user
    val account = model.accountInfo
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsCard(stringResource(R.string.admin_overview_account)) {
            InfoRow(Icons.Default.Dns, stringResource(R.string.admin_overview_server), model.account?.managementUrl.orEmpty())
            if (account != null) {
                if (account.domain.isNotEmpty()) InfoRow(Icons.Default.Domain, stringResource(R.string.admin_overview_domain), account.domain)
                if (account.settings.dnsDomain.isNotEmpty()) InfoRow(Icons.Default.Language, stringResource(R.string.admin_overview_dns_domain), account.settings.dnsDomain)
                if (account.settings.networkRange.isNotEmpty()) InfoRow(Icons.Default.Hub, stringResource(R.string.admin_overview_range), account.settings.networkRange)
                formatApiDate(account.createdAt).takeIf { it.isNotEmpty() }?.let { InfoRow(Icons.Default.Event, stringResource(R.string.admin_overview_created), it) }
            }
            if (user != null) {
                InfoRow(
                    Icons.Default.Person,
                    stringResource(R.string.admin_overview_you),
                    listOf(user.email.ifEmpty { user.name }, user.role).filter { it.isNotEmpty() }.joinToString(" · ") +
                        if (user.isServiceUser) " · " + stringResource(R.string.admin_overview_service_user) else ""
                )
            }
            InfoRow(
                Icons.Default.Key,
                stringResource(R.string.admin_overview_signed_in_with),
                stringResource(if (model.authMode == AdminViewModel.AuthMode.DEVICE) R.string.admin_overview_auth_device else R.string.admin_overview_auth_token)
            )
        }

        SettingsCard(stringResource(R.string.admin_overview_counts)) {
            val peers = model.peers?.items
            if (peers != null) {
                InfoRow(Icons.Default.Devices, stringResource(R.string.admin_tab_peers),
                    stringResource(R.string.admin_overview_peers_count, peers.count { it.connected }, peers.size))
                val pending = peers.count { it.approvalRequired }
                if (pending > 0) InfoRow(Icons.Default.HourglassTop, stringResource(R.string.admin_overview_pending), pending.toString())
                val expired = peers.count { it.loginExpired }
                if (expired > 0) InfoRow(Icons.Default.TimerOff, stringResource(R.string.admin_overview_expired), expired.toString())
            }
            model.keys?.items?.let { keys ->
                InfoRow(Icons.Default.Key, stringResource(R.string.admin_tab_keys),
                    stringResource(R.string.admin_overview_keys_count, keys.count { it.valid }, keys.size))
            }
            model.groups?.items?.let { InfoRow(Icons.Default.Workspaces, stringResource(R.string.admin_tab_groups), it.size.toString()) }
            if (peers == null && model.keys == null && model.groups == null) HelpText(stringResource(R.string.admin_overview_nothing))
        }

        // Why nothing can be changed, when nothing can.
        val why = when {
            model.caps?.readOnly == true -> R.string.admin_overview_ro_role
            model.lockState == LockState.NO_SCREEN_LOCK -> R.string.admin_overview_ro_lock
            model.readOnly -> R.string.admin_overview_ro_switch
            else -> null
        }
        if (why != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(why), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (model.caps?.restricted == true) HelpText(stringResource(R.string.admin_overview_restricted), Modifier.padding(vertical = 4.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ) { Text(value, style = MaterialTheme.typography.bodyLarge) }
}

/** A partial list's note: some elements could not be read and are not shown. */
@Composable
private fun <T> IncompleteNote(listing: Listing<T>) {
    if (listing.isComplete) return
    Text(
        pluralStringResource(R.plurals.admin_list_incomplete, listing.issues.size, listing.issues.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

// --- Peers ---

@Composable
internal fun PeersPage(model: AdminViewModel) {
    val listing = model.peers
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val error = model.listErrors[Capabilities.PEERS]
    when {
        listing == null && error != null -> ListError(error) { model.refreshAll() }
        listing == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else -> {
            val shown = listing.items
                .filter { p -> query.isBlank() || listOf(p.name, p.ip, p.dnsLabel, p.hostname, p.os).any { it.contains(query, true) } }
                .sortedWith(compareByDescending<ApiPeer> { it.approvalRequired }.thenByDescending { model.isSelf(it) }.thenByDescending { it.connected }.thenBy { it.name.lowercase() })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.admin_peers_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.action_clear)) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                item { IncompleteNote(listing) }
                if (shown.isEmpty()) item {
                    EmptyState(Icons.Default.Devices, stringResource(if (query.isBlank()) R.string.admin_peers_empty else R.string.admin_peers_no_match), Modifier.padding(vertical = 32.dp))
                }
                items(shown, key = { it.id }) { peer -> PeerRow(peer, model.isSelf(peer)) { openId = peer.id } }
            }
            listing.items.firstOrNull { it.id == openId }?.let { peer ->
                PeerSheet(model, peer, onDismiss = { openId = null })
            }
        }
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
                tint = if (peer.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        },
        supportingContent = {
            Text(
                listOf(peer.ip, peer.os).filter { it.isNotEmpty() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (self) Badge(stringResource(R.string.admin_peer_this_device), MaterialTheme.colorScheme.primaryContainer)
                if (peer.approvalRequired) Badge(stringResource(R.string.admin_peer_pending), MaterialTheme.colorScheme.tertiaryContainer)
                if (peer.loginExpired) Badge(stringResource(R.string.admin_peer_expired), MaterialTheme.colorScheme.errorContainer)
                if (peer.ephemeral) Badge(stringResource(R.string.admin_peer_ephemeral), MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    ) { Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// --- Setup keys ---

@Composable
internal fun KeysPage(model: AdminViewModel) {
    val listing = model.keys
    val error = model.listErrors[Capabilities.SETUP_KEYS]
    var creating by rememberSaveable { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ApiSetupKey?>(null) }
    var created by remember { mutableStateOf<ApiSetupKey?>(null) }
    val canCreate = model.can(Capabilities.SETUP_KEYS, Op.CREATE)
    val canRevoke = model.can(Capabilities.SETUP_KEYS, Op.UPDATE)
    val activity = rememberFragmentActivity()

    Box(Modifier.fillMaxSize()) {
        when {
            listing == null && error != null -> ListError(error) { model.refreshAll() }
            listing == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> {
                val sorted = listing.items.sortedWith(compareByDescending<ApiSetupKey> { it.valid }.thenBy { it.name.lowercase() })
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)) {
                    item { IncompleteNote(listing) }
                    if (sorted.isEmpty()) item { EmptyState(Icons.Default.Key, stringResource(R.string.admin_keys_empty), Modifier.padding(vertical = 32.dp)) }
                    items(sorted, key = { it.id }) { key ->
                        KeyRow(key, model.groups?.items.orEmpty(), canRevoke = canRevoke && key.valid && !key.revoked) { revoking = key }
                    }
                }
            }
        }
        if (canCreate) {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_key_create)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            )
        }
    }

    if (creating) {
        CreateKeyDialog(model, onDismiss = { creating = false }, onCreated = { creating = false; created = it })
    }
    created?.let { NewKeyDialog(model, it, onDismiss = { created = null }) }
    revoking?.let { key ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            icon = { Icon(Icons.Default.Block, null) },
            title = { Text(stringResource(R.string.admin_key_revoke_title, key.name)) },
            text = { Text(stringResource(R.string.admin_key_revoke_text)) },
            confirmButton = {
                TextButton(onClick = { revoking = null; model.revokeKey(activity, key) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.admin_key_revoke))
                }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun KeyRow(key: ApiSetupKey, groups: List<ApiGroup>, canRevoke: Boolean, onRevoke: () -> Unit) {
    val names = key.autoGroups.mapNotNull { id -> groups.firstOrNull { it.id == id }?.name }
    ListItem(
        leadingContent = { Icon(Icons.Default.Key, null, tint = if (key.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
        supportingContent = {
            val usage = if (key.usageLimit > 0) "${key.usedTimes}/${key.usageLimit}" else key.usedTimes.toString()
            val lines = listOfNotNull(
                listOf(
                    stringResource(if (key.isReusable) R.string.admin_key_reusable else R.string.admin_key_one_off),
                    stringResource(R.string.admin_key_used, usage),
                    formatApiDate(key.expires).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_key_expires, it) },
                ).filterNotNull().joinToString(" · "),
                names.takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Badge(keyState(key), if (key.valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                if (canRevoke) TextButton(onClick = onRevoke) { Text(stringResource(R.string.admin_key_revoke)) }
            }
        }
    ) { Text(key.name.ifEmpty { key.id }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun keyState(key: ApiSetupKey): String = stringResource(
    when {
        key.revoked || key.state == "revoked" -> R.string.admin_key_state_revoked
        key.state == "expired" -> R.string.admin_key_state_expired
        key.state == "overused" -> R.string.admin_key_state_overused
        key.valid -> R.string.admin_key_state_valid
        else -> R.string.admin_key_state_invalid
    }
)

// --- Groups ---

@Composable
internal fun GroupsPage(model: AdminViewModel) {
    val listing = model.groups
    val error = model.listErrors[Capabilities.GROUPS]
    when {
        listing == null && error != null -> ListError(error) { model.refreshAll() }
        listing == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { IncompleteNote(listing) }
            val sorted = listing.items.sortedWith(compareBy<ApiGroup> { it.name != "All" }.thenBy { it.name.lowercase() })
            if (sorted.isEmpty()) item { EmptyState(Icons.Default.Workspaces, stringResource(R.string.admin_groups_empty), Modifier.padding(vertical = 32.dp)) }
            items(sorted, key = { it.id }) { g ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                pluralStringResource(R.plurals.admin_group_peers, g.peersCount, g.peersCount),
                                if (g.resourcesCount > 0) pluralStringResource(R.plurals.admin_group_resources, g.resourcesCount, g.resourcesCount) else null,
                                g.issued.takeIf { it.isNotEmpty() && it != "api" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                ) { Text(g.name) }
            }
        }
    }
}
