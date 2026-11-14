package io.github.bropines.birdsocks.admin.areas

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.readableWidth

/**
 * One area of the panel: a tile on the home screen and the screen it opens.
 *
 * To add one: a file `areas/<name>/<Name>Area.kt` with a `val <Name>Area =
 * AdminArea(…)`, and its line in [AdminAreas.all] (replacing the stub).
 *
 * @param id stable: routes, `birdsocks://admin?page=<id>` and saved state use it.
 * @param module the permission module whose READ decides whether the tile shows;
 *   null for an area anyone signed in may open.
 * @param available what else it needs: `{ it.account?.isCloud == true }` for a cloud-only area.
 * @param aliases other `page=` values that open it (old links).
 * @param ready false for a stub: the tile is dimmed and says "coming".
 * @param content the screen, usually an [AreaScaffold]. Its defaults must do nothing, so it previews.
 */
class AdminArea(
    val id: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector,
    val module: String?,
    val available: (AdminHost) -> Boolean = { true },
    val aliases: Set<String> = emptySet(),
    val ready: Boolean = true,
    val content: @Composable (AreaScope) -> Unit,
) {
    /** Whether the home screen offers it to this credential. */
    fun visibleFor(host: AdminHost): Boolean = (module == null || host.canRead(module)) && available(host)
}

/** What an area's screen gets: the host, itself, its route argument and the way back. */
class AreaScope(val host: AdminHost, val area: AdminArea, val arg: String?, val onBack: () -> Unit)

/**
 * An area's frame: the top bar with its title, back and a refresh, a progress
 * line while [refreshing], an optional FAB, the content in readable width.
 * Snackbars and the change gates are drawn by the activity above every area.
 */
@Composable
fun AreaScaffold(
    scope: AreaScope,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                AppTopBar(
                    title = stringResource(scope.area.title),
                    subtitle = subtitle,
                    onBack = scope.onBack,
                    actions = {
                        actions()
                        if (onRefresh != null) {
                            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, stringResource(R.string.admin_refresh)) }
                        }
                    },
                )
                if (refreshing || scope.host.changeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize().readableWidth(), content = content)
        }
    }
}

/** A registered area whose screen is not written yet. */
fun comingArea(id: String, @StringRes title: Int, @StringRes summary: Int, icon: ImageVector, module: String?, available: (AdminHost) -> Boolean = { true }) =
    AdminArea(id, title, summary, icon, module, available = available, ready = false) { scope -> ComingScreen(scope) }

@Composable
private fun ComingScreen(scope: AreaScope) {
    AreaScaffold(scope) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Default.Construction, stringResource(R.string.admin_area_coming), Modifier.padding(top = 48.dp))
            HelpText(stringResource(scope.area.summary), textAlign = TextAlign.Center)
        }
    }
}
