package io.github.bropines.birdsocks.admin.areas.events

import io.github.bropines.birdsocks.admin.api.ApiEvent
import io.github.bropines.birdsocks.ui.parseRfc3339Millis
import java.util.Calendar
import java.util.TimeZone

/** One row of an audit entry's meta: a value that changed, or a plain fact. */
sealed class MetaRow {
    abstract val key: String

    data class Changed(override val key: String, val before: String, val after: String) : MetaRow()
    data class Plain(override val key: String, val value: String) : MetaRow()
}

/** A local day of the log, newest first: [day] is yyyymmdd, [start] its first millisecond. */
data class EventDay(val day: Int, val start: Long, val events: List<ApiEvent>)

/** What the Events area decides without a screen: filters, days, meta. */
object EventRules {

    /** yyyymmdd of [millis] in [zone]. */
    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun dayStart(millis: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** [events] by local day, newest day first and newest first within it; undated entries last. */
    fun byDay(events: List<ApiEvent>, zone: TimeZone = TimeZone.getDefault()): List<EventDay> {
        val dated = events.map { it to (parseRfc3339Millis(it.timestamp) ?: Long.MIN_VALUE) }
        return dated.groupBy { (_, t) -> if (t == Long.MIN_VALUE) 0 else dayKey(t, zone) }
            .map { (day, list) ->
                val sorted = list.sortedByDescending { it.second }
                EventDay(day, if (day == 0) 0 else dayStart(sorted.first().second, zone), sorted.map { it.first })
            }
            .sortedWith(compareBy<EventDay> { it.day == 0 }.thenByDescending { it.day })
    }

    /** Who did it, as a person reads it. */
    fun initiator(e: ApiEvent): String = e.initiatorName.ifEmpty { e.initiatorEmail.ifEmpty { e.initiatorId } }

    /** The entries matching every given filter: activity codes (any of), initiator id, and every word of [query]. */
    fun filter(events: List<ApiEvent>, codes: Set<String>, initiatorId: String?, query: String): List<ApiEvent> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return events.filter { e ->
            (codes.isEmpty() || e.activityCode in codes) &&
                (initiatorId == null || e.initiatorId == initiatorId) &&
                words.all { w ->
                    listOf(e.activity, e.activityCode, e.initiatorName, e.initiatorEmail, e.targetId).any { it.contains(w, true) } ||
                        e.meta.any { (k, v) -> k.contains(w, true) || v.contains(w, true) }
                }
        }
    }

    /** The activity codes present, most frequent first. */
    fun codes(events: List<ApiEvent>): List<Pair<String, Int>> =
        events.groupingBy { it.activityCode }.eachCount().filterKeys { it.isNotEmpty() }.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /** The initiators present as id to name, most frequent first. */
    fun initiators(events: List<ApiEvent>): List<Pair<String, String>> =
        events.filter { it.initiatorId.isNotEmpty() }.groupBy { it.initiatorId }
            .entries.sortedByDescending { it.value.size }.map { (id, list) -> id to initiator(list.first()) }

    private val OLD_PREFIXES = listOf("old_", "previous_", "prev_", "from_")
    private val NEW_PREFIXES = listOf("new_", "to_")
    private val OLD_SUFFIXES = listOf("_old", "_before", "_from")
    private val NEW_SUFFIXES = listOf("_new", "_after", "_to")

    /**
     * An entry's meta as rows: pairs that read as before and after
     * (`old_name`/`new_name`, `name_old`/`name_new`, `old`/`new`, or
     * `old_name` beside a plain `name`) become one Changed row named by
     * their common part; the rest stay key/value, sorted by key.
     */
    fun metaRows(meta: Map<String, String>): List<MetaRow> {
        val used = HashSet<String>()
        val changed = mutableListOf<MetaRow.Changed>()
        fun pair(base: String, oldKey: String, newKey: String) {
            val o = meta[oldKey] ?: return
            val n = meta[newKey] ?: return
            if (oldKey in used || newKey in used) return
            used += oldKey; used += newKey
            changed += MetaRow.Changed(base, o, n)
        }
        if ("old" in meta && "new" in meta) pair("", "old", "new")
        for (k in meta.keys.sorted()) {
            OLD_PREFIXES.firstOrNull { k.startsWith(it) }?.let { p ->
                val base = k.removePrefix(p)
                NEW_PREFIXES.forEach { np -> pair(base, k, np + base) }
                pair(base, k, base)
            }
            OLD_SUFFIXES.firstOrNull { k.endsWith(it) }?.let { s ->
                val base = k.removeSuffix(s)
                NEW_SUFFIXES.forEach { ns -> pair(base, k, base + ns) }
                pair(base, k, base)
            }
        }
        val plain = meta.filterKeys { it !in used }.toSortedMap().map { (k, v) -> MetaRow.Plain(k, v) }
        return changed.sortedBy { it.key } + plain
    }
}
