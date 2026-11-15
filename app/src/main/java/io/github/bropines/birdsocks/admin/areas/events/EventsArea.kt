package io.github.bropines.birdsocks.admin.areas.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiEvent
import io.github.bropines.birdsocks.admin.api.ApiNetworkTrafficEvent
import io.github.bropines.birdsocks.admin.api.ApiTrafficPage
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.api.TrafficQuery
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.IncompleteNote
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.MemberKind
import io.github.bropines.birdsocks.admin.ui.MemberOption
import io.github.bropines.birdsocks.admin.ui.MemberPickerSheet
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.parseRfc3339Millis
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Events: the account's audit log by local day, and network traffic where the server keeps it. Nothing here is cached. */
val EventsArea = AdminArea(
    id = "events",
    title = R.string.admin_area_events,
    summary = R.string.admin_area_events_summary,
    icon = Icons.Default.History,
    module = Capabilities.EVENTS,
    aliases = setOf("audit", "logs"),
) { scope -> EventsScreen(scope) }

/** The filters and the traffic pages, kept across rotation. */
class EventsState(host: AdminHost) : AreaState(host) {
    var tab by mutableStateOf(0)
    var query by mutableStateOf("")
    var codes by mutableStateOf(setOf<String>())
    var initiator by mutableStateOf<String?>(null)

    var traffic by mutableStateOf(Loadable<ApiTrafficPage>())
    /** The server answered 404 or 403: no traffic events here (self-hosted, or a plan without them). */
    var trafficUnavailable by mutableStateOf(false)

    fun loadTraffic(page: Int) {
        val api = host.api ?: return
        traffic = traffic.copy(loading = true, error = null)
        host.scope.launch {
            try {
                traffic = Loadable(api.trafficEvents(TrafficQuery(page = page.coerceAtLeast(1), pageSize = 50)), loadedAt = System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdminApiException.NotFound) {
                trafficUnavailable = true; traffic = Loadable()
            } catch (e: AdminApiException.Forbidden) {
                trafficUnavailable = true; traffic = Loadable()
            } catch (e: Exception) {
                traffic = traffic.copy(loading = false, error = e)
            }
        }
    }

    override fun onCleared() {
        traffic = Loadable()
    }
}

@Composable
private fun EventsScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("events") { EventsState(it) }
    LaunchedEffect(state.tab) {
        if (state.tab == 0) host.load(AdminKeys.AUDIT_EVENTS)
        else if (state.traffic.value == null && !state.trafficUnavailable) state.loadTraffic(1)
    }
    AreaScaffold(
        scope,
        refreshing = if (state.tab == 0) host.data[AdminKeys.AUDIT_EVENTS].loading else state.traffic.loading,
        onRefresh = {
            if (state.tab == 0) host.load(AdminKeys.AUDIT_EVENTS, force = true)
            else { state.trafficUnavailable = false; state.loadTraffic(state.traffic.value?.page ?: 1) }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            SlidingSegmentedChips(
                options = listOf(stringResource(R.string.admin_ev_tab_audit), stringResource(R.string.admin_ev_tab_traffic)),
                selectedIndex = state.tab,
                onOptionSelected = { state.tab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.tab == 0) AuditContent(host, state) else TrafficContent(state)
        }
    }
}

// ================================================================ audit

@Composable
internal fun AuditContent(host: AdminHost, state: EventsState) {
    var open by remember { mutableStateOf<ApiEvent?>(null) }
    var pickingCodes by remember { mutableStateOf(false) }
    var pickingWho by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    LoadableContent(host.data[AdminKeys.AUDIT_EVENTS], onRetry = { host.load(AdminKeys.AUDIT_EVENTS, force = true) }) { listing, stale ->
        val shown = remember(listing, state.codes, state.initiator, state.query) { EventRules.filter(listing.items, state.codes, state.initiator, state.query) }
        val days = remember(shown) { EventRules.byDay(shown) }
        val initiators = remember(listing) { EventRules.initiators(listing.items) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                OutlinedTextField(
                    state.query, { state.query = it },
                    placeholder = { Text(stringResource(R.string.admin_ev_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { state.query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.action_clear)) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "chips") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.codes.isNotEmpty(),
                        onClick = { pickingCodes = true },
                        label = { Text(if (state.codes.isEmpty()) stringResource(R.string.admin_ev_filter_activity) else stringResource(R.string.admin_ev_filter_activity_n, state.codes.size)) },
                        leadingIcon = if (state.codes.isNotEmpty()) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                    FilterChip(
                        selected = state.initiator != null,
                        onClick = { pickingWho = true },
                        label = { Text(state.initiator?.let { id -> initiators.firstOrNull { it.first == id }?.second ?: id } ?: stringResource(R.string.admin_ev_filter_initiator)) },
                        leadingIcon = if (state.initiator != null) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                    if (state.codes.isNotEmpty() || state.initiator != null) FilterChip(
                        selected = false,
                        onClick = { state.codes = emptySet(); state.initiator = null },
                        label = { Text(stringResource(R.string.admin_ev_filter_clear)) },
                    )
                }
            }
            item(key = "incomplete") { IncompleteNote(listing.issues.size) }
            if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.AUDIT_EVENTS, force = true) }) }
            if (shown.isEmpty()) item(key = "empty") {
                val filtered = state.query.isNotBlank() || state.codes.isNotEmpty() || state.initiator != null
                EmptyState(Icons.Default.History, stringResource(if (filtered) R.string.admin_ev_no_match else R.string.admin_ev_empty), Modifier.padding(vertical = 32.dp))
            }
            days.forEach { d ->
                item(key = "day-${d.day}") { DayHeader(d, now) }
                items(d.events, key = { "e-${d.day}-${it.id}-${it.timestamp}" }) { e -> EventRow(e) { open = e } }
            }
        }
        if (pickingCodes) MemberPickerSheet(
            title = stringResource(R.string.admin_ev_filter_activity),
            options = EventRules.codes(listing.items).map { (code, n) -> MemberOption(MemberKind.OTHER, code, code, n.toString()) },
            selected = state.codes,
            onDone = { state.codes = it; pickingCodes = false },
            onDismiss = { pickingCodes = false },
        )
        if (pickingWho) MemberPickerSheet(
            title = stringResource(R.string.admin_ev_filter_initiator),
            options = initiators.map { (id, name) -> MemberOption(MemberKind.USER, id, name, id.takeIf { it != name }.orEmpty()) },
            selected = setOfNotNull(state.initiator),
            single = true,
            onDone = { state.initiator = it.firstOrNull(); pickingWho = false },
            onDismiss = { pickingWho = false },
        )
    }
    open?.let { EventSheet(it, onDismiss = { open = null }) }
}

@Composable
private fun DayHeader(d: EventDay, now: Long) {
    val today = EventRules.dayKey(now)
    val yesterday = EventRules.dayKey(now - 86_400_000L)
    val label = when (d.day) {
        0 -> stringResource(R.string.admin_ev_undated)
        today -> stringResource(R.string.admin_ev_today)
        yesterday -> stringResource(R.string.admin_ev_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(d.start))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label · ${d.events.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EventRow(e: ApiEvent, onClick: () -> Unit) {
    val time = parseRfc3339Millis(e.timestamp)?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        overlineContent = { Text(listOf(time, e.activityCode).filter { it.isNotEmpty() }.joinToString(" · "), fontFamily = FontFamily.Monospace) },
        headlineContent = { Text(e.activity.ifEmpty { e.activityCode }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val changed = EventRules.metaRows(e.meta).filterIsInstance<MetaRow.Changed>().firstOrNull()
            Text(
                listOfNotNull(
                    EventRules.initiator(e).takeIf { it.isNotEmpty() },
                    changed?.let { "${it.before} → ${it.after}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** One entry: who, when, what, and its meta as before → after rows and key/value facts. */
@Composable
private fun EventSheet(e: ApiEvent, onDismiss: () -> Unit) {
    AdminDetailSheet(title = e.activity.ifEmpty { e.activityCode }, subtitle = formatApiDate(e.timestamp), onDismiss = onDismiss) {
        SelectionContainer {
            Column {
                DetailField(stringResource(R.string.admin_ev_code), e.activityCode, mono = true)
                DetailField(stringResource(R.string.admin_ev_initiator), listOf(e.initiatorName, e.initiatorEmail).filter { it.isNotEmpty() }.distinct().joinToString(" · "))
                DetailField(stringResource(R.string.admin_ev_initiator_id), e.initiatorId, mono = true)
                DetailField(stringResource(R.string.admin_ev_target), e.targetId, mono = true)
                val rows = EventRules.metaRows(e.meta)
                if (rows.isNotEmpty()) Text(stringResource(R.string.admin_ev_meta), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                rows.forEach { r ->
                    when (r) {
                        is MetaRow.Changed -> ChangedRow(r)
                        is MetaRow.Plain -> DetailField(r.key, r.value, mono = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedRow(r: MetaRow.Changed) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(r.key.ifEmpty { stringResource(R.string.admin_ev_value) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text(r.before.ifEmpty { "∅" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Text(r.after.ifEmpty { "∅" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

// ================================================================ traffic

@Composable
internal fun TrafficContent(state: EventsState) {
    if (state.trafficUnavailable) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Default.SwapHoriz, stringResource(R.string.admin_ev_traffic_unavailable), Modifier.padding(top = 32.dp))
        }
        return
    }
    LoadableContent(state.traffic, onRetry = { state.loadTraffic(state.traffic.value?.page ?: 1) }) { page, stale ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "incomplete") { IncompleteNote(page.data.issues.size) }
            if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { state.loadTraffic(page.page) }) }
            if (page.data.items.isEmpty()) item(key = "empty") {
                EmptyState(Icons.Default.SwapHoriz, stringResource(R.string.admin_ev_traffic_empty), Modifier.padding(vertical = 32.dp))
            }
            items(page.data.items, key = { it.flowId + it.reporterId + it.windowStart }) { TrafficRow(it) }
            item(key = "pager") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = page.page > 1 && !state.traffic.loading, onClick = { state.loadTraffic(page.page - 1) }) { Text(stringResource(R.string.admin_ev_prev)) }
                    Text(stringResource(R.string.admin_ev_page, page.page, page.totalPages.coerceAtLeast(1), page.totalRecords), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = page.page < page.totalPages && !state.traffic.loading, onClick = { state.loadTraffic(page.page + 1) }) { Text(stringResource(R.string.admin_ev_next)) }
                }
            }
        }
    }
}

private fun protocolName(p: Int): String = when (p) {
    1 -> "ICMP"; 6 -> "TCP"; 17 -> "UDP"; 58 -> "ICMPv6"; else -> p.toString()
}

private fun endpoint(e: io.github.bropines.birdsocks.admin.api.ApiTrafficEndpoint): String =
    listOf(e.name.ifEmpty { e.dnsLabel }, e.address).filter { it.isNotEmpty() }.joinToString(" ")

@Composable
private fun TrafficRow(t: ApiNetworkTrafficEvent) {
    ListItem(
        overlineContent = { Text(listOf(formatApiDate(t.windowStart), protocolName(t.protocol), t.direction).filter { it.isNotEmpty() }.joinToString(" · ")) },
        headlineContent = { Text("${endpoint(t.source)} → ${endpoint(t.destination)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    t.user.name.ifEmpty { t.user.email }.takeIf { it.isNotEmpty() },
                    t.policy.name.takeIf { it.isNotEmpty() },
                    stringResource(R.string.admin_ev_bytes, t.txBytes, t.rxBytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = { if (t.numOfDrops > 0) StatusBadge(stringResource(R.string.admin_ev_dropped), MaterialTheme.colorScheme.errorContainer) },
    )
}

@Preview
@Composable
private fun AuditContentPreview() = BirdSocksTheme {
    val host = remember { PreviewAdminHost(seed = { set(AdminKeys.AUDIT_EVENTS, Loadable(previewEvents(), loadedAt = 1)) }) }
    Surface { AuditContent(host, EventsState(host)) }
}

private fun previewEvents() = Listing(
    listOf(
        ApiEvent("1", "2026-11-14T09:12:00Z", "Peer renamed", "peer.rename", "u-you", "You", "you@example.com", "p1", mapOf("old_name" to "laptop", "name" to "work-laptop")),
        ApiEvent("2", "2026-11-13T18:00:00Z", "Setup key created", "setupkey.add", "u-you", "You", "", "k1", mapOf("name" to "servers")),
    )
)
