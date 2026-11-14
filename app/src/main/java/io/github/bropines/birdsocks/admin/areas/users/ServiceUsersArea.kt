package io.github.bropines.birdsocks.admin.areas.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiRoles
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Service users: identities for scripts, each with its personal access tokens. */
val ServiceUsersArea = AdminArea(
    id = "service_users",
    title = R.string.admin_area_service_users,
    summary = R.string.admin_area_service_users_summary,
    icon = Icons.Default.ManageAccounts,
    module = Capabilities.USERS,
) { scope -> ServiceUsersScreen(scope) }

@Composable
private fun ServiceUsersScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.SERVICE_USERS, AdminKeys.GROUPS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.SERVICE_USERS].loading,
        onRefresh = { host.load(AdminKeys.SERVICE_USERS, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.USERS, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_svc_create)) },
            )
        },
    ) { ServiceUsersContent(host, scope.arg, creating, onCreatingChange = { creating = it }) }
}

@Composable
internal fun ServiceUsersContent(host: AdminHost, initialOpenId: String? = null, creating: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}) {
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    val groups = host.data.items(AdminKeys.GROUPS)
    Column(Modifier.fillMaxSize()) {
        OwnTokenNotice(host)
        LoadableContent(host.data[AdminKeys.SERVICE_USERS], onRetry = { host.load(AdminKeys.SERVICE_USERS, force = true) }) { listing, stale ->
            // A server that ignores the filter lists everyone; keep the service users.
            val items = listing.items.filter { it.isServiceUser }
            AdminList(
                items = items,
                key = { it.id },
                searchText = { u -> listOf(u.name, u.role) + groupNames(groups, u.autoGroups) },
                searchHint = stringResource(R.string.admin_svc_search),
                emptyText = stringResource(R.string.admin_svc_empty),
                noMatchText = stringResource(R.string.admin_svc_no_match),
                emptyIcon = Icons.Default.SmartToy,
                sorts = listOf(ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() })),
                issues = listing.issues.size,
                header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.SERVICE_USERS, force = true) }) } },
            ) { u ->
                ListItem(
                    modifier = Modifier.clickable { openId = u.id },
                    leadingContent = { Icon(Icons.Default.SmartToy, null, tint = if (u.isBlocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(UserChanges.displayName(u), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(u.role, groupNames(groups, u.autoGroups).takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = { if (u.isCurrent) StatusBadge(stringResource(R.string.admin_user_you), MaterialTheme.colorScheme.primaryContainer) },
                )
            }
            items.firstOrNull { it.id == openId }?.let { u -> ServiceUserSheet(host, u, host.data.items(AdminKeys.USERS).ifEmpty { items }, onDismiss = { openId = null }) }
        }
    }
    if (creating) CreateServiceUserDialog(host, onDismiss = { onCreatingChange(false) })
}

/** A service user: role, auto-groups, deletion, and its tokens. */
@Composable
private fun ServiceUserSheet(host: AdminHost, user: ApiUser, all: List<ApiUser>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ownId = host.user?.id
    val lock = UserRules.lock(user, all, ownId)
    val canUpdate = host.can(Capabilities.USERS, Op.UPDATE) && !host.changeBusy
    val groupOptions = MemberOptions.groups(host.data.items(AdminKeys.GROUPS), includeAll = false)
    AdminDetailSheet(
        title = UserChanges.displayName(user),
        subtitle = stringResource(R.string.admin_svc_subtitle),
        onDismiss = onDismiss,
        badges = { if (user.isCurrent) StatusBadge(stringResource(R.string.admin_user_you), MaterialTheme.colorScheme.primaryContainer) },
    ) {
        DetailField(stringResource(R.string.admin_user_id), user.id, mono = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.admin_user_role), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            RolePicker(
                current = user.role,
                choices = UserRules.roleChoices(user.role).filter { it == user.role || (it != ApiRoles.OWNER && UserRules.canSetRole(user, it, all, ownId)) },
                enabled = canUpdate && lock == null,
                onPick = { host.propose(UserChanges.setRole(ctx, user, it)) },
            )
        }
        MemberField(
            label = stringResource(R.string.admin_user_auto_groups),
            options = groupOptions,
            selected = user.autoGroups,
            onChange = { host.propose(UserChanges.setAutoGroups(ctx, user, it, { ids -> MemberOptions.labels(groupOptions, ids) })) },
            emptyText = stringResource(R.string.admin_user_auto_groups_none),
            enabled = canUpdate && lock == null,
        )
        if (host.canRead(Capabilities.PATS)) TokensSection(host, user)
        if (host.can(Capabilities.USERS, Op.DELETE) && lock == null) OutlinedButton(
            enabled = !host.changeBusy,
            onClick = { if (host.propose(UserChanges.delete(ctx, user))) onDismiss() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = 12.dp),
        ) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_user_delete)) }
        if (lock == UserLock.OWN) HelpText(stringResource(R.string.admin_user_lock_own), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CreateServiceUserDialog(host: AdminHost, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ApiRoles.USER) }
    var chosen by remember { mutableStateOf(listOf<String>()) }
    val groupOptions = MemberOptions.groups(host.data.items(AdminKeys.GROUPS), includeAll = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_svc_create)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_user_name)) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_user_role), Modifier.weight(1f))
                        RolePicker(role, UserRules.grantableRoles, enabled = true, onPick = { role = it })
                    }
                    MemberField(stringResource(R.string.admin_user_auto_groups), groupOptions, chosen, { chosen = it }, emptyText = stringResource(R.string.admin_user_auto_groups_none))
                    Spacer(Modifier.height(4.dp))
                    HelpText(stringResource(R.string.admin_svc_desc), lines = 4)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !host.changeBusy, onClick = {
                if (host.propose(UserChanges.createServiceUser(ctx, name.trim(), role, chosen, MemberOptions.labels(groupOptions, chosen)))) onDismiss()
            }) { Text(ctx.getString(R.string.admin_svc_create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun ServiceUsersContentPreview() = BirdSocksTheme {
    Surface { ServiceUsersContent(PreviewAdminHost.demo()) }
}
