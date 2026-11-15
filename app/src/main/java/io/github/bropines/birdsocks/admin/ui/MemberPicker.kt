package io.github.bropines.birdsocks.admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** What a picker offers. [OTHER] is for anything else an area lists (posture checks, networks). */
enum class MemberKind(val icon: ImageVector) {
    PEER(Icons.Default.Devices),
    GROUP(Icons.Default.Workspaces),
    USER(Icons.Default.Person),
    OTHER(Icons.AutoMirrored.Filled.Label),
}

/** One choice: [id] goes back to the caller, [label] and [detail] are shown and searched. */
data class MemberOption(val kind: MemberKind, val id: String, val label: String, val detail: String = "", val enabled: Boolean = true)

/** Options from the API's lists. */
object MemberOptions {
    fun peers(peers: List<ApiPeer>): List<MemberOption> =
        peers.sortedBy { it.name.lowercase() }.map { MemberOption(MemberKind.PEER, it.id, it.name, listOf(it.ip, it.os).filter(String::isNotEmpty).joinToString(" · ")) }

    /** Groups; "All" last-but-listed, integration-issued ones marked by their origin. */
    fun groups(groups: List<ApiGroup>, includeAll: Boolean = true): List<MemberOption> =
        groups.filter { includeAll || !it.isAll }
            .sortedWith(compareBy<ApiGroup> { it.isAll }.thenBy { it.name.lowercase() })
            .map { MemberOption(MemberKind.GROUP, it.id, it.name, it.issued.takeIf { i -> i.isNotEmpty() && i != "api" }.orEmpty()) }

    fun users(users: List<ApiUser>): List<MemberOption> =
        users.sortedBy { it.name.ifEmpty { it.email }.lowercase() }
            .map { MemberOption(MemberKind.USER, it.id, it.name.ifEmpty { it.email.ifEmpty { it.id } }, listOf(it.email, it.role).filter(String::isNotEmpty).joinToString(" · ")) }

    /** The labels of [ids] as [options] name them; an id not among them is shown as it is. */
    fun labels(options: List<MemberOption>, ids: Collection<String>): List<String> {
        val byId = options.associateBy { it.id }
        return ids.map { byId[it]?.label ?: it }
    }
}

/**
 * The members of something as chips, with an Edit that opens the picker —
 * a group's peers, a policy's sources, a key's auto groups. [label] and
 * [emptyText] come in resolved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberField(
    label: String,
    options: List<MemberOption>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    emptyText: String = "",
    pickerTitle: String = label,
    enabled: Boolean = true,
    single: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val byId = remember(options) { options.associateBy { it.id } }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (enabled) IconButton(onClick = { picking = true }) { Icon(Icons.Default.Edit, stringResource(R.string.action_edit)) }
        }
        if (selected.isEmpty()) {
            if (emptyText.isNotEmpty()) Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { id ->
                    val o = byId[id]
                    InputChip(
                        selected = false,
                        onClick = { if (enabled) picking = true },
                        label = { Text(o?.label ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon((o?.kind ?: MemberKind.OTHER).icon, null, Modifier.size(16.dp)) },
                        trailingIcon = if (enabled) ({
                            Icon(Icons.Default.Clear, stringResource(R.string.action_clear), Modifier.size(16.dp).clickable { onChange(selected - id) })
                        }) else null,
                    )
                }
            }
        }
    }
    if (picking) {
        MemberPickerSheet(
            title = pickerTitle,
            options = options,
            selected = selected.toSet(),
            single = single,
            onDone = { picked ->
                picking = false
                // Keep the order the caller had, new ones after.
                onChange(selected.filter { it in picked } + picked.filter { it !in selected })
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Picks from peers, groups, users or anything else, with a search and, when
 * there is more than one kind, a chip per kind. [single] picks one and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberPickerSheet(
    title: String,
    options: List<MemberOption>,
    selected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    single: Boolean = false,
) {
    val parent = rememberParentLocals()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        parent.Provide { MemberPickerContent(title, options, selected, onDone, single) }
    }
}

/** The picker's body, apart so a preview can draw it without a sheet. */
@Composable
fun MemberPickerContent(title: String, options: List<MemberOption>, selected: Set<String>, onDone: (Set<String>) -> Unit, single: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<MemberKind?>(null) }
    var chosen by rememberSaveable { mutableStateOf(selected.toList()) }
    val kinds = remember(options) { options.map { it.kind }.distinct() }
    val shown = remember(options, query, kind) {
        options.filter { o -> (kind == null || o.kind == kind) && (query.isBlank() || o.label.contains(query, true) || o.detail.contains(query, true) || o.id.equals(query.trim(), true)) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (!single) Button(onClick = { onDone(chosen.toSet()) }) { Text(stringResource(R.string.admin_picker_done, chosen.size)) }
        }
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(stringResource(R.string.admin_picker_search)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (kinds.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = if (kind == k) null else k },
                        label = { Text(stringResource(kindLabel(k))) },
                        leadingIcon = { Icon(k.icon, null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
        if (!single && chosen.isNotEmpty()) {
            TextButton(onClick = { chosen = emptyList() }) { Text(stringResource(R.string.admin_picker_clear)) }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(shown, key = { it.kind.name + it.id }) { o ->
                val on = o.id in chosen
                ListItem(
                    modifier = Modifier.clickable(enabled = o.enabled) {
                        if (single) onDone(setOf(o.id)) else chosen = if (on) chosen - o.id else chosen + o.id
                    },
                    leadingContent = { Icon(o.kind.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(o.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (o.detail.isNotEmpty()) ({ Text(o.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                    trailingContent = {
                        if (single) RadioButton(selected = on, onClick = null, enabled = o.enabled)
                        else Checkbox(checked = on, onCheckedChange = null, enabled = o.enabled)
                    },
                )
            }
        }
    }
}

private fun kindLabel(k: MemberKind): Int = when (k) {
    MemberKind.PEER -> R.string.admin_picker_kind_peers
    MemberKind.GROUP -> R.string.admin_picker_kind_groups
    MemberKind.USER -> R.string.admin_picker_kind_users
    MemberKind.OTHER -> R.string.admin_picker_kind_other
}

@Preview
@Composable
private fun MemberPickerPreview() = BirdSocksTheme {
    Surface {
        MemberPickerContent(
            title = "Members",
            options = listOf(
                MemberOption(MemberKind.GROUP, "g1", "All"),
                MemberOption(MemberKind.GROUP, "g2", "office"),
                MemberOption(MemberKind.PEER, "p1", "laptop", "100.64.0.7 · linux"),
                MemberOption(MemberKind.USER, "u1", "Ann", "ann@example.com · admin"),
            ),
            selected = setOf("g2"),
            onDone = {},
        )
    }
}
