package io.github.bropines.birdsocks.admin.areas.dns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiDnsRecord
import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiNameserver
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.areas.routing.Badges
import io.github.bropines.birdsocks.admin.areas.routing.FormDialog
import io.github.bropines.birdsocks.admin.areas.routing.JsonTarget
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonEditor
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.areas.routing.SwitchRow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.IncompleteNote
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonObject

/** DNS: nameserver groups, private zones with their records, and which groups manage their own DNS. */
val DnsArea = AdminArea(
    id = "dns",
    title = R.string.admin_area_dns,
    summary = R.string.admin_area_dns_summary,
    icon = Icons.Default.Dns,
    module = null,
    available = { it.canRead(Capabilities.DNS) || it.canRead(Capabilities.NAMESERVERS) },
    aliases = setOf("nameservers"),
) { scope -> DnsScreen(scope) }

/** The area's tabs, in order. */
enum class DnsTab { NAMESERVERS, ZONES, SETTINGS }

/** The tab, the open forms and the JSON document, across rotations; [draft] is the settings being edited. */
class DnsState(host: AdminHost) : AreaState(host) {
    var tab by mutableStateOf(DnsTab.NAMESERVERS)
    var ns by mutableStateOf<Pair<String?, NsGroupForm>?>(null)
    var zone by mutableStateOf<Pair<String?, ZoneForm>?>(null)
    /** Zone id, record id (null for a new one), the form. */
    var record by mutableStateOf<Triple<String, String?, RecordForm>?>(null)
    var json by mutableStateOf<JsonTarget?>(null)
    var draft by mutableStateOf<List<String>?>(null)
}

@Composable
private fun DnsScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("dns") { DnsState(it) }
    val tabs = DnsTab.entries.filter { if (it == DnsTab.NAMESERVERS) host.canRead(Capabilities.NAMESERVERS) else host.canRead(Capabilities.DNS) }
    LaunchedEffect(Unit) {
        host.load(AdminKeys.NAMESERVERS, AdminKeys.DNS_SETTINGS, AdminKeys.DNS_ZONES, AdminKeys.GROUPS, AdminKeys.PEERS)
        if (state.tab !in tabs) tabs.firstOrNull()?.let { state.tab = it }
        when (scope.arg) { "zones" -> state.tab = DnsTab.ZONES; "settings" -> state.tab = DnsTab.SETTINGS }
    }
    val keys = when (state.tab) {
        DnsTab.NAMESERVERS -> listOf(AdminKeys.NAMESERVERS)
        DnsTab.ZONES -> listOf(AdminKeys.DNS_ZONES)
        DnsTab.SETTINGS -> listOf(AdminKeys.DNS_SETTINGS)
    }
    val canCreate = when (state.tab) {
        DnsTab.NAMESERVERS -> host.can(Capabilities.NAMESERVERS, Op.CREATE)
        DnsTab.ZONES -> host.can(Capabilities.DNS, Op.CREATE) && host.data[AdminKeys.DNS_ZONES].error !is AdminApiException.NotFound
        DnsTab.SETTINGS -> false
    }
    AreaScaffold(
        scope,
        refreshing = keys.any { host.data[it].loading },
        onRefresh = { host.load(*(keys + AdminKeys.PEERS).toTypedArray(), force = true) },
        floatingActionButton = {
            if (canCreate) ExtendedFloatingActionButton(
                onClick = { if (state.tab == DnsTab.NAMESERVERS) state.ns = null to NsGroupForm() else state.zone = null to ZoneForm() },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(if (state.tab == DnsTab.NAMESERVERS) R.string.admin_dns_ns_new else R.string.admin_dns_zone_new)) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (tabs.size > 1) SlidingSegmentedChips(
                options = tabs.map { stringResource(tabLabel(it)) },
                selectedIndex = tabs.indexOf(state.tab).coerceAtLeast(0),
                onOptionSelected = { state.tab = tabs[it] },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    DnsTab.NAMESERVERS -> NameserversContent(host, state)
                    DnsTab.ZONES -> ZonesContent(host, state)
                    DnsTab.SETTINGS -> SettingsContent(host, state)
                }
            }
        }
    }
    DnsDialogs(host, state)
}

private fun tabLabel(t: DnsTab) = when (t) {
    DnsTab.NAMESERVERS -> R.string.admin_dns_tab_nameservers
    DnsTab.ZONES -> R.string.admin_dns_tab_zones
    DnsTab.SETTINGS -> R.string.admin_dns_tab_settings
}

private fun groupNames(host: AdminHost): (String) -> String {
    val groups = host.data.items(AdminKeys.GROUPS)
    return { id -> groups.firstOrNull { it.id == id }?.name ?: id }
}

// ================================================================ nameservers

@Composable
internal fun NameserversContent(host: AdminHost, state: DnsState) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val names = groupNames(host)
    val canUpdate = host.can(Capabilities.NAMESERVERS, Op.UPDATE)
    LoadableContent(host.data[AdminKeys.NAMESERVERS], onRetry = { host.load(AdminKeys.NAMESERVERS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { g -> listOf(g.name, g.description) + g.nameservers.map { it.ip } + g.domains + g.groups.map(names) },
            searchHint = stringResource(R.string.admin_dns_ns_search_hint),
            emptyText = stringResource(R.string.admin_dns_ns_empty),
            noMatchText = stringResource(R.string.admin_dns_ns_no_match),
            emptyIcon = Icons.Default.Dns,
            filters = listOf(
                ListFilter("primary", stringResource(R.string.admin_dns_ns_primary)) { it.primary },
                ListFilter("self", stringResource(R.string.admin_routing_this_device)) { DnsRules.uses(it, self) },
                ListFilter("off", stringResource(R.string.admin_routing_disabled)) { !it.enabled },
            ),
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareByDescending<ApiNameserverGroup> { it.primary }.thenBy { it.name.lowercase() }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.NAMESERVERS, force = true) }) } },
        ) { g ->
            ListItem(
                modifier = Modifier.clickable { state.ns = g.id to DnsRules.form(g) },
                leadingContent = { Icon(Icons.Default.Dns, null, tint = if (g.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                headlineContent = { Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(g.nameservers.joinToString(", ") { if (it.port == 53) it.ip else "${it.ip}:${it.port}" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(if (g.primary) stringResource(R.string.admin_dns_ns_all_domains) else g.domains.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (g.groups.isNotEmpty()) Text(stringResource(R.string.admin_dns_row_groups, g.groups.joinToString(", ") { names(it) }), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Badges {
                            if (g.primary) StatusBadge(stringResource(R.string.admin_dns_ns_primary), MaterialTheme.colorScheme.secondaryContainer)
                            if (g.searchDomainsEnabled) StatusBadge(stringResource(R.string.admin_dns_ns_search_badge), MaterialTheme.colorScheme.surfaceVariant)
                            if (DnsRules.uses(g, self)) StatusBadge(stringResource(R.string.admin_routing_this_device), MaterialTheme.colorScheme.primaryContainer)
                            if (!g.enabled) StatusBadge(stringResource(R.string.admin_routing_disabled), MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                },
                trailingContent = {
                    Switch(g.enabled, { on -> host.propose(DnsChanges.nsUpdate(ctx, g, io.github.bropines.birdsocks.admin.api.ApiNameserverGroupRequest.of(g).copy(enabled = on), self, names)) },
                        enabled = canUpdate && !host.changeBusy)
                },
            )
        }
    }
}

@Composable
private fun nsProblemText(p: NsProblem): String = when (p.kind) {
    NsProblemKind.NAME -> stringResource(R.string.admin_dns_problem_name)
    NsProblemKind.NO_NAMESERVERS -> stringResource(R.string.admin_dns_problem_no_servers)
    NsProblemKind.TOO_MANY_NAMESERVERS -> stringResource(R.string.admin_dns_problem_too_many_servers, DnsRules.MAX_NAMESERVERS)
    NsProblemKind.BAD_IP -> stringResource(R.string.admin_dns_problem_ip, p.value.ifEmpty { "#${p.index + 1}" })
    NsProblemKind.BAD_PORT -> stringResource(R.string.admin_dns_problem_port, p.value)
    NsProblemKind.DUPLICATE_NAMESERVER -> stringResource(R.string.admin_dns_problem_duplicate, p.value)
    NsProblemKind.NO_DOMAINS -> stringResource(R.string.admin_dns_problem_no_domains)
    NsProblemKind.BAD_DOMAIN -> stringResource(R.string.admin_dns_problem_domain, p.value)
    NsProblemKind.NO_GROUPS -> stringResource(R.string.admin_dns_problem_no_groups)
}

@Composable
private fun NsGroupDialog(host: AdminHost, initial: NsGroupForm, existing: ApiNameserverGroup?, onDismiss: () -> Unit, onSave: (NsGroupForm) -> Unit, onJson: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var f by remember(initial) { mutableStateOf(initial) }
    val editable = host.can(Capabilities.NAMESERVERS, if (existing == null) Op.CREATE else Op.UPDATE)
    val problems = DnsRules.validate(f)
    val problemTexts = problems.map { nsProblemText(it) }
    FormDialog(
        title = existing?.name ?: stringResource(R.string.admin_dns_ns_new),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problemTexts,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        OutlinedTextField(f.name, { f = f.copy(name = it.take(DnsRules.MAX_NAME)) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_dns_ns_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.description, { f = f.copy(description = it) }, enabled = editable,
            label = { Text(stringResource(R.string.admin_dns_ns_description)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.admin_dns_ns_servers), style = MaterialTheme.typography.labelLarge)
        f.nameservers.forEachIndexed { i, ns ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ns.ip, { v -> f = f.copy(nameservers = f.nameservers.toMutableList().also { it[i] = ns.copy(ip = v.trim()) }) },
                    singleLine = true, enabled = editable, placeholder = { Text("1.1.1.1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(ns.port, { v -> f = f.copy(nameservers = f.nameservers.toMutableList().also { it[i] = ns.copy(port = v.filter(Char::isDigit).take(5)) }) },
                    singleLine = true, enabled = editable, label = { Text(stringResource(R.string.admin_dns_ns_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(88.dp))
                if (editable && f.nameservers.size > 1) IconButton(onClick = { f = f.copy(nameservers = f.nameservers.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_clear))
                }
            }
        }
        if (editable && f.nameservers.size < DnsRules.MAX_NAMESERVERS) TextButton(onClick = { f = f.copy(nameservers = f.nameservers + NameserverForm()) }) {
            Icon(Icons.Default.Add, null); Text(" " + stringResource(R.string.admin_dns_ns_add_server))
        }
        HelpText(stringResource(R.string.admin_dns_ns_servers_help))
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            options = listOf(stringResource(R.string.admin_dns_ns_all_domains), stringResource(R.string.admin_dns_ns_match_domains)),
            selectedIndex = if (f.primary) 0 else 1,
            onOptionSelected = { if (editable) f = f.copy(primary = it == 0) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (f.primary) HelpText(stringResource(R.string.admin_dns_ns_primary_help))
        else {
            OutlinedTextField(f.domains, { f = f.copy(domains = it) }, enabled = editable, minLines = 2,
                label = { Text(stringResource(R.string.admin_dns_ns_domains)) }, supportingText = { Text(stringResource(R.string.admin_dns_ns_domains_hint)) }, modifier = Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.admin_dns_ns_search), f.searchDomainsEnabled, { f = f.copy(searchDomainsEnabled = it) }, enabled = editable, help = stringResource(R.string.admin_dns_ns_search_help))
        }
        MemberField(stringResource(R.string.admin_dns_groups), MemberOptions.groups(host.data.items(AdminKeys.GROUPS)), f.groups, { f = f.copy(groups = it) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        HelpText(stringResource(R.string.admin_dns_groups_help))
        SwitchRow(stringResource(R.string.admin_routing_enabled), f.enabled, { f = f.copy(enabled = it) }, enabled = editable)
    }
}

// ================================================================ zones

@Composable
internal fun ZonesContent(host: AdminHost, state: DnsState) {
    val ctx = LocalContext.current
    val zones = host.data[AdminKeys.DNS_ZONES]
    val self = SelfUse.of(host)
    val names = groupNames(host)
    if (zones.value == null && zones.error is AdminApiException.NotFound) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Default.Domain, stringResource(R.string.admin_dns_zones_unsupported), Modifier.padding(top = 32.dp))
            HelpText(stringResource(R.string.admin_dns_zones_unsupported_help))
        }
        return
    }
    val canCreate = host.can(Capabilities.DNS, Op.CREATE)
    LoadableContent(zones, onRetry = { host.load(AdminKeys.DNS_ZONES, force = true) }) { listing, stale ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
            item(key = "incomplete") { IncompleteNote(listing.issues.size) }
            if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.DNS_ZONES, force = true) }) }
            if (listing.items.isEmpty()) item(key = "empty") { EmptyState(Icons.Default.Domain, stringResource(R.string.admin_dns_zones_empty), Modifier.padding(vertical = 32.dp)) }
            items(listing.items.sortedBy { it.domain }, key = { it.id }) { z ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    ListItem(
                        modifier = Modifier.clickable { state.zone = z.id to DnsRules.form(z) },
                        leadingContent = { Icon(Icons.Default.Domain, null, tint = if (z.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                        headlineContent = { Text(z.domain, fontFamily = FontFamily.Monospace) },
                        supportingContent = {
                            Column {
                                Text(listOf(z.name, pluralStringResource(R.plurals.admin_dns_records, z.records.size, z.records.size)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                if (z.distributionGroups.isNotEmpty()) Text(stringResource(R.string.admin_dns_row_groups, z.distributionGroups.joinToString(", ") { names(it) }), style = MaterialTheme.typography.bodySmall)
                                Badges {
                                    if (z.enableSearchDomain) StatusBadge(stringResource(R.string.admin_dns_ns_search_badge), MaterialTheme.colorScheme.surfaceVariant)
                                    if (DnsRules.uses(z, self)) StatusBadge(stringResource(R.string.admin_routing_this_device), MaterialTheme.colorScheme.primaryContainer)
                                    if (!z.enabled) StatusBadge(stringResource(R.string.admin_routing_disabled), MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        },
                        trailingContent = { Icon(Icons.Default.Edit, stringResource(R.string.action_edit)) },
                    )
                    HorizontalDivider()
                    z.records.sortedWith(compareBy<ApiDnsRecord> { it.name }.thenBy { it.type }).forEach { r ->
                        ListItem(
                            modifier = Modifier.clickable { state.record = Triple(z.id, r.id, DnsRules.form(r, z.domain)) },
                            headlineContent = { Text(r.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${r.type}  ${r.content}  ·  TTL ${r.ttl}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                    if (canCreate) TextButton(onClick = { state.record = Triple(z.id, null, RecordForm()) }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Default.Add, null); Text(" " + stringResource(R.string.admin_dns_record_new))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneDialog(host: AdminHost, initial: ZoneForm, existing: ApiDnsZone?, onDismiss: () -> Unit, onSave: (ZoneForm) -> Unit, onJson: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var f by remember(initial) { mutableStateOf(initial) }
    val editable = host.can(Capabilities.DNS, if (existing == null) Op.CREATE else Op.UPDATE)
    val problems = DnsRules.validate(f).map {
        when (it) {
            ZoneProblem.NAME -> stringResource(R.string.admin_routing_problem_name)
            ZoneProblem.DOMAIN -> stringResource(R.string.admin_dns_problem_zone_domain)
            ZoneProblem.NO_GROUPS -> stringResource(R.string.admin_dns_problem_no_groups)
        }
    }
    FormDialog(
        title = existing?.domain ?: stringResource(R.string.admin_dns_zone_new),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problems,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        OutlinedTextField(f.name, { f = f.copy(name = it) }, singleLine = true, enabled = editable, label = { Text(stringResource(R.string.admin_dns_zone_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.domain, { f = f.copy(domain = it.trim()) }, singleLine = true, enabled = editable, label = { Text(stringResource(R.string.admin_dns_zone_domain)) },
            placeholder = { Text("office.lan") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        SwitchRow(stringResource(R.string.admin_dns_zone_search), f.searchDomain, { f = f.copy(searchDomain = it) }, enabled = editable, help = stringResource(R.string.admin_dns_zone_search_help))
        MemberField(stringResource(R.string.admin_dns_groups), MemberOptions.groups(host.data.items(AdminKeys.GROUPS)), f.groups, { f = f.copy(groups = it) },
            emptyText = stringResource(R.string.admin_routing_none_chosen), enabled = editable)
        SwitchRow(stringResource(R.string.admin_routing_enabled), f.enabled, { f = f.copy(enabled = it) }, enabled = editable)
    }
}

@Composable
private fun RecordDialog(host: AdminHost, zone: ApiDnsZone, initial: RecordForm, existing: ApiDnsRecord?, onDismiss: () -> Unit, onSave: (RecordForm) -> Unit, onJson: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var f by remember(initial) { mutableStateOf(initial) }
    val editable = host.can(Capabilities.DNS, if (existing == null) Op.CREATE else Op.UPDATE)
    val problems = DnsRules.validate(f, zone.domain).map {
        when (it) {
            RecordProblem.NAME -> stringResource(R.string.admin_dns_problem_record_name)
            RecordProblem.TYPE -> stringResource(R.string.admin_dns_problem_record_type)
            RecordProblem.CONTENT -> stringResource(if (f.type == "CNAME") R.string.admin_dns_problem_record_cname else R.string.admin_dns_problem_record_ip, f.type)
            RecordProblem.TTL -> stringResource(R.string.admin_dns_problem_ttl)
        }
    }
    FormDialog(
        title = existing?.name ?: stringResource(R.string.admin_dns_record_new),
        confirmLabel = stringResource(if (existing == null) R.string.admin_routing_create else R.string.action_save),
        confirmEnabled = editable && problems.isEmpty() && f != initial && !host.changeBusy,
        onConfirm = { onSave(f) },
        onDismiss = onDismiss,
        problems = problems,
        onJson = onJson,
        onDelete = onDelete,
    ) {
        OutlinedTextField(f.name, { f = f.copy(name = it.trim()) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_dns_record_name)) },
            supportingText = { Text(stringResource(R.string.admin_dns_record_name_is, DnsRules.fqdn(f.name, zone.domain))) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DnsRules.RECORD_TYPES.forEach { t -> FilterChip(selected = f.type == t, onClick = { if (editable) f = f.copy(type = t) }, label = { Text(t) }) }
        }
        OutlinedTextField(f.content, { f = f.copy(content = it.trim()) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_dns_record_content)) },
            placeholder = { Text(if (f.type == "CNAME") "host.${zone.domain}" else if (f.type == "AAAA") "fd00::1" else "10.0.0.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.ttl, { f = f.copy(ttl = it.filter(Char::isDigit).take(9)) }, singleLine = true, enabled = editable,
            label = { Text(stringResource(R.string.admin_dns_record_ttl)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}

// ================================================================ settings

@Composable
internal fun SettingsContent(host: AdminHost, state: DnsState) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val names = groupNames(host)
    val canUpdate = host.can(Capabilities.DNS, Op.UPDATE)
    LoadableContent(host.data[AdminKeys.DNS_SETTINGS], onRetry = { host.load(AdminKeys.DNS_SETTINGS, force = true) }) { settings, stale ->
        val current = state.draft ?: settings.disabledManagementGroups
        val selfOff = DnsRules.selfGroupsTurnedOff(settings.disabledManagementGroups, current, self)
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (stale != null) ErrorCard(stale, onRetry = { host.load(AdminKeys.DNS_SETTINGS, force = true) })
            Text(stringResource(R.string.admin_dns_disabled_groups), style = MaterialTheme.typography.titleSmall)
            HelpText(stringResource(R.string.admin_dns_disabled_groups_help))
            MemberField(
                stringResource(R.string.admin_dns_groups), MemberOptions.groups(host.data.items(AdminKeys.GROUPS)), current, { state.draft = it },
                emptyText = stringResource(R.string.admin_dns_disabled_none), enabled = canUpdate,
            )
            if (selfOff.isNotEmpty()) Text(stringResource(R.string.admin_dns_settings_self_warn, selfOff.joinToString(", ") { names(it) }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (state.draft != null && state.draft != settings.disabledManagementGroups) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !host.changeBusy, onClick = {
                    if (host.propose(DnsChanges.settings(ctx, settings, current, self, names))) state.draft = null
                }) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = { state.draft = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

// ================================================================ dialogs

@Composable
private fun DnsDialogs(host: AdminHost, state: DnsState) {
    val ctx = LocalContext.current
    val self = SelfUse.of(host)
    val names = groupNames(host)

    state.ns?.let { (id, form) ->
        val existing = id?.let { i -> host.data.items(AdminKeys.NAMESERVERS).firstOrNull { it.id == i } }
        NsGroupDialog(
            host, form, existing,
            onDismiss = { state.ns = null },
            onSave = { f ->
                val request = DnsRules.request(f)
                val change = if (existing == null) DnsChanges.nsCreate(ctx, request, names) else DnsChanges.nsUpdate(ctx, existing, request, self, names)
                if (host.propose(change)) state.ns = null
            },
            onJson = existing?.let { g ->
                {
                    state.ns = null
                    state.json = JsonTarget(g.name, ctx.getString(R.string.admin_dns_tab_nameservers), DnsChanges.nsPath(g.id),
                        AppJson.encodeToJsonElement(ApiNameserverGroup.serializer(), g) as JsonObject, host.can(Capabilities.NAMESERVERS, Op.UPDATE)) { b, a -> DnsChanges.nsJson(ctx, g, b, a, self) }
                }
            },
            onDelete = existing?.takeIf { host.can(Capabilities.NAMESERVERS, Op.DELETE) }?.let { g -> { if (host.propose(DnsChanges.nsDelete(ctx, g, self))) state.ns = null } },
        )
    }

    state.zone?.let { (id, form) ->
        val existing = id?.let { i -> host.data.items(AdminKeys.DNS_ZONES).firstOrNull { it.id == i } }
        ZoneDialog(
            host, form, existing,
            onDismiss = { state.zone = null },
            onSave = { f ->
                val request = DnsRules.request(f)
                val change = if (existing == null) DnsChanges.zoneCreate(ctx, request, names) else DnsChanges.zoneUpdate(ctx, existing, request, self, names)
                if (host.propose(change)) state.zone = null
            },
            onJson = existing?.let { z ->
                {
                    state.zone = null
                    state.json = JsonTarget(z.domain, ctx.getString(R.string.admin_dns_tab_zones), DnsChanges.zonePath(z.id),
                        AppJson.encodeToJsonElement(ApiDnsZone.serializer(), z) as JsonObject, host.can(Capabilities.DNS, Op.UPDATE)) { b, a -> DnsChanges.zoneJson(ctx, z, b, a) }
                }
            },
            onDelete = existing?.takeIf { host.can(Capabilities.DNS, Op.DELETE) }?.let { z -> { if (host.propose(DnsChanges.zoneDelete(ctx, z, self))) state.zone = null } },
        )
    }

    state.record?.let { (zoneId, recordId, form) ->
        val zone = host.data.items(AdminKeys.DNS_ZONES).firstOrNull { it.id == zoneId } ?: return@let
        val existing = recordId?.let { i -> zone.records.firstOrNull { it.id == i } }
        RecordDialog(
            host, zone, form, existing,
            onDismiss = { state.record = null },
            onSave = { f ->
                val request = DnsRules.request(f, zone.domain)
                val change = if (existing == null) DnsChanges.recordCreate(ctx, zone, request) else DnsChanges.recordUpdate(ctx, zone, existing, request)
                if (host.propose(change)) state.record = null
            },
            onJson = existing?.let { r ->
                {
                    state.record = null
                    state.json = JsonTarget(r.name, zone.domain, DnsChanges.recordPath(zone.id, r.id),
                        AppJson.encodeToJsonElement(ApiDnsRecord.serializer(), r) as JsonObject, host.can(Capabilities.DNS, Op.UPDATE)) { b, a -> DnsChanges.recordJson(ctx, zone, r, b, a) }
                }
            },
            onDelete = existing?.takeIf { host.can(Capabilities.DNS, Op.DELETE) }?.let { r -> { if (host.propose(DnsChanges.recordDelete(ctx, zone, r))) state.record = null } },
        )
    }

    state.json?.let { RawJsonEditor(host, it, onClose = { state.json = null }) }
}

// ================================================================ previews

internal fun previewDnsHost(): PreviewAdminHost = PreviewAdminHost.demo().also { h ->
    h.data.set(AdminKeys.NAMESERVERS, Loadable(Listing(listOf(
        ApiNameserverGroup(id = "ns1", name = "Quad9", nameservers = listOf(ApiNameserver("9.9.9.9"), ApiNameserver("149.112.112.112")), enabled = true, groups = listOf("g-all"), primary = true),
        ApiNameserverGroup(id = "ns2", name = "Office", nameservers = listOf(ApiNameserver("192.168.1.1", port = 5353)), enabled = true, groups = listOf("g-office"), domains = listOf("office.lan"), searchDomainsEnabled = true),
    )), loadedAt = 1))
    h.data.set(AdminKeys.DNS_ZONES, Loadable(Listing(listOf(
        ApiDnsZone(id = "z1", name = "Office", domain = "office.lan", distributionGroups = listOf("g-office"), records = listOf(
            ApiDnsRecord("rec1", "nas.office.lan", "A", "192.168.1.9", 300),
            ApiDnsRecord("rec2", "files.office.lan", "CNAME", "nas.office.lan", 300),
        )),
    )), loadedAt = 1))
    h.data.set(AdminKeys.DNS_SETTINGS, Loadable(ApiDnsSettings(listOf("g-office")), loadedAt = 1))
}

@Preview
@Composable
private fun NameserversPreview() = BirdSocksTheme {
    val host = remember { previewDnsHost() }
    Surface { NameserversContent(host, host.areaState("dns") { DnsState(it) }) }
}

@Preview
@Composable
private fun ZonesPreview() = BirdSocksTheme {
    val host = remember { previewDnsHost() }
    Surface { ZonesContent(host, host.areaState("dns") { DnsState(it) }) }
}

@Preview
@Composable
private fun DnsSettingsPreview() = BirdSocksTheme {
    val host = remember { previewDnsHost() }
    Surface { SettingsContent(host, host.areaState("dns") { DnsState(it) }) }
}
