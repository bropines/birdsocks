package io.github.bropines.birdsocks.admin.areas.posture

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.ui.ConfirmDialog
import io.github.bropines.birdsocks.admin.ui.JsonEditor
import io.github.bropines.birdsocks.admin.ui.JsonText
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonObject

/**
 * One posture check: a section per check type with a switch to include it,
 * or the same check as JSON. Delete names the policies that still use it
 * and is not offered while any does (the server would refuse).
 */
@Composable
internal fun PostureEditorScreen(host: AdminHost, state: PostureState, editor: PostureEditor) {
    val ctx = LocalContext.current
    val canWrite = host.can(Capabilities.POLICIES, if (editor.base == null) Op.CREATE else Op.UPDATE)
    val usedBy = remember(editor.base) { state.usedBy(editor.base?.id) }
    var leaving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val title = editor.draft.name.ifBlank { stringResource(R.string.admin_posture_new) }

    if (editor.json) {
        JsonEditor(
            title = title,
            subtitle = stringResource(R.string.admin_policy_json_subtitle),
            initial = PostureJson.text(editor.draft),
            readOnly = !canWrite,
            check = { el -> if (el !is JsonObject) ctx.getString(R.string.admin_policy_json_not_object) else null },
            onSave = { text ->
                (JsonText.parse(text) as? JsonObject)?.let { o ->
                    editor.draft = PostureJson.fromJson(o).copy(id = editor.base?.id)
                    editor.json = false
                }
            },
            onClose = { editor.json = false },
        )
        return
    }

    BackHandler { if (editor.dirty && canWrite) leaving = true else state.editor = null }
    val problems = remember(editor.draft) { PostureValidation.problems(editor.draft) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            AppTopBar(
                title = title,
                subtitle = stringResource(if (editor.base == null) R.string.admin_posture_editor_new else R.string.admin_posture_editor_edit),
                onBack = { if (editor.dirty && canWrite) leaving = true else state.editor = null },
                actions = {
                    IconButton(onClick = { editor.json = true }) { Icon(Icons.Default.DataObject, stringResource(R.string.admin_policy_json_mode)) }
                    if (editor.base != null && host.can(Capabilities.POLICIES, Op.DELETE)) {
                        IconButton(
                            onClick = {
                                // NetBird refuses while a policy names it: say so instead of offering the change.
                                if (usedBy.isNotEmpty()) deleting = true
                                else if (host.propose(PostureChanges.delete(ctx, editor.base))) state.editor = null
                            },
                            enabled = !host.changeBusy,
                        ) { Icon(Icons.Default.Delete, stringResource(R.string.admin_posture_delete)) }
                    }
                    if (canWrite) {
                        IconButton(
                            onClick = { if (host.propose(PostureChanges.save(ctx, editor.draft, editor.base, usedBy))) state.editor = null },
                            enabled = editor.dirty && problems.isEmpty() && !host.changeBusy,
                        ) { Icon(Icons.Default.Check, stringResource(R.string.action_save)) }
                    }
                },
            )
        },
    ) { padding ->
        PostureFormBody(editor.draft, canWrite, usedBy, problems.map { problemText(ctx, it) }, { editor.draft = it }, Modifier.padding(padding))
    }

    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.admin_json_discard_title),
            text = stringResource(R.string.admin_policy_discard_text),
            confirmLabel = stringResource(R.string.admin_json_discard),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { leaving = false; state.editor = null },
            onDismiss = { leaving = false },
            destructive = true,
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.admin_posture_delete_blocked_title),
            text = stringResource(R.string.admin_posture_delete_blocked_text, usedBy.joinToString(", ")),
            confirmLabel = stringResource(R.string.action_close),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { deleting = false },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
internal fun PostureFormBody(f: PostureForm, enabled: Boolean, usedBy: List<String>, problems: List<String>, onChange: (PostureForm) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(f.name, { onChange(f.copy(name = it)) }, label = { Text(stringResource(R.string.admin_policy_name)) }, singleLine = true, enabled = enabled, isError = f.name.isBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(f.description, { onChange(f.copy(description = it)) }, label = { Text(stringResource(R.string.admin_policy_description)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Text(
            if (usedBy.isEmpty()) stringResource(R.string.admin_posture_unused) else stringResource(R.string.admin_posture_used_list, usedBy.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )

        CheckSection(ctx, CheckType.NB_VERSION, f, enabled, onChange) {
            val v = f.nbVersion ?: return@CheckSection
            HelpText(stringResource(R.string.admin_posture_nb_help), lines = 3)
            VersionField(stringResource(R.string.admin_posture_min_version), v.minVersion, enabled) { onChange(f.copy(nbVersion = v.copy(minVersion = it))) }
        }

        CheckSection(ctx, CheckType.OS_VERSION, f, enabled, onChange) {
            val os = f.os ?: return@CheckSection
            HelpText(stringResource(R.string.admin_posture_os_help), lines = 3)
            OsRow("Android", os.android, enabled) { onChange(f.copy(os = os.copy(android = it))) }
            OsRow("iOS", os.ios, enabled) { onChange(f.copy(os = os.copy(ios = it))) }
            OsRow("macOS", os.darwin, enabled) { onChange(f.copy(os = os.copy(darwin = it))) }
            OsRow(stringResource(R.string.admin_posture_os_linux), os.linux, enabled) { onChange(f.copy(os = os.copy(linux = it))) }
            OsRow(stringResource(R.string.admin_posture_os_windows), os.windows, enabled) { onChange(f.copy(os = os.copy(windows = it))) }
        }

        CheckSection(ctx, CheckType.GEO_LOCATION, f, enabled, onChange) {
            val g = f.geo ?: return@CheckSection
            ActionRow(g.action, enabled) { onChange(f.copy(geo = g.copy(action = it))) }
            g.locations.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        l.countryCode, { onChange(f.copy(geo = g.copy(locations = g.locations.mapIndexed { j, x -> if (j == i) x.copy(countryCode = it.uppercase().take(2)) else x }))) },
                        label = { Text(stringResource(R.string.admin_posture_country)) }, singleLine = true, enabled = enabled,
                        placeholder = { Text("DE") }, modifier = Modifier.weight(0.35f),
                    )
                    OutlinedTextField(
                        l.cityName, { onChange(f.copy(geo = g.copy(locations = g.locations.mapIndexed { j, x -> if (j == i) x.copy(cityName = it) else x }))) },
                        label = { Text(stringResource(R.string.admin_posture_city)) }, singleLine = true, enabled = enabled,
                        modifier = Modifier.weight(0.65f),
                    )
                    if (enabled) IconButton(onClick = { onChange(f.copy(geo = g.copy(locations = g.locations.filterIndexed { j, _ -> j != i }))) }) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_clear))
                    }
                }
            }
            if (enabled) AddButton(stringResource(R.string.admin_posture_add_location)) { onChange(f.copy(geo = g.copy(locations = g.locations + LocationForm()))) }
        }

        CheckSection(ctx, CheckType.NETWORK_RANGE, f, enabled, onChange) {
            val n = f.network ?: return@CheckSection
            HelpText(stringResource(R.string.admin_posture_network_help), lines = 3)
            ActionRow(n.action, enabled) { onChange(f.copy(network = n.copy(action = it))) }
            var text by remember(n.ranges) { mutableStateOf(n.ranges.joinToString("\n")) }
            OutlinedTextField(
                text,
                { t -> text = t; onChange(f.copy(network = n.copy(ranges = t.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }))) },
                label = { Text(stringResource(R.string.admin_posture_ranges)) },
                placeholder = { Text("192.168.1.0/24\n10.0.0.0/8") },
                enabled = enabled, minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        CheckSection(ctx, CheckType.PROCESS, f, enabled, onChange) {
            val p = f.process ?: return@CheckSection
            HelpText(stringResource(R.string.admin_posture_process_help), lines = 3)
            p.processes.forEachIndexed { i, e ->
                fun set(x: ProcessForm) = onChange(f.copy(process = p.copy(processes = p.processes.mapIndexed { j, o -> if (j == i) x else o })))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.admin_posture_process_n, i + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (enabled) IconButton(onClick = { onChange(f.copy(process = p.copy(processes = p.processes.filterIndexed { j, _ -> j != i }))) }) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_clear))
                            }
                        }
                        PathField("Linux", e.linuxPath, enabled) { set(e.copy(linuxPath = it)) }
                        PathField("macOS", e.macPath, enabled) { set(e.copy(macPath = it)) }
                        PathField("Windows", e.windowsPath, enabled) { set(e.copy(windowsPath = it)) }
                    }
                }
            }
            if (enabled) AddButton(stringResource(R.string.admin_posture_add_process)) { onChange(f.copy(process = p.copy(processes = p.processes + ProcessForm()))) }
        }

        if (f.otherChecks.isNotEmpty()) {
            HelpText(stringResource(R.string.admin_posture_other_checks, f.otherChecks.keys.joinToString(", ") { typeName(ctx, it) }), lines = 4)
        }
        problems.forEach { p ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(6.dp))
                Text(p, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CheckSection(ctx: Context, t: CheckType, f: PostureForm, enabled: Boolean, onChange: (PostureForm) -> Unit, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeName(ctx, t.key), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(f.has(t), { onChange(f.toggle(t, it)) }, enabled = enabled)
            }
            if (f.has(t)) content()
        }
    }
}

@Composable
private fun VersionField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = value.isNotEmpty() && !PostureValidation.versionOk(value),
        placeholder = { Text("0.28.0") },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One OS: a switch that includes it, and its minimum version. */
@Composable
private fun OsRow(name: String, value: String?, enabled: Boolean, onChange: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(value != null, { onChange(if (it) "" else null) }, enabled = enabled)
        if (value == null) Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
        else OutlinedTextField(
            value, { onChange(it) }, label = { Text(name) }, singleLine = true, enabled = enabled,
            isError = !PostureValidation.versionOk(value),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(action: String, enabled: Boolean, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(ALLOW, DENY).forEachIndexed { i, a ->
            SegmentedButton(selected = action == a, enabled = enabled, onClick = { onChange(a) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                Text(stringResource(if (a == ALLOW) R.string.admin_posture_allow else R.string.admin_posture_deny))
            }
        }
    }
}

@Composable
private fun PathField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(text)
    }
}

internal fun problemText(ctx: Context, p: PostureProblem): String = when (p.kind) {
    PostureProblem.Kind.NO_NAME -> ctx.getString(R.string.admin_policy_err_no_name)
    PostureProblem.Kind.NO_CHECKS -> ctx.getString(R.string.admin_posture_err_no_checks)
    PostureProblem.Kind.BAD_VERSION -> ctx.getString(R.string.admin_posture_err_version, p.value)
    PostureProblem.Kind.NO_OS -> ctx.getString(R.string.admin_posture_err_no_os)
    PostureProblem.Kind.NO_LOCATIONS -> ctx.getString(R.string.admin_posture_err_no_locations)
    PostureProblem.Kind.BAD_COUNTRY -> ctx.getString(R.string.admin_posture_err_country, p.value)
    PostureProblem.Kind.NO_RANGES -> ctx.getString(R.string.admin_posture_err_no_ranges)
    PostureProblem.Kind.BAD_RANGE -> ctx.getString(R.string.admin_posture_err_range, p.value)
    PostureProblem.Kind.NO_PROCESSES -> ctx.getString(R.string.admin_posture_err_no_processes)
    PostureProblem.Kind.EMPTY_PROCESS -> ctx.getString(R.string.admin_posture_err_empty_process, (p.index ?: 0) + 1)
    PostureProblem.Kind.BAD_ACTION -> ctx.getString(R.string.admin_policy_err_action, p.value)
}

@Preview
@Composable
private fun PostureFormPreview() = BirdSocksTheme {
    Surface {
        PostureFormBody(
            PostureForm(name = "Recent client", nbVersion = VersionForm("0.28.0"), geo = GeoForm(locations = listOf(LocationForm("DE", "Berlin")))),
            enabled = true, usedBy = listOf("SSH"), problems = emptyList(), onChange = {},
        )
    }
}

