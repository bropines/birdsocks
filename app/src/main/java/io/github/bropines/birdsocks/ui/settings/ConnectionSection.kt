package io.github.bropines.birdsocks.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.ByeDpiProxy
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsEditItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import kotlinx.serialization.json.put

/** Settings → Connection: how peers connect (BirdSocks' start options), and the overlay's addresses and packets (the profile's). */
@Composable
internal fun ConnectionSection(env: SettingsEnv) {
    val context = LocalContext.current
    val cfg = env.config

    SettingsCard(stringResource(R.string.settings_card_peers), note = stringResource(R.string.settings_note_restart)) {
        var lazy by remember { mutableStateOf(GlobalSettings.getLazyConn(context)) }
        val lazyValues = listOf("", "on", "off")
        Text(stringResource(R.string.nb_settings_lazy), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        HelpText(stringResource(R.string.nb_settings_lazy_desc), Modifier.padding(bottom = 8.dp))
        SlidingSegmentedChips(
            listOf(stringResource(R.string.nb_settings_lazy_server), stringResource(R.string.nb_settings_lazy_on), stringResource(R.string.nb_settings_lazy_off)),
            lazyValues.indexOf(lazy).coerceAtLeast(0), { i ->
                env.startSetting { GlobalSettings.setLazyConn(context, lazyValues[i]); lazy = lazyValues[i] }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        var quic by remember { mutableStateOf(GlobalSettings.isRelayQuic(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_settings_quic), stringResource(R.string.nb_settings_quic_desc), Icons.Default.Speed, quic) {
            env.startSetting { GlobalSettings.setRelayQuic(context, it); quic = it }
        }
        var forceRelay by remember { mutableStateOf(GlobalSettings.isForceRelay(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_settings_force_relay), stringResource(R.string.nb_settings_force_relay_desc), Icons.AutoMirrored.Filled.CallSplit, forceRelay) {
            env.startSetting { GlobalSettings.setForceRelay(context, it); forceRelay = it }
        }
    }

    ServerConnectionCard(env)

    SettingsCard(stringResource(R.string.settings_card_packets), note = stringResource(R.string.settings_note_reconnect)) {
        if (cfg == null) {
            HelpText(stringResource(R.string.nb_settings_netbird_off))
        } else {
            SettingsSwitchItem(stringResource(R.string.nb_settings_ipv6), stringResource(R.string.nb_settings_ipv6_desc), Icons.Default.Language, !cfg.disableIpv6) {
                env.setConfig { put("disableIpv6", !it) }
            }
            SettingsEditItem("MTU", if (cfg.mtu > 0) cfg.mtu.toString() else "", Icons.Default.Straighten,
                placeholder = "1280", description = stringResource(R.string.nb_settings_mtu_desc)
            ) { v ->
                val mtu = v.trim().toIntOrNull()
                if (mtu == null || mtu !in 576..8192) Toast.makeText(context, context.getString(R.string.nb_settings_bad_mtu), Toast.LENGTH_SHORT).show()
                else env.setConfig { put("mtu", mtu) }
            }
        }
    }
}

/**
 * How the daemon reaches its control plane — management, signal, the relay:
 * directly, through a proxy of the user's (SOCKS5 or HTTP), or through
 * ByeDPI, which gets the TLS handshake past DPI. Peer traffic is UDP and
 * keeps its own way.
 */
@Composable
private fun ServerConnectionCard(env: SettingsEnv) {
    val context = LocalContext.current
    val modes = listOf(GlobalSettings.CONTROL_DIRECT, GlobalSettings.CONTROL_PROXY, GlobalSettings.CONTROL_BYEDPI)
    var mode by remember { mutableStateOf(GlobalSettings.getControlMode(context)) }
    SettingsCard(stringResource(R.string.settings_card_server_connection), note = stringResource(R.string.settings_note_restart)) {
        HelpText(stringResource(R.string.cp_desc), Modifier.padding(top = 4.dp, bottom = 8.dp))
        SlidingSegmentedChips(
            listOf(stringResource(R.string.cp_mode_direct), stringResource(R.string.cp_mode_proxy), "ByeDPI"),
            modes.indexOf(mode).coerceAtLeast(0), { i ->
                env.startSetting { GlobalSettings.setControlMode(context, modes[i]); mode = modes[i] }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        when (mode) {
            GlobalSettings.CONTROL_PROXY -> {
                var type by remember { mutableStateOf(GlobalSettings.getControlProxyType(context)) }
                var host by remember { mutableStateOf(GlobalSettings.getControlProxyHost(context)) }
                var port by remember { mutableStateOf(GlobalSettings.getControlProxyPort(context)) }
                var user by remember { mutableStateOf(GlobalSettings.getControlProxyUser(context)) }
                var pass by remember { mutableStateOf(GlobalSettings.getControlProxyPass(context)) }
                val types = listOf("socks5", "http")
                SlidingSegmentedChips(
                    listOf("SOCKS5", "HTTP"), types.indexOf(type).coerceAtLeast(0), { i ->
                        env.startSetting { GlobalSettings.setControlProxyType(context, types[i]); type = types[i] }
                    }, Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
                SettingsEditItem(stringResource(R.string.cp_host), host, Icons.Default.Dns, placeholder = "proxy.example.com") {
                    env.startSetting { GlobalSettings.setControlProxyHost(context, it); host = it.trim() }
                }
                SettingsEditItem(stringResource(R.string.cp_port), port, Icons.Default.Numbers, placeholder = if (type == "http") "8080" else "1080") { v ->
                    val p = v.trim().toIntOrNull()
                    if (v.isNotBlank() && (p == null || p !in 1..65535)) Toast.makeText(context, context.getString(R.string.cp_bad_port), Toast.LENGTH_SHORT).show()
                    else env.startSetting { GlobalSettings.setControlProxyPort(context, v); port = v.trim() }
                }
                SettingsEditItem(stringResource(R.string.cp_user), user, Icons.Default.Person, description = stringResource(R.string.cp_auth_desc)) {
                    env.startSetting { GlobalSettings.setControlProxyUser(context, it.trim()); user = it.trim() }
                }
                SettingsEditItem(stringResource(R.string.cp_pass), if (pass.isEmpty()) "" else "••••••••", Icons.Default.Password, description = stringResource(R.string.cp_auth_desc)) {
                    env.startSetting { GlobalSettings.setControlProxyPass(context, it); pass = it }
                }
                if (host.isBlank()) {
                    Text(stringResource(R.string.cp_no_host), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
            }
            GlobalSettings.CONTROL_BYEDPI -> {
                var flags by remember { mutableStateOf(GlobalSettings.getByeDpiFlags(context)) }
                var ipv4 by remember { mutableStateOf(GlobalSettings.isByeDpiIpv4Only(context)) }
                SettingsEditItem(stringResource(R.string.cp_byedpi_flags), flags, Icons.Default.Tune,
                    placeholder = ByeDpiProxy.DEFAULT_FLAGS, description = stringResource(R.string.cp_byedpi_flags_desc)
                ) { env.startSetting { GlobalSettings.setByeDpiFlags(context, it); flags = it.trim() } }
                SettingsSwitchItem(stringResource(R.string.cp_byedpi_ipv4), stringResource(R.string.cp_byedpi_ipv4_desc), Icons.Default.Language, ipv4) {
                    env.startSetting { GlobalSettings.setByeDpiIpv4Only(context, it); ipv4 = it }
                }
                // Where it listens, while it does: its own log lines are in Diagnostics.
                ByeDpiProxy.activeAddress?.let { (ip, p) ->
                    Text(stringResource(R.string.cp_byedpi_running, "$ip:$p"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
