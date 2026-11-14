package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Automation
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.UpdateChannel
import io.github.bropines.birdsocks.search.SearchEngine
import io.github.bropines.birdsocks.search.SearchEntry
import io.github.bropines.birdsocks.search.SearchField
import io.github.bropines.birdsocks.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * The search of Settings, between the catalogue (ui/SettingsCatalog.kt), the engine
 * (search/SearchEngine.kt) and the screen (ui/SettingsSearchUi.kt): what is handed to the
 * engine to search in, what a result shows, what was found before, and what opening a
 * result does.
 *
 * English and Russian are searched whatever the interface's language is, and that
 * language with them: a Russian query finds a setting in an English interface, and a
 * translator's keywords work from the day they land.
 */

/** A string of the app by its resource, in one language. */
internal typealias Strings = (Int) -> String

/** The catalogue as the engine takes it: every entry's texts, in each of [languages]. */
internal fun searchEntries(catalog: List<SettingEntry>, languages: List<Strings>): List<SearchEntry> = catalog.map { entry ->
    val texts = ArrayList<Pair<SearchField, String>>()
    for (text in languages) {
        texts += SearchField.TITLE to text(entry.title)
        keywordsOf(text(entry.keywords)).forEach { texts += SearchField.KEYWORD to it }
        if (!entry.isPage) texts += SearchField.PLACE to text(entry.page.title)
        if (entry.card != 0) texts += SearchField.PLACE to text(entry.card)
        texts += SearchField.DESCRIPTION to text(entry.summary)
        if (entry.help != 0) texts += SearchField.DESCRIPTION to text(entry.help)
    }
    SearchEntry(entry.id, texts)
}

/** A keywords string is a list: its entries, between the commas. */
internal fun keywordsOf(list: String): List<String> = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** The little words of a question, in each of [languages]. */
internal fun searchStopWords(languages: List<Strings>): Set<String> =
    languages.flatMapTo(HashSet()) { keywordsOf(it(R.string.search_stop_words)) }

/**
 * What was opened from the search before, most recent first, with how many times: what the
 * empty field offers, and what rises a little among equals. One preference, "id:n;id:n".
 */
internal object SettingsSearchHistory {
    const val KEY = "settings_search_opened"
    private const val KEPT = 12

    fun parse(stored: String): List<Pair<String, Int>> = stored.split(';').mapNotNull { item ->
        val id = item.substringBefore(':').trim()
        val count = item.substringAfter(':', "").trim().toIntOrNull()
        if (id.isEmpty() || count == null || count <= 0) null else id to count
    }

    /** [stored] after [id] was opened once more. */
    fun opened(stored: String, id: String): String {
        val before = parse(stored)
        val count = (before.firstOrNull { it.first == id }?.second ?: 0) + 1
        return (listOf(id to count) + before.filter { it.first != id }).take(KEPT).joinToString(";") { "${it.first}:${it.second}" }
    }

    /** What an entry opened [count] times is given over its equals: enough to pass them, never a better match. */
    fun boost(count: Int): Int = 12 * count.coerceIn(0, 3)
}

/** [Need]s answered from what is stored, the way the sections themselves read it. */
internal class StoredSettingsFacts(private val context: Context) : SettingsFacts {
    override fun met(need: Need): Boolean {
        val tun = GlobalSettings.isTunModeEnabled(context)
        val engine = GlobalSettings.getTunEngine(context)
        return when (need) {
            Need.DAEMON -> NetbirdState.daemon.value == NetbirdState.Daemon.Running
            Need.TUN_MODE -> tun
            Need.TUN_VPN -> tun && engine != GlobalSettings.TUN_ENGINE_ROOT
            Need.TUN_SOCKS -> tun && engine == GlobalSettings.TUN_ENGINE_SOCKS
            Need.CONTROL_PROXY -> GlobalSettings.getControlMode(context) == GlobalSettings.CONTROL_PROXY
            Need.CONTROL_BYEDPI -> GlobalSettings.getControlMode(context) == GlobalSettings.CONTROL_BYEDPI
            Need.AUTOMATION -> Automation.isEnabled(context)
            Need.STATIC_COLORS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !GlobalSettings.isDynamicColorEnabled(context)
            Need.ANDROID_12 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Need.SELF_UPDATE -> UpdateChannel.selfUpdate(context)
        }
    }
}

/** Whether a section is in the hub: Tunnel mode only once it exists. */
internal fun settingsPageShown(page: SettingsPage): Boolean = page != SettingsPage.TUNNEL || TunnelSection.AVAILABLE

/** A result as it is drawn. The marks are the parts of a text the query was found in. */
internal class SettingsSearchRow(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val titleMarks: List<IntRange>,
    /** One line: what the setting does, or why it is not in its section now ([unavailable]). */
    val summary: String,
    val summaryMarks: List<IntRange>,
    val unavailable: Boolean,
    /** Section › card ([TRAIL_SEPARATOR]). */
    val trail: String,
    val trailMarks: List<IntRange>,
    /** What the query was found in, when that is none of the above: a keyword, a title in another language. */
    val matched: String?,
    val matchedMarks: List<IntRange>,
)

/** Between the steps of a result's place: "Tunnel mode › VPN". No language in it. */
internal const val TRAIL_SEPARATOR = " › "

/** A list of results under an optional heading ([heading] is a string resource, or 0). */
internal class SettingsSearchGroup(val heading: Int, val rows: List<SettingsSearchRow>)

/**
 * What the screen shows for [query]. A blank one has the entries found before and a few to
 * start from; another has one group, [closest] when nothing had all the words and [readAs]
 * when the query was understood as typed in the other keyboard layout.
 */
internal class SettingsSearchResults(
    val query: String,
    val groups: List<SettingsSearchGroup>,
    val closest: Boolean = false,
    val readAs: String? = null,
) {
    val first: SettingsSearchRow? get() = groups.firstOrNull { it.rows.isNotEmpty() }?.rows?.first()
    val empty: Boolean get() = groups.all { it.rows.isEmpty() }
}

/** The row a result is opened on: the title it shows, in the section [section]. */
@Stable
internal class SettingTarget(val section: String, title: String, val fallback: String?) {
    /** The title looked for: [fallback] once the first turned out not to be in the section. */
    var title by mutableStateOf(title)
    /** A row of the section answered to [title]. */
    var found by mutableStateOf(false)
    /** That row has been marked, and the mark has faded. */
    var marked by mutableStateOf(false)
}

@Stable
class SettingsSearchState internal constructor(
    private val context: Context,
    private val facts: SettingsFacts,
    private val typed: MutableState<String>,
) {
    var query: String
        get() = typed.value
        set(value) { typed.value = value }

    /** The field has the keyboard. */
    var focused by mutableStateOf(false)

    /** Results, or what the empty field offers, stand in the place of the list of sections. */
    val active: Boolean get() = focused || query.isNotBlank()

    internal var results by mutableStateOf<SettingsSearchResults?>(null)
    internal var target by mutableStateOf<SettingTarget?>(null)

    /** Bumped to search again with the same query: the facts may have changed since. */
    internal var stale by mutableIntStateOf(0)

    internal var onOpen: (String) -> Unit = {}
    private var engine: SearchEngine? = null

    private var list = LazyListState()
    private var listQuery: String? = null

    /** Where the list of results for [query] was left; from its top for another query. */
    internal fun listFor(query: String?): LazyListState {
        if (query != listQuery) {
            listQuery = query
            list = LazyListState()
        }
        return list
    }

    private fun text(resource: Int): String = context.getString(resource)

    private fun history(): List<Pair<String, Int>> =
        SettingsSearchHistory.parse(GlobalSettings.getString(context, SettingsSearchHistory.KEY, ""))

    private fun strings(tag: String): Strings {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        // A renderer that cannot make such a context searches in the interface's language alone.
        val resources = runCatching { context.createConfigurationContext(config).resources }.getOrNull() ?: context.resources
        return { resources.getString(it) }
    }

    /** Builds the engine; some tens of milliseconds, so off the main thread. */
    internal fun prepare(): SearchEngine = engine ?: run {
        val languages = listOf(strings("en"), strings("ru"), ::text)
        SearchEngine(searchEntries(SettingsCatalog.entries, languages), searchStopWords(languages)).also { engine = it }
    }

    private fun place(entry: SettingEntry): SettingPlace? = SettingsCatalog.place(entry, facts, ::settingsPageShown)

    /** What to show for [query]. Reads the stored settings and searches: off the main thread. */
    internal fun find(query: String): SettingsSearchResults {
        val history = history()
        if (query.isBlank()) {
            val recent = history.mapNotNull { SettingsCatalog.byId(it.first) }.take(RECENT).mapNotNull { row(it, null) }
            val suggested = SettingsCatalog.entries.filter { it.suggested }.mapNotNull { row(it, null) }.filter { s -> recent.none { it.id == s.id } }
            return SettingsSearchResults(
                query,
                listOf(SettingsSearchGroup(R.string.search_recent, recent), SettingsSearchGroup(R.string.search_suggested, suggested))
                    .filter { it.rows.isNotEmpty() }
            )
        }
        val opened = history.toMap()
        val engine = prepare()
        val found = engine.search(query, boost = { SettingsSearchHistory.boost(opened[it] ?: 0) })
        // What can be changed now before what cannot: a row that is not in its section gives way to its
        // equals and to the next kind of match, and no further. The sort keeps equals in their order.
        val rows = found.hits
            .mapNotNull { hit ->
                // A keyword or a title in another language is worth showing beside the result; a sentence is not.
                val word = hit.text.takeIf { hit.field != SearchField.DESCRIPTION } ?: ""
                SettingsCatalog.byId(hit.id)?.let { row(it, engine to found, word) }?.let { it to hit.score }
            }
            .sortedByDescending { (row, score) -> if (row.unavailable) score - NOT_THERE else score }
            .map { it.first }
        return SettingsSearchResults(query, listOf(SettingsSearchGroup(0, rows)), found.closest, found.readAs)
    }

    private fun row(entry: SettingEntry, search: Pair<SearchEngine, SearchResult>?, where: String = ""): SettingsSearchRow? {
        val place = place(entry) ?: return null
        fun marks(text: String) = search?.let { (engine, result) -> engine.highlight(text, result) } ?: emptyList()
        val title = text(entry.title)
        // An explanation runs on; the first line of it is what a result has room for.
        val summary = text(if (place.reason != 0) place.reason else entry.summary).substringBefore('\n').trim()
        val trail = place.trail.joinToString(TRAIL_SEPARATOR) { text(it) }
        val titleMarks = marks(title)
        val summaryMarks = if (place.reason != 0) emptyList() else marks(summary)
        val trailMarks = marks(trail)
        val elsewhere = search != null && titleMarks.isEmpty() && summaryMarks.isEmpty() && trailMarks.isEmpty()
        val matched = where.takeIf { elsewhere && it.isNotEmpty() && marks(it).isNotEmpty() }
        return SettingsSearchRow(
            entry.id, entry.page.icon, title, titleMarks, summary, summaryMarks, place.reason != 0,
            trail, trailMarks, matched, matched?.let { marks(it) } ?: emptyList(),
        )
    }

    /**
     * Opens the entry [id] where it is now: its section, with its row to be brought into
     * view and marked. [counted] is false for an opening that is not the user's choice
     * among results — a link.
     */
    fun open(id: String, counted: Boolean = true) {
        val entry = SettingsCatalog.byId(id) ?: return
        // Asked afresh: what was so when the results were drawn may not be any more.
        val place = place(entry) ?: return
        if (counted) {
            val stored = GlobalSettings.getString(context, SettingsSearchHistory.KEY, "")
            GlobalSettings.setString(context, SettingsSearchHistory.KEY, SettingsSearchHistory.opened(stored, id))
        }
        target = if (place.anchor == 0) null else SettingTarget(place.section, text(place.anchor), place.fallback.takeIf { it != 0 }?.let(::text))
        onOpen(place.section)
    }

    private companion object {
        /** How many of the entries found before the empty field offers. */
        const val RECENT = 4

        /** What a row that is not in its section now loses: the step from a title to a keyword (search/SearchEngine.kt). */
        const val NOT_THERE = 300
    }
}

/**
 * The search of the Settings screen it is called from. [open] opens a section by its id.
 * [initialQuery] is a query the search starts with (a birdsocks://settings?q= link).
 */
@Composable
fun rememberSettingsSearch(initialQuery: String?, open: (String) -> Unit): SettingsSearchState {
    val context = LocalContext.current
    val inspected = LocalInspectionMode.current
    val typed = rememberSaveable { mutableStateOf(initialQuery ?: "") }
    val state = remember(context) {
        // A renderer has no stored settings to ask: its sections are those of a fresh install.
        val facts = if (inspected) SettingsFacts { it == Need.STATIC_COLORS || it == Need.ANDROID_12 } else StoredSettingsFacts(context)
        SettingsSearchState(context, facts, typed)
    }
    SideEffect { state.onOpen = open }
    if (inspected) return state

    LaunchedEffect(state) {
        withContext(Dispatchers.Default) { state.prepare() }
        snapshotFlow { state.query to state.stale }.collectLatest { (query, _) ->
            state.results = withContext(Dispatchers.Default) { state.find(query) }
        }
    }
    return state
}
