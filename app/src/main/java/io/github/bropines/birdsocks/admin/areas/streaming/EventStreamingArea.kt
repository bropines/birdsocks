package io.github.bropines.birdsocks.admin.areas.streaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiIntegration
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.AdminArea
import io.github.bropines.birdsocks.admin.areas.AreaScaffold
import io.github.bropines.birdsocks.admin.areas.AreaScope
import io.github.bropines.birdsocks.admin.formatApiDate
import io.github.bropines.birdsocks.admin.secure.SecureWindow
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.AdminList
import io.github.bropines.birdsocks.admin.ui.DetailField
import io.github.bropines.birdsocks.admin.ui.ErrorCard
import io.github.bropines.birdsocks.admin.ui.LoadableContent
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** Event streaming (cloud): copies of the audit log to Datadog, S3, Firehose or an HTTP endpoint. Never cached. */
val EventStreamingArea = AdminArea(
    id = "event_streaming",
    title = R.string.admin_area_event_streaming,
    summary = R.string.admin_area_event_streaming_summary,
    icon = Icons.Default.Stream,
    module = Capabilities.SETTINGS,
    available = { it.account?.isCloud == true },
) { scope -> EventStreamingScreen(scope) }

@Composable
private fun EventStreamingScreen(scope: AreaScope) {
    val host = scope.host
    LaunchedEffect(Unit) { host.load(AdminKeys.EVENT_STREAMS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    AreaScaffold(
        scope,
        refreshing = host.data[AdminKeys.EVENT_STREAMS].loading,
        onRefresh = { host.load(AdminKeys.EVENT_STREAMS, force = true) },
        floatingActionButton = {
            if (host.can(Capabilities.SETTINGS, Op.CREATE)) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.admin_stream_create)) },
            )
        },
    ) { EventStreamingContent(host, creating, onCreatingChange = { creating = it }) }
}

@Composable
internal fun EventStreamingContent(host: AdminHost, creating: Boolean = false, onCreatingChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val canUpdate = host.can(Capabilities.SETTINGS, Op.UPDATE) && !host.changeBusy
    LoadableContent(host.data[AdminKeys.EVENT_STREAMS], onRetry = { host.load(AdminKeys.EVENT_STREAMS, force = true) }) { listing, stale ->
        AdminList(
            items = listing.items,
            key = { it.id },
            searchText = { listOf(it.platform) + it.config.keys },
            searchHint = stringResource(R.string.admin_stream_search),
            emptyText = stringResource(R.string.admin_stream_empty),
            noMatchText = stringResource(R.string.admin_stream_no_match),
            emptyIcon = Icons.Default.Stream,
            issues = listing.issues.size,
            header = { if (stale != null) item(key = "stale") { ErrorCard(stale, onRetry = { host.load(AdminKeys.EVENT_STREAMS, force = true) }) } },
        ) { i ->
            ListItem(
                modifier = Modifier.clickable { openId = i.id },
                leadingContent = { Icon(Icons.Default.Stream, null, tint = if (i.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                headlineContent = { Text(EventStreamChanges.name(i)) },
                supportingContent = { Text(formatApiDate(i.updatedAt ?: i.createdAt), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Switch(i.enabled, { host.propose(EventStreamChanges.setEnabled(ctx, i, it)) }, enabled = canUpdate) },
            )
        }
        listing.items.firstOrNull { it.id == openId }?.let { StreamSheet(host, it, onDismiss = { openId = null }) }
    }
    if (creating) CreateStreamDialog(host, onDismiss = { onCreatingChange(false) })
}

@Composable
private fun StreamSheet(host: AdminHost, i: ApiIntegration, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    AdminDetailSheet(title = EventStreamChanges.name(i), subtitle = formatApiDate(i.createdAt), onDismiss = onDismiss) {
        DetailField(stringResource(R.string.admin_stream_platform), i.platform)
        DetailField(stringResource(R.string.admin_stream_enabled), stringResource(if (i.enabled) R.string.admin_acct_on else R.string.admin_acct_off))
        Text(stringResource(R.string.admin_stream_config), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        if (i.config.isEmpty()) HelpText(stringResource(R.string.admin_stream_config_empty))
        i.config.toSortedMap().forEach { (k, v) -> DetailField(k, EventStreamChanges.shown(k, v).ifEmpty { "—" }, mono = true) }
        HelpText(stringResource(R.string.admin_stream_secret_note), Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (host.can(Capabilities.SETTINGS, Op.UPDATE)) OutlinedButton(enabled = !host.changeBusy, onClick = { editing = true }) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_stream_edit))
            }
            if (host.can(Capabilities.SETTINGS, Op.DELETE)) OutlinedButton(
                enabled = !host.changeBusy,
                onClick = { if (host.propose(EventStreamChanges.delete(ctx, i))) onDismiss() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_stream_delete)) }
        }
    }
    if (editing) ConfigDialog(
        title = ctx.getString(R.string.admin_stream_edit),
        keys = i.config.keys.sorted(),
        editingExisting = true,
        busy = host.changeBusy,
        onDismiss = { editing = false },
    ) { changes ->
        if (changes.isNotEmpty() && host.propose(EventStreamChanges.updateConfig(ctx, i, changes))) editing = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateStreamDialog(host: AdminHost, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var platform by remember { mutableStateOf<String?>(null) }
    val p = platform
    if (p == null) {
        val parent = rememberParentLocals()
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(ctx.getString(R.string.admin_stream_create)) },
            text = {
                parent.Provide {
                    Column {
                        HelpText(stringResource(R.string.admin_stream_pick_platform))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            EventStreamChanges.PLATFORMS.forEach { FilterChip(selected = false, onClick = { platform = it }, label = { Text(it) }) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
        )
    } else ConfigDialog(
        title = ctx.getString(R.string.admin_stream_create_for, p),
        keys = EventStreamChanges.presetKeys(p),
        editingExisting = false,
        busy = host.changeBusy,
        onDismiss = onDismiss,
    ) { config ->
        if (config.isNotEmpty() && host.propose(EventStreamChanges.create(ctx, p, config, enabled = true))) onDismiss()
    }
}

/**
 * Key/value rows. Values of secret-looking keys are hidden while typed and
 * the window is kept out of screenshots. Editing an existing stream, a blank
 * value leaves that key as the server has it.
 */
@Composable
private fun ConfigDialog(title: String, keys: List<String>, editingExisting: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll((keys.ifEmpty { listOf("") }).map { it to "" }) } }
    SecureWindow()
    val result = rows.filter { it.first.isNotBlank() && it.second.isNotEmpty() }.associate { it.first.trim() to it.second }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (editingExisting) HelpText(stringResource(R.string.admin_stream_edit_note), lines = 3)
                    rows.forEachIndexed { idx, (k, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(k, { rows[idx] = it to v }, singleLine = true, label = { Text(stringResource(R.string.admin_stream_key)) }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(
                                    v, { rows[idx] = k to it }, singleLine = true,
                                    label = { Text(stringResource(R.string.admin_stream_value)) },
                                    placeholder = if (editingExisting) ({ Text(stringResource(R.string.admin_stream_unchanged)) }) else null,
                                    visualTransformation = if (EventStreamChanges.isSecret(k)) PasswordVisualTransformation() else VisualTransformation.None,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            IconButton(onClick = { rows.removeAt(idx) }) { Icon(Icons.Default.Clear, stringResource(R.string.action_clear)) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = { rows.add("" to "") }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.admin_stream_add_key)) }
                }
            }
        },
        confirmButton = { TextButton(enabled = result.isNotEmpty() && !busy, onClick = { onSave(result) }) { Text(ctx.getString(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Preview
@Composable
private fun EventStreamingPreview() = BirdSocksTheme {
    Surface { EventStreamingContent(PreviewAdminHost.demo()) }
}
