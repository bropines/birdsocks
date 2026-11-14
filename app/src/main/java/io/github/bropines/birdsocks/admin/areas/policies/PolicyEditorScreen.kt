package io.github.bropines.birdsocks.admin.areas.policies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.ConfirmDialog
import io.github.bropines.birdsocks.admin.ui.JsonEditor
import io.github.bropines.birdsocks.admin.ui.JsonText
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberKind
import io.github.bropines.birdsocks.admin.ui.MemberOption
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonObject

/**
 * One policy in the editor: the visual form or the same policy as JSON.
 * Both modes read and write [PolicyEditor.draft] whole, so switching is
 * lossless; Save runs the pipeline ([PoliciesState.check]).
 */
@Composable
internal fun PolicyEditorScreen(host: AdminHost, state: PoliciesState, editor: PolicyEditor) {
    val ctx = LocalContext.current
    val canWrite = host.can(Capabilities.POLICIES, if (editor.base == null) Op.CREATE else Op.UPDATE)
    val net = remember(host.data[AdminKeys.GROUPS], host.data[AdminKeys.PEERS], host.data[AdminKeys.POSTURE_CHECKS], state.resources) { state.net() }
    var leaving by remember { mutableStateOf(false) }
    val title = editor.draft.name.ifBlank { stringResource(R.string.admin_policy_new) }

    if (editor.mode == EditorMode.JSON) {
        JsonEditor(
            title = title,
            subtitle = stringResource(R.string.admin_policy_json_subtitle),
            initial = PolicyJson.text(editor.draft),
            readOnly = !canWrite,
            check = { el -> if (el !is JsonObject) ctx.getString(R.string.admin_policy_json_not_object) else null },
            onSave = { text ->
                (JsonText.parse(text) as? JsonObject)?.let { o ->
                    // Keep the policy's identity: the id is the editor's, not the text's.
                    editor.draft = PolicyJson.fromJson(o).copy(id = editor.base?.id)
                    editor.mode = EditorMode.VISUAL
                }
            },
            onClose = { editor.mode = EditorMode.VISUAL },
        )
        return
    }

    BackHandler { if (editor.dirty && canWrite) leaving = true else state.close() }
    var editingRule by remember { mutableStateOf<Int?>(null) }
    val problems = remember(editor.draft, net) { PolicyValidation.problems(PolicyEdits.fillRuleNames(editor.draft), net) }
    val risks = remember(editor.draft, net) { PolicyLint.introduced(editor.base, editor.draft, net) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            AppTopBar(
                title = title,
                subtitle = stringResource(if (editor.base == null) R.string.admin_policy_editor_new else R.string.admin_policy_editor_edit),
                onBack = { if (editor.dirty && canWrite) leaving = true else state.close() },
                actions = {
                    IconButton(onClick = { editor.mode = EditorMode.JSON }) { Icon(Icons.Default.DataObject, stringResource(R.string.admin_policy_json_mode)) }
                    if (editor.base != null && host.can(Capabilities.POLICIES, Op.DELETE)) {
                        IconButton(onClick = { state.check(PendingChange.Delete(editor.base)) }, enabled = !host.changeBusy) { Icon(Icons.Default.Delete, stringResource(R.string.admin_policy_delete)) }
                    }
                    if (canWrite) {
                        IconButton(onClick = { state.check(PendingChange.Save(editor.draft, editor.base)) }, enabled = editor.dirty && !host.changeBusy && state.review == null) {
                            Icon(Icons.Default.Check, stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        PolicyFormBody(
            host, net, editor.draft, enabled = canWrite,
            onChange = { editor.draft = it },
            onEditRule = { editingRule = it },
            problems = problems.map { PolicyText.problem(ctx, it) },
            risks = risks.map { PolicyText.risk(ctx, it) },
            modifier = Modifier.padding(padding),
        )
    }

    editingRule?.let { i ->
        val rule = editor.draft.rules.getOrNull(i)
        if (rule != null) RuleEditorSheet(
            host, state, net, rule, index = i, enabled = canWrite,
            onDone = { r -> editor.draft = PolicyEdits.updateRule(editor.draft, i) { r }; editingRule = null },
            onDismiss = { editingRule = null },
        )
    }

    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.admin_json_discard_title),
            text = stringResource(R.string.admin_policy_discard_text),
            confirmLabel = stringResource(R.string.admin_json_discard),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { leaving = false; state.close() },
            onDismiss = { leaving = false },
            destructive = true,
        )
    }
}

/** The visual form's body; stateless, so a preview draws it. */
@Composable
internal fun PolicyFormBody(
    host: AdminHost,
    net: PolicyNet,
    p: PolicyForm,
    enabled: Boolean,
    onChange: (PolicyForm) -> Unit,
    onEditRule: (Int) -> Unit,
    modifier: Modifier = Modifier,
    problems: List<String> = emptyList(),
    risks: List<String> = emptyList(),
) {
    val ctx = LocalContext.current
    val checks = host.data.items(AdminKeys.POSTURE_CHECKS)
    val checkOptions = remember(checks) { checks.map { MemberOption(MemberKind.OTHER, it.id, it.name, it.description) } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 88.dp)) {
        OutlinedTextField(
            p.name, { onChange(PolicyEdits.rename(p, it)) },
            label = { Text(stringResource(R.string.admin_policy_name)) },
            singleLine = true, enabled = enabled,
            isError = p.name.isBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            p.description, { onChange(PolicyEdits.describe(p, it)) },
            label = { Text(stringResource(R.string.admin_policy_description)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.admin_policy_enabled), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(p.enabled, { onChange(PolicyEdits.setEnabled(p, it)) }, enabled = enabled)
        }
        MemberField(
            label = stringResource(R.string.admin_policy_posture_checks),
            options = checkOptions,
            selected = p.postureChecks,
            onChange = { onChange(PolicyEdits.setPostureChecks(p, it)) },
            emptyText = stringResource(R.string.admin_policy_posture_none),
            enabled = enabled,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.admin_policy_rules), style = MaterialTheme.typography.titleMedium)
        p.rules.forEachIndexed { i, r ->
            RuleCard(
                ctx = ctx, rule = r, net = net, index = i, count = p.rules.size, enabled = enabled,
                onEdit = { onEditRule(i) },
                onToggle = { on -> onChange(PolicyEdits.updateRule(p, i) { it.copy(enabled = on) }) },
                onDuplicate = { onChange(PolicyEdits.duplicateRule(p, i)) },
                onMove = { to -> onChange(PolicyEdits.moveRule(p, i, to)) },
                onDelete = { onChange(PolicyEdits.removeRule(p, i)) },
            )
        }
        if (enabled) {
            OutlinedButton(onClick = { onChange(PolicyEdits.addRule(p)); onEditRule(p.rules.size) }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.admin_policy_add_rule))
            }
        }
        val covered = remember(p, net) { PolicyCoverage.peers(p, net).size }
        Text(
            pluralStringResource(R.plurals.admin_policy_covers, covered, covered),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (problems.isNotEmpty() || risks.isNotEmpty()) Spacer(Modifier.height(8.dp))
        problems.forEach { NoteLine(it, error = true) }
        risks.forEach { NoteLine(it, error = false) }
    }
}

@Composable
internal fun NoteLine(text: String, error: Boolean) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (error) Icons.Default.ErrorOutline else Icons.Default.Warning, null,
            Modifier.size(16.dp).padding(top = 2.dp),
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RuleCard(
    ctx: android.content.Context,
    rule: RuleForm,
    net: PolicyNet,
    index: Int,
    count: Int,
    enabled: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (rule.isAccept) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule.name.ifBlank { stringResource(R.string.admin_policy_rule_n, index + 1) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(PolicyText.rule(ctx, rule, net), style = MaterialTheme.typography.bodySmall)
                val n = remember(rule, net) { PolicyCoverage.peers(rule, net).size }
                Text(pluralStringResource(R.plurals.admin_policy_covers, n, n), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(rule.enabled, onToggle, enabled = enabled)
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.admin_policy_rule_menu)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                if (enabled) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.admin_policy_rule_duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    if (index > 0) DropdownMenuItem(text = { Text(stringResource(R.string.admin_policy_rule_up)) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { menu = false; onMove(index - 1) })
                    if (index < count - 1) DropdownMenuItem(text = { Text(stringResource(R.string.admin_policy_rule_down)) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { menu = false; onMove(index + 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.admin_policy_rule_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Preview
@Composable
private fun PolicyFormPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface {
        PolicyFormBody(
            host, PoliciesState(host).net(),
            PolicyForm(name = "SSH", rules = listOf(RuleForm(name = "SSH", protocol = RuleForm.TCP, ports = listOf(PortSpan(22)), sources = listOf("g-office"), destinations = listOf("g-all")))),
            enabled = true, onChange = {}, onEditRule = {},
            problems = listOf("Rule 1: no sources"),
        )
    }
}
