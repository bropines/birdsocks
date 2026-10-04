package io.github.bropines.birdsocks.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Automation
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.ui.theme.findActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Action links (DeepLinks.requestFor) waiting for the user on the main
 * screen. Kept outside the activity, so a rotation does not drop the question.
 */
object ActionLinks {
    val pending = MutableStateFlow<DeepLinks.Request?>(null)

    /**
     * Takes a link MainActivity received. With the automation token (and
     * automation on) a command runs at once; otherwise the main screen asks
     * first, naming what it will do — a toggle as the start or stop it is now.
     */
    fun receive(activity: Activity, request: DeepLinks.Request, secret: String?) {
        when (request) {
            DeepLinks.Request.Invalid -> Toast.makeText(activity, R.string.automation_link_invalid, Toast.LENGTH_LONG).show()
            is DeepLinks.Request.AddAccount -> pending.value = request
            is DeepLinks.Request.Run -> {
                if (secret != null && Automation.refusal(activity, secret) == null) {
                    Automation.runFromActivity(activity, request.command)
                    return
                }
                val command = if (request.command != Automation.Command.Toggle) request.command
                    else if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) Automation.Command.Connect
                    else Automation.Command.Disconnect
                pending.value = DeepLinks.Request.Run(command)
            }
        }
    }
}

/** The question a pending action link asks, or the invite's add-account dialog. */
@Composable
fun ActionLinkDialogs() {
    val request by ActionLinks.pending.collectAsState()
    when (val r = request) {
        is DeepLinks.Request.Run -> ConfirmLinkDialog(r.command)
        is DeepLinks.Request.AddAccount -> InviteDialog(r)
        else -> {}
    }
}

@Composable
private fun ConfirmLinkDialog(command: Automation.Command) {
    val context = LocalContext.current
    // Read here, not in the dialog's own window (see wrapContextWithLocale).
    val strTitle = when (command) {
        Automation.Command.Connect -> stringResource(R.string.automation_link_connect)
        Automation.Command.Disconnect -> stringResource(R.string.automation_link_disconnect)
        is Automation.Command.ExitNode -> command.target?.let { stringResource(R.string.automation_link_exit_node, it) }
            ?: stringResource(R.string.automation_link_exit_none)
        is Automation.Command.Account -> stringResource(R.string.automation_link_account, command.name)
        is Automation.Command.Tun -> stringResource(if (command.on) R.string.automation_link_tun_on else R.string.automation_link_tun_off)
        // No link asks for the others.
        else -> Automation.describe(command)
    }
    val strBody = stringResource(R.string.automation_link_body)
    val strAllow = stringResource(R.string.automation_link_allow)
    val strCancel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = { ActionLinks.pending.value = null },
        icon = { Icon(Icons.Default.Link, null) },
        title = { Text(strTitle) },
        text = { Text(strBody) },
        confirmButton = {
            Button(onClick = {
                ActionLinks.pending.value = null
                context.findActivity()?.let { Automation.runFromActivity(it, command) }
            }) { Text(strAllow) }
        },
        dismissButton = { TextButton(onClick = { ActionLinks.pending.value = null }) { Text(strCancel) } }
    )
}

/** An invite link's account, filled in; nothing is added until the user taps. */
@Composable
private fun InviteDialog(invite: DeepLinks.Request.AddAccount) {
    val context = LocalContext.current
    val profile by NetbirdState.profile.collectAsState()
    val accounts by rememberAccounts(profile)
    AddAccountDialog(
        existing = accounts.map { it.profile.name },
        onDismiss = { ActionLinks.pending.value = null },
        initialServer = invite.server,
        initialName = invite.name,
        initialSetupKey = invite.setupKey
    ) { name, server, setupKey ->
        ActionLinks.pending.value = null
        // The main screen's sign-in card follows the login from here.
        switchAccount(context, name, add = true, server = server, setupKey = setupKey)
    }
}
