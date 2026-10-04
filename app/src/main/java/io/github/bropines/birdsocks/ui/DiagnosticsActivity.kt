package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.EventLog
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.PredictiveBackContainer
import io.github.bropines.birdsocks.core.ScrollableSlidingSegmentedChips
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbEvent
import io.github.bropines.birdsocks.models.NbServerState
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The connection's health and NetBird's events, with the diagnostic tools. */
class DiagnosticsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_CONNECTION = 0
        const val PAGE_EVENTS = 1
        fun intent(context: Context, page: Int = PAGE_CONNECTION) =
            Intent(context, DiagnosticsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The history is a file; a notification may open this before anything else has read it.
        EventLog.init(this)
        val page = intent.getIntExtra(EXTRA_PAGE, PAGE_CONNECTION)
        setContent { BirdSocksTheme { DiagnosticsScreen(page, onBack = { finish() }) } }
    }
}

/**
 * A page of [DiagnosticsScreen]: the host draws the title and the switch, and
 * shows the page's top-bar actions while it is the current page.
 */
class DiagnosticsPage(val actions: MutableState<@Composable RowScope.() -> Unit>)

/**
 * Two pages under one title, a switch that follows the swipe: how the
 * connection is — the servers, the relays, what is forwarded here — with the
 * tools to dig further, and what NetBird reported lately.
 */
@Composable
fun DiagnosticsScreen(initialPage: Int, onBack: () -> Unit) {
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    val pages = remember { List(2) { DiagnosticsPage(mutableStateOf({})) } }
    PredictiveBackContainer(onBack = onBack, popsInAppState = false) {
        Scaffold(
            topBar = {
                Column {
                    AppTopBar(
                        title = stringResource(R.string.nb_menu_diagnostics),
                        onBack = onBack,
                        // Read here, so a page publishing its actions redraws only these.
                        actions = { pages[pager.currentPage].actions.value(this) }
                    )
                    SlidingSegmentedChips(
                        items = listOf(
                            SegmentedChipItem(stringResource(R.string.nb_details_title), Icons.Default.NetworkCheck),
                            SegmentedChipItem(stringResource(R.string.nb_events_title), Icons.AutoMirrored.Filled.EventNote)
                        ),
                        selectedIndex = pager.currentPage,
                        onOptionSelected = { scope.launch { pager.animateScrollToPage(it) } },
                        positionOffset = pager.currentPage + pager.currentPageOffsetFraction,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        ) { padding ->
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) { i ->
                if (i == DiagnosticsActivity.PAGE_CONNECTION) ConnectionPage() else EventsPage(pages[i])
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------------------------

/**
 * What the daemon reports about the connection itself. The stream speaks when
 * a state flips; a relay's error or a server's reconnect show sooner in a
 * fresh read, so the page re-reads every 3 s while it is in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPage() {
    val scope = rememberCoroutineScope()
    val daemon by NetbirdState.daemon.collectAsState()
    val streamed by NetbirdState.status.collectAsState()
    var polled by remember { mutableStateOf<NbStatus?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val running = daemon == NetbirdState.Daemon.Running
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, lifecycle) {
        if (!running) {
            polled = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { Netbird.status() }.onSuccess { polled = it }
                delay(3000)
            }
        }
    }
    val fs = if (running) (polled ?: streamed)?.fullStatus else null

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                if (running) runCatching { Netbird.status() }.onSuccess { polled = it }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize().readableWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                // The tools stay below: the logs are what to read when it will not start.
                !running -> item { DaemonStoppedState(onStarted = {}, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
                fs == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
                else -> {
                    item {
                        SettingsCard(stringResource(R.string.nb_details_servers)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ServerRow("Management", fs.managementState)
                                ServerRow("Signal", fs.signalState)
                            }
                        }
                    }
                    item {
                        SettingsCard(stringResource(R.string.nb_details_relays)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (fs.relays.isEmpty()) QuietLine(stringResource(R.string.nb_details_none))
                                fs.relays.forEach { r ->
                                    StateRow(
                                        ok = r.available,
                                        title = r.uri,
                                        detail = listOfNotNull(relayTransport(r.transport), r.error.ifEmpty { null }).joinToString(" · ")
                                    )
                                }
                            }
                        }
                    }
                    item { ForwardingRow(fs.forwardingRules) }
                }
            }
            item { DiagnosticToolsCard(running) }
        }
    }
}

/** How the relay is reached, in the words people know it by. */
private fun relayTransport(raw: String): String? = when (raw.lowercase()) {
    "" -> null
    "quic" -> "QUIC"
    "ws", "wss", "websocket" -> "WebSocket"
    else -> raw
}

/** Ports the network forwards to this device: a count, the rules themselves live on the server. */
@Composable
private fun ForwardingRow(count: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        ListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Filled.AltRoute, null, tint = MaterialTheme.colorScheme.primary) },
            supportingContent = { HelpText(stringResource(R.string.diag_forwarding_desc), tapToExpand = false) },
            trailingContent = {
                Text(
                    if (count > 0) count.toString() else stringResource(R.string.nb_details_none),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(stringResource(R.string.nb_details_forwarding)) }
    }
}

@Composable
private fun ServerRow(name: String, s: NbServerState) {
    StateRow(ok = s.connected, title = name, detail = listOfNotNull(s.url.ifEmpty { null }, s.error.ifEmpty { null }).joinToString("\n"))
}

@Composable
private fun StateRow(ok: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuietLine(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

/** A chip of the events page: [id] is NetBird's category, null for all of them. */
private class EventCategory(val id: String?, val label: Int, val icon: ImageVector)

private val EVENT_CATEGORIES = listOf(
    EventCategory(null, R.string.nb_events_all, Icons.AutoMirrored.Filled.List),
    EventCategory("NETWORK", R.string.nb_events_cat_network, Icons.Default.Lan),
    EventCategory("DNS", R.string.nb_events_cat_dns, Icons.Default.Dns),
    EventCategory("AUTHENTICATION", R.string.nb_events_cat_auth, Icons.Default.Key),
    EventCategory("CONNECTIVITY", R.string.nb_events_cat_connectivity, Icons.Default.SyncAlt),
    EventCategory("SYSTEM", R.string.nb_events_cat_system, Icons.Default.Memory)
)

/** The chip an event belongs under; one in a category the app does not know counts as System. */
private fun categoryOf(e: NbEvent): String =
    e.category.takeIf { c -> EVENT_CATEGORIES.any { it.id == c } } ?: "SYSTEM"

/**
 * What NetBird reported lately: network, DNS, sign-in and connectivity
 * events, newest first. Warnings and worse also arrive as notifications,
 * switched in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventsPage(page: DiagnosticsPage) {
    val scope = rememberCoroutineScope()
    val events by EventLog.events.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val filter = EVENT_CATEGORIES[selected].id
    val shown = remember(events, filter) { events.asReversed().filter { filter == null || categoryOf(it) == filter } }

    val clearLabel = stringResource(R.string.nb_events_clear)
    val hasEvents = events.isNotEmpty()
    SideEffect {
        page.actions.value = {
            IconButton(enabled = hasEvents, onClick = { EventLog.clear() }) { Icon(Icons.Default.ClearAll, clearLabel) }
        }
    }

    Column(Modifier.fillMaxSize().readableWidth()) {
        ScrollableSlidingSegmentedChips(
            items = EVENT_CATEGORIES.map { SegmentedChipItem(stringResource(it.label), it.icon) },
            selectedIndex = selected,
            onOptionSelected = { selected = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            height = 36.dp
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            // The status carries the latest events too: a pull picks up any the stream missed.
            onRefresh = {
                scope.launch {
                    refreshing = true
                    if (NetbirdState.isRunning) runCatching { Netbird.status() }.onSuccess { EventLog.add(it.fullStatus.events) }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (shown.isEmpty()) {
                    item {
                        // In the list, so a pull still refreshes.
                        if (hasEvents) EmptyState(
                            icon = Icons.Default.FilterAltOff,
                            text = stringResource(R.string.state_logs_category_empty, stringResource(EVENT_CATEGORIES[selected].label)),
                            modifier = Modifier.fillParentMaxSize(),
                            actionLabel = stringResource(R.string.state_show_all),
                            onAction = { selected = 0 }
                        ) else EmptyState(
                            icon = Icons.AutoMirrored.Filled.EventNote,
                            text = stringResource(R.string.nb_events_empty),
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                } else {
                    items(shown, key = { it.id }) { EventRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: NbEvent) {
    var open by remember { mutableStateOf(false) }
    val tint = when (e.severity) {
        "CRITICAL", "ERROR" -> MaterialTheme.colorScheme.error
        "WARNING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val more = e.metadata.isNotEmpty() || (e.userMessage.isNotEmpty() && e.message != e.userMessage)
    Card(onClick = { open = !open }, enabled = more, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        disabledContentColor = MaterialTheme.colorScheme.onSurface
    )) {
        ListItem(
            leadingContent = {
                Icon(when (e.severity) {
                    "CRITICAL", "ERROR" -> Icons.Default.Error
                    "WARNING" -> Icons.Default.Warning
                    else -> Icons.Default.Info
                }, null, tint = tint)
            },
            overlineContent = { Text("${stringResource(categoryLabel(categoryOf(e)))} · ${eventTime(e.timestamp)}") },
            trailingContent = if (more) {
                { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else null,
            supportingContent = if (open && more) {
                {
                    Column {
                        if (e.userMessage.isNotEmpty() && e.message != e.userMessage) Text(e.message, style = MaterialTheme.typography.bodySmall)
                        e.metadata.toSortedMap().forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else null,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(e.userMessage.ifEmpty { e.message }) }
    }
}

private fun categoryLabel(c: String): Int = EVENT_CATEGORIES.firstOrNull { it.id == c }?.label ?: R.string.nb_events_cat_system

/** The event's local time; today's without the date. */
private fun eventTime(ts: String?): String {
    val at = parseRfc3339Millis(ts) ?: return ""
    val format = if (DateUtils.isToday(at)) DateFormat.getTimeInstance(DateFormat.MEDIUM)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return format.format(Date(at))
}
