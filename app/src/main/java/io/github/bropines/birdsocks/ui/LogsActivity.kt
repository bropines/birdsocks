package io.github.bropines.birdsocks.ui
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.birdsocks.core.*
import io.github.bropines.birdsocks.models.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

/**
 * One log line as the Go bridge reports it. [unix] is epoch milliseconds and
 * the key the merged list is ordered by; [timestamp] is the `HH:MM:SS` shown.
 */
@Serializable
data class LogEntry(
    @SerialName("unix") val unix: Long = 0L,
    @SerialName("timestamp") val timestamp: String = "",
    @SerialName("level") val level: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("message") val message: String = ""
)

class LogsActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_CATEGORY = "category"

        /** The Logs screen with one category picked, e.g. NETBIRD. */
        fun intent(context: Context, category: String): Intent =
            Intent(context, LogsActivity::class.java).putExtra(EXTRA_CATEGORY, category)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "ALL"
        setContent {
            BirdSocksTheme {
                LogsScreen(onBack = { finish() }, initialCategory = category)
            }
        }
    }
}

/** What a pasted log starts with: enough to tell builds and devices apart. */
fun getDebugHeader(context: Context): String = buildString {
    appendLine("BirdSocks ${io.github.bropines.birdsocks.BuildConfig.VERSION_NAME}, NetBird ${runCatching { Appctr.coreVersion() }.getOrDefault("?")}")
    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    appendLine("Daemon: ${io.github.bropines.birdsocks.core.NetbirdState.daemon.value}, ${io.github.bropines.birdsocks.core.NetbirdState.status.value?.status ?: "no status"}")
    appendLine()
}

/**
 * The category of the daemon's own lines, its output as the bridge files it
 * (appctr/core.go). CORE is the app. Clearing works by this split: the
 * daemon's lines, the app's, or everything.
 */
private const val DAEMON_CATEGORY = "NETBIRD"

/** What one Clear removes. */
private enum class ClearScope { ALL, APP, DAEMON }

/** Preference: whether the Logs screen also shows the process's own logcat. */
private const val LOGCAT_PREF = "logs_include_logcat"
private const val LOGCAT_CATEGORY = "LOGCAT"

/**
 * The app's own logcat: what the Kotlin side writes with android.util.Log, plus
 * the platform's lines for this process. An app may read its own without any
 * permission; `--pid` keeps it to this process. Optional, because Compose and
 * the platform are chatty, and off by default.
 */
private object LogcatSource {
    private const val MAX_LINES = 600

    /** `-v epoch`: `1757340000.123  pid  tid L Tag: message` */
    private val lineRegex = Regex("""^\s*(\d+)\.(\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*:\s?(.*)$""")

    fun rawText(): String = try {
        val pid = android.os.Process.myPid()
        val proc = ProcessBuilder("logcat", "-d", "-v", "epoch", "--pid=$pid", "-t", MAX_LINES.toString())
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        text
    } catch (e: Exception) {
        ""
    }

    /** Entries newer than [since] (epoch millis); the level follows logcat's priority letter. */
    fun entries(since: Long): List<LogEntry> {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val out = ArrayList<LogEntry>()
        for (line in rawText().lineSequence()) {
            val m = lineRegex.matchEntire(line) ?: continue
            val unix = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
            if (unix <= since) continue
            val level = when (m.groupValues[3]) {
                "E", "F" -> "ERROR"
                "W" -> "WARN"
                else -> "INFO"
            }
            out += LogEntry(
                unix = unix,
                timestamp = time.format(Date(unix)),
                level = level,
                category = LOGCAT_CATEGORY,
                message = "${m.groupValues[4]}: ${m.groupValues[5]}"
            )
        }
        return out
    }
}

/** One row of the list: an entry, or the divider that opens a new day. */
private sealed interface LogRow {
    data class Entry(val log: LogEntry) : LogRow
    data class Day(val label: String) : LogRow
}

/**
 * Inserts a day divider before the first entry of each day, but only when the
 * list spans more than one day. Every line shows just `HH:MM:SS`, and the Go
 * buffer lives as long as the app process — with the phone on for two days,
 * yesterday's "16:43" and today's "11:27" are otherwise indistinguishable.
 */
private fun withDayDividers(logs: List<LogEntry>, today: String, yesterday: String): List<LogRow> {
    if (logs.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    fun dayOf(unix: Long): Long {
        cal.timeInMillis = unix
        return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
    }
    val days = HashSet<Long>()
    for (log in logs) if (log.unix != 0L) days.add(dayOf(log.unix))
    if (days.size < 2) return logs.map { LogRow.Entry(it) }

    val now = System.currentTimeMillis()
    val todayDay = dayOf(now)
    val yesterdayDay = dayOf(now - 86_400_000L)
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val rows = ArrayList<LogRow>(logs.size + days.size)
    var lastDay = -1L
    for (log in logs) {
        if (log.unix != 0L) {
            val day = dayOf(log.unix)
            if (day != lastDay) {
                lastDay = day
                rows.add(LogRow.Day(when (day) {
                    todayDay -> today
                    yesterdayDay -> yesterday
                    else -> dateFormat.format(Date(log.unix))
                }))
            }
        }
        rows.add(LogRow.Entry(log))
    }
    return rows
}

/**
 * An entry longer than [FOLD_OVER_LINES] lines — a netmap dump, a panic — is
 * shown as its first [FOLDED_LINES] lines until tapped, so one dump does not
 * push a screen of real lines out of view.
 */
private const val FOLD_OVER_LINES = 6
private const val FOLDED_LINES = 3

/** Identity of an entry across refreshes, for the set of unfolded ones. */
private fun foldKey(log: LogEntry): Long = log.unix * 31 + log.message.hashCode()

private const val LOGCAT_SECTION_HEADER = "\n--- LOGCAT (this process) ---\n"

/**
 * Upper bound on what Copy hands to the clipboard. A ClipData travels in a
 * single Binder transaction (about 1 MB, strings as UTF-16), so a daemon log
 * of a few hundred KB made setPrimaryClip fail and nothing was copied at all.
 */
private const val CLIPBOARD_LOG_LIMIT = 400 * 1024

private const val CLIPBOARD_TRUNCATED_MARKER =
    "[... log is large: only the tail is on the clipboard; Save exports the complete log ...]\n"

/** Everything, for Save: the debug header, the Go buffer and the whole daemon log. */
fun buildFullLogString(context: Context): String {
    val header = getDebugHeader(context)
    val goLogs = try { Appctr.getLogs() } catch (e: Exception) { "" }
    val logcat = if (GlobalSettings.getBoolean(context, LOGCAT_PREF, false)) LOGCAT_SECTION_HEADER + LogcatSource.rawText() else ""
    return header + goLogs + logcat
}

/** Drops the beginning of [text] so that at most [maxChars] remain, cutting at a line boundary. */
private fun cutToTail(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.length - maxChars.coerceAtLeast(0)
    val nl = text.indexOf('\n', cut)
    return if (nl >= 0) text.substring(nl + 1) else text.substring(cut)
}

/**
 * Text for the clipboard: like [buildFullLogString] but capped at
 * [CLIPBOARD_LOG_LIMIT] characters. The debug header is always kept; the Go
 * buffer gets at most half of the remaining budget and the daemon log the
 * rest, each cut from the front at a line boundary so only whole lines are
 * pasted. The daemon file is read as a tail, never whole.
 *
 * @return the text and whether anything was left out.
 */
fun buildClipboardLogString(context: Context): Pair<String, Boolean> {
    val header = getDebugHeader(context)
    val budget = CLIPBOARD_LOG_LIMIT - header.length - CLIPBOARD_TRUNCATED_MARKER.length
    var goLogs = try { Appctr.getLogs() } catch (e: Exception) { "" }
    val truncated = goLogs.length > budget
    if (truncated) goLogs = cutToTail(goLogs, budget)
    val text = buildString {
        append(header)
        if (truncated) append(CLIPBOARD_TRUNCATED_MARKER)
        append(goLogs)
    }
    return text to truncated
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit, initialCategory: String = "ALL") {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // The preview renderer has no native bridge to read logs from: there the screen counts as
    // loaded and empty, and draws the state it would with an empty buffer.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    // Whether a read has come back, so the empty state does not flash before the first one.
    var loaded by remember { mutableStateOf(inPreview) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var searchQuery by remember { mutableStateOf("") }
    var unfolded by remember { mutableStateOf(emptySet<Long>()) }
    var clearMenuOpen by remember { mutableStateOf(false) }
    var includeLogcat by remember { mutableStateOf(GlobalSettings.getBoolean(context, LOGCAT_PREF, false)) }
    // Clearing cannot empty logcat itself (that is the system's buffer); it hides what came before.
    var logcatSince by remember { mutableStateOf(0L) }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val categoryItems = remember(includeLogcat) {
        val list = mutableListOf(
            SegmentedChipItem("ALL", Icons.AutoMirrored.Filled.List),
            SegmentedChipItem("ERROR", Icons.Default.Error, containerColor = Color(0xFFEF5350).copy(alpha = 0.25f), contentColor = Color(0xFFEF5350)),
            SegmentedChipItem("CORE", Icons.Default.Memory, containerColor = Color(0xFF42A5F5).copy(alpha = 0.25f), contentColor = Color(0xFF1E88E5)),
            SegmentedChipItem("NETBIRD", Icons.Default.VpnLock, containerColor = Color(0xFF66BB6A).copy(alpha = 0.25f), contentColor = Color(0xFF43A047))
        )
        if (includeLogcat) {
            list.add(SegmentedChipItem(LOGCAT_CATEGORY, Icons.Default.BugReport, containerColor = Color(0xFF26A69A).copy(alpha = 0.25f), contentColor = Color(0xFF26A69A)))
        }
        list.toList()
    }
    val categories = remember(categoryItems) { categoryItems.map { it.title } }

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = when (selectedCategory) {
                "ALL" -> true
                // An error is a level as much as a category: the daemon file's
                // lines are all ROOT and the app's own logAndroid("ERROR", "CORE", …)
                // are CORE, and the ERROR chip used to show neither.
                "ERROR" -> log.category == "ERROR" || log.level == "ERROR"
                else -> log.category == selectedCategory
            }
            val matchQuery = searchQuery.isEmpty() || log.message.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }
    val todayLabel = stringResource(R.string.logs_day_today)
    val yesterdayLabel = stringResource(R.string.logs_day_yesterday)
    val rows = remember(displayedLogs, todayLabel, yesterdayLabel) {
        withDayDividers(displayedLogs, todayLabel, yesterdayLabel)
    }

    var showBundle by remember { mutableStateOf(false) }
    if (showBundle) DebugBundleDialog(onDismiss = { showBundle = false })

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fullLog = buildFullLogString(context)
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(fullLog) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.logs_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData(manual: Boolean = false) {
        if (manual) isRefreshing = true
        coroutineScope.launch(Dispatchers.IO) {
            var jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            var logsList: List<LogEntry> = if (jsonString.isBlank()) emptyList()
                else runCatching { AppJson.decodeFromString<List<LogEntry>>(jsonString) }.getOrDefault(emptyList())

            if (includeLogcat) {
                val lines = LogcatSource.entries(logcatSince)
                if (lines.isNotEmpty()) logsList = (logsList + lines).sortedBy { it.unix }
            }

            withContext(Dispatchers.Main) {
                allLogs = logsList
                loaded = true
                if (manual) isRefreshing = false
            }
        }
    }

    /**
     * One Clear for both sources. The daemon's lines live in the Go buffer in
     * Proxy mode and in the root-owned file in Root Mode; the app's only in the
     * buffer. Two buttons with different, unstated scopes (the whole buffer plus
     * the file, or the file alone) used to do this.
     */
    fun clearLogs(scope: ClearScope) {
        coroutineScope.launch(Dispatchers.IO) {
            val fileOk = true
            when (scope) {
                ClearScope.ALL -> Appctr.clearLogs()
                ClearScope.APP -> Appctr.clearLogsWhere(DAEMON_CATEGORY, true)
                ClearScope.DAEMON -> Appctr.clearLogsWhere(DAEMON_CATEGORY, false)
            }
            if (scope != ClearScope.DAEMON) logcatSince = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                allLogs = when (scope) {
                    ClearScope.ALL -> if (fileOk) emptyList() else allLogs.filter { it.category == DAEMON_CATEGORY }
                    ClearScope.APP -> allLogs.filter { it.category == DAEMON_CATEGORY }
                    ClearScope.DAEMON -> if (fileOk) allLogs.filter { it.category != DAEMON_CATEGORY } else allLogs
                }
                val message = if (fileOk) R.string.logs_cleared else R.string.logs_root_clear_failed
                Toast.makeText(context, context.getString(message), if (fileOk) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (inPreview) return@LaunchedEffect
        while (true) {
            loadLogsData()
            delay(2000)
        }
    }

    // Follow the tail only while the reader is at the tail. isAutoScroll used to
    // be set once and never cleared, so every refresh tick (2 s) yanked the list
    // back to the bottom while the user was reading further up — "the log is
    // stuck at the bottom". A manual scroll away from the end switches following
    // off; scrolling back to the end, or the arrow button, switches it on again.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) isAutoScroll = isAtBottom
    }

    LaunchedEffect(rows.size) {
        if (isAutoScroll && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(
            topBar = {
                Column {
                    AppTopBar(
                        title = stringResource(R.string.logs_title),
                        onBack = onBack,
                        actions = {
                            IconButton(onClick = {
                                // Calls JNI — off the main thread.
                                coroutineScope.launch(Dispatchers.IO) {
                                    val (text, truncated) = buildClipboardLogString(context)
                                    withContext(Dispatchers.Main) {
                                        try {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("BirdSocks Logs", text))
                                            if (truncated) {
                                                Toast.makeText(context, context.getString(R.string.logs_copied_tail), Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                            
                            IconButton(onClick = { saveFileLauncher.launch("birdsocks_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save)) }
                            IconButton(onClick = { showBundle = true }) { Icon(Icons.Default.Inventory2, contentDescription = stringResource(R.string.nb_bundle_title)) }

                            IconButton(onClick = {
                                includeLogcat = !includeLogcat
                                GlobalSettings.setBoolean(context, LOGCAT_PREF, includeLogcat)
                                if (!includeLogcat && selectedCategory == LOGCAT_CATEGORY) selectedCategory = "ALL"
                            }) {
                                Icon(
                                    Icons.Default.BugReport,
                                    contentDescription = stringResource(R.string.logs_cd_logcat),
                                    tint = if (includeLogcat) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                        }
                    )
                    
                    CompactSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholderText = stringResource(R.string.logs_search_placeholder),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    val selectedCategoryIndex = categories.indexOf(selectedCategory).coerceAtLeast(0)
                    ScrollableSlidingSegmentedChips(
                        items = categoryItems,
                        selectedIndex = selectedCategoryIndex,
                        onOptionSelected = { idx ->
                            selectedCategory = categories[idx]
                            isAutoScroll = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        height = 36.dp
                    )
                }
            },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Back to the live tail after reading further up; following resumes.
                AnimatedVisibility(visible = !isAutoScroll && rows.isNotEmpty()) {
                    SmallFloatingActionButton(onClick = {
                        isAutoScroll = true
                        coroutineScope.launch { listState.scrollToItem(rows.size - 1) }
                    }) { Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.logs_cd_jump_to_end)) }
                }
                // The scopes slide out beside the button instead of a menu: one
                // tap opens, the next clears and folds them back.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(
                        visible = clearMenuOpen,
                        enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((scope, label) in listOf(
                                ClearScope.ALL to R.string.logs_clear_all,
                                ClearScope.APP to R.string.logs_clear_app,
                                ClearScope.DAEMON to R.string.logs_clear_daemon
                            )) {
                                FilledTonalButton(
                                    onClick = { clearMenuOpen = false; clearLogs(scope) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    }
                    FloatingActionButton(onClick = { clearMenuOpen = !clearMenuOpen }) {
                        Icon(
                            if (clearMenuOpen) Icons.Default.Close else Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_clear)
                        )
                    }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadLogsData(true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            if (loaded && rows.isEmpty()) {
                // In a list, so a pull still refreshes. A filter that hides everything says
                // which one and offers to lift it; an empty buffer just says it is empty.
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val fill = Modifier.fillParentMaxSize()
                        when {
                            allLogs.isNotEmpty() && searchQuery.isNotEmpty() -> EmptyState(
                                icon = Icons.Default.SearchOff,
                                text = stringResource(R.string.state_nothing_found),
                                modifier = fill,
                                actionLabel = stringResource(R.string.state_clear_search),
                                onAction = { searchQuery = "" }
                            )
                            allLogs.isNotEmpty() && selectedCategory != "ALL" -> EmptyState(
                                icon = Icons.Default.FilterAltOff,
                                text = stringResource(R.string.state_logs_category_empty, selectedCategory),
                                modifier = fill,
                                actionLabel = stringResource(R.string.state_show_all),
                                onAction = { selectedCategory = "ALL" }
                            )
                            else -> EmptyState(
                                icon = Icons.Default.Description,
                                text = stringResource(R.string.state_logs_empty),
                                modifier = fill
                            )
                        }
                    }
                }
            } else SelectionContainer {
                LazyColumn(
                    state = listState,
                    // Room under the last line for the buttons, which otherwise cover the tail.
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 4f) }
                    }
                ) {
                    items(rows, contentType = { it::class }) { row ->
                        when (row) {
                            is LogRow.Day -> DayDivider(row.label)
                            is LogRow.Entry -> {
                                val key = foldKey(row.log)
                                LogEntryRow(
                                    log = row.log,
                                    scale = scale,
                                    expanded = key in unfolded,
                                    onToggle = { unfolded = if (key in unfolded) unfolded - key else unfolded + key }
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

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogEntryRow(log: LogEntry, scale: Float, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.primary
    val lineCount = remember(log.message) { log.message.count { it == '\n' } + 1 }
    val foldable = lineCount > FOLD_OVER_LINES
    val text = remember(log, defaultColor, expanded) {
        val shown = if (foldable && !expanded) log.message.lineSequence().take(FOLDED_LINES).joinToString("\n") else log.message
        val body = highlightLogMessage(log.timestamp, log.category, shown, defaultColor)
        if (!foldable) body else buildAnnotatedString {
            append(body)
            withStyle(SpanStyle(color = hintColor, fontStyle = FontStyle.Italic)) {
                append('\n')
                append(
                    if (expanded) context.getString(R.string.logs_collapse)
                    else context.getString(R.string.logs_expand_more, lineCount - FOLDED_LINES)
                )
            }
        }
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = (12 * scale).sp,
        modifier = Modifier
            .then(if (foldable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 2.dp)
    )
}

/**
 * NetBird's debug bundle: the daemon's logs, status, routes and system
 * details in one archive — uploaded to NetBird for support (the key goes to
 * the clipboard) or saved as a zip. Anonymized by default.
 */
@Composable
private fun DebugBundleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var anonymize by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var bundlePath by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val path = bundlePath ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> java.io.File(path).inputStream().use { it.copyTo(out) } }
            }.isSuccess
            withContext(Dispatchers.Main) { result = context.getString(if (ok) R.string.logs_saved else R.string.nb_bundle_failed, "") }
        }
    }

    fun build(upload: Boolean) {
        busy = true
        result = null
        scope.launch {
            runCatching { io.github.bropines.birdsocks.core.Netbird.debugBundle(anonymize, upload) }
                .onSuccess { b ->
                    bundlePath = b.path
                    when {
                        upload && b.uploadedKey.isNotEmpty() -> {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NetBird debug key", b.uploadedKey))
                            result = context.getString(R.string.nb_bundle_uploaded, b.uploadedKey)
                        }
                        upload -> result = context.getString(R.string.nb_bundle_failed, b.uploadFailureReason)
                        else -> save.launch("netbird-debug-${System.currentTimeMillis()}.zip")
                    }
                }
                .onFailure { result = context.getString(R.string.nb_bundle_failed, it.message ?: "") }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.nb_bundle_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nb_bundle_desc), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = anonymize, onCheckedChange = { anonymize = it }, enabled = !busy)
                    Text(stringResource(R.string.nb_bundle_anonymize))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { build(upload = true) }) { Text(stringResource(R.string.nb_bundle_upload)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { build(upload = false) }) { Text(stringResource(R.string.nb_bundle_save)) } }
    )
}
