package io.github.bropines.birdsocks.admin

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiCreateSetupKey
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.DeepLinks
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsSwitchItem

/** The activity BiometricPrompt needs, from inside the composition. */
@Composable
internal fun rememberFragmentActivity(): FragmentActivity {
    val context = LocalContext.current
    return remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<FragmentActivity>().first()
    }
}

// --- One peer ---

/**
 * A peer's details and what may be changed: the name, login expiration, and
 * deletion behind typing the peer's name. Never this device's own peer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeerSheet(model: AdminViewModel, peer: ApiPeer, onDismiss: () -> Unit) {
    val activity = rememberFragmentActivity()
    val self = model.isSelf(peer)
    val canUpdate = model.can(Capabilities.PEERS, Op.UPDATE)
    val canDelete = model.can(Capabilities.PEERS, Op.DELETE) && !self
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(peer.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (self) Badge(stringResource(R.string.admin_peer_this_device), MaterialTheme.colorScheme.primaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column {
                    Detail(stringResource(R.string.admin_peer_address), listOf(peer.ip, peer.ipv6).filter { it.isNotEmpty() }.joinToString("\n"), mono = true)
                    Detail(stringResource(R.string.admin_peer_dns), peer.dnsLabel, mono = true)
                    Detail(stringResource(R.string.admin_peer_status), stringResource(if (peer.connected) R.string.admin_peer_online else R.string.admin_peer_offline) +
                        (formatApiDate(peer.lastSeen).takeIf { !peer.connected && it.isNotEmpty() }?.let { " · $it" } ?: ""))
                    Detail(stringResource(R.string.admin_peer_os), listOf(peer.os, peer.version).filter { it.isNotEmpty() }.joinToString(" · "))
                    Detail(stringResource(R.string.admin_peer_public_ip), listOf(peer.connectionIp, peer.cityName, peer.countryCode).filter { it.isNotEmpty() }.joinToString(" · "), mono = true)
                    Detail(stringResource(R.string.admin_peer_groups), peer.groups.joinToString(", ") { it.name })
                    Detail(stringResource(R.string.admin_peer_last_login), formatApiDate(peer.lastLogin))
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsSwitchItem(
                stringResource(R.string.admin_peer_login_expiration),
                stringResource(if (peer.loginExpired) R.string.admin_peer_login_expired_desc else R.string.admin_peer_login_expiration_desc),
                Icons.Default.Timer,
                checked = peer.loginExpirationEnabled,
                enabled = canUpdate && !model.busy
            ) { model.setLoginExpiration(activity, peer, it) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUpdate) OutlinedButton(enabled = !model.busy, onClick = { renaming = true }) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_rename))
                }
                if (canDelete) OutlinedButton(
                    enabled = !model.busy,
                    onClick = { deleting = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.admin_peer_delete))
                }
            }
            if (self) HelpText(stringResource(R.string.admin_peer_self_note), Modifier.padding(top = 8.dp))
            else if (!model.writesAllowed) HelpText(stringResource(R.string.admin_peer_read_only_note), Modifier.padding(top = 8.dp))
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(peer.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.admin_peer_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_peer_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != peer.name, onClick = { renaming = false; model.renamePeer(activity, peer, name) }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (deleting) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleting = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.admin_peer_delete_title, peer.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.admin_peer_delete_text))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.admin_type_name, peer.name)) })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == peer.name,
                    onClick = { deleting = false; onDismiss(); model.deletePeer(activity, peer) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.admin_peer_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun Detail(label: String, value: String, mono: Boolean = false) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

// --- Setup keys ---

/** A new setup key: name, one-off or reusable, days valid, groups, a usage limit for a reusable one, ephemeral peers. */
@Composable
internal fun CreateKeyDialog(model: AdminViewModel, onDismiss: () -> Unit, onCreated: (ApiSetupKey) -> Unit) {
    val activity = rememberFragmentActivity()
    var name by remember { mutableStateOf("") }
    var reusable by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("7") }
    var limit by remember { mutableStateOf("") }
    var ephemeral by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val groups = model.groups?.items.orEmpty().filter { it.name != "All" }.sortedBy { it.name.lowercase() }
    val dayCount = days.toLongOrNull()
    val daysOk = dayCount != null && dayCount * 86_400 in ApiCreateSetupKey.MIN_EXPIRES_SEC..ApiCreateSetupKey.MAX_EXPIRES_SEC
    val limitValue = if (reusable) limit.toIntOrNull() ?: if (limit.isBlank()) 0 else -1 else 0
    val valid = name.isNotBlank() && daysOk && limitValue >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_key_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.admin_key_name)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                SlidingSegmentedChips(
                    options = listOf(stringResource(R.string.admin_key_one_off), stringResource(R.string.admin_key_reusable)),
                    selectedIndex = if (reusable) 1 else 0,
                    onOptionSelected = { reusable = it == 1 },
                    modifier = Modifier.fillMaxWidth()
                )
                HelpText(stringResource(if (reusable) R.string.admin_key_reusable_desc else R.string.admin_key_one_off_desc), Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    days, { days = it.filter(Char::isDigit).take(3) }, singleLine = true,
                    label = { Text(stringResource(R.string.admin_key_days)) },
                    supportingText = { Text(stringResource(R.string.admin_key_days_hint)) },
                    isError = !daysOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (reusable) {
                    OutlinedTextField(
                        limit, { limit = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text(stringResource(R.string.admin_key_limit)) },
                        supportingText = { Text(stringResource(R.string.admin_key_limit_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_key_ephemeral), Modifier.weight(1f))
                    Switch(ephemeral, { ephemeral = it })
                }
                HelpText(stringResource(R.string.admin_key_ephemeral_desc))
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.admin_key_groups), style = MaterialTheme.typography.labelLarge)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { g ->
                            FilterChip(
                                selected = g.id in chosen,
                                onClick = { chosen = if (g.id in chosen) chosen - g.id else chosen + g.id },
                                label = { Text(g.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !model.busy, onClick = {
                val request = ApiCreateSetupKey(
                    name = name.trim(),
                    type = if (reusable) ApiSetupKey.TYPE_REUSABLE else ApiSetupKey.TYPE_ONE_OFF,
                    expiresIn = dayCount!! * 86_400,
                    autoGroups = chosen.toList(),
                    usageLimit = limitValue,
                    ephemeral = ephemeral,
                )
                model.createKey(activity, request, onCreated)
            }) { Text(stringResource(R.string.admin_key_create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/**
 * A key just created, shown this once: the server keeps only a hash. The key
 * itself and a QR of the BirdSocks invite link, so another phone joins with
 * one scan. Out of screenshots while it is open.
 */
@Composable
internal fun NewKeyDialog(model: AdminViewModel, key: ApiSetupKey, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = remember(key.key) { DeepLinks.inviteLink(model.account?.managementUrl.orEmpty(), "", key.key) }
    var showLink by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(stringResource(R.string.admin_key_new_title, key.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.admin_key_new_once), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                QrCodeImage(link, stringResource(R.string.admin_key_qr), Modifier.fillMaxWidth(0.8f))
                HelpText(stringResource(R.string.admin_key_qr_desc), Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(key.key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copySecret(context, key.name, key.key) }) { Text(stringResource(R.string.admin_key_copy)) }
                    TextButton(onClick = { copySecret(context, key.name, link) }) { Text(stringResource(R.string.admin_key_copy_link)) }
                }
                if (showLink) Text(link, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                else TextButton(onClick = { showLink = true }) { Text(stringResource(R.string.admin_key_show_link)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_key_new_done)) } }
    )
}
