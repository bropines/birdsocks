package io.github.bropines.birdsocks.admin.areas.proxy

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminNote
import io.github.bropines.birdsocks.admin.api.ApiReverseProxyDomain
import io.github.bropines.birdsocks.admin.api.ApiService
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Urls
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.settings.SettingsInput
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.secure.SecureWindow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.JsonEditorDialog
import io.github.bropines.birdsocks.admin.ui.JsonText
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.ExposeActivity
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The reverse proxy: the account's published services and their domains.
 * Publishing a port of this phone is the Publish screen's job; this area
 * links there instead of repeating it.
 */
val ReverseProxyArea = AdminArea(
    id = "reverse_proxy",
    title = R.string.admin_area_reverse_proxy,
    summary = R.string.admin_area_reverse_proxy_summary,
    icon = Icons.Default.Public,
    module = Capabilities.SERVICES,
    aliases = setOf("services", "proxy"),
) { scope -> ReverseProxyScreen(scope) }

@Composable
private fun ReverseProxyScreen(scope: AreaScope) {
    val host = scope.host
    var tab by rememberSaveable { mutableStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { host.load(AdminKeys.PROXY_SERVICES, AdminKeys.PROXY_DOMAINS) }
    val key = if (tab == 0) AdminKeys.PROXY_SERVICES else AdminKeys.PROXY_DOMAINS
    AreaScaffold(
        scope,
        refreshing = host.data[key].loading,
        onRefresh = { host.load(key, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.SERVICES, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(if (tab == 0) R.string.admin_rp_service_create else R.string.admin_rp_domain_add)) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            SlidingSegmentedChips(
                options = listOf(stringResource(R.string.admin_rp_tab_services), stringResource(R.string.admin_rp_tab_domains)),
                selectedIndex = tab,
                onOptionSelected = { tab = it; creating = false },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (tab == 0) ServicesContent(host, creating, onCreatingChange = { creating = it })
            else DomainsContent(host, creating, onCreatingChange = { creating = it })
        }
    }
}

/** The link to the app's own Publish screen, for this phone's ports. */
@Composable
private fun PublishLinkCard() {
    val ctx = LocalContext.current
    Card(
        onClick = { ctx.startActivity(Intent(ctx, ExposeActivity::class.java)) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.admin_rp_publish_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.admin_rp_publish_desc), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
        }
    }
}

@Composable
private fun serviceStatus(s: ApiService): String = when (s.meta.status) {
    "active" -> stringResource(R.string.admin_rp_status_active)
    "pending", "certificate_pending" -> stringResource(R.string.admin_rp_status_pending)
    "" -> ""
    else -> s.meta.status.replace('_', ' ')
}

@Composable
internal fun ServicesContent(host: AdminHost, creating: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val canUpdate = host.can(Capabilities.SERVICES, Op.UPDATE) && !host.changeBusy
    LoadableContent(host.data[AdminKeys.PROXY_SERVICES], onRetry = { host.load(AdminKeys.PROXY_SERVICES, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { s -> listOf(s.name, s.domain, s.mode.orEmpty(), s.meta.status) + s.targets.map { it.host.orEmpty() } },
            searchHint = stringResource(R.string.admin_rp_search_services),
            emptyText = stringResource(R.string.admin_rp_services_empty),
            noMatchText = stringResource(R.string.admin_rp_no_match),
            emptyIcon = Icons.Default.Public,
            filters = listOf(
                ListFilter("on", stringResource(R.string.admin_rp_enabled)) { it.enabled },
                ListFilter("problem", stringResource(R.string.admin_rp_filter_problem)) { it.meta.status.isNotEmpty() && it.meta.status != "active" },
            ),
            issues = listing.issues.size,
            header = {
                item(key = "publish") { PublishLinkCard() }
                if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.PROXY_SERVICES, force = true) }) }
            },
        ) { s ->
            ListItem(
                modifier = Modifier.clickable { openId = s.id },
                leadingContent = { Icon(Icons.Default.Public, null, tint = if (s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                headlineContent = { Text(s.name.ifEmpty { s.domain }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(listOfNotNull(s.domain, s.mode, serviceStatus(s).takeIf { it.isNotEmpty() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                },
                trailingContent = { Switch(s.enabled, { host.propose(ProxyChanges.setEnabled(ctx, s, it)) }, enabled = canUpdate) },
            )
        }
        listing.items.firstOrNull { it.id == openId }?.let { ServiceSheet(host, it, onDismiss = { openId = null }) }
    }
    if (creating) JsonEditorDialog(
        title = stringResource(R.string.admin_rp_service_create),
        initial = ProxyChanges.TEMPLATE,
        onSave = { text ->
            val obj = JsonText.parse(text) as? JsonObject
            if (obj != null && host.propose(ProxyChanges.createService(ctx, obj))) onCreatingChange(false)
        },
        onClose = { onCreatingChange(false) },
        check = { ProxyChanges.serviceProblem(it as? JsonObject) },
    )
}

@Composable
private fun ServiceSheet(host: AdminHost, s: ApiService, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var reading by remember { mutableStateOf(false) }
    AdminDetailSheet(
        title = s.name.ifEmpty { s.domain },
        subtitle = s.domain,
        onDismiss = onDismiss,
        badges = { serviceStatus(s).takeIf { it.isNotEmpty() }?.let { StatusBadge(it, if (s.meta.status == "active") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) } },
    ) {
        DetailField(stringResource(R.string.admin_rp_mode), listOfNotNull(s.mode, s.listenPort?.toString()).joinToString(" · "))
        DetailField(stringResource(R.string.admin_rp_cluster), s.proxyCluster.orEmpty(), mono = true)
        DetailField(stringResource(R.string.admin_rp_targets), s.targets.joinToString("\n") { t ->
            listOfNotNull(t.targetType, t.host ?: t.targetId, t.protocol + ":" + t.port, t.path, if (!t.enabled) "off" else null).joinToString(" ")
        }, mono = true)
        DetailField(stringResource(R.string.admin_rp_auth), listOfNotNull(
            "password".takeIf { s.auth.passwordAuth?.enabled == true },
            "pin".takeIf { s.auth.pinAuth?.enabled == true },
            "sso".takeIf { s.auth.bearerAuth?.enabled == true },
            "link".takeIf { s.auth.linkAuth?.enabled == true },
            "header".takeIf { s.auth.headerAuths.orEmpty().any { it.enabled } },
        ).joinToString(", ").ifEmpty { stringResource(R.string.admin_rp_auth_none) })
        DetailField(stringResource(R.string.admin_rp_certificate), formatApiDate(s.meta.certificateIssuedAt))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = !reading && host.api != null, onClick = {
                val api = host.api!!
                reading = true
                host.scope.launch {
                    try {
                        editing = api.rawGet("/reverse-proxies/services/${Urls.seg(s.id)}") as? JsonObject
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        host.notify(AdminNote.error(e))
                    } finally {
                        reading = false
                    }
                }
            }) { Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_rp_json)) }
            if (host.can(Capabilities.SERVICES, Op.DELETE)) OutlinedButton(
                enabled = !host.changeBusy,
                onClick = { if (host.propose(ProxyChanges.deleteService(ctx, s))) onDismiss() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_rp_delete)) }
        }
    }
    editing?.let { before ->
        // The service's JSON may carry its visitors' passwords: out of screenshots while open.
        SecureWindow()
        JsonEditorDialog(
            title = s.name.ifEmpty { s.domain },
            subtitle = stringResource(R.string.admin_rp_json_note),
            initial = JsonText.format(before),
            readOnly = !host.can(Capabilities.SERVICES, Op.UPDATE),
            onSave = { text ->
                val after = JsonText.parse(text) as? JsonObject
                if (after != null && host.propose(ProxyChanges.editService(ctx, s, before, after))) editing = null
            },
            onClose = { editing = null },
            check = { ProxyChanges.serviceProblem(it as? JsonObject) },
        )
    }
}

// ================================================================ domains

@Composable
internal fun DomainsContent(host: AdminHost, adding: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var checking by remember { mutableStateOf<String?>(null) }
    LoadableContent(host.data[AdminKeys.PROXY_DOMAINS], onRetry = { host.load(AdminKeys.PROXY_DOMAINS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { listOf(it.domain, it.type, it.targetCluster.orEmpty()) },
            searchHint = stringResource(R.string.admin_rp_search_domains),
            emptyText = stringResource(R.string.admin_rp_domains_empty),
            noMatchText = stringResource(R.string.admin_rp_no_match),
            emptyIcon = Icons.Default.Language,
            filters = listOf(ListFilter("unvalidated", stringResource(R.string.admin_rp_unvalidated)) { !it.validated }),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.PROXY_DOMAINS, force = true) }) } },
        ) { d ->
            DomainRow(
                d,
                checking = checking == d.id,
                canDelete = host.can(Capabilities.SERVICES, Op.DELETE) && !host.changeBusy && d.type != "free",
                onValidate = {
                    host.api?.let { api ->
                        checking = d.id
                        host.scope.launch {
                            // validateProxyDomain answers false on any failure; it does not throw.
                            val ok = try { api.validateProxyDomain(d.id) } finally { checking = null }
                            host.notify(AdminNote.res(if (ok) R.string.admin_rp_validate_ok else R.string.admin_rp_validate_fail, d.domain))
                            host.load(AdminKeys.PROXY_DOMAINS, force = true)
                        }
                    }
                },
                onDelete = { host.propose(ProxyChanges.deleteDomain(ctx, d, host.data.items(AdminKeys.PROXY_SERVICES))) },
            )
        }
    }
    if (adding) AddDomainDialog(host, onDismiss = { onCreatingChange(false) })
}

@Composable
private fun DomainRow(d: ApiReverseProxyDomain, checking: Boolean, canDelete: Boolean, onValidate: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        leadingContent = { Icon(if (d.validated) Icons.Default.Verified else Icons.Default.Language, null, tint = if (d.validated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
        headlineContent = { Text(d.domain, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOfNotNull(d.type.takeIf { it.isNotEmpty() }, d.targetCluster).joinToString(" · "), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusBadge(
                    stringResource(if (d.validated) R.string.admin_rp_validated else R.string.admin_rp_unvalidated),
                    if (d.validated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                )
                Row {
                    if (!d.validated) TextButton(enabled = !checking, onClick = onValidate) { Text(stringResource(R.string.admin_rp_validate)) }
                    if (canDelete) TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.admin_rp_delete), tint = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )
}

/** A custom domain: its name and the proxy cluster it points at (a CNAME), the clusters read from the server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDomainDialog(host: AdminHost, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var domain by remember { mutableStateOf("") }
    var cluster by remember { mutableStateOf("") }
    var clusters by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val api = host.api ?: return@LaunchedEffect
        clusters = runCatching { ProxyChanges.clusterAddresses(api.rawGet("/reverse-proxies/clusters")) }.getOrDefault(emptyList())
        if (cluster.isEmpty()) clusters.firstOrNull()?.let { cluster = it }
    }
    val domainOk = SettingsInput.isDomain(domain) && '.' in domain.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_rp_domain_add)) },
        text = {
            parent.Provide {
                Column {
                    OutlinedTextField(
                        domain, { domain = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_rp_domain)) },
                        isError = domain.isNotEmpty() && !domainOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(cluster, { cluster = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.admin_rp_cluster)) }, modifier = Modifier.fillMaxWidth())
                    if (clusters.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        clusters.forEach { c -> FilterChip(selected = c == cluster, onClick = { cluster = c }, label = { Text(c) }) }
                    }
                    HelpText(stringResource(R.string.admin_rp_domain_help), lines = 4)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = domainOk && cluster.isNotBlank() && !host.changeBusy, onClick = {
                if (host.propose(ProxyChanges.addDomain(ctx, domain, cluster))) onDismiss()
            }) { Text(ctx.getString(R.string.admin_rp_domain_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun ServicesPreview() = BirdSocksTheme {
    Surface { ServicesContent(PreviewAdminHost.demo()) }
}
