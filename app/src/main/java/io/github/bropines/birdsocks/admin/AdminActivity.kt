package io.github.bropines.birdsocks.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.openUrl
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.DaemonStoppedState
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.readableWidth
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.launch

/**
 * The admin panel: the active account's network through NetBird's management
 * API — an overview, peers, setup keys and groups. A FragmentActivity for
 * BiometricPrompt, which every change goes through ([AdminWriteGate]).
 * Opened through [AdminPanel].
 */
class AdminActivity : FragmentActivity() {
    private val model: AdminViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(AdminPanel.EXTRA_PAGE)
        if (savedInstanceState == null) model.start()
        setContent { BirdSocksTheme { AdminScreen(model, page, onBack = { finish() }) } }
    }
}

private data class AdminTab(val page: String, val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun AdminScreen(model: AdminViewModel, initialPage: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val daemon by NetbirdState.daemon.collectAsState()
    // The credential screen on demand, over a working panel.
    var editingCredential by rememberSaveable { mutableStateOf(initialPage == AdminPanel.PAGE_SETUP) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(daemon) {
        if (daemon == NetbirdState.Daemon.Running && model.phase == AdminViewModel.Phase.NO_DAEMON) model.start()
    }
    model.note?.let { n ->
        val text = n.error?.let { describeError(context, it) } ?: if (n.arg != null) context.getString(n.res, n.arg) else context.getString(n.res)
        LaunchedEffect(n) {
            snackbar.showSnackbar(text, withDismissAction = true)
            model.note = null
        }
    }

    val showSetup = model.phase == AdminViewModel.Phase.SETUP || (editingCredential && model.phase == AdminViewModel.Phase.READY)
    if (showSetup) {
        AdminSetupScreen(
            model = model,
            onBack = { if (editingCredential && model.phase == AdminViewModel.Phase.READY) editingCredential = false else onBack() },
            onSaved = { editingCredential = false }
        )
        return
    }

    val caps = model.caps
    val tabs = buildList {
        add(AdminTab(AdminPanel.PAGE_OVERVIEW, R.string.admin_tab_overview, Icons.Default.Dashboard))
        if (caps?.can(Capabilities.PEERS, Op.READ) != false) add(AdminTab(AdminPanel.PAGE_PEERS, R.string.admin_tab_peers, Icons.Default.Devices))
        if (caps?.can(Capabilities.SETUP_KEYS, Op.READ) != false) add(AdminTab(AdminPanel.PAGE_KEYS, R.string.admin_tab_keys, Icons.Default.Key))
        if (caps?.can(Capabilities.GROUPS, Op.READ) != false) add(AdminTab(AdminPanel.PAGE_GROUPS, R.string.admin_tab_groups, Icons.Default.Workspaces))
    }
    val start = tabs.indexOfFirst { it.page == initialPage }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { tabs.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                AppTopBar(
                    title = stringResource(R.string.admin_title),
                    subtitle = model.account?.let { a ->
                        listOfNotNull(a.host, model.user?.role?.takeIf { it.isNotEmpty() }).joinToString(" · ")
                    },
                    onBack = onBack,
                    actions = {
                        // What the API does not offer is on NetBird's web dashboard.
                        model.account?.dashboardUrl?.let { url ->
                            IconButton(onClick = { openUrl(context, url) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.admin_open_dashboard))
                            }
                        }
                        if (model.phase == AdminViewModel.Phase.READY) {
                            IconButton(onClick = { model.refreshAll() }, enabled = !model.refreshing) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.admin_refresh))
                            }
                            IconButton(onClick = { editingCredential = true }) {
                                Icon(Icons.Default.VpnKey, stringResource(R.string.admin_credential_title))
                            }
                        }
                    }
                )
                if (model.phase == AdminViewModel.Phase.READY && tabs.size > 1) {
                    SlidingSegmentedChips(
                        items = tabs.map { SegmentedChipItem(stringResource(it.title), it.icon) },
                        selectedIndex = pager.currentPage.coerceAtMost(tabs.size - 1),
                        onOptionSelected = { scope.launch { pager.animateScrollToPage(it) } },
                        positionOffset = pager.currentPage + pager.currentPageOffsetFraction,
                        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (model.refreshing || model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (model.phase) {
                AdminViewModel.Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                AdminViewModel.Phase.NO_DAEMON -> DaemonStoppedState(onStarted = { model.start() })
                AdminViewModel.Phase.BAD_URL -> EmptyState(Icons.Default.GppBad, stringResource(R.string.admin_bad_url, model.account?.managementUrl.orEmpty()))
                AdminViewModel.Phase.SETUP -> Unit
                AdminViewModel.Phase.READY -> HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { i ->
                    Box(Modifier.fillMaxSize().readableWidth()) {
                        when (tabs.getOrNull(i)?.page) {
                            AdminPanel.PAGE_OVERVIEW -> OverviewPage(model)
                            AdminPanel.PAGE_PEERS -> PeersPage(model)
                            AdminPanel.PAGE_KEYS -> KeysPage(model)
                            AdminPanel.PAGE_GROUPS -> GroupsPage(model)
                        }
                    }
                }
            }
        }
    }
}

/** A list that did not load: why, and a way to try again. */
@Composable
internal fun ListError(error: Throwable, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(describeError(context, error), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.admin_retry)) }
    }
}
