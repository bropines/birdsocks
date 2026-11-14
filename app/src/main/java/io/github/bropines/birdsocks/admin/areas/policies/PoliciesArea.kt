package io.github.bropines.birdsocks.admin.areas.policies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminNote
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.JsonEditor
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Policies: the list, the visual and JSON editors, the whole set as one document, templates, "who can reach". */
val PoliciesArea = AdminArea(
    id = "policies",
    title = R.string.admin_area_policies,
    summary = R.string.admin_area_policies_summary,
    icon = Icons.Default.Policy,
    module = Capabilities.POLICIES,
) { scope -> PoliciesScreen(scope) }

private val KEYS = arrayOf(AdminKeys.POLICIES, AdminKeys.GROUPS, AdminKeys.PEERS, AdminKeys.POSTURE_CHECKS)

@Composable
private fun PoliciesScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("policies") { PoliciesState(it) }
    LaunchedEffect(Unit) {
        host.load(*KEYS)
        scope.arg?.let { state.open(it) }
    }
    val editor = state.editor
    val setLoaded = state.setLoaded
    when {
        editor != null -> PolicyEditorScreen(host, state, editor)
        setLoaded != null -> PolicySetScreen(host, state)
        else -> PolicyListScreen(scope, state)
    }
    state.review?.let { PolicyReviewSheet(host, state, it) }
}

@Composable
private fun PolicyListScreen(scope: AreaScope, state: PoliciesState) {
    val host = scope.host
    var templates by rememberSaveable { mutableStateOf(false) }
    var reach by rememberSaveable { mutableStateOf(false) }
    val canCreate = host.can(Capabilities.POLICIES, Op.CREATE)
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.POLICIES].loading || state.opening,
        onRefresh = { host.load(*KEYS, force = true) },
        actions = {
            IconButton(onClick = { reach = true }) { Icon(Icons.Default.TravelExplore, stringResource(R.string.admin_policy_reach)) }
            if (canCreate) IconButton(onClick = { templates = true }) { Icon(Icons.Default.AutoAwesome, stringResource(R.string.admin_policy_templates)) }
            IconButton(onClick = { state.openSet() }) { Icon(Icons.Default.DataObject, stringResource(R.string.admin_policy_set_open)) }
        },
        floatingActionButton = {
            if (canCreate) ExtendedFloatingActionButton(
                onClick = { state.open(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_policy_new)) },
            )
        },
    ) {
        PolicyListContent(host, state)
    }
    if (templates) PolicyTemplatesSheet(host, state, onDismiss = { templates = false })
    if (reach) WhoCanReachSheet(host, state, onDismiss = { reach = false })
}

@Composable
internal fun PolicyListContent(host: AdminHost, state: PoliciesState) {
    val ctx = LocalContext.current
    val canUpdate = host.can(Capabilities.POLICIES, Op.UPDATE)
    LoadableContent(host.data[AdminKeys.POLICIES], onRetry = { host.load(*KEYS, force = true) }) { listing, stale ->
        val net = remember(listing, host.data[AdminKeys.GROUPS], host.data[AdminKeys.PEERS], state.resources) { state.net() }
        val forms = remember(listing) { listing.items.map(PolicyJson::fromApi) }
        val coverage = remember(forms, net) { forms.associate { (it.id ?: "") to PolicyCoverage.peers(it, net).size } }
        val risky = remember(forms, net) { forms.filter { PolicyLint.findings(it, net).isNotEmpty() }.mapNotNull { it.id }.toSet() }
        AdminList(
            items = forms,
            key = { it.id ?: it.name },
            searchText = { p -> listOf(p.name, p.description) + p.rules.flatMap { r -> (r.sources + r.destinations).map(net::groupName) + r.name } },
            searchHint = stringResource(R.string.admin_policy_search),
            emptyText = stringResource(R.string.admin_policy_empty),
            noMatchText = stringResource(R.string.admin_policy_no_match),
            emptyIcon = Icons.Default.Policy,
            filters = listOf(
                ListFilter("on", stringResource(R.string.admin_policy_filter_on)) { it.enabled },
                ListFilter("off", stringResource(R.string.admin_policy_filter_off)) { !it.enabled },
                ListFilter("risky", stringResource(R.string.admin_policy_filter_risky)) { it.id in risky },
                ListFilter("posture", stringResource(R.string.admin_policy_filter_posture)) { it.postureChecks.isNotEmpty() },
                ListFilter("block", stringResource(R.string.admin_policy_filter_block)) { p -> p.rules.any { !it.isAccept } },
            ),
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() }),
                ListSort("size", stringResource(R.string.admin_policy_sort_coverage), compareByDescending { coverage[it.id ?: ""] ?: 0 }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(*KEYS, force = true) }) } },
        ) { p ->
            val n = coverage[p.id ?: ""] ?: 0
            ListItem(
                modifier = Modifier.clickable { p.id?.let(state::open) },
                headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(PolicyText.summary(ctx, p, net), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pluralStringResource(R.plurals.admin_policy_covers, n, n), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            if (p.postureChecks.isNotEmpty()) StatusBadge(pluralStringResource(R.plurals.admin_policy_posture_count, p.postureChecks.size, p.postureChecks.size), MaterialTheme.colorScheme.secondaryContainer)
                            if (p.id in risky) StatusBadge(stringResource(R.string.admin_policy_badge_risky), MaterialTheme.colorScheme.errorContainer)
                        }
                    }
                },
                trailingContent = {
                    Switch(
                        checked = p.enabled,
                        enabled = canUpdate && !host.changeBusy && state.review == null,
                        onCheckedChange = { on -> state.check(PendingChange.Toggle(p, on)) },
                    )
                },
            )
        }
    }
    if (state.opening) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The whole set as one JSON array; saving splits it into a plan and runs the pipeline. */
@Composable
private fun PolicySetScreen(host: AdminHost, state: PoliciesState) {
    val ctx = LocalContext.current
    val canWrite = host.can(Capabilities.POLICIES, Op.UPDATE) && host.can(Capabilities.POLICIES, Op.CREATE) && host.can(Capabilities.POLICIES, Op.DELETE)
    JsonEditor(
        title = stringResource(R.string.admin_policy_set_title_screen),
        subtitle = pluralStringResource(R.plurals.admin_policy_count, state.setLoaded.orEmpty().size, state.setLoaded.orEmpty().size),
        initial = state.setText,
        readOnly = !canWrite,
        check = { el ->
            val plan = PolicySet.plan(state.setLoaded.orEmpty(), el, state.net())
            plan.problems.firstOrNull()?.let { PolicyText.setProblem(ctx, it) }
        },
        onSave = { text ->
            val plan = state.planSet(text)
            when {
                !plan.ok -> host.notify(AdminNote.text(PolicyText.setProblem(ctx, plan.problems.first())))
                plan.empty -> host.notify(AdminNote.res(R.string.admin_policy_set_nothing))
                else -> {
                    state.setText = text
                    state.check(PendingChange.ApplySet(state.setLoaded.orEmpty(), plan))
                }
            }
        },
        onClose = { state.closeSet() },
    )
}

@Preview
@Composable
private fun PolicyListPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface { PolicyListContent(host, PoliciesState(host)) }
}
