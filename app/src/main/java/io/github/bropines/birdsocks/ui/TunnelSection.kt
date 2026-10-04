package io.github.bropines.birdsocks.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.NetAddr
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.TunRoutes
import io.github.bropines.birdsocks.core.TunVpnService

/**
 * Settings → Tunnel mode. The Settings hub shows this section only while
 * [AVAILABLE] is true and draws [TunnelSettings] inside it; the TUN work
 * owns this file.
 */
object TunnelSection {
    const val AVAILABLE = true
}

/**
 * Proxy or VPN, and the VPN's own settings. Turning the VPN on or off needs
 * no daemon restart: the daemon always answers the VPN's resolver, and the
 * tunnel follows the running daemon (TunVpnService). Its settings apply at
 * once — a changed route set or exclusion list rebuilds the VPN.
 */
@Composable
fun ColumnScope.TunnelSettings() {
    val context = LocalContext.current
    var tunOn by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    var askSlot by remember { mutableStateOf(false) }
    val running by TunVpnService.running.collectAsState()
    val routes by TunVpnService.routes.collectAsState()
    val daemon by NetbirdState.daemon.collectAsState()
    // Re-read what other screens change (the excluded apps) on the way back.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()

    fun enable() {
        GlobalSettings.setTunModeEnabled(context, true)
        tunOn = true
        TunVpnService.clearConsentNotice(context)
        TunVpnService.start(context)
    }
    fun disable() {
        GlobalSettings.setTunModeEnabled(context, false)
        tunOn = false
        TunVpnService.stop(waitMs = 0)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) enable()
        else Toast.makeText(context, context.getString(R.string.nb_tun_denied), Toast.LENGTH_SHORT).show()
    }

    SettingsCard(stringResource(R.string.nb_tun_mode_title)) {
        SlidingSegmentedChips(
            options = listOf(stringResource(R.string.nb_tun_mode_proxy), stringResource(R.string.nb_tun_mode_vpn)),
            selectedIndex = if (tunOn) 1 else 0,
            onOptionSelected = { i ->
                when {
                    i == 1 && !tunOn -> askSlot = true
                    i == 0 && tunOn -> disable()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        HelpText(stringResource(if (tunOn) R.string.nb_tun_mode_vpn_desc else R.string.nb_tun_mode_proxy_desc))
        if (!TunVpnService.nativeLoaded) {
            Text(
                stringResource(R.string.nb_tun_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (tunOn) SettingsCard(stringResource(R.string.nb_tun_card)) {
        val needsConsent = remember(running, daemon, lifecycle) { VpnService.prepare(context) != null }
        val state = when {
            running -> {
                val how = routes?.defaultReason?.let { reason ->
                    stringResource(
                        R.string.nb_tun_routes_all,
                        stringResource(
                            when (reason) {
                                "exit node" -> R.string.nb_tun_reason_exit
                                "domain route" -> R.string.nb_tun_reason_domain
                                else -> R.string.nb_tun_reason_all
                            }
                        )
                    )
                } ?: stringResource(R.string.nb_tun_routes_split)
                stringResource(R.string.nb_tun_status_up, how)
            }
            needsConsent -> stringResource(R.string.nb_tun_status_consent)
            else -> stringResource(R.string.nb_tun_status_waiting)
        }
        Text(
            state,
            style = MaterialTheme.typography.bodyMedium,
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        HelpText(
            stringResource(R.string.nb_tun_help, NetAddr.dialable(GlobalSettings.getSocksAddress(context))),
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        var routeAll by remember { mutableStateOf(GlobalSettings.isTunRouteAll(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_tun_route_all_title), stringResource(R.string.nb_tun_route_all_desc), Icons.Default.Route, routeAll) {
            GlobalSettings.setTunRouteAll(context, it); routeAll = it
            TunVpnService.start(context)
        }
        var ipv6 by remember { mutableStateOf(GlobalSettings.isTunIpv6Enabled(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_tun_ipv6_title), stringResource(R.string.nb_tun_ipv6_desc), Icons.Default.Language, ipv6) {
            GlobalSettings.setTunIpv6Enabled(context, it); ipv6 = it
            TunVpnService.start(context)
        }
        val excludedApps = remember(lifecycle) { GlobalSettings.getTunExcludedApps(context).size }
        SettingsClickableItem(
            title = stringResource(R.string.nb_tun_apps_title),
            subtitle = stringResource(R.string.nb_tun_apps_desc, excludedApps),
            icon = Icons.Default.Apps
        ) { context.startActivity(Intent(context, TunExcludedAppsActivity::class.java)) }
        var cidrs by remember { mutableStateOf(GlobalSettings.getTunExcludedCIDRs(context)) }
        SettingsEditItem(
            stringResource(R.string.nb_tun_cidrs_title), cidrs, Icons.Default.Block,
            placeholder = stringResource(R.string.nb_tun_cidrs_hint), description = stringResource(R.string.nb_tun_cidrs_desc)
        ) { v ->
            val list = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val bad = list.firstOrNull { TunRoutes.normalize(it) == null }
            if (bad != null) {
                Toast.makeText(context, context.getString(R.string.nb_tun_address_invalid, bad), Toast.LENGTH_SHORT).show()
            } else {
                GlobalSettings.setTunExcludedCIDRs(context, list.joinToString(","))
                cidrs = GlobalSettings.getTunExcludedCIDRs(context)
                TunVpnService.start(context)
            }
        }
        var address by remember { mutableStateOf(GlobalSettings.getTunAddress(context)) }
        SettingsEditItem(
            stringResource(R.string.nb_tun_address_title), address, Icons.Default.SettingsEthernet,
            placeholder = GlobalSettings.DEFAULT_TUN_ADDRESS, description = stringResource(R.string.nb_tun_address_desc)
        ) { v ->
            val value = v.trim().ifEmpty { GlobalSettings.DEFAULT_TUN_ADDRESS }
            val withPrefix = if ('/' in value) value else "$value/32"
            if (':' in value || TunRoutes.normalize(withPrefix) == null || withPrefix.substringAfter('/').toInt() !in 1..32) {
                Toast.makeText(context, context.getString(R.string.nb_tun_address_invalid, v), Toast.LENGTH_SHORT).show()
            } else {
                GlobalSettings.setTunAddress(context, withPrefix)
                address = GlobalSettings.getTunAddress(context)
                TunVpnService.start(context)
            }
        }
    }

    if (askSlot) {
        val busy = remember { TunVpnService.otherVpnActive(context) }
        AlertDialog(
            onDismissRequest = { askSlot = false },
            title = { Text(stringResource(R.string.nb_tun_warning_title)) },
            text = { Text(stringResource(if (busy) R.string.nb_tun_warning_body_busy else R.string.nb_tun_warning_body)) },
            confirmButton = {
                Button(onClick = {
                    askSlot = false
                    val ask = VpnService.prepare(context)
                    if (ask == null) enable() else runCatching { consent.launch(ask) }.onFailure {
                        Toast.makeText(context, context.getString(R.string.nb_tun_denied), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.nb_tun_warning_confirm)) }
            },
            dismissButton = { TextButton(onClick = { askSlot = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
