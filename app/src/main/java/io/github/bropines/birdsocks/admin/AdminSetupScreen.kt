package io.github.bropines.birdsocks.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.admin.secure.SecureWindow
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.openUrl
import io.github.bropines.birdsocks.ui.readableWidth
import kotlinx.coroutines.launch

/** Where NetBird documents access tokens and service users. */
private const val TOKEN_DOCS = "https://docs.netbird.io/how-to/access-netbird-public-api"

/**
 * The panel's credential for the active account: a personal access token,
 * tested before it is stored, the read-only switch, and the experimental
 * sign-in with this device's own account.
 */
@Composable
internal fun AdminSetupScreen(model: AdminViewModel, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<Result<ApiUser>?>(null) }
    var readOnly by remember { mutableStateOf(model.readOnly) }
    var confirmForget by remember { mutableStateOf(false) }
    val noLock = model.lockState == LockState.NO_SCREEN_LOCK
    val device = model.deviceSignIn
    SecureWindow()

    LaunchedEffect(device) {
        if (device is AdminViewModel.DeviceSignIn.Browser) openUrl(context, device.url)
    }

    Scaffold(topBar = {
        AppTopBar(
            title = stringResource(R.string.admin_credential_title),
            subtitle = model.account?.let { "${it.profile} · ${it.host}" },
            onBack = onBack
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            model.setupReason?.let { reason ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(reason.res), color = MaterialTheme.colorScheme.onErrorContainer)
                        reason.error?.let { Text(describeError(context, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                        if (reason.error != null && model.hasToken) {
                            TextButton(onClick = { model.start() }) { Text(stringResource(R.string.admin_retry)) }
                        }
                    }
                }
            }

            // What a token is worth, before it is pasted.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.WarningAmber, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.admin_token_warning_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        HelpText(stringResource(R.string.admin_token_warning), color = MaterialTheme.colorScheme.onTertiaryContainer, lines = 3)
                    }
                }
            }

            SettingsCard(stringResource(R.string.admin_token_card)) {
                if (model.hasToken) HelpText(stringResource(R.string.admin_token_stored), Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim(); tested = null },
                    label = { Text(stringResource(R.string.admin_token_label)) },
                    placeholder = { Text("nbp_…") },
                    singleLine = true,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { shown = !shown }) {
                            Icon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (shown) R.string.admin_token_hide else R.string.admin_token_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { openUrl(context, TOKEN_DOCS) }) {
                    Icon(Icons.AutoMirrored.Filled.Help, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.admin_token_docs))
                }
                when (val t = tested) {
                    null -> Unit
                    else -> t.fold(
                        onSuccess = { u ->
                            Text(
                                stringResource(R.string.admin_test_ok, u.email.ifEmpty { u.name.ifEmpty { u.id } }, u.role),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onFailure = { Text(describeError(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = token.isNotBlank() && !testing, onClick = {
                        testing = true
                        scope.launch { tested = model.testToken(token); testing = false }
                    }) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.admin_test))
                    }
                    Button(enabled = token.isNotBlank() && tested?.isSuccess == true, onClick = {
                        model.saveToken(token, readOnly)
                        token = ""
                        tested = null
                        onSaved()
                    }) { Text(stringResource(R.string.action_save)) }
                }
                HelpText(stringResource(R.string.admin_test_first), Modifier.padding(top = 4.dp), lines = 1)
            }

            SettingsCard(stringResource(R.string.admin_safety_card)) {
                SettingsSwitchItem(
                    stringResource(R.string.admin_read_only),
                    stringResource(if (noLock) R.string.admin_read_only_no_lock else R.string.admin_read_only_desc),
                    Icons.Default.Lock,
                    checked = readOnly || noLock,
                    enabled = !noLock
                ) {
                    readOnly = it
                    if (model.hasToken || model.authMode == AdminViewModel.AuthMode.DEVICE) model.changeReadOnly(it)
                }
            }

            SettingsCard(stringResource(R.string.admin_device_card)) {
                HelpText(stringResource(R.string.admin_device_desc), lines = 3)
                Spacer(Modifier.height(8.dp))
                when (device) {
                    is AdminViewModel.DeviceSignIn.Failed -> Text(describeError(context, device.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is AdminViewModel.DeviceSignIn.Browser -> Text(stringResource(R.string.nb_login_waiting), style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
                if (model.authMode == AdminViewModel.AuthMode.DEVICE && model.phase == AdminViewModel.Phase.READY) {
                    OutlinedButton(onClick = { model.signOutDevice() }) { Text(stringResource(R.string.admin_device_stop)) }
                } else {
                    val working = device is AdminViewModel.DeviceSignIn.Working || device is AdminViewModel.DeviceSignIn.Browser
                    OutlinedButton(enabled = !working, onClick = { model.startDeviceSignIn() }) {
                        if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Science, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.admin_device_button))
                        }
                    }
                }
            }

            if (model.hasToken) {
                TextButton(onClick = { confirmForget = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.admin_token_forget))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.admin_token_forget)) },
            text = { Text(stringResource(R.string.admin_token_forget_confirm)) },
            confirmButton = { TextButton(onClick = { confirmForget = false; model.forgetToken() }) { Text(stringResource(R.string.admin_token_forget_action)) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
