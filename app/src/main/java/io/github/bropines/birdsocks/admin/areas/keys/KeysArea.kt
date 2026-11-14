package io.github.bropines.birdsocks.admin.areas.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.QrCodeImage
import io.github.bropines.birdsocks.admin.api.ApiCreateSetupKey
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.copySecret
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.DeepLinks
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Setup keys: create (shown once, with an invite QR), revoke. `page=keys` still opens it. */
val SetupKeysArea = AdminArea(
    id = "setup_keys",
    title = R.string.admin_area_setup_keys,
    summary = R.string.admin_area_setup_keys_summary,
    icon = Icons.Default.Key,
    module = Capabilities.SETUP_KEYS,
    aliases = setOf("keys"),
) { scope -> KeysScreen(scope) }

@Composable
private fun KeysScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.SETUP_KEYS, AdminKeys.GROUPS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val canCreate = host.can(Capabilities.SETUP_KEYS, Op.CREATE)
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.SETUP_KEYS].loading,
        onRefresh = { host.load(AdminKeys.SETUP_KEYS, force = true) },
        floatingActionButton = {
            if (canCreate) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_key_create)) },
            )
        },
    ) { KeysContent(host, creating, onCreatingChange = { creating = it }, initialOpenId = scope.arg) }
}

@Composable
internal fun KeysContent(host: AdminHost, creating: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}, initialOpenId: String? = null) {
    var created by remember { mutableStateOf<ApiSetupKey?>(null) }
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    val groups = host.data.items(AdminKeys.GROUPS)
    val now = remember { System.currentTimeMillis() }

    LoadableContent(host.data[AdminKeys.SETUP_KEYS], onRetry = { host.load(AdminKeys.SETUP_KEYS, force = true) }) { listing, stale ->
        val labels = mapOf(
            KeyQuery.VALID to stringResource(R.string.admin_key_state_valid),
            KeyQuery.EXPIRED to stringResource(R.string.admin_key_state_expired),
            KeyQuery.REVOKED to stringResource(R.string.admin_key_state_revoked),
            KeyQuery.REUSABLE to stringResource(R.string.admin_key_reusable),
        )
        val valid: (ApiSetupKey) -> Boolean = { KeyQuery.state(it, now) == KeyQuery.State.VALID }
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { k -> listOf(k.name, k.state) + k.autoGroups.mapNotNull { id -> groups.firstOrNull { it.id == id }?.name } },
            searchHint = stringResource(R.string.admin_keys_search),
            emptyText = stringResource(R.string.admin_keys_empty),
            noMatchText = stringResource(R.string.admin_keys_no_match),
            emptyIcon = Icons.Default.Key,
            filters = KeyQuery.filters(now).map { (id, test) -> ListFilter(id, labels.getValue(id), test) },
            sorts = listOf(
                ListSort("valid", stringResource(R.string.admin_sort_attention), compareByDescending<ApiSetupKey> { valid(it) }.thenBy { it.name.lowercase() }),
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() }),
                ListSort("expires", stringResource(R.string.admin_sort_expires), compareBy<ApiSetupKey> { !valid(it) }.thenBy { it.expires.orEmpty() }),
                ListSort("used", stringResource(R.string.admin_keys_sort_used), compareByDescending { it.lastUsed.orEmpty() }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.SETUP_KEYS, force = true) }) } },
        ) { key -> KeyRow(key, groups, now) { openId = key.id } }
        listing.items.firstOrNull { it.id == openId }?.let { key -> KeySheet(host, key, now, onDismiss = { openId = null }) }
    }

    if (creating) {
        CreateKeyDialog(host, onDismiss = { onCreatingChange(false) }, onCreated = { onCreatingChange(false); created = it })
    }
    created?.let { NewKeyDialog(host, it, onDismiss = { created = null }) }
}

@Composable
private fun KeyRow(key: ApiSetupKey, groups: List<ApiGroup>, now: Long, onClick: () -> Unit) {
    val names = key.autoGroups.mapNotNull { id -> groups.firstOrNull { it.id == id }?.name }
    val state = KeyQuery.state(key, now)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Default.Key, null, tint = if (state == KeyQuery.State.VALID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
        supportingContent = {
            val lines = listOfNotNull(
                listOfNotNull(
                    stringResource(if (key.isReusable) R.string.admin_key_reusable else R.string.admin_key_one_off),
                    stringResource(R.string.admin_key_used, usage(key)),
                    formatApiDate(key.expires).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.admin_key_expires, it) },
                ).joinToString(" · "),
                names.takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = { StatusBadge(keyState(state), if (state == KeyQuery.State.VALID) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) },
        headlineContent = { Text(key.name.ifEmpty { key.id }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

private fun usage(key: ApiSetupKey) = if (key.usageLimit > 0) "${key.usedTimes}/${key.usageLimit}" else key.usedTimes.toString()

@Composable
private fun keyState(state: KeyQuery.State): String = stringResource(
    when (state) {
        KeyQuery.State.REVOKED -> R.string.admin_key_state_revoked
        KeyQuery.State.EXPIRED -> R.string.admin_key_state_expired
        KeyQuery.State.OVERUSED -> R.string.admin_key_state_overused
        KeyQuery.State.VALID -> R.string.admin_key_state_valid
        KeyQuery.State.INVALID -> R.string.admin_key_state_invalid
    }
)

/**
 * One key: what it is, the groups peers it adds join (editable), revoke and
 * delete. The key itself is never shown again — the server keeps a hash.
 */
@Composable
private fun KeySheet(host: AdminHost, key: ApiSetupKey, now: Long, onDismiss: () -> Unit) {
    val state = KeyQuery.state(key, now)
    AdminDetailSheet(
        title = key.name.ifEmpty { key.id },
        onDismiss = onDismiss,
        badges = { StatusBadge(keyState(state), if (state == KeyQuery.State.VALID) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) },
    ) { KeyDetails(host, key, state, onDeleted = onDismiss) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyDetails(host: AdminHost, key: ApiSetupKey, state: KeyQuery.State, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val groups = host.data.items(AdminKeys.GROUPS)
    val canUpdate = host.can(Capabilities.SETUP_KEYS, Op.UPDATE)
    val canDelete = host.can(Capabilities.SETUP_KEYS, Op.DELETE)
    val idle = !host.changeBusy
    val on = stringResource(R.string.admin_value_on)
    DetailField(stringResource(R.string.admin_key_type), stringResource(if (key.isReusable) R.string.admin_key_reusable else R.string.admin_key_one_off))
    DetailField(stringResource(R.string.admin_key_usage), stringResource(R.string.admin_key_used, usage(key)))
    DetailField(stringResource(R.string.admin_key_last_used), formatApiDate(key.lastUsed))
    DetailField(stringResource(R.string.admin_key_expires_label), formatApiDate(key.expires))
    DetailField(stringResource(R.string.admin_key_updated), formatApiDate(key.updatedAt))
    DetailField(stringResource(R.string.admin_key_ephemeral), if (key.ephemeral) on else "")
    DetailField(stringResource(R.string.admin_key_extra_dns), if (key.allowExtraDnsLabels) on else "")
    DetailField(stringResource(R.string.admin_key_masked), key.key, mono = true)
    MemberField(
        label = stringResource(R.string.admin_key_groups),
        options = MemberOptions.groups(groups, includeAll = false),
        selected = key.autoGroups,
        enabled = canUpdate && idle && state != KeyQuery.State.REVOKED,
        emptyText = stringResource(R.string.admin_key_groups_none),
        onChange = { after -> host.propose(KeyChanges.setAutoGroups(ctx, key, after, { id -> groups.firstOrNull { it.id == id }?.name ?: id })) },
    )
    HelpText(stringResource(R.string.admin_key_groups_desc), lines = 3)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canUpdate && state != KeyQuery.State.REVOKED) OutlinedButton(enabled = idle, onClick = { host.propose(KeyChanges.revoke(ctx, key)) }) {
            Icon(Icons.Default.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_key_revoke))
        }
        if (canDelete) OutlinedButton(
            enabled = idle,
            onClick = { if (host.propose(KeyChanges.delete(ctx, key))) onDeleted() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_key_delete))
        }
    }
    if (!host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
}

/** A new setup key: name, one-off or reusable, days valid, groups, a usage limit for a reusable one, ephemeral peers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateKeyDialog(host: AdminHost, onDismiss: () -> Unit, onCreated: (ApiSetupKey) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var name by remember { mutableStateOf("") }
    var reusable by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("7") }
    var limit by remember { mutableStateOf("") }
    var ephemeral by remember { mutableStateOf(false) }
    var extraDns by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val groups = host.data.items(AdminKeys.GROUPS).filter { !it.isAll }.sortedBy { it.name.lowercase() }
    val dayCount = days.toLongOrNull()
    val daysOk = dayCount != null && dayCount * 86_400 in ApiCreateSetupKey.MIN_EXPIRES_SEC..ApiCreateSetupKey.MAX_EXPIRES_SEC
    val limitValue = if (reusable) limit.toIntOrNull() ?: if (limit.isBlank()) 0 else -1 else 0
    val valid = name.isNotBlank() && daysOk && limitValue >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_key_create)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_key_name)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    SlidingSegmentedChips(
                        options = listOf(stringResource(R.string.admin_key_one_off), stringResource(R.string.admin_key_reusable)),
                        selectedIndex = if (reusable) 1 else 0,
                        onOptionSelected = { reusable = it == 1 },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelpText(stringResource(if (reusable) R.string.admin_key_reusable_desc else R.string.admin_key_one_off_desc), Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        days, { days = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_key_days)) },
                        supportingText = { Text(stringResource(R.string.admin_key_days_hint)) },
                        isError = !daysOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (reusable) {
                        OutlinedTextField(
                            limit, { limit = it.filter(Char::isDigit).take(6) }, singleLine = true,
                            label = { Text(stringResource(R.string.admin_key_limit)) },
                            supportingText = { Text(stringResource(R.string.admin_key_limit_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_key_ephemeral), Modifier.weight(1f))
                        Switch(ephemeral, { ephemeral = it })
                    }
                    HelpText(stringResource(R.string.admin_key_ephemeral_desc))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_key_extra_dns), Modifier.weight(1f))
                        Switch(extraDns, { extraDns = it })
                    }
                    HelpText(stringResource(R.string.admin_key_extra_dns_desc))
                    if (groups.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.admin_key_groups), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            groups.forEach { g ->
                                FilterChip(selected = g.id in chosen, onClick = { chosen = if (g.id in chosen) chosen - g.id else chosen + g.id }, label = { Text(g.name) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !host.changeBusy, onClick = {
                val request = ApiCreateSetupKey(
                    name = name.trim(),
                    type = if (reusable) ApiSetupKey.TYPE_REUSABLE else ApiSetupKey.TYPE_ONE_OFF,
                    expiresIn = dayCount!! * 86_400,
                    autoGroups = chosen.toList(),
                    usageLimit = limitValue,
                    ephemeral = ephemeral,
                    allowExtraDnsLabels = extraDns.takeIf { it },
                )
                val names = groups.filter { it.id in chosen }.map { it.name }
                host.propose(KeyChanges.create(ctx, request, names, onCreated))
            }) { Text(ctx.getString(R.string.admin_key_create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/**
 * A key just created, shown this once: the server keeps only a hash. The key
 * itself and a QR of the BirdSocks invite link, so another phone joins with
 * one scan. Out of screenshots while it is open.
 */
@Composable
private fun NewKeyDialog(host: AdminHost, key: ApiSetupKey, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    val link = remember(key.key) { DeepLinks.inviteLink(host.account?.managementUrl.orEmpty(), "", key.key) }
    var showLink by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(ctx.getString(R.string.admin_key_new_title, key.name)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.admin_key_new_once), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    QrCodeImage(link, stringResource(R.string.admin_key_qr), Modifier.fillMaxWidth(0.8f))
                    HelpText(stringResource(R.string.admin_key_qr_desc), Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer { Text(key.key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copySecret(ctx, key.name, key.key) }) { Text(stringResource(R.string.admin_key_copy)) }
                        TextButton(onClick = { copySecret(ctx, key.name, link) }) { Text(stringResource(R.string.admin_key_copy_link)) }
                    }
                    if (showLink) Text(link, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    else TextButton(onClick = { showLink = true }) { Text(stringResource(R.string.admin_key_show_link)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.admin_key_new_done)) } },
    )
}

@Preview
@Composable
private fun KeysContentPreview() = BirdSocksTheme {
    Surface { Box { KeysContent(PreviewAdminHost.demo()) } }
}
