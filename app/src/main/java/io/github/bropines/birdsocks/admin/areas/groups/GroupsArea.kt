package io.github.bropines.birdsocks.admin.areas.groups

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Groups: read-only for now (the Groups area work adds create, members, delete). */
val GroupsArea = AdminArea(
    id = "groups",
    title = R.string.admin_area_groups,
    summary = R.string.admin_area_groups_summary,
    icon = Icons.Default.Workspaces,
    module = Capabilities.GROUPS,
) { scope -> GroupsScreen(scope) }

@Composable
private fun GroupsScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.GROUPS) }
    AreaScaffold(scope, refreshing = host.data[AdminKeys.GROUPS].loading, onRefresh = { host.load(AdminKeys.GROUPS, force = true) }) {
        GroupsContent(host)
    }
}

@Composable
internal fun GroupsContent(host: AdminHost) {
    LoadableContent(host.data[AdminKeys.GROUPS], onRetry = { host.load(AdminKeys.GROUPS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { g -> listOf(g.name, g.issued) + g.peers.map { it.name } },
            searchHint = stringResource(R.string.admin_groups_search),
            emptyText = stringResource(R.string.admin_groups_empty),
            noMatchText = stringResource(R.string.admin_groups_no_match),
            emptyIcon = Icons.Default.Workspaces,
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy<ApiGroup> { !it.isAll }.thenBy { it.name.lowercase() }),
                ListSort("size", stringResource(R.string.admin_sort_size), compareByDescending { it.peersCount + it.resourcesCount }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.GROUPS, force = true) }) } },
        ) { g ->
            ListItem(
                leadingContent = { Icon(Icons.Default.Workspaces, null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            pluralStringResource(R.plurals.admin_group_peers, g.peersCount, g.peersCount),
                            if (g.resourcesCount > 0) pluralStringResource(R.plurals.admin_group_resources, g.resourcesCount, g.resourcesCount) else null,
                            g.issued.takeIf { it.isNotEmpty() && it != "api" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                headlineContent = { Text(g.name) },
            )
        }
    }
}

@Preview
@Composable
private fun GroupsContentPreview() = BirdSocksTheme {
    Surface { GroupsContent(PreviewAdminHost.demo()) }
}
