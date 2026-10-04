package io.github.bropines.birdsocks.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.EgressProbe
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The peer that routes [network], by its name, as far as the status shows it. */
fun routingPeerOf(network: NbNetwork, status: NbStatus?): String? =
    status?.fullStatus?.peers?.firstOrNull { network.routedBy(it) }?.fqdn

/**
 * Whether the internet answers through the proxy while [exit] is selected;
 * null while unknown or with none. An exit node can be up as a peer and
 * forward nothing, so it is checked every 2 min, every 20 s after a failure.
 */
@Composable
fun rememberExitWorks(exit: NbNetwork?, connected: Boolean): State<Boolean?> {
    val context = LocalContext.current
    val works = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(exit?.id, connected) {
        works.value = null
        if (exit == null || !connected) return@LaunchedEffect
        while (true) {
            works.value = EgressProbe.internetThroughProxy(context)
            delay(if (works.value == true) 120_000L else 20_000L)
        }
    }
    return works
}

/**
 * The exit node row with its picker and the dead-exit check, for a screen
 * that holds [networks] (ListNetworks, exit nodes included). Shows nothing
 * when no exit node is offered; [onChanged] gets the list re-read after a pick.
 */
@Composable
fun ExitNodeCard(
    networks: List<NbNetwork>,
    status: NbStatus?,
    connected: Boolean,
    onChanged: (List<NbNetwork>) -> Unit,
    modifier: Modifier = Modifier
) {
    val exitNodes = networks.filter { it.isExitNode }
    if (exitNodes.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = exitNodes.firstOrNull { it.selected }
    val works by rememberExitWorks(selected, connected)
    var picking by remember { mutableStateOf(false) }

    fun change(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block(); onChanged(Netbird.networks()) }.onFailure { toast(context, it) }
        }
    }

    ExitNodeRow(
        current = selected,
        via = selected?.let { routingPeerOf(it, status) }?.substringBefore('.'),
        works = works,
        onTurnOff = { selected?.let { change { Netbird.deselectNetworks(listOf(it.id)) } } },
        onClick = { picking = true },
        modifier = modifier
    )
    if (picking) {
        ExitNodePicker(
            exitNodes = exitNodes,
            status = status,
            onPick = { id ->
                change {
                    if (id.isEmpty()) selected?.let { Netbird.deselectNetworks(listOf(it.id)) }
                    else Netbird.selectNetworks(listOf(id))
                }
            },
            onDismiss = { picking = false }
        )
    }
}

/** The exit node in use; [works] false when the internet does not answer through it. */
@Composable
fun ExitNodeRow(
    current: NbNetwork?,
    via: String?,
    works: Boolean?,
    onTurnOff: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dead = current != null && works == false
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            leadingContent = {
                Icon(
                    if (dead) Icons.Default.PublicOff else Icons.Default.Public, null,
                    tint = if (dead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            overlineContent = { Text(stringResource(R.string.nb_exit_node)) },
            supportingContent = when {
                dead -> { { HelpText(stringResource(R.string.nb_exit_node_dead), color = MaterialTheme.colorScheme.error) } }
                current != null && via != null -> { { Text(stringResource(R.string.nb_exit_node_via, via)) } }
                else -> null
            },
            trailingContent = {
                if (dead) TextButton(onClick = onTurnOff) { Text(stringResource(R.string.nb_exit_node_off)) }
                else Icon(Icons.Default.ChevronRight, null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(current?.id ?: stringResource(R.string.nb_exit_node_none)) }
    }
}

/** Picks one of [exitNodes], or none: [onPick] gets its id, or "" for none. */
@Composable
fun ExitNodePicker(exitNodes: List<NbNetwork>, status: NbStatus?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    PickerSheet(
        title = stringResource(R.string.nb_exit_node),
        options = listOf(PickerOption("", stringResource(R.string.nb_exit_node_none), Icons.Default.Block)) +
            exitNodes.map { PickerOption(it.id, it.id, Icons.Default.Public, supporting = routingPeerOf(it, status)) },
        selected = exitNodes.firstOrNull { it.selected }?.id ?: "",
        onPick = onPick,
        onDismiss = onDismiss
    )
}

private fun toast(context: Context, e: Throwable) {
    Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
}
