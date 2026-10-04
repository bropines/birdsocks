package io.github.bropines.birdsocks.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.models.NbProfile
import kotlinx.coroutines.launch

/** A profile and the server it points at, for the account lists. */
data class Account(val profile: NbProfile, val server: String) {
    /** The default profile has no name of its own: its server stands in for one. */
    val label: String get() = if (profile.isDefault) server.ifEmpty { profile.name } else profile.name
}

/** The daemon's profiles with their servers; re-read when [key] changes. */
@Composable
fun rememberAccounts(key: Any?): State<List<Account>> {
    val accounts = remember { mutableStateOf<List<Account>>(emptyList()) }
    val daemon by NetbirdState.daemon.collectAsState()
    LaunchedEffect(key, daemon) {
        if (daemon != NetbirdState.Daemon.Running) return@LaunchedEffect
        accounts.value = runCatching {
            Netbird.profiles().map { p ->
                val url = runCatching { Netbird.config(p.name).managementUrl }.getOrDefault("")
                Account(p, Uri.parse(url).host ?: url)
            }
        }.getOrDefault(accounts.value)
    }
    return accounts
}

/** The account switcher: tap one to make it active, or add another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by NetbirdState.profile.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val accounts by rememberAccounts(reload to active)
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // Accounts live in the daemon: a stopped app starts it to show them.
    LaunchedEffect(Unit) {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) io.github.bropines.birdsocks.core.NetbirdService.start(context)
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching {
                if (!io.github.bropines.birdsocks.core.NetbirdService.awaitRunning(context)) error(context.getString(R.string.nb_error_not_running))
                block()
            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            busy = false
            reload++
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.nb_accounts_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            accounts.forEach { a ->
                val isActive = a.profile.name == active || (active == null && a.profile.isActive)
                ListItem(
                    modifier = Modifier.clickable(enabled = !busy && !isActive) {
                        act { Netbird.switchProfile(a.profile.name) }
                    },
                    leadingContent = { Icon(if (isActive) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(a.label) },
                    supportingContent = {
                        val email = GlobalSettings.getAccountEmail(context, a.profile.name)
                        val line = listOfNotNull(email.ifEmpty { null }, a.server.takeIf { !a.profile.isDefault && it.isNotEmpty() }).joinToString(" · ")
                        if (line.isNotEmpty()) Text(line)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { adding = true },
                leadingContent = { Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.nb_accounts_add)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }

    if (adding) {
        ProfileNameDialog(
            title = stringResource(R.string.nb_accounts_add),
            initial = "",
            onDismiss = { adding = false }
        ) { name ->
            adding = false
            // A new profile is not logged in: switching to it brings the sign-in card.
            act { Netbird.addProfile(name); Netbird.switchProfile(name) }
        }
    }
}

@Composable
fun ProfileNameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.trim() != "default"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.nb_accounts_name)) },
                placeholder = { Text(stringResource(R.string.nb_accounts_name_hint)) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onDone(name.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Settings: every account, with rename and remove for the ones that allow it. */
@Composable
fun AccountsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by NetbirdState.profile.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val accounts by rememberAccounts(reload to active)
    var renaming by remember { mutableStateOf<Account?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            reload++
        }
    }

    SettingsCard(stringResource(R.string.nb_accounts_title)) {
        if (accounts.isEmpty()) HelpText(stringResource(R.string.nb_settings_netbird_off))
        accounts.forEach { a ->
            val isActive = a.profile.name == active
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isActive) stringResource(R.string.nb_accounts_active) else a.server,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!a.profile.isDefault) {
                    IconButton(onClick = { renaming = a }) { Icon(Icons.Default.Edit, stringResource(R.string.nb_accounts_rename)) }
                    IconButton(enabled = !isActive, onClick = { removing = a }) { Icon(Icons.Default.Delete, stringResource(R.string.nb_accounts_remove)) }
                }
            }
        }
    }

    renaming?.let { a ->
        ProfileNameDialog(stringResource(R.string.nb_accounts_rename), a.profile.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            act {
                Netbird.renameProfile(a.profile.name, name)
                GlobalSettings.renameProfileKeys(context, a.profile.name, name)
                if (NetbirdState.profile.value == a.profile.name) NetbirdState.profileFlow.value = name
            }
        }
    }
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.nb_accounts_remove)) },
            text = { Text(stringResource(R.string.nb_accounts_remove_confirm, a.label)) },
            confirmButton = { TextButton(onClick = { removing = null; act { Netbird.removeProfile(a.profile.name); GlobalSettings.removeProfileKeys(context, a.profile.name) } }) { Text(stringResource(R.string.nb_accounts_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
