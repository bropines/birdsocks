package io.github.bropines.birdsocks.admin.areas.policies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/** "Who can reach X?" over the saved policies, for a peer or a group; a row opens its policy. */
@Composable
internal fun WhoCanReachSheet(host: AdminHost, state: PoliciesState, onDismiss: () -> Unit) {
    AdminDetailSheet(title = stringResource(R.string.admin_policy_reach), onDismiss = onDismiss) {
        WhoCanReachBody(host, state, onOpen = { id -> onDismiss(); state.open(id) })
    }
}

@Composable
internal fun WhoCanReachBody(host: AdminHost, state: PoliciesState, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var byPeer by rememberSaveable { mutableStateOf(true) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val net = remember(host.data[AdminKeys.GROUPS], host.data[AdminKeys.PEERS]) { state.net() }
    val policies = remember(host.data[AdminKeys.POLICIES]) { state.cached() }
    val options = remember(byPeer, host.data[AdminKeys.GROUPS], host.data[AdminKeys.PEERS]) {
        if (byPeer) MemberOptions.peers(host.data.items(AdminKeys.PEERS)) else MemberOptions.groups(host.data.items(AdminKeys.GROUPS))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = byPeer, onClick = { byPeer = true; target = null }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.admin_picker_kind_peers)) }
            SegmentedButton(selected = !byPeer, onClick = { byPeer = false; target = null }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.admin_picker_kind_groups)) }
        }
        MemberField(
            label = stringResource(R.string.admin_policy_reach_target),
            options = options, selected = listOfNotNull(target), single = true,
            onChange = { target = it.lastOrNull() },
            emptyText = stringResource(R.string.admin_policy_reach_pick),
        )
        val t = target ?: return@Column
        val reaches = remember(t, byPeer, policies, net) { WhoCanReach.of(if (byPeer) WhoCanReach.Target.Peer(t) else WhoCanReach.Target.Group(t), policies, net) }
        val peers = remember(reaches, net) { WhoCanReach.peers(reaches, net) - t }
        Text(
            if (reaches.isEmpty()) stringResource(R.string.admin_policy_reach_none) else pluralStringResource(R.plurals.admin_policy_reach_peers, peers.size, peers.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        reaches.forEach { r ->
            ListItem(
                modifier = Modifier.clickable(enabled = r.policyId != null) { r.policyId?.let(onOpen) },
                leadingContent = {
                    Icon(
                        if (r.blocks) Icons.Default.Block else Icons.Default.CheckCircle, null,
                        tint = if (r.blocks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = { Text(PolicyText.side(ctx, r.fromGroups, r.fromResource, net)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (r.blocks) R.string.admin_policy_reach_row_block else R.string.admin_policy_reach_row,
                            PolicyText.traffic(ctx, r.rule), r.policy,
                        ) + if (r.reverse) " " + stringResource(R.string.admin_policy_reach_reverse) else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Preview
@Composable
private fun WhoCanReachPreview() = BirdSocksTheme {
    val host = PreviewAdminHost.demo()
    Surface { DetailSheetBody("Who can reach…", null) { WhoCanReachBody(host, PoliciesState(host), {}) } }
}
