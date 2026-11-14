package io.github.bropines.birdsocks.admin.areas.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiAccount
import io.github.bropines.birdsocks.admin.api.ApiAccountSettings
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Account settings: every setting the API has, each saved on its own and described by what it does. */
val AccountSettingsArea = AdminArea(
    id = "account_settings",
    title = R.string.admin_area_account_settings,
    summary = R.string.admin_area_account_settings_summary,
    icon = Icons.Default.Tune,
    module = Capabilities.ACCOUNTS,
    aliases = setOf("settings"),
) { scope -> AccountSettingsScreen(scope) }

@Composable
private fun AccountSettingsScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.ACCOUNTS, AdminKeys.GROUPS) }
    AreaScaffold(scope, refreshing = host.data[AdminKeys.ACCOUNTS].loading, onRefresh = { host.load(AdminKeys.ACCOUNTS, force = true) }) {
        AccountSettingsContent(host)
    }
}

/** A value in words, for the rows and the review. */
internal fun renderSetting(ctx: Context, spec: SettingSpec, v: JsonElement, groupName: (String) -> String): String = when (spec.type) {
    SettingType.BOOL -> ctx.getString(if (AccountSettingsChanges.asBool(v)) R.string.admin_acct_on else R.string.admin_acct_off)
    SettingType.SECONDS -> duration(ctx, AccountSettingsChanges.asLong(v))
    SettingType.TEXT -> AccountSettingsChanges.asText(v)
    SettingType.NAMES -> AccountSettingsChanges.asList(v).joinToString(", ")
    SettingType.GROUPS -> AccountSettingsChanges.asList(v).joinToString(", ") { groupName(it) }
}

internal fun duration(ctx: Context, sec: Long): String = when {
    sec <= 0 -> ctx.getString(R.string.admin_acct_none)
    sec % 86_400 == 0L -> (sec / 86_400).toInt().let { ctx.resources.getQuantityString(R.plurals.admin_acct_dur_days, it, it) }
    else -> (sec / 3_600).toInt().coerceAtLeast(1).let { ctx.resources.getQuantityString(R.plurals.admin_acct_dur_hours, it, it) }
}

@Composable
internal fun AccountSettingsContent(host: AdminHost) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<SettingSpec?>(null) }
    var accountIndex by remember { mutableStateOf(0) }
    val groups = host.data.items(AdminKeys.GROUPS)
    val groupOptions = MemberOptions.groups(groups, includeAll = true)
    val groupName: (String) -> String = { id -> groups.firstOrNull { it.id == id }?.name ?: id }
    val render: (SettingSpec, JsonElement) -> String = { s, v -> renderSetting(ctx, s, v, groupName) }
    val canUpdate = host.can(Capabilities.ACCOUNTS, Op.UPDATE) && !host.changeBusy

    LoadableContent(host.data[AdminKeys.ACCOUNTS], onRetry = { host.load(AdminKeys.ACCOUNTS, force = true) }) { listing, stale ->
        val accounts = listing.items
        if (accounts.isEmpty()) EmptyState(Icons.Default.Tune, stringResource(R.string.admin_acct_none_readable))
        else SettingsBody(host, accounts, accountIndex, { accountIndex = it }, stale, groupOptions, render, canUpdate, editing) { editing = it }
    }
}

@Composable
private fun SettingsBody(
    host: AdminHost,
    accounts: List<ApiAccount>,
    accountIndex: Int,
    onAccountIndex: (Int) -> Unit,
    stale: Throwable?,
    groupOptions: List<io.github.bropines.birdsocks.admin.ui.MemberOption>,
    render: (SettingSpec, JsonElement) -> String,
    canUpdate: Boolean,
    editing: SettingSpec?,
    onEditing: (SettingSpec?) -> Unit,
) {
    val ctx = LocalContext.current
    run {
        val account = accounts[accountIndex.coerceIn(0, accounts.lastIndex)]
        val json = remember(account) { AccountSettingsChanges.json(account.settings) }
        fun propose(spec: SettingSpec, after: JsonElement) {
            val before = AccountSettingsChanges.current(json, spec)
            if (!AccountSettingsChanges.same(spec, before, after)) host.propose(AccountSettingsChanges.change(ctx, account, spec, before, after, render))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (stale != null) ErrorCard(stale, onRetry = { host.load(AdminKeys.ACCOUNTS, force = true) })
            if (accounts.size > 1) SlidingSegmentedChips(
                options = accounts.map { it.domain.ifEmpty { it.id } },
                selectedIndex = accountIndex.coerceIn(0, accounts.lastIndex),
                onOptionSelected = onAccountIndex,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            SettingsCard(stringResource(R.string.admin_acct_section_account)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    DetailField(stringResource(R.string.admin_overview_domain), listOf(account.domain, account.domainCategory).filter { it.isNotEmpty() }.joinToString(" · "))
                    DetailField(stringResource(R.string.admin_acct_id), account.id, mono = true)
                    DetailField(stringResource(R.string.admin_overview_created), formatApiDate(account.createdAt))
                    account.settings.embeddedIdpEnabled?.let { DetailField(stringResource(R.string.admin_acct_embedded_idp), stringResource(if (it) R.string.admin_acct_on else R.string.admin_acct_off)) }
                }
            }
            AccountSettingsChanges.SECTIONS.forEach { section ->
                SettingsCard(stringResource(section.title)) {
                    section.specs.forEach { spec ->
                        val value = AccountSettingsChanges.current(json, spec)
                        when (spec.type) {
                            SettingType.BOOL -> SettingsSwitchItem(
                                stringResource(spec.label), stringResource(spec.help), Icons.Default.Tune,
                                checked = AccountSettingsChanges.asBool(value), enabled = canUpdate,
                            ) { propose(spec, JsonPrimitive(it)) }
                            SettingType.GROUPS -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                MemberField(
                                    label = stringResource(spec.label),
                                    options = groupOptions,
                                    selected = AccountSettingsChanges.asList(value),
                                    onChange = { propose(spec, AccountSettingsChanges.list(it)) },
                                    emptyText = stringResource(R.string.admin_acct_none),
                                    enabled = canUpdate,
                                )
                                HelpText(stringResource(spec.help))
                            }
                            else -> ValueRow(stringResource(spec.label), render(spec, value).ifEmpty { stringResource(R.string.admin_acct_none) }, stringResource(spec.help), canUpdate) { onEditing(spec) }
                        }
                    }
                }
            }
            if (!host.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
        editing?.let { spec ->
            EditValueDialog(spec, AccountSettingsChanges.current(json, spec), onDismiss = { onEditing(null) }) { after ->
                onEditing(null)
                propose(spec, after)
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, help: String, enabled: Boolean, onEdit: () -> Unit) {
    ListItem(
        modifier = if (enabled) Modifier.clickable(onClick = onEdit) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                HelpText(help)
            }
        },
        trailingContent = { if (enabled) Icon(Icons.Default.Edit, null) },
    )
}

/** One typed value: a text, names separated by commas, or a duration in hours or days. */
@Composable
private fun EditValueDialog(spec: SettingSpec, current: JsonElement, onDismiss: () -> Unit, onSave: (JsonElement) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    val seconds = spec.type == SettingType.SECONDS
    val cur = AccountSettingsChanges.asLong(current)
    var inDays by remember { mutableStateOf(!seconds || cur == 0L || cur % 86_400 == 0L) }
    var text by remember {
        mutableStateOf(
            when (spec.type) {
                SettingType.SECONDS -> if (cur <= 0) "" else if (cur % 86_400 == 0L) (cur / 86_400).toString() else (cur / 3_600).toString()
                SettingType.NAMES -> AccountSettingsChanges.asList(current).joinToString(", ")
                else -> AccountSettingsChanges.asText(current)
            }
        )
    }
    val value: JsonElement = when (spec.type) {
        SettingType.SECONDS -> JsonPrimitive((text.toLongOrNull() ?: 0L) * if (inDays) 86_400L else 3_600L)
        SettingType.NAMES -> AccountSettingsChanges.list(text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        else -> JsonPrimitive(text.trim())
    }
    val problem = AccountSettingsChanges.problem(spec, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(spec.label)) },
        text = {
            parent.Provide {
                Column {
                    HelpText(stringResource(spec.help), lines = 6)
                    Spacer(Modifier.height(8.dp))
                    if (seconds) SlidingSegmentedChips(
                        options = listOf(stringResource(R.string.admin_acct_unit_hours), stringResource(R.string.admin_acct_unit_days)),
                        selectedIndex = if (inDays) 1 else 0,
                        onOptionSelected = { inDays = it == 1 },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        text, { text = if (seconds) it.filter(Char::isDigit).take(5) else it },
                        singleLine = true,
                        isError = problem != null,
                        supportingText = problem?.let { { Text(stringResource(it)) } },
                        keyboardOptions = if (seconds) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = { onSave(value) }) { Text(ctx.getString(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun AccountSettingsPreview() = BirdSocksTheme {
    val host = remember {
        PreviewAdminHost(seed = {
            set(AdminKeys.ACCOUNTS, Loadable(Listing(listOf(ApiAccount(id = "acc", domain = "example.com", settings = ApiAccountSettings(
                peerLoginExpirationEnabled = true, peerLoginExpiration = 86_400 * 7, dnsDomain = "netbird.cloud", networkRange = "100.64.0.0/10",
            )))), loadedAt = 1))
        })
    }
    Surface { AccountSettingsContent(host) }
}
