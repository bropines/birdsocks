package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import kotlinx.coroutines.launch

/**
 * A new account with its server at once: NetBird Cloud or an own server
 * (a pasted dashboard link is cut to its origin), a name that defaults to
 * the server's, and a setup key or the browser. [onDone] gets the name, the
 * management URL and the key; signing in starts from there. An invite link
 * fills it in with the initial values ("" server: NetBird Cloud).
 */
@Composable
fun AddAccountDialog(
    existing: Collection<String>,
    onDismiss: () -> Unit,
    initialServer: String = "",
    initialName: String = "",
    initialSetupKey: String = "",
    onDone: (name: String, server: String, setupKey: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A link to NetBird Cloud (its API or its dashboard) is the Cloud choice.
    val cloudInvite = initialServer.isNotEmpty() &&
        runCatching { java.net.URI(initialServer).host }.getOrNull().orEmpty().lowercase().endsWith("netbird.io")
    val invite = initialServer.isNotEmpty() || initialName.isNotEmpty() || initialSetupKey.isNotEmpty()
    // Own server is the usual choice; an invite without a server of its own is for the Cloud.
    var own by rememberSaveable { mutableStateOf(if (invite) initialServer.isNotEmpty() && !cloudInvite else true) }
    var server by rememberSaveable { mutableStateOf(if (cloudInvite) "" else initialServer) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var useKey by rememberSaveable { mutableStateOf(initialSetupKey.isNotEmpty()) }
    var setupKey by rememberSaveable { mutableStateOf(initialSetupKey) }
    var probing by remember { mutableStateOf(false) }
    // A server that did not answer: the next tap adds it anyway.
    var unreachable by remember { mutableStateOf<String?>(null) }

    val url = if (own) serverOrigin(server) else GlobalSettings.CLOUD_MANAGEMENT_URL
    val suggested = if (own && server.isBlank()) "" else suggestAccountName(url, existing)
    val finalName = name.trim().ifEmpty { suggested }
    val taken = finalName in existing || finalName == "default"
    val valid = finalName.isNotEmpty() && !taken && (!own || server.isNotBlank()) && (!useKey || setupKey.isNotBlank())

    // Read here, not in the dialog's own window (see wrapContextWithLocale).
    val strTitle = stringResource(R.string.nb_accounts_add)
    val strCloud = stringResource(R.string.nb_login_cloud)
    val strOwn = stringResource(R.string.nb_login_self_hosted)
    val strServer = stringResource(R.string.nb_login_server)
    val strName = stringResource(R.string.nb_accounts_name)
    val strTaken = stringResource(R.string.nb_accounts_name_taken)
    val strUseKey = stringResource(R.string.nb_login_use_setup_key)
    val strKey = stringResource(R.string.nb_login_setup_key)
    val strUnreachable = unreachable?.let { stringResource(R.string.nb_login_unreachable, it) }
    val strConfirm = stringResource(if (unreachable != null) R.string.nb_accounts_add_anyway else R.string.nb_accounts_add_sign_in)
    val strCancel = stringResource(R.string.action_cancel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SlidingSegmentedChips(
                    items = listOf(SegmentedChipItem(strCloud, Icons.Default.Cloud), SegmentedChipItem(strOwn, Icons.Default.Dns)),
                    selectedIndex = if (own) 1 else 0,
                    onOptionSelected = { own = it == 1; unreachable = null },
                    modifier = Modifier.fillMaxWidth()
                )
                if (own) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it.trim(); unreachable = null },
                        label = { Text(strServer) },
                        placeholder = { Text("https://netbird.example.com") },
                        singleLine = true,
                        enabled = !probing,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strName) },
                    placeholder = { if (suggested.isNotEmpty()) Text(suggested) },
                    singleLine = true,
                    enabled = !probing,
                    isError = taken && finalName.isNotEmpty(),
                    supportingText = if (taken && finalName.isNotEmpty()) { { Text(strTaken) } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useKey, onCheckedChange = { useKey = it }, enabled = !probing)
                    Text(strUseKey)
                }
                if (useKey) {
                    OutlinedTextField(
                        value = setupKey,
                        onValueChange = { setupKey = it.trim() },
                        label = { Text(strKey) },
                        singleLine = true,
                        enabled = !probing,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                strUnreachable?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !probing,
                onClick = {
                    val key = setupKey.takeIf { useKey }
                    if (!own || unreachable != null) onDone(finalName, url, key)
                    else scope.launch {
                        // A typo in an own server's address should not end in a 30 s timeout.
                        probing = true
                        val problem = probeServer(context, url)
                        probing = false
                        if (problem == null) onDone(finalName, url, key) else unreachable = problem
                    }
                }
            ) {
                if (probing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(strConfirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strCancel) } }
    )
}

/**
 * The management URL in what was typed or pasted: a scheme added when
 * missing, and a dashboard link (https://netbird.example.com/peers) cut to
 * its origin — the management API answers at the root.
 */
fun serverOrigin(raw: String): String {
    val url = normalizeServer(raw)
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    return buildString {
        append(uri.scheme).append("://").append(host)
        if (uri.port != -1) append(':').append(uri.port)
    }
}

/**
 * A name for the account from its server: the domain's own label
 * (access.example.com → example, NetBird Cloud → netbird), numbered when
 * [existing] has it already.
 */
fun suggestAccountName(url: String, existing: Collection<String>): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.trim('[', ']')?.lowercase().orEmpty()
    if (host.isEmpty()) return ""
    val labels = host.split('.').filter { it.isNotEmpty() }
    val base = when {
        host.any { it == ':' } || labels.all { l -> l.all { it.isDigit() } } -> host
        labels.size < 2 -> labels.firstOrNull().orEmpty()
        // example.co.uk: the second-level label is the registry's, not the owner's.
        labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL -> labels[labels.size - 3]
        else -> labels[labels.size - 2]
    }
    if (base.isEmpty()) return ""
    if (base !in existing && base != "default") return base
    return (2..99).map { "$base $it" }.firstOrNull { it !in existing } ?: base
}

private val SECOND_LEVEL = setOf("co", "com", "net", "org", "ac", "gov", "edu")
