package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/** The filter last settled on, for the next visit. A per-device preference, like the theme. */
private const val PEERS_FILTER_PREF = "peers_filter"

private fun storedPeerFilter(context: Context): PeerFilter {
    val name = GlobalSettings.getString(context, PEERS_FILTER_PREF, PeerFilter.All.name)
    return PeerFilter.entries.firstOrNull { it.name == name } ?: PeerFilter.All
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
    // A preview hands in the network to draw, and maybe the sheet to open; see LocalDemo.
    val demo = LocalDemo.current
    val streamed by NetbirdState.status.collectAsStateOr { it.status }
    val daemon by NetbirdState.daemon.collectAsStateOr { it.daemon }
    val profile by NetbirdState.profile.collectAsStateOr { it.profile }
    val running = daemon == NetbirdState.Daemon.Running

    // The stream speaks when a peer's state moves; byte counters, latency and handshakes move
    // without it, so a visible list re-reads them every few seconds. The last answer wins,
    // whichever of the two it came from.
    var latest by remember { mutableStateOf(streamed) }
    LaunchedEffect(streamed) { latest = streamed }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, lifecycle) {
        if (!running || demo != null) return@LaunchedEffect
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
    val pager = rememberPagerState(initialPage = remember { storedPeerFilter(context).ordinal }) { PeerFilter.entries.size }
    LaunchedEffect(pager) {
        // Where a swipe or a tap settles is kept for the next visit; the first value is only
        // where the pager opened.
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            GlobalSettings.setString(context, PEERS_FILTER_PREF, PeerFilter.entries[page].name)
        }
    }
    var openKey by rememberSaveable { mutableStateOf(demo?.openPeer) }
    // The peer whose "Copy as…" is open, from a long press on its row: its name and forms.
    var copyAs by remember { mutableStateOf<Pair<String, List<PeerCopyForm>>?>(null) }

    // The search field, folded away until a pull at the top of the list or the top bar's
    // button brings it out. A pull no longer reloads: the top bar's button does.
    val searchShown = rememberSaveable { mutableFloatStateOf(if (query.isNotEmpty()) 1f else 0f) }
    val searchSlotPx = with(LocalDensity.current) { SEARCH_SLOT_HEIGHT.toPx() }
    val reveal = remember { SearchReveal(searchShown, scope, searchSlotPx) { query.isNotEmpty() } }
    val searchOut by remember { derivedStateOf { reveal.fraction > 0f } }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Set by the top bar's button: the field takes the keyboard once it is there to take it.
    var focusSearchOnOpen by remember { mutableStateOf(false) }
    // Text typed into a field let go of half-way opens it the rest of the way.
    val searchPinned = query.isNotEmpty()
    LaunchedEffect(searchPinned) { if (searchPinned && reveal.fraction < 1f) reveal.animateTo(1f) }

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
                AppTopBar(
                    title = stringResource(R.string.nb_menu_peers),
                    onBack = onBack,
                    actions = {
                        if (running) {
                            // The search without the pull, for whoever does not know the pull;
                            // once it is out, the one tap that empties and folds it.
                            IconButton(onClick = {
                                if (searchOut) {
                                    query = ""
                                    focusManager.clearFocus()
                                    reveal.animateTo(0f)
                                } else {
                                    focusSearchOnOpen = true
                                    reveal.animateTo(1f)
                                }
                            }) {
                                if (searchOut) Icon(Icons.Default.SearchOff, stringResource(R.string.nb_peers_search_close))
                                else Icon(Icons.Default.Search, stringResource(R.string.nb_peers_search_open))
                            }
                            // The one way to reload, and the sign that a reload is running.
                            IconButton(onClick = { refresh() }, enabled = !refreshing) {
                                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, stringResource(R.string.nb_peers_refresh))
                            }
                        }
                    }
                )
                // Nothing to search while the daemon is stopped. Out of the composition while
                // folded, so neither a screen reader nor the keyboard finds a hidden field.
                if (running && searchOut) {
                    Box(
                        Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                // Measured whole, shown as far as it is out: it slides down from
                                // under the top bar with the finger, fading in as it comes.
                                val placeable = measurable.measure(constraints)
                                reveal.heightPx = placeable.height.toFloat()
                                val height = (placeable.height * reveal.fraction).roundToInt()
                                layout(placeable.width, height) {
                                    placeable.placeWithLayer(0, height - placeable.height) { alpha = reveal.fraction }
                                }
                            }
                    ) {
                        CompactSearchBar(
                            value = query,
                            onValueChange = { query = it },
                            placeholderText = stringResource(R.string.nb_peers_search),
                            modifier = Modifier
                                .readableWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .focusRequester(searchFocus)
                                // A tap on a field let go of half-way opens it the rest of the way.
                                .onFocusChanged { if (it.isFocused) reveal.animateTo(1f) }
                        )
                    }
                    LaunchedEffect(Unit) {
                        if (focusSearchOnOpen) {
                            focusSearchOnOpen = false
                            searchFocus.requestFocus()
                        }
                    }
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
                        // A pull at the top of a list brings out the search, from whichever page is
                        // under the finger.
                        Box(Modifier.fillMaxSize()) {
                            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().nestedScroll(reveal.connection)) { index ->
                                val filter = PeerFilter.entries[index]
                                val list = matching.filter(filter::admits)
                                val self = selfShown?.takeIf { filter == PeerFilter.All }
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                                    if (self != null) {
                                        item(key = SELF_PAGE_KEY) {
                                            SelfPeerItem(
                                                self,
                                                selfConnOf(status),
                                                onLongClick = { copyAs = self.shortName to peerCopyForms(self.shortName, self.fqdn, self.address, self.ipv6Address) }
                                            ) { openKey = SELF_PAGE_KEY }
                                        }
                                    }
                                    items(list, key = { it.pubKey }) { peer ->
                                        PeerItem(
                                            peer,
                                            nowMillis,
                                            onLongClick = { copyAs = peer.shortName to peerCopyForms(peer.shortName, peer.fqdn, peer.address, peer.ipv6Address) }
                                        ) { openKey = peer.pubKey }
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

        copyAs?.let { (title, forms) ->
            PeerCopyAsSheet(title = title, forms = forms, onDismiss = { copyAs = null })
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

/** The search field's slot until a layout measures it: CompactSearchBar's 40 dp and the
 *  8 dp above and below. */
private val SEARCH_SLOT_HEIGHT = 56.dp

/**
 * How far the peers list's search field is out, from 0 to 1, and the scrolling that moves it.
 *
 * A pull down at the top of the list draws it out under the finger; let go, it settles open
 * past half its height and folds back short of that. Empty, it folds again as soon as the
 * list is scrolled on, before the list itself moves, the way a collapsing top bar goes;
 * while [pinned], with text in it, it stays. [shown] is a saveable state, so a rotation
 * leaves the field as it was.
 */
@Stable
private class SearchReveal(
    shown: MutableFloatState,
    private val scope: CoroutineScope,
    /** The field's full height in pixels; the screen's layout keeps it measured. */
    var heightPx: Float,
    private val pinned: () -> Boolean
) {
    var fraction by shown
        private set
    private var settling: Job? = null

    fun animateTo(target: Float) {
        settling?.cancel()
        settling = scope.launch { animate(fraction, target) { value, _ -> fraction = value } }
    }

    /** Moves the field by [delta] pixels, as far as it goes; returns the pixels that took. */
    private fun drag(delta: Float): Float {
        if (heightPx <= 0f) return 0f
        settling?.cancel()
        val before = fraction
        fraction = (before + delta / heightPx).coerceIn(0f, 1f)
        return (fraction - before) * heightPx
    }

    val connection = object : NestedScrollConnection {
        // The list moving on folds the field first.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f && fraction > 0f && !pinned()) Offset(0f, drag(available.y)) else Offset.Zero

        // The pull is what the list could not take at its top. A finger's only: a fling that
        // runs into the top was not asking for the search.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f && source == NestedScrollSource.UserInput && fraction < 1f) Offset(0f, drag(available.y))
            else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (fraction > 0f && fraction < 1f) animateTo(if (fraction >= 0.5f || pinned()) 1f else 0f)
            return Velocity.Zero
        }
    }
}
