package io.github.bropines.birdsocks.admin.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminRoute
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AdminAreas
import io.github.bropines.birdsocks.admin.areas.peers.PeerChanges
import io.github.bropines.birdsocks.admin.areas.users.UserChanges
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.InfoRow
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** What the home screen shows besides the data: why nothing can be changed, how it is signed in. */
data class HomeInfo(
    /** A string resource saying why writes are off, or null when they are on. */
    val readOnlyReason: Int? = null,
    val signedInWithDevice: Boolean = false,
    val restricted: Boolean = false,
)

/**
 * The panel's home: what waits for an admin (with its action inline, through
 * the gates), the account at a glance, and every area the role may open.
 */
@Composable
fun AdminHome(host: AdminHost, info: HomeInfo, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis()) {
    LaunchedEffect(Unit) { host.load(*AdminKeys.HOME.toTypedArray()) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        info.readOnlyReason?.let { ReadOnlyCard(stringResource(it)) }
        AttentionCard(host, now)
        OverviewCard(host, info)
        Text(
            stringResource(R.string.admin_home_areas),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        AreaGrid(AdminAreas.all.filter { it.visibleFor(host) }) { host.navigate(AdminRoute(it.id)) }
        if (info.restricted) HelpText(stringResource(R.string.admin_overview_restricted), Modifier.padding(vertical = 8.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReadOnlyCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AttentionCard(host: AdminHost, now: Long) {
    val ctx = LocalContext.current
    val peers = host.data[AdminKeys.PEERS].value?.items
    val users = host.data[AdminKeys.USERS].value?.items
    val keys = host.data[AdminKeys.SETUP_KEYS].value?.items
    val items = remember(peers, users, keys, now) { Attention.items(peers, users, keys, now) }
    val anythingRead = peers != null || users != null || keys != null
    SettingsCard(stringResource(R.string.admin_home_attention)) {
        when {
            items.isEmpty() && anythingRead -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.admin_home_attention_none), style = MaterialTheme.typography.bodyMedium)
            }
            items.isEmpty() -> HelpText(stringResource(R.string.admin_overview_nothing), Modifier.padding(16.dp))
            else -> items.forEach { item ->
                when (item) {
                    is AttentionItem.PeerAwaiting -> AttentionRow(
                        icon = Icons.Default.HourglassTop,
                        title = stringResource(R.string.admin_home_peer_awaiting, item.peer.name),
                        detail = listOf(item.peer.ip, item.peer.os).filter { it.isNotEmpty() }.joinToString(" · "),
                        onOpen = { host.navigate(AdminRoute("peers", item.peer.id)) },
                    ) {
                        if (host.can(Capabilities.PEERS, Op.UPDATE)) FilledTonalButton(enabled = !host.changeBusy, onClick = { host.propose(PeerChanges.approve(ctx, item.peer)) }) {
                            Text(stringResource(R.string.admin_peer_approve))
                        }
                    }
                    is AttentionItem.UserAwaiting -> AttentionRow(
                        icon = Icons.Default.PersonAdd,
                        title = stringResource(R.string.admin_home_user_awaiting, UserChanges.displayName(item.user)),
                        detail = item.user.email,
                        onOpen = { host.navigate(AdminRoute("users", item.user.id)) },
                    ) {
                        if (host.can(Capabilities.USERS, Op.DELETE)) OutlinedButton(enabled = !host.changeBusy, onClick = { host.propose(UserChanges.reject(ctx, item.user)) }) {
                            Text(stringResource(R.string.admin_user_reject))
                        }
                        if (host.can(Capabilities.USERS, Op.UPDATE)) FilledTonalButton(enabled = !host.changeBusy, onClick = { host.propose(UserChanges.approve(ctx, item.user)) }) {
                            Text(stringResource(R.string.admin_user_approve))
                        }
                    }
                    is AttentionItem.KeyExpiring -> AttentionRow(
                        icon = Icons.Default.TimerOff,
                        title = stringResource(R.string.admin_home_key_expiring, item.key.name.ifEmpty { item.key.id }),
                        detail = if (item.daysLeft == 0) stringResource(R.string.admin_home_key_expires_today)
                        else pluralStringResource(R.plurals.admin_home_key_days_left, item.daysLeft, item.daysLeft),
                        onOpen = { host.navigate(AdminRoute("setup_keys", item.key.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttentionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onOpen: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.admin_home_open)) }
            actions()
        }
    }
}

@Composable
private fun OverviewCard(host: AdminHost, info: HomeInfo) {
    val user = host.user
    val account = host.data[AdminKeys.ACCOUNTS].value?.items?.firstOrNull()
    SettingsCard(stringResource(R.string.admin_overview_account)) {
        InfoRow(Icons.Default.Dns, stringResource(R.string.admin_overview_server), host.account?.managementUrl.orEmpty())
        if (account != null) {
            if (account.domain.isNotEmpty()) InfoRow(Icons.Default.Domain, stringResource(R.string.admin_overview_domain), account.domain)
            if (account.settings.dnsDomain.isNotEmpty()) InfoRow(Icons.Default.Language, stringResource(R.string.admin_overview_dns_domain), account.settings.dnsDomain)
            if (account.settings.networkRange.isNotEmpty()) InfoRow(Icons.Default.Hub, stringResource(R.string.admin_overview_range), account.settings.networkRange)
        }
        if (user != null) {
            InfoRow(
                Icons.Default.Person,
                stringResource(R.string.admin_overview_you),
                listOf(user.email.ifEmpty { user.name }, user.role).filter { it.isNotEmpty() }.joinToString(" · ") +
                    if (user.isServiceUser) " · " + stringResource(R.string.admin_overview_service_user) else "",
            )
        }
        InfoRow(
            Icons.Default.Key,
            stringResource(R.string.admin_overview_signed_in_with),
            stringResource(if (info.signedInWithDevice) R.string.admin_overview_auth_device else R.string.admin_overview_auth_token),
        )
        val peers = host.data[AdminKeys.PEERS].value?.items
        val keys = host.data[AdminKeys.SETUP_KEYS].value?.items
        val groups = host.data[AdminKeys.GROUPS].value?.items
        if (peers != null) InfoRow(Icons.Default.Devices, stringResource(R.string.admin_area_peers), stringResource(R.string.admin_overview_peers_count, peers.count { it.connected }, peers.size))
        if (keys != null) InfoRow(Icons.Default.Key, stringResource(R.string.admin_area_setup_keys), stringResource(R.string.admin_overview_keys_count, keys.count { it.valid }, keys.size))
        if (groups != null) InfoRow(Icons.Default.Workspaces, stringResource(R.string.admin_area_groups), groups.size.toString())
        account?.createdAt?.let { formatApiDate(it) }?.takeIf { it.isNotEmpty() }?.let {
            ListItem(
                headlineContent = { Text(stringResource(R.string.admin_overview_created) + ": " + it, style = MaterialTheme.typography.bodySmall) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** The areas as tiles, two to a row on a phone, more on a wide window. */
@Composable
private fun AreaGrid(areas: List<AdminArea>, onOpen: (AdminArea) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 180.dp).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            areas.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a -> AreaTile(a, Modifier.weight(1f)) { onOpen(a) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AreaTile(area: AdminArea, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp).alpha(if (area.ready) 1f else 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(area.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (!area.ready) StatusBadge(stringResource(R.string.admin_area_coming_badge), MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(area.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(area.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview
@Composable
private fun AdminHomePreview() = BirdSocksTheme {
    Surface { AdminHome(PreviewAdminHost.demo(), HomeInfo(readOnlyReason = R.string.admin_overview_ro_switch)) }
}
