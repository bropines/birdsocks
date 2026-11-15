package io.github.bropines.birdsocks.admin.areas.posture

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.StatusBadge
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Posture checks: list, a visual editor per check type, JSON mode, which policies use each. */
val PostureChecksArea = AdminArea(
    id = "posture_checks",
    title = R.string.admin_area_posture_checks,
    summary = R.string.admin_area_posture_checks_summary,
    icon = Icons.Default.HealthAndSafety,
    module = Capabilities.POLICIES,
    aliases = setOf("posture"),
) { scope -> PostureChecksScreen(scope) }

/** The open editor: the server's copy it came from (null for a new one) and the draft. */
class PostureEditor(val base: PostureForm?, draft: PostureForm) {
    var draft by mutableStateOf(draft)
    var json by mutableStateOf(false)
    val dirty: Boolean get() = base == null || !PostureJson.same(base, draft)
}

class PostureState(host: AdminHost) : AreaState(host) {
    var editor by mutableStateOf<PostureEditor?>(null)
    var opening by mutableStateOf(false)

    /** Opens [id] from a fresh read, so the parts this app does not draw come along. */
    fun open(id: String?) {
        if (id == null) {
            editor = PostureEditor(null, PostureForm(nbVersion = VersionForm()))
            return
        }
        val api = host.api ?: return
        opening = true
        host.scope.launch {
            try {
                val f = PostureChanges.read(api, id)
                editor = PostureEditor(f, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notify(AdminNote.error(e))
            } finally {
                opening = false
            }
        }
    }

    /** The names of the policies that use posture check [id]. */
    fun usedBy(id: String?): List<String> = id?.let { PostureValidation.usedBy(it, host.data.items(AdminKeys.POLICIES)).map { p -> p.name } }.orEmpty()
}

@Composable
private fun PostureChecksScreen(scope: AreaScope) {
    val host = scope.host
    val state = host.areaState("posture_checks") { PostureState(it) }
    LaunchedEffect(Unit) {
        host.load(AdminKeys.POSTURE_CHECKS, AdminKeys.POLICIES)
        scope.arg?.let { state.open(it) }
    }
    val editor = state.editor
    if (editor != null) {
        PostureEditorScreen(host, state, editor)
        return
    }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.POSTURE_CHECKS].loading || state.opening,
        onRefresh = { host.load(AdminKeys.POSTURE_CHECKS, AdminKeys.POLICIES, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.POLICIES, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { state.open(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_posture_new)) },
            )
        },
    ) {
        PostureListContent(host, state)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PostureListContent(host: AdminHost, state: PostureState) {
    val ctx = LocalContext.current
    LoadableContent(host.data[AdminKeys.POSTURE_CHECKS], onRetry = { host.load(AdminKeys.POSTURE_CHECKS, force = true) }) { listing, stale ->
        val forms = remember(listing) { listing.items.map(PostureJson::fromApi) }
        val policies = host.data.items(AdminKeys.POLICIES)
        val used = remember(forms, policies) { forms.associate { (it.id ?: "") to PostureValidation.usedBy(it.id.orEmpty(), policies).size } }
        AdminList(
            items = forms,
            key = { it.id ?: it.name },
            searchText = { listOf(it.name, it.description) + it.types.map { t -> typeName(ctx, t) } },
            searchHint = stringResource(R.string.admin_posture_search),
            emptyText = stringResource(R.string.admin_posture_empty),
            noMatchText = stringResource(R.string.admin_posture_no_match),
            emptyIcon = Icons.Default.HealthAndSafety,
            filters = listOf(
                ListFilter("used", stringResource(R.string.admin_posture_filter_used)) { (used[it.id ?: ""] ?: 0) > 0 },
                ListFilter("unused", stringResource(R.string.admin_posture_filter_unused)) { (used[it.id ?: ""] ?: 0) == 0 },
            ),
            sorts = listOf(
                ListSort("name", stringResource(R.string.admin_sort_name), compareBy { it.name.lowercase() }),
                ListSort("used", stringResource(R.string.admin_posture_sort_used), compareByDescending { used[it.id ?: ""] ?: 0 }),
            ),
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.POSTURE_CHECKS, force = true) }) } },
        ) { f ->
            val n = used[f.id ?: ""] ?: 0
            ListItem(
                modifier = Modifier.clickable { f.id?.let(state::open) },
                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (f.description.isNotBlank()) Text(f.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            f.types.forEach { StatusBadge(typeName(ctx, it), MaterialTheme.colorScheme.secondaryContainer) }
                        }
                        Text(
                            if (n == 0) stringResource(R.string.admin_posture_unused) else pluralStringResource(R.plurals.admin_posture_used_by, n, n),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
            )
        }
    }
}

/** A check type's name: the drawn ones by name, any other by its JSON key. */
internal fun typeName(ctx: Context, key: String): String = when (key) {
    CheckType.NB_VERSION.key -> ctx.getString(R.string.admin_posture_type_nb)
    CheckType.OS_VERSION.key -> ctx.getString(R.string.admin_posture_type_os)
    CheckType.GEO_LOCATION.key -> ctx.getString(R.string.admin_posture_type_geo)
    CheckType.NETWORK_RANGE.key -> ctx.getString(R.string.admin_posture_type_network)
    CheckType.PROCESS.key -> ctx.getString(R.string.admin_posture_type_process)
    "certificate_check" -> ctx.getString(R.string.admin_posture_type_certificate)
    else -> key
}

@Preview
@Composable
private fun PostureListPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface { PostureListContent(host, PostureState(host)) }
}
