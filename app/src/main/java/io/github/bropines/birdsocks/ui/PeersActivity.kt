package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.CompactSearchBar
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.PredictiveBackContainer
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbLocalPeer
import io.github.bropines.birdsocks.models.NbPeer
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PeersActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { PeersScreen(onBack = { finish() }) } }
    }
}

/** How often a visible list re-reads the status. */
private const val PEERS_POLL_MS = 3_000L

/** The chips, in order, and the pager page each one is. */
private enum class PeerFilter {
    All, Connected, Connecting, Idle;

    fun admits(peer: NbPeer): Boolean = when (this) {
        All -> true
        Connected -> peer.connected
        Connecting -> peer.connecting
        Idle -> !peer.connected && !peer.connecting
    }
}

private fun NbLocalPeer.matches(query: String) =
    fqdn.contains(query, true) || address.contains(query) || ipv6.contains(query, true)

private fun NbPeer.matches(query: String) =
    fqdn.contains(query, true) || address.contains(query) || ipv6.contains(query, true)

/**
 * The network's peers, this device first, as TailSocks lists its tailnet. The filter chips
 * are pages of a pager: the list swipes between them and the pill follows the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val streamed by NetbirdState.status.collectAsState()
    val daemon by NetbirdState.daemon.collectAsState()
    val profile by NetbirdState.profile.collectAsState()
    val running = daemon == NetbirdState.Daemon.Running

    // The stream speaks when a peer's state moves; byte counters, latency and handshakes move
    // without it, so a visible list re-reads them every few seconds. The last answer wins,
    // whichever of the two it came from.
    var latest by remember { mutableStateOf(streamed) }
    LaunchedEffect(streamed) { latest = streamed }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, lifecycle) {
        if (!running) return@LaunchedEffect
        // Only while the screen is in front: nobody reads a list in the back stack.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { Netbird.status() }.onSuccess { if (NetbirdState.isRunning) latest = it }
                delay(PEERS_POLL_MS)
            }
        }
    }
    var refreshing by remember { mutableStateOf(false) }
    fun refresh() {
        if (refreshing || !NetbirdState.isRunning) return
        refreshing = true
        scope.launch {
            runCatching { Netbird.status() }.onSuccess { if (NetbirdState.isRunning) latest = it }
            refreshing = false
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    val pager = rememberPagerState { PeerFilter.entries.size }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    val status = latest
    val fs = status?.fullStatus
    val lazy = fs?.lazyConnectionEnabled == true
    // Connected first, then connecting, then idle; by name within each.
    val peers = remember(fs?.peers) {
        fs?.peers.orEmpty().sortedWith(compareBy<NbPeer> { it.conn.ordinal }.thenBy { it.shortName.lowercase() })
    }
    val matching = remember(peers, query) { if (query.isBlank()) peers else peers.filter { it.matches(query) } }
    // This device, while it has an address and the search leaves it on screen. The row and
    // the sheet's page turn both read this one value, so a search that hides the row also
    // keeps the sheet from paging onto a device that is not in the list behind it.
    val selfShown = fs?.localPeerState?.takeIf { it.address.isNotEmpty() && (query.isBlank() || it.matches(query)) }
    // A cheap read, done each time: the labels are edited in Settings while this screen
    // waits in the back stack.
    val dnsLabels = GlobalSettings.getDnsLabels(context, profile ?: "default")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // What "since" is measured against: the clock at each status, so rows age between
    // answers rather than on every recomposition.
    val nowMillis = remember(status) { System.currentTimeMillis() }

    /** What the sheet pages through on a filter's page: the list behind it, in its order. */
    fun pagesOf(filter: PeerFilter): List<PeerPage> =
        listOfNotNull(status?.takeIf { selfShown != null && filter == PeerFilter.All }?.let { PeerPage.Self(it, dnsLabels) }) +
            matching.filter(filter::admits).map { PeerPage.Peer(it, lazy) }

    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(topBar = {
            Column {
                AppTopBar(title = stringResource(R.string.nb_menu_peers), onBack = onBack)
                // Nothing to search while the daemon is stopped.
                if (running) {
                    CompactSearchBar(
                        value = query,
                        onValueChange = { query = it },
                        placeholderText = stringResource(R.string.nb_peers_search),
                        modifier = Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }) { padding ->
            // Held to a readable width on a tablet; see ReadableWidth.
            ReadableWidth {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (!running) {
                        DaemonStoppedState(onStarted = { refresh() })
                    } else {
                        if (lazy) {
                            HelpText(stringResource(R.string.nb_peers_lazy), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        SlidingSegmentedChips(
                            options = listOf(
                                stringResource(R.string.nb_peers_filter_all),
                                stringResource(R.string.nb_peers_filter_connected),
                                stringResource(R.string.nb_peers_filter_connecting),
                                stringResource(R.string.nb_peers_filter_idle)
                            ),
                            selectedIndex = pager.currentPage,
                            onOptionSelected = { scope.launch { pager.animateScrollToPage(it) } },
                            positionOffset = pager.currentPage + pager.currentPageOffsetFraction,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = { refresh() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                                val filter = PeerFilter.entries[index]
                                val list = matching.filter(filter::admits)
                                val self = selfShown?.takeIf { filter == PeerFilter.All }
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                                    if (self != null) {
                                        item(key = SELF_PAGE_KEY) {
                                            SelfPeerItem(self, selfConnOf(status)) { openKey = SELF_PAGE_KEY }
                                        }
                                    }
                                    items(list, key = { it.pubKey }) { peer ->
                                        PeerItem(peer, nowMillis) { openKey = peer.pubKey }
                                    }
                                    when {
                                        // Running, but no status has come back yet.
                                        status == null -> item {
                                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator()
                                            }
                                        }
                                        // Not even this device matched: say so, and offer the way back.
                                        query.isNotBlank() && list.isEmpty() && self == null -> item {
                                            EmptyState(
                                                icon = Icons.Default.SearchOff,
                                                text = stringResource(R.string.state_nothing_found),
                                                modifier = Modifier.fillParentMaxSize(),
                                                actionLabel = stringResource(R.string.state_clear_search),
                                                onAction = { query = "" }
                                            )
                                        }
                                        // Under this device's own row, so it takes a margin, not the page.
                                        self != null && peers.isEmpty() -> item {
                                            EmptyState(
                                                icon = Icons.Default.Devices,
                                                text = stringResource(R.string.state_peers_alone),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                                            )
                                        }
                                        list.isEmpty() && self == null -> item {
                                            EmptyState(
                                                icon = Icons.Default.Devices,
                                                text = stringResource(R.string.nb_peers_empty),
                                                modifier = Modifier.fillParentMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        openKey?.let { key ->
            // The sheet pages through the list on screen, so the list stays here and the
            // sheet borrows a window onto it. By key, not by object: every refresh replaces
            // each peer with an equal-looking but unequal instance.
            val pages = pagesOf(PeerFilter.entries[pager.currentPage])
            val index = pages.indexOfFirst { it.key == key }
            // A refresh or a search can take the open page out of the list behind the sheet:
            // then it is the only page there is, as long as the daemon still reports it.
            val alone = if (index >= 0) null else when (key) {
                SELF_PAGE_KEY -> status?.takeIf { it.fullStatus.localPeerState.address.isNotEmpty() }?.let { PeerPage.Self(it, dnsLabels) }
                else -> peers.firstOrNull { it.pubKey == key }?.let { PeerPage.Peer(it, lazy) }
            }
            if (index < 0 && alone == null) {
                // Gone from the network, or the daemon stopped: nothing left to show.
                LaunchedEffect(key) { openKey = null }
            } else {
                PeerDetailsModal(
                    peerAt = { offset -> if (index < 0) alone?.takeIf { offset == 0 } else pages.getOrNull(index + offset) },
                    // From the daemon, not from the row: a search that hides this device's row
                    // does not change which address the connections leave from.
                    selfAddress = fs?.localPeerState?.address.orEmpty(),
                    nowMillis = nowMillis,
                    onDismiss = { openKey = null },
                    onSelectPage = { openKey = it.key }
                )
            }
        }
    }
}
