package io.github.bropines.birdsocks.admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.EmptyState
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** A chip over a list; active chips AND together. [label] is already resolved. */
class ListFilter<T>(val id: String, val label: String, val test: (T) -> Boolean)

/** A way to order a list; the first of a list's sorts is its default. */
class ListSort<T>(val id: String, val label: String, val comparator: Comparator<T>)

/** What the list shows, apart from Compose so it is tested on the JVM. */
object ListQuery {
    /**
     * [items] whose [searchText] contains every word of [query] (any field,
     * case-insensitive), that pass every filter in [active], in [sort]'s order.
     */
    fun <T> apply(items: List<T>, query: String, searchText: (T) -> List<String>, filters: List<ListFilter<T>>, active: Set<String>, sort: ListSort<T>?): List<T> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val chosen = filters.filter { it.id in active }
        val shown = items.filter { item ->
            val fields = if (words.isEmpty()) emptyList() else searchText(item)
            words.all { w -> fields.any { it.contains(w, ignoreCase = true) } } && chosen.all { it.test(item) }
        }
        return if (sort != null) shown.sortedWith(sort.comparator) else shown
    }
}

/** The list's search, chips and order; saved across rotation. */
class AdminListState(query: String = "", active: Set<String> = emptySet(), sortId: String? = null) {
    var query by mutableStateOf(query)
    var active by mutableStateOf(active)
    var sortId by mutableStateOf(sortId)

    fun toggle(id: String) {
        active = if (id in active) active - id else active + id
    }

    companion object {
        val Saver = listSaver<AdminListState, Any?>(
            save = { listOf(it.query, it.active.toList(), it.sortId) },
            restore = { @Suppress("UNCHECKED_CAST") AdminListState(it[0] as String, (it[1] as List<String>).toSet(), it[2] as String?) },
        )
    }
}

@Composable
fun rememberAdminListState(initialFilters: Set<String> = emptySet()): AdminListState =
    rememberSaveable(saver = AdminListState.Saver) { AdminListState(active = initialFilters) }

/**
 * A searchable list: the search field, the filter chips (AND), an order
 * chooser, the "N could not be read" note, an empty state, then the rows.
 * Strings come in resolved.
 *
 * @param header items drawn after the controls and before the rows (an error card, a banner).
 * @param row one item; give it a stable [key].
 */
@Composable
fun <T> AdminList(
    items: List<T>,
    key: (T) -> Any,
    searchText: (T) -> List<String>,
    searchHint: String,
    emptyText: String,
    noMatchText: String,
    modifier: Modifier = Modifier,
    emptyIcon: ImageVector = Icons.Default.Inbox,
    filters: List<ListFilter<T>> = emptyList(),
    sorts: List<ListSort<T>> = emptyList(),
    state: AdminListState = rememberAdminListState(),
    issues: Int = 0,
    contentPadding: PaddingValues = PaddingValues(bottom = 88.dp),
    header: LazyListScope.() -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    val sort = sorts.firstOrNull { it.id == state.sortId } ?: sorts.firstOrNull()
    val shown = remember(items, state.query, state.active, sort, filters) { ListQuery.apply(items, state.query, searchText, filters, state.active, sort) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "admin-list-search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = { Text(searchHint) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { state.query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.action_clear)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (filters.isNotEmpty() || sorts.size > 1) item(key = "admin-list-chips") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sorts.size > 1 && sort != null) SortChip(sorts, sort) { state.sortId = it }
                filters.forEach { f ->
                    FilterChip(
                        selected = f.id in state.active,
                        onClick = { state.toggle(f.id) },
                        label = { Text(f.label) },
                        leadingIcon = if (f.id in state.active) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                }
            }
        }
        item(key = "admin-list-incomplete") { IncompleteNote(issues) }
        header()
        if (shown.isEmpty()) item(key = "admin-list-empty") {
            val filtered = state.query.isNotBlank() || state.active.isNotEmpty()
            EmptyState(emptyIcon, if (filtered) noMatchText else emptyText, Modifier.padding(vertical = 32.dp))
        }
        items(shown, key = key) { row(it) }
    }
}

@Composable
private fun <T> SortChip(sorts: List<ListSort<T>>, current: ListSort<T>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current.label) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { open = false; onPick(s.id) },
                    leadingIcon = if (s.id == current.id) ({ Icon(Icons.Default.Check, null) }) else null,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AdminListPreview() = BirdSocksTheme {
    Surface {
        data class Row(val name: String, val online: Boolean)
        AdminList(
            items = listOf(Row("laptop", true), Row("phone", false), Row("nas", true)),
            key = { it.name },
            searchText = { listOf(it.name) },
            searchHint = "Search",
            emptyText = "Nothing here",
            noMatchText = "No match",
            emptyIcon = Icons.Default.Devices,
            filters = listOf(ListFilter("online", "Online") { it.online }),
            sorts = listOf(ListSort("name", "Name", compareBy { it.name }), ListSort("online", "Online first", compareByDescending { it.online })),
            issues = 1,
        ) { r -> ListItem(modifier = Modifier.clickable {}, headlineContent = { Text(r.name) }) }
    }
}
