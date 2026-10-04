package io.github.bropines.birdsocks.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.ui.AccountSheet
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SessionRow
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsEditItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.openUrl
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Settings → Account & device: who is signed in and where, the way to the
 * account switcher, this device's name and session, and the profile's own
 * switches about networks.
 */
@Composable
internal fun AccountSection(env: SettingsEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAccounts by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val cfg = env.config

    SettingsCard(stringResource(R.string.settings_card_account)) {
        // The active account, and the sheet that switches, adds, renames and
        // removes accounts: one place for all of that.
        val server = cfg?.managementUrl?.let { Uri.parse(it).host ?: it }.orEmpty()
        val label = when {
            !env.running -> stringResource(R.string.nb_accounts_title)
            env.profile == "default" -> server.ifEmpty { env.profile }
            else -> env.profile
        }
        val who = if (env.running) {
            listOf(GlobalSettings.getAccountEmail(context, env.profile), server.takeIf { it != label }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · ")
        } else ""
        SettingsClickableItem(
            title = label,
            subtitle = listOf(who, stringResource(R.string.settings_account_hint)).filter { it.isNotEmpty() }.joinToString("\n"),
            icon = Icons.Default.AccountCircle
        ) { showAccounts = true }

        var deviceName by remember { mutableStateOf(GlobalSettings.getDeviceName(context)) }
        SettingsEditItem(
            title = stringResource(R.string.nb_settings_device_name),
            value = deviceName,
            icon = Icons.Default.Smartphone,
            placeholder = GlobalSettings.systemDeviceName(context),
            description = stringResource(R.string.nb_settings_device_name_desc)
        ) { env.startSetting { GlobalSettings.setDeviceName(context, it); deviceName = GlobalSettings.getDeviceName(context) } }

        cfg?.let {
            // The dashboard of that server: where its peers, groups and keys are managed.
            SettingsClickableItem(
                title = stringResource(R.string.nb_settings_server),
                subtitle = it.managementUrl,
                icon = Icons.Default.Dns
            ) { openUrl(context, it.dashboardUrl) }
        }
        env.status?.sessionExpiresAt?.let { Box(Modifier.padding(vertical = 4.dp)) { SessionRow(it) } }

        SettingsClickableItem(
            title = stringResource(R.string.nb_settings_logout),
            subtitle = stringResource(R.string.nb_settings_logout_desc),
            icon = Icons.AutoMirrored.Filled.Logout,
            enabled = env.running && env.status?.state?.needsLogin == false
        ) { confirmLogout = true }
    }

    SettingsCard(stringResource(R.string.settings_card_network), note = stringResource(R.string.settings_note_reconnect)) {
        if (cfg == null) {
            HelpText(stringResource(R.string.nb_settings_netbird_off))
        } else {
            // Rosenpass is not offered: its key exchange runs over the overlay
            // from a host socket, which userspace mode cannot route, so the
            // switch would read "on" without post-quantum keys.
            SettingsSwitchItem(stringResource(R.string.nb_settings_client_routes), stringResource(R.string.nb_settings_client_routes_desc), Icons.Default.Hub, !cfg.disableClientRoutes) {
                env.setConfig { put("disableClientRoutes", !it) }
            }
            SettingsSwitchItem(stringResource(R.string.nb_settings_server_routes), stringResource(R.string.nb_settings_server_routes_desc), Icons.AutoMirrored.Filled.AltRoute, !cfg.disableServerRoutes) {
                env.setConfig { put("disableServerRoutes", !it) }
            }
            SettingsEditItem(stringResource(R.string.nb_settings_psk), if (cfg.preSharedKey.isEmpty()) "" else "••••••••", Icons.Default.Key,
                description = stringResource(R.string.nb_settings_psk_desc)
            ) { v -> env.setConfig { put("optionalPreSharedKey", v.trim()) } }
        }
    }

    if (showAccounts) AccountSheet(onDismiss = { showAccounts = false })

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.nb_settings_logout)) },
            text = { Text(stringResource(R.string.nb_settings_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { runCatching { Netbird.logout() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } }
                }) { Text(stringResource(R.string.nb_settings_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
