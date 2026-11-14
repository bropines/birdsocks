package io.github.bropines.birdsocks.admin.areas.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiRoles
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.ApiUserInvite
import io.github.bropines.birdsocks.admin.api.ApiUserInviteCreateRequest
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
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Users: people of the account, their roles and state; link invites on a second tab. [AreaScope.arg] opens one by id. */
val UsersArea = AdminArea(
    id = "users",
    title = R.string.admin_area_users,
    summary = R.string.admin_area_users_summary,
    icon = Icons.Default.People,
    module = Capabilities.USERS,
) { scope -> UsersScreen(scope) }

@Composable
private fun UsersScreen(scope: AreaScope) {
    val host = scope.host
    var tab by rememberSaveable { mutableStateOf(0) }
    var inviting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab == 0) host.load(AdminKeys.USERS, AdminKeys.GROUPS) else host.load(AdminKeys.USER_INVITES, AdminKeys.GROUPS) }
    val key = if (tab == 0) AdminKeys.USERS else AdminKeys.USER_INVITES
    AreaScaffold(
        scope,
        refreshing = host.data[key].loading,
        onRefresh = { host.load(key, force = true) },
        floatingActionButton = {
            if (tab == 1 && host.can(Capabilities.USERS, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { inviting = true },
                icon = { Icon(Icons.Default.PersonAdd, null) },
                text = { Text(stringResource(R.string.admin_invite_create)) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            SlidingSegmentedChips(
                options = listOf(stringResource(R.string.admin_users_tab_people), stringResource(R.string.admin_users_tab_invites)),
                selectedIndex = tab,
                onOptionSelected = { tab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (tab == 0) UsersContent(host, initialOpenId = scope.arg)
            else InvitesContent(host, inviting, onInvitingChange = { inviting = it })
        }
    }
}

@Composable
internal fun UsersContent(host: AdminHost, initialOpenId: String? = null) {
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    val groups = host.data.items(AdminKeys.GROUPS)
    LoadableContent(host.data[AdminKeys.USERS], onRetry = { host.load(AdminKeys.USERS, force = true) }) { listing, stale ->
        val people = listing.items.filter { !it.isServiceUser }
        AdminList(
            items = people,
            key = { it.id },
            searchText = { u -> listOf(u.name, u.email, u.role, u.status) + groupNames(groups, u.autoGroups) },
            searchHint = stringResource(R.string.admin_users_search),
            emptyText = stringResource(R.string.admin_users_empty),
            noMatchText = stringResource(R.string.admin_users_no_match),
            emptyIcon = Icons.Default.People,
            filters = listOf(
                ListFilter("pending", stringResource(R.string.admin_user_status_pending)) { UserRules.status(it) == UserStatus.PENDING },
                ListFilter("blocked", stringResource(R.string.admin_user_status_blocked)) { UserRules.status(it) == UserStatus.BLOCKED },
                ListFilter("admins", stringResource(R.string.admin_users_filter_admins)) { it.role == ApiRoles.OWNER || it.role == ApiRoles.ADMIN },
            ),
            sorts = listOf(
                ListSort("attention", stringResource(R.string.admin_sort_attention), UserRules.attentionOrder),
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.ifEmpty { it.email }.lowercase() }),
                ListSort("login", stringResource(R.string.admin_users_sort_login), compareByDescending { it.lastLogin.orEmpty() }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.USERS, force = true) }) } },
        ) { u -> UserRow(u, groups) { openId = u.id } }
        listing.items.firstOrNull { it.id == openId }?.let { u -> UserSheet(host, u, listing.items, onDismiss = { openId = null }) }
    }
}

internal fun groupNames(groups: List<ApiGroup>, ids: List<String>): List<String> = ids.map { id -> groups.firstOrNull { it.id == id }?.name ?: id }

@Composable
internal fun statusLabel(s: UserStatus): String = stringResource(
    when (s) {
        UserStatus.PENDING -> R.string.admin_user_status_pending
        UserStatus.BLOCKED -> R.string.admin_user_status_blocked
        UserStatus.INVITED -> R.string.admin_user_status_invited
        UserStatus.ACTIVE -> R.string.admin_user_status_active
    }
)

@Composable
internal fun statusColor(s: UserStatus): Color = when (s) {
    UserStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
    UserStatus.BLOCKED -> MaterialTheme.colorScheme.errorContainer
    UserStatus.INVITED -> MaterialTheme.colorScheme.secondaryContainer
    UserStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun UserRow(u: ApiUser, groups: List<ApiGroup>, onClick: () -> Unit) {
    val status = UserRules.status(u)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Default.Person, null, tint = if (status == UserStatus.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
        headlineContent = { Text(UserChanges.displayName(u), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val lines = listOfNotNull(
                listOfNotNull(
                    u.email.takeIf { it.isNotEmpty() && it != u.name },
                    u.role,
                    formatApiDate(u.lastLogin).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_user_last_login_short, it) },
                ).joinToString(" · "),
                groupNames(groups, u.autoGroups).takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (u.isCurrent) StatusBadge(stringResource(R.string.admin_user_you), MaterialTheme.colorScheme.primaryContainer)
                StatusBadge(statusLabel(status), statusColor(status))
            }
        },
    )
}

/**
 * One user: details, role, block, auto-groups, approval, deletion — each
 * through the gates. Your own user and the last owner are shown, not
 * offered; your own user's tokens are listed here.
 */
@Composable
internal fun UserSheet(host: AdminHost, user: ApiUser, all: List<ApiUser>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val ownId = host.user?.id
    val lock = UserRules.lock(user, all, ownId)
    val status = UserRules.status(user)
    val canUpdate = host.can(Capabilities.USERS, Op.UPDATE) && !host.changeBusy
    val canDelete = host.can(Capabilities.USERS, Op.DELETE) && !host.changeBusy
    val groups = host.data.items(AdminKeys.GROUPS)
    val groupOptions = MemberOptions.groups(groups, includeAll = false)
    val names: (List<String>) -> List<String> = { MemberOptions.labels(groupOptions, it) }

    AdminDetailSheet(
        title = UserChanges.displayName(user),
        subtitle = user.email.takeIf { it != user.name },
        onDismiss = onDismiss,
        badges = {
            if (user.isCurrent) StatusBadge(stringResource(R.string.admin_user_you), MaterialTheme.colorScheme.primaryContainer)
            StatusBadge(statusLabel(status), statusColor(status))
        },
    ) {
        DetailField(stringResource(R.string.admin_user_last_login), formatApiDate(user.lastLogin))
        DetailField(stringResource(R.string.admin_user_issued), user.issued)
        DetailField(stringResource(R.string.admin_user_idp), user.idpId, mono = true)
        DetailField(stringResource(R.string.admin_user_id), user.id, mono = true)

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.admin_user_role), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            RolePicker(
                current = user.role,
                choices = UserRules.roleChoices(user.role).filter { it == user.role || UserRules.canSetRole(user, it, all, ownId) },
                enabled = canUpdate && lock != UserLock.OWN,
                onPick = { host.propose(UserChanges.setRole(ctx, user, it)) },
            )
        }

        if (status == UserStatus.PENDING) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            if (canUpdate) OutlinedButton(onClick = { host.propose(UserChanges.approve(ctx, user)) }) {
                Icon(Icons.Default.HowToReg, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_user_approve))
            }
            if (canDelete) OutlinedButton(
                onClick = { if (host.propose(UserChanges.reject(ctx, user))) onDismiss() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.admin_user_reject)) }
        }

        if (status != UserStatus.PENDING) SettingsSwitchItem(
            stringResource(R.string.admin_user_blocked),
            stringResource(R.string.admin_user_blocked_desc),
            Icons.Default.Block,
            checked = user.isBlocked,
            enabled = canUpdate && UserRules.canBlockOrDelete(user, all, ownId),
        ) { host.propose(UserChanges.setBlocked(ctx, user, it)) }

        MemberField(
            label = stringResource(R.string.admin_user_auto_groups),
            options = groupOptions,
            selected = user.autoGroups,
            onChange = { host.propose(UserChanges.setAutoGroups(ctx, user, it, names)) },
            emptyText = stringResource(R.string.admin_user_auto_groups_none),
            enabled = canUpdate && lock != UserLock.OWN,
        )
        HelpText(stringResource(R.string.admin_user_auto_groups_desc))

        if (canDelete && UserRules.canBlockOrDelete(user, all, ownId) && status != UserStatus.PENDING) OutlinedButton(
            onClick = { if (host.propose(UserChanges.delete(ctx, user))) onDismiss() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_user_delete))
        }
        when (lock) {
            UserLock.OWN -> HelpText(stringResource(R.string.admin_user_lock_own), Modifier.padding(top = 8.dp))
            UserLock.LAST_OWNER -> HelpText(stringResource(R.string.admin_user_lock_last_owner), Modifier.padding(top = 8.dp))
            null -> if (!host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
        }
        // Only your own tokens (and a service user's, in its own area) are yours to see.
        if (user.isCurrent && host.canRead(Capabilities.PATS)) TokensSection(host, user)
    }
}

// ================================================================ invites

/**
 * Link invites (servers with the embedded identity provider). The links are
 * bearer credentials: the list never has them, a new one is shown once.
 */
@Composable
internal fun InvitesContent(host: AdminHost, inviting: Boolean = false, onInvitingChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var secret by remember { mutableStateOf<Pair<String, String>?>(null) }
    val groups = host.data.items(AdminKeys.GROUPS)
    val loadable = host.data[AdminKeys.USER_INVITES]
    val unsupported = loadable.value == null && loadable.error.let { it is AdminApiException.NotFound || it is AdminApiException.BadRequest || it is AdminApiException.Forbidden }
    if (unsupported) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Default.Mail, stringResource(R.string.admin_invites_unavailable), Modifier.padding(top = 32.dp))
        }
    } else LoadableContent(loadable, onRetry = { host.load(AdminKeys.USER_INVITES, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { listOf(it.email, it.name, it.role) },
            searchHint = stringResource(R.string.admin_invites_search),
            emptyText = stringResource(R.string.admin_invites_empty),
            noMatchText = stringResource(R.string.admin_invites_no_match),
            emptyIcon = Icons.Default.Mail,
            filters = listOf(ListFilter("expired", stringResource(R.string.admin_invite_expired)) { it.expired }),
            sorts = listOf(ListSort("expires", stringResource(R.string.admin_sort_expires), compareBy { it.expiresAt.orEmpty() })),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.USER_INVITES, force = true) }) } },
        ) { inv ->
            InviteRow(
                inv, groups,
                canRegenerate = host.can(Capabilities.USERS, Op.UPDATE) && !host.changeBusy,
                canDelete = host.can(Capabilities.USERS, Op.DELETE) && !host.changeBusy,
                onRegenerate = { host.propose(UserChanges.regenerateInvite(ctx, inv) { r -> secret = UserChanges.inviteTarget(inv).name to r.inviteToken }) },
                onDelete = { host.propose(UserChanges.deleteInvite(ctx, inv)) },
            )
        }
    }
    if (inviting) InviteDialog(host, onDismiss = { onInvitingChange(false) }) { inv ->
        onInvitingChange(false)
        inv.inviteToken?.takeIf { it.isNotEmpty() }?.let { secret = inv.email to it }
    }
    secret?.let { (who, token) ->
        SecretOnceDialog(
            title = ctx.getString(R.string.admin_invite_new_title, who),
            secret = token,
            note = ctx.getString(R.string.admin_invite_new_once),
            copyLabel = ctx.getString(R.string.action_copy),
            onDismiss = { secret = null },
        )
    }
}

@Composable
private fun InviteRow(inv: ApiUserInvite, groups: List<ApiGroup>, canRegenerate: Boolean, canDelete: Boolean, onRegenerate: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.Default.Mail, null, tint = if (inv.expired) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(inv.email.ifEmpty { inv.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    listOf(inv.name, inv.role).filter { it.isNotEmpty() }.joinToString(" · "),
                    formatApiDate(inv.expiresAt).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_invite_expires, it) },
                    groupNames(groups, inv.autoGroups).takeIf { it.isNotEmpty() }?.joinToString(", "),
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                if (inv.expired) StatusBadge(stringResource(R.string.admin_invite_expired), MaterialTheme.colorScheme.errorContainer)
                Row {
                    if (canRegenerate) TextButton(onClick = onRegenerate) { Icon(Icons.Default.Refresh, stringResource(R.string.admin_invite_regenerate)) }
                    if (canDelete) TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.admin_invite_delete), tint = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )
}

@Composable
private fun InviteDialog(host: AdminHost, onDismiss: () -> Unit, onCreated: (ApiUserInvite) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ApiRoles.USER) }
    var hours by remember { mutableStateOf("72") }
    var chosen by remember { mutableStateOf(listOf<String>()) }
    val groupOptions = MemberOptions.groups(host.data.items(AdminKeys.GROUPS), includeAll = false)
    val h = hours.toLongOrNull()
    val hoursOk = h != null && h in 1..(24 * 30)
    val emailOk = email.trim().let { '@' in it && !it.startsWith('@') && !it.endsWith('@') && ' ' !in it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_invite_create)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(stringResource(R.string.admin_user_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_user_name)) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_user_role), Modifier.weight(1f))
                        RolePicker(role, UserRules.grantableRoles, enabled = true, onPick = { role = it })
                    }
                    OutlinedTextField(
                        hours, { hours = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_invite_hours)) },
                        isError = !hoursOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MemberField(stringResource(R.string.admin_user_auto_groups), groupOptions, chosen, { chosen = it }, emptyText = stringResource(R.string.admin_user_auto_groups_none))
                    Spacer(Modifier.height(4.dp))
                    HelpText(stringResource(R.string.admin_invite_bearer_warning))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = emailOk && name.isNotBlank() && hoursOk && !host.changeBusy, onClick = {
                val request = ApiUserInviteCreateRequest(email.trim(), name.trim(), role, chosen, h!! * 3600)
                host.propose(UserChanges.createInvite(ctx, request, MemberOptions.labels(groupOptions, chosen), onCreated))
            }) { Text(ctx.getString(R.string.admin_invite_create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun UsersContentPreview() = BirdSocksTheme {
    Surface { UsersContent(PreviewAdminHost.demo()) }
}
