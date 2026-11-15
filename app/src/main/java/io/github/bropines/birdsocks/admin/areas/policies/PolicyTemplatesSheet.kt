package io.github.bropines.birdsocks.admin.areas.policies

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailSheetBody
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * Templates: pick one, fill its form, see exactly the policy it creates,
 * then create it (through the pipeline) or open it in the editor first.
 */
@Composable
internal fun PolicyTemplatesSheet(host: AdminHost, state: PoliciesState, onDismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val template = chosen?.let(PolicyTemplates::byId)
    AdminDetailSheet(
        title = template?.let { templateTitle(LocalContext.current, it.id) } ?: stringResource(R.string.admin_policy_templates),
        subtitle = template?.let { templateText(LocalContext.current, it.id) },
        onDismiss = onDismiss,
    ) {
        if (template == null) TemplateList { chosen = it }
        else TemplateForm(
            host, state, template,
            onBack = { chosen = null },
            onCreate = { p -> onDismiss(); state.check(PendingChange.Save(p, null)) },
            onEdit = { p -> onDismiss(); state.openNew(p) },
        )
    }
}

@Composable
private fun TemplateList(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Column {
        PolicyTemplates.all.forEach { t ->
            ListItem(
                modifier = Modifier.clickable { onPick(t.id) },
                leadingContent = { Icon(if (t.denies) Icons.Default.Block else Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(templateTitle(ctx, t.id)) },
                supportingContent = { Text(templateText(ctx, t.id), style = MaterialTheme.typography.bodySmall) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
internal fun TemplateForm(
    host: AdminHost,
    state: PoliciesState,
    t: PolicyTemplate,
    onBack: () -> Unit,
    onCreate: (PolicyForm) -> Unit,
    onEdit: (PolicyForm) -> Unit,
) {
    val ctx = LocalContext.current
    val net = remember(host.data[AdminKeys.GROUPS], host.data[AdminKeys.PEERS]) { state.net() }
    val groupOptions = remember(host.data[AdminKeys.GROUPS]) { MemberOptions.groups(host.data.items(AdminKeys.GROUPS)) }
    var values by remember(t.id) { mutableStateOf(PolicyTemplates.defaults(t, net.allGroupId)) }
    val result = remember(values) { t.build(TemplateValues(values)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        t.fields.forEach { f ->
            val label = fieldLabel(ctx, f.key)
            when (f.kind) {
                FieldKind.GROUPS -> MemberField(
                    label = label, options = groupOptions, selected = values[f.key].orEmpty(),
                    onChange = { values = values + (f.key to it) },
                    emptyText = stringResource(R.string.admin_policy_side_none),
                )
                FieldKind.PORTS, FieldKind.NAME -> OutlinedTextField(
                    values[f.key].orEmpty().joinToString(", "), { values = values + (f.key to listOf(it)) },
                    label = { Text(label) }, singleLine = true,
                    isError = f.key in result.missing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(stringResource(R.string.admin_policy_template_preview), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        val p = result.policy
        if (p == null) {
            Text(stringResource(R.string.admin_policy_template_missing, result.missing.joinToString(", ") { fieldLabel(ctx, it) }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(PolicyText.summary(ctx, p, net), style = MaterialTheme.typography.bodySmall)
                }
            }
            PolicyLint.findings(p, net).forEach { NoteLine(PolicyText.risk(ctx, it), error = false) }
            if (t.needsDefaultOff) HelpText(stringResource(R.string.admin_policy_template_default_off), lines = 4)
            if (t.denies) HelpText(stringResource(R.string.admin_policy_template_denies), lines = 4)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            OutlinedButton(onClick = { p?.let(onEdit) }, enabled = p != null) { Text(stringResource(R.string.admin_policy_template_edit)) }
            Button(onClick = { p?.let(onCreate) }, enabled = p != null && !host.changeBusy) { Text(stringResource(R.string.admin_policy_template_create)) }
        }
    }
}

private fun templateTitle(ctx: Context, id: String): String = ctx.getString(
    when (id) {
        "ssh_admins" -> R.string.admin_policy_tpl_ssh_admins
        "web_server" -> R.string.admin_policy_tpl_web_server
        "dns_server" -> R.string.admin_policy_tpl_dns_server
        "monitoring" -> R.string.admin_policy_tpl_monitoring
        "group_only" -> R.string.admin_policy_tpl_group_only
        "isolate" -> R.string.admin_policy_tpl_isolate
        else -> R.string.admin_policy_templates
    }
)

private fun templateText(ctx: Context, id: String): String = ctx.getString(
    when (id) {
        "ssh_admins" -> R.string.admin_policy_tpl_ssh_admins_text
        "web_server" -> R.string.admin_policy_tpl_web_server_text
        "dns_server" -> R.string.admin_policy_tpl_dns_server_text
        "monitoring" -> R.string.admin_policy_tpl_monitoring_text
        "group_only" -> R.string.admin_policy_tpl_group_only_text
        "isolate" -> R.string.admin_policy_tpl_isolate_text
        else -> R.string.admin_policy_templates
    }
)

private fun fieldLabel(ctx: Context, key: String): String = ctx.getString(
    when (key) {
        "name" -> R.string.admin_policy_name
        "ports" -> R.string.admin_policy_ports
        "admins" -> R.string.admin_policy_tf_admins
        "servers" -> R.string.admin_policy_tf_servers
        "clients" -> R.string.admin_policy_tf_clients
        "monitors" -> R.string.admin_policy_tf_monitors
        "targets" -> R.string.admin_policy_tf_targets
        "group" -> R.string.admin_policy_tf_group
        "from" -> R.string.admin_policy_tf_from
        "to" -> R.string.admin_policy_tf_to
        else -> R.string.admin_policy_name
    }
)

@Preview
@Composable
private fun TemplateFormPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface {
        DetailSheetBody("Web server", null) {
            TemplateForm(host, PoliciesState(host), PolicyTemplates.WEB_SERVER, {}, {}, {})
        }
    }
}
