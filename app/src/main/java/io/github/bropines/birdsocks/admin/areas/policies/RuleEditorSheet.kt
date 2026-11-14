package io.github.bropines.birdsocks.admin.areas.policies

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailSheetBody
import io.github.bropines.birdsocks.admin.ui.MemberField
import io.github.bropines.birdsocks.admin.ui.MemberKind
import io.github.bropines.birdsocks.admin.ui.MemberOption
import io.github.bropines.birdsocks.admin.ui.MemberOptions
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * One rule in plain words, as a sheet: what it does (accept or block), the
 * traffic (protocol, ports as chips and text), who (sources) to what
 * (destinations) — groups, or one resource — and for NetBird SSH the local
 * users per source group. Edits a copy; Done hands it back.
 */
@Composable
internal fun RuleEditorSheet(
    host: AdminHost,
    state: PoliciesState,
    net: PolicyNet,
    rule: RuleForm,
    index: Int,
    enabled: Boolean,
    onDone: (RuleForm) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { state.loadResources() }
    var r by remember { mutableStateOf(rule) }
    AdminDetailSheet(
        title = rule.name.ifBlank { stringResource(R.string.admin_policy_rule_n, index + 1) },
        onDismiss = onDismiss,
    ) {
        RuleEditorBody(host, net, state.resources.orEmpty(), r, enabled) { r = it }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            if (enabled) Button(onClick = { onDone(r) }) { Text(stringResource(R.string.admin_policy_rule_done)) }
        }
    }
}

/** The rule's fields; [resources] are the network resources a side may name instead of groups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditorBody(host: AdminHost, net: PolicyNet, resources: List<ApiNetworkResource>, r: RuleForm, enabled: Boolean, onChange: (RuleForm) -> Unit) {
    val ctx = LocalContext.current
    val groupOptions = remember(host.data[AdminKeys.GROUPS]) { MemberOptions.groups(host.data.items(AdminKeys.GROUPS)) }
    val peerOptions = remember(host.data[AdminKeys.PEERS]) { MemberOptions.peers(host.data.items(AdminKeys.PEERS)) }
    val resourceOptions = remember(peerOptions, resources) {
        peerOptions + resources.map { MemberOption(MemberKind.OTHER, it.id, it.name.ifEmpty { it.address }, listOf(it.type, it.address).filter(String::isNotEmpty).joinToString(" · ")) }
    }
    val types = remember(resources) { resources.associate { it.id to it.type } }
    fun ref(id: String): ResourceRef = ResourceRef(id, types[id]?.ifEmpty { null } ?: ResourceRef.TYPE_PEER)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(r.name, { onChange(r.copy(name = it)) }, label = { Text(stringResource(R.string.admin_policy_rule_name)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(r.description, { onChange(r.copy(description = it)) }, label = { Text(stringResource(R.string.admin_policy_description)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        SwitchRow(stringResource(R.string.admin_policy_rule_enabled), r.enabled, enabled) { onChange(r.copy(enabled = it)) }

        Label(stringResource(R.string.admin_policy_rule_action))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RuleForm.ACTIONS.forEachIndexed { i, a ->
                SegmentedButton(
                    selected = r.action == a, enabled = enabled,
                    onClick = { onChange(PolicyEdits.setAction(r, a)) },
                    shape = SegmentedButtonDefaults.itemShape(i, RuleForm.ACTIONS.size),
                ) { Text(PolicyText.action(ctx, a)) }
            }
        }

        Label(stringResource(R.string.admin_policy_rule_protocol))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleForm.PROTOCOLS.forEach { p ->
                FilterChip(selected = r.protocol == p, enabled = enabled, onClick = { onChange(PolicyEdits.setProtocol(r, p)) }, label = { Text(PolicyText.protocol(ctx, p)) })
            }
        }
        if (r.takesPorts) PortsEditor(r, enabled, onChange)
        else HelpText(stringResource(if (r.protocol == RuleForm.SSH) R.string.admin_policy_ssh_help else R.string.admin_policy_no_ports_help), lines = 3)

        SwitchRow(stringResource(R.string.admin_policy_rule_bidirectional), r.bidirectional, enabled) { onChange(r.copy(bidirectional = it)) }

        SideEditor(
            title = stringResource(R.string.admin_policy_sources),
            groups = r.sources, res = r.sourceResource, groupOptions = groupOptions, resourceOptions = resourceOptions, enabled = enabled,
            onGroups = { onChange(PolicyEdits.setSources(r, it)) },
            onResource = { id -> onChange(PolicyEdits.setSourceResource(r, id?.let(::ref))) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { onChange(PolicyEdits.swap(r)) }, enabled = enabled) {
                Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.admin_policy_swap))
            }
        }
        SideEditor(
            title = stringResource(R.string.admin_policy_destinations),
            groups = r.destinations, res = r.destinationResource, groupOptions = groupOptions, resourceOptions = resourceOptions, enabled = enabled,
            onGroups = { onChange(PolicyEdits.setDestinations(r, it)) },
            onResource = { id -> onChange(PolicyEdits.setDestinationResource(r, id?.let(::ref))) },
        )

        if (r.protocol == RuleForm.SSH && r.sources.isNotEmpty()) {
            Label(stringResource(R.string.admin_policy_ssh_users))
            HelpText(stringResource(R.string.admin_policy_ssh_users_help), lines = 3)
            r.sources.forEach { g ->
                var text by remember(g) { mutableStateOf(r.authorizedGroups[g].orEmpty().joinToString(", ")) }
                OutlinedTextField(
                    text, { text = it; onChange(PolicyEdits.setAuthorizedUsers(r, g, it.split(',', ' '))) },
                    label = { Text(net.groupName(g)) }, singleLine = true, enabled = enabled,
                    placeholder = { Text(stringResource(R.string.admin_policy_ssh_users_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val problems = remember(r, net) { PolicyValidation.rule(r, 0, net).map { it.copy(rule = null) } }
        problems.forEach { NoteLine(PolicyText.problem(ctx, it), error = true) }
        val n = remember(r, net) { PolicyCoverage.peers(r, net).size }
        Text(pluralStringResource(R.plurals.admin_policy_covers, n, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortsEditor(r: RuleForm, enabled: Boolean, onChange: (RuleForm) -> Unit) {
    val ctx = LocalContext.current
    Label(stringResource(R.string.admin_policy_ports))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = r.ports.isEmpty(), enabled = enabled,
            onClick = { onChange(PolicyEdits.setPorts(r, emptyList())) },
            label = { Text(stringResource(R.string.admin_policy_ports_all)) },
        )
        PortPreset.entries.forEach { p ->
            val on = PortWords.isOn(r.ports, p)
            FilterChip(
                selected = on, enabled = enabled,
                onClick = { onChange(PolicyEdits.togglePreset(r, p)) },
                label = { Text(presetLabel(ctx, p)) },
                leadingIcon = if (on) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
        }
    }
    var text by remember(r.ports) { mutableStateOf(PortWords.text(r.ports) + r.badPorts.joinToString(", ", prefix = if (r.badPorts.isNotEmpty() && r.ports.isNotEmpty()) ", " else "")) }
    val parsed = PortWords.parse(text)
    OutlinedTextField(
        text,
        { t ->
            text = t
            PortWords.parse(t).spans?.let { onChange(PolicyEdits.setPorts(r, it)) }
        },
        label = { Text(stringResource(R.string.admin_policy_ports_text)) },
        placeholder = { Text("22, 80, 8000-8100") },
        isError = parsed.spans == null,
        supportingText = { Text(parsed.bad?.let { stringResource(R.string.admin_policy_err_port, it) } ?: stringResource(R.string.admin_policy_ports_help)) },
        singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun presetLabel(ctx: android.content.Context, p: PortPreset): String {
    val name = when (p) {
        PortPreset.SSH -> "SSH"
        PortPreset.HTTP -> "HTTP"
        PortPreset.HTTPS -> "HTTPS"
        PortPreset.WEB -> ctx.getString(R.string.admin_policy_preset_web)
        PortPreset.DNS -> "DNS"
        PortPreset.RDP -> "RDP"
        PortPreset.MYSQL -> "MySQL"
        PortPreset.POSTGRES -> "PostgreSQL"
    }
    return "$name ${p.ports.joinToString(", ")}"
}

/** One side of a rule: groups, or one resource (a peer or a network resource) instead. */
@Composable
private fun SideEditor(
    title: String,
    groups: List<String>,
    res: ResourceRef?,
    groupOptions: List<MemberOption>,
    resourceOptions: List<MemberOption>,
    enabled: Boolean,
    onGroups: (List<String>) -> Unit,
    onResource: (String?) -> Unit,
) {
    var useResource by remember(res != null) { mutableStateOf(res != null) }
    Column {
        Label(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !useResource, enabled = enabled, onClick = { useResource = false; if (res != null) onResource(null) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.admin_policy_side_groups))
            }
            SegmentedButton(selected = useResource, enabled = enabled, onClick = { useResource = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.admin_policy_side_resource))
            }
        }
        if (!useResource) {
            MemberField(
                label = stringResource(R.string.admin_policy_side_groups),
                options = groupOptions, selected = groups, onChange = onGroups,
                emptyText = stringResource(R.string.admin_policy_side_none), pickerTitle = title, enabled = enabled,
            )
        } else {
            MemberField(
                label = stringResource(R.string.admin_policy_side_resource),
                options = resourceOptions, selected = listOfNotNull(res?.id), onChange = { onResource(it.lastOrNull()) },
                emptyText = stringResource(R.string.admin_policy_side_none), pickerTitle = title, enabled = enabled, single = true,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Preview
@Composable
private fun RuleEditorPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface {
        DetailSheetBody("SSH", null) {
            RuleEditorBody(
                host, PoliciesState(host).net(), emptyList(),
                RuleForm(name = "SSH", protocol = RuleForm.TCP, ports = listOf(PortSpan(22)), sources = listOf("g-office"), destinations = listOf("g-all")),
                enabled = true,
            ) {}
        }
    }
}
