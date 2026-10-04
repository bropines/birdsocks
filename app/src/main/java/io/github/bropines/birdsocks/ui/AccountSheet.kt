package io.github.bropines.birdsocks.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.BirdSocksApp
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.LoginFlow
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.models.NbConnState
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

/**
 * Makes [name] the active account, in the app's scope: the status card shows
 * the reconnect, and a profile not signed in yet its sign-in card. [add]
 * creates it first; a new account with its [server] signs in right after the
 * switch instead of connecting. The account sheet and invite links use it.
 */
fun switchAccount(context: Context, name: String, add: Boolean = false, server: String? = null, setupKey: String? = null) {
    val app = context.applicationContext
    BirdSocksApp.scope.launch {
        runCatching {
            if (!NetbirdService.awaitRunning(app)) error(app.getString(R.string.nb_error_not_running))
            if (add) Netbird.addProfile(name)
            Netbird.switchProfile(name, connect = server == null)
            if (server != null) LoginFlow.start(app, server, setupKey)
        }.onFailure { Toast.makeText(app, it.message, Toast.LENGTH_LONG).show() }
    }
}

/**
 * Accounts, in one place: tap one to make it active, add another, and Edit
 * to rename or remove. The default profile keeps its name and stays, and
 * the daemon removes no active profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by NetbirdState.profile.collectAsState()
    val status by NetbirdState.status.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val accounts by rememberAccounts(reload to active)
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Account?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    // Accounts live in the daemon: a stopped app starts it to show them.
    LaunchedEffect(Unit) {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) NetbirdService.start(context)
    }

    // Renaming and removing stay in the sheet, which re-reads the list after.
    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching {
                if (!NetbirdService.awaitRunning(context)) error(context.getString(R.string.nb_error_not_running))
                block()
            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            busy = false
            reload++
        }
    }

    // The switch outlives the sheet, which closes at once.
    fun switchTo(name: String, add: Boolean = false, server: String? = null, setupKey: String? = null) {
        switchAccount(context, name, add, server, setupKey)
        onDismiss()
    }

    // A sheet is a window of its own, which may not get the app's language:
    // its strings are read out here (see wrapContextWithLocale).
    val strTitle = stringResource(R.string.nb_accounts_title)
    val strHelp = stringResource(R.string.nb_accounts_sheet_help)
    val strEdit = stringResource(R.string.action_edit)
    val strDone = stringResource(R.string.accounts_done)
    val strAdd = stringResource(R.string.nb_accounts_add)
    val strNotConnected = stringResource(R.string.accounts_not_connected)
    val strRename = stringResource(R.string.nb_accounts_rename)
    val strRemove = stringResource(R.string.nb_accounts_remove)
    val strCancel = stringResource(R.string.action_cancel)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (accounts.any { !it.profile.isDefault }) {
                    TextButton(onClick = { editing = !editing }) {
                        Text(if (editing) strDone else strEdit)
                    }
                }
            }
            HelpText(strHelp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(accounts, key = { it.profile.name }) { a ->
                    val isActive = a.profile.name == active || (active == null && a.profile.isActive)
                    val email = remember(a.profile.name, reload) { GlobalSettings.getAccountEmail(context, a.profile.name) }
                    // The active account says what it is doing; the others where they sign in.
                    val live = if (!isActive) null
                        else status?.takeIf { it.state == NbConnState.Connected }?.fullStatus?.localPeerState?.address?.ifEmpty { null }
                            ?: strNotConnected
                    AccountRow(
                        account = a,
                        line = listOfNotNull(email.ifEmpty { null }, a.server.takeIf { !a.profile.isDefault && it.isNotEmpty() }, live).joinToString(" · "),
                        active = isActive,
                        editing = editing,
                        enabled = !busy,
                        onClick = { if (!isActive) switchTo(a.profile.name) else onDismiss() },
                        onRename = if (a.profile.isDefault) null else { { renaming = a } },
                        onRemove = if (a.profile.isDefault) null else { { removing = a } },
                        removable = !isActive,
                        renameLabel = strRename,
                        removeLabel = strRemove
                    )
                }
                item {
                    // A row, not a button: adding is part of the same list.
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = false; adding = true },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                strAdd,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            if (busy || accounts.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }

    if (adding) {
        AddAccountDialog(
            existing = accounts.map { it.profile.name },
            onDismiss = { adding = false }
        ) { name, server, setupKey ->
            adding = false
            // The main screen's sign-in card follows the login from here.
            switchTo(name, add = true, server = server, setupKey = setupKey)
        }
    }
    renaming?.let { a ->
        ProfileNameDialog(strRename, a.profile.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            act {
                Netbird.renameProfile(a.profile.name, name)
                GlobalSettings.renameProfileKeys(context, a.profile.name, name)
                if (NetbirdState.profile.value == a.profile.name) NetbirdState.profileFlow.value = name
            }
        }
    }
    removing?.let { a ->
        val strConfirm = stringResource(R.string.nb_accounts_remove_confirm, a.label)
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(strRemove) },
            text = { Text(strConfirm) },
            confirmButton = {
                Button(
                    onClick = {
                        removing = null
                        act { Netbird.removeProfile(a.profile.name); GlobalSettings.removeProfileKeys(context, a.profile.name) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Text(strRemove) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(strCancel) } }
        )
    }
}

/**
 * One account in the sheet: its initial, its name and one line about it;
 * in Edit mode, rename and remove ([onRename], [onRemove] null where the
 * profile allows neither; [removable] false for the active one).
 */
@Composable
private fun AccountRow(
    account: Account,
    line: String,
    active: Boolean,
    editing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    removable: Boolean,
    renameLabel: String,
    removeLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !editing, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(account.label, active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onRename != null) {
                        FilledTonalIconButton(onClick = onRename, enabled = enabled, modifier = Modifier.size(38.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Edit, renameLabel, Modifier.size(17.dp))
                        }
                    }
                    if (onRemove != null) {
                        FilledTonalIconButton(
                            onClick = onRemove,
                            enabled = enabled && removable,
                            modifier = Modifier.size(38.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Delete, removeLabel, Modifier.size(17.dp))
                        }
                    }
                }
            } else if (active) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The account's initial in a circle; NetBird gives no picture. */
@Composable
private fun AccountAvatar(name: String, active: Boolean) {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ProfileNameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.trim() != "default"
    // Read here, not in the dialog's own window (see wrapContextWithLocale).
    val strName = stringResource(R.string.nb_accounts_name)
    val strHint = stringResource(R.string.nb_accounts_name_hint)
    val strSave = stringResource(R.string.action_save)
    val strCancel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(strName) },
                placeholder = { Text(strHint) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onDone(name.trim()) }) { Text(strSave) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strCancel) } }
    )
}

