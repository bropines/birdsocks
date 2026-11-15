package io.github.bropines.birdsocks.admin.areas.idp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiIdentityProvider
import io.github.bropines.birdsocks.admin.api.ApiIdentityProviderRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.users.RolePicker
import io.github.bropines.birdsocks.admin.secure.SecureWindow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Identity providers: where people sign in. Read-mostly; client secrets are sent, never shown. */
val IdentityProvidersArea = AdminArea(
    id = "identity_providers",
    title = R.string.admin_area_identity_providers,
    summary = R.string.admin_area_identity_providers_summary,
    icon = Icons.Default.Fingerprint,
    module = Capabilities.IDENTITY_PROVIDERS,
    aliases = setOf("idp"),
) { scope -> IdentityProvidersScreen(scope) }

@Composable
private fun IdentityProvidersScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.IDENTITY_PROVIDERS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.IDENTITY_PROVIDERS].loading,
        onRefresh = { host.load(AdminKeys.IDENTITY_PROVIDERS, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.IDENTITY_PROVIDERS, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_idp_create)) },
            )
        },
    ) { IdentityProvidersContent(host, creating, onCreatingChange = { creating = it }) }
}

@Composable
internal fun IdentityProvidersContent(host: AdminHost, creating: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    LoadableContent(host.data[AdminKeys.IDENTITY_PROVIDERS], onRetry = { host.load(AdminKeys.IDENTITY_PROVIDERS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { listOf(it.name, it.type, it.issuer, it.clientId) },
            searchHint = stringResource(R.string.admin_idp_search),
            emptyText = stringResource(R.string.admin_idp_empty),
            noMatchText = stringResource(R.string.admin_idp_no_match),
            emptyIcon = Icons.Default.Fingerprint,
            sorts = listOf(ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() })),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.IDENTITY_PROVIDERS, force = true) }) } },
        ) { p ->
            ListItem(
                modifier = Modifier.clickable { openId = p.id },
                leadingContent = { Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(p.name.ifEmpty { p.id }) },
                supportingContent = { Text(listOf(p.type, p.issuer).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) },
            )
        }
        listing.items.firstOrNull { it.id == openId }?.let { p ->
            var editing by remember(p.id) { mutableStateOf(false) }
            AdminDetailSheet(title = p.name.ifEmpty { p.id }, subtitle = p.type, onDismiss = { openId = null }) {
                DetailField(stringResource(R.string.admin_idp_issuer), p.issuer, mono = true)
                DetailField(stringResource(R.string.admin_idp_client_id), p.clientId, mono = true)
                DetailField(stringResource(R.string.admin_idp_client_secret), stringResource(R.string.admin_idp_secret_hidden))
                DetailField(stringResource(R.string.admin_idp_id), p.id, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (host.can(Capabilities.IDENTITY_PROVIDERS, Op.UPDATE)) OutlinedButton(enabled = !host.changeBusy, onClick = { editing = true }) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_idp_edit))
                    }
                    if (host.can(Capabilities.IDENTITY_PROVIDERS, Op.DELETE)) OutlinedButton(
                        enabled = !host.changeBusy,
                        onClick = { if (host.propose(IdentityProviderChanges.delete(ctx, p))) openId = null },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_idp_delete)) }
                }
            }
            if (editing) ProviderDialog(host, p, onDismiss = { editing = false }) { r ->
                if (host.propose(IdentityProviderChanges.update(ctx, p, r))) editing = false
            }
        }
    }
    if (creating) ProviderDialog(host, null, onDismiss = { onCreatingChange(false) }) { r ->
        if (host.propose(IdentityProviderChanges.create(ctx, r))) onCreatingChange(false)
    }
}

/** A new provider, or [existing] changed; the client secret is asked for each time (the API requires it and never returns it). */
@Composable
private fun ProviderDialog(host: AdminHost, existing: ApiIdentityProvider?, onDismiss: () -> Unit, onSave: (ApiIdentityProviderRequest) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var type by remember { mutableStateOf(existing?.type?.takeIf { it in IdentityProviderChanges.TYPES } ?: "oidc") }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var issuer by remember { mutableStateOf(existing?.issuer.orEmpty()) }
    var clientId by remember { mutableStateOf(existing?.clientId.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    SecureWindow()
    val request = ApiIdentityProviderRequest(type, name.trim(), issuer.trim(), clientId.trim(), secret)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(ctx.getString(if (existing == null) R.string.admin_idp_create else R.string.admin_idp_edit)) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_idp_type), Modifier.weight(1f))
                        RolePicker(type, IdentityProviderChanges.TYPES, enabled = true, onPick = { type = it })
                    }
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_idp_name)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        issuer, { issuer = it }, singleLine = true, label = { Text(stringResource(R.string.admin_idp_issuer)) },
                        isError = issuer.isNotBlank() && !IdentityProviderChanges.issuerOk(issuer),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(clientId, { clientId = it }, singleLine = true, label = { Text(stringResource(R.string.admin_idp_client_id)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        secret, { secret = it }, singleLine = true, label = { Text(stringResource(R.string.admin_idp_client_secret)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelpText(stringResource(if (existing == null) R.string.admin_idp_secret_note else R.string.admin_idp_secret_again), lines = 3)
                }
            }
        },
        confirmButton = { TextButton(enabled = IdentityProviderChanges.requestOk(request) && !host.changeBusy, onClick = { onSave(request) }) { Text(ctx.getString(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun IdentityProvidersPreview() = BirdSocksTheme {
    Surface { IdentityProvidersContent(PreviewAdminHost.demo()) }
}
