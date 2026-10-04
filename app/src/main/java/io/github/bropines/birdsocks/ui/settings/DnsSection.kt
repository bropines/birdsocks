package io.github.bropines.birdsocks.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.ui.DnsActivity
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsEditItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Settings → DNS: what NetBird resolves and by which names this device goes,
 * the local DNS proxy apps point at, and the DNS screen.
 */
@Composable
internal fun DnsSection(env: SettingsEnv) {
    val context = LocalContext.current
    val cfg = env.config

    SettingsCard(stringResource(R.string.nb_settings_dns), note = stringResource(R.string.settings_note_reconnect)) {
        if (cfg == null) {
            HelpText(stringResource(R.string.nb_settings_netbird_off))
        } else {
            SettingsSwitchItem(stringResource(R.string.nb_settings_dns), stringResource(R.string.nb_settings_dns_desc), Icons.Default.Dns, !cfg.disableDns) {
                env.setConfig { put("disableDns", !it) }
            }
            // Kept per profile here as well: the daemon does not report them back.
            var labels by remember(env.profile) { mutableStateOf(GlobalSettings.getDnsLabels(context, env.profile)) }
            SettingsEditItem(stringResource(R.string.nb_settings_dns_labels), labels, Icons.AutoMirrored.Filled.Label,
                placeholder = "phone, poco", description = stringResource(R.string.nb_settings_dns_labels_desc)
            ) { v ->
                val list = v.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                GlobalSettings.setDnsLabels(context, env.profile, list.joinToString(", "))
                labels = GlobalSettings.getDnsLabels(context, env.profile)
                env.setConfig {
                    putJsonArray("dnsLabels") { list.forEach { add(it) } }
                    put("cleanDNSLabels", list.isEmpty())
                }
            }
        }
    }

    SettingsCard(stringResource(R.string.nb_settings_dns_proxy), note = stringResource(R.string.settings_note_restart)) {
        var enabled by remember { mutableStateOf(GlobalSettings.isDnsProxyEnabled(context)) }
        var address by remember { mutableStateOf(GlobalSettings.getDnsProxyAddress(context)) }
        var upstream by remember { mutableStateOf(GlobalSettings.getDnsUpstream(context)) }
        SettingsSwitchItem(stringResource(R.string.nb_settings_dns_proxy), stringResource(R.string.nb_settings_dns_proxy_desc), Icons.Default.Dns, enabled) {
            env.startSetting { GlobalSettings.setDnsProxyEnabled(context, it); enabled = it }
        }
        SettingsEditItem(stringResource(R.string.nb_settings_dns_proxy_address), address, Icons.Default.SettingsEthernet,
            placeholder = GlobalSettings.DEFAULT_DNS_PROXY, description = stringResource(R.string.nb_settings_loopback_desc), enabled = enabled,
            onAction = { GlobalSettings.randomLoopback(GlobalSettings.getDnsProxyAddress(context).substringAfterLast(':').toIntOrNull() ?: 48153) },
            actionIcon = Icons.Default.Casino
        ) { v ->
            if (!GlobalSettings.isLoopbackAddress(v.trim())) {
                Toast.makeText(context, context.getString(R.string.nb_settings_bad_loopback), Toast.LENGTH_SHORT).show()
            } else env.startSetting { GlobalSettings.setDnsProxyAddress(context, v); address = GlobalSettings.getDnsProxyAddress(context) }
        }
        SettingsEditItem(stringResource(R.string.nb_settings_dns_upstream), upstream, Icons.AutoMirrored.Filled.CallSplit,
            placeholder = stringResource(R.string.nb_settings_dns_upstream_hint), description = stringResource(R.string.nb_settings_dns_upstream_desc), enabled = enabled
        ) { v -> env.startSetting { GlobalSettings.setDnsUpstream(context, v); upstream = GlobalSettings.getDnsUpstream(context) } }
    }

    SettingsCard(stringResource(R.string.settings_sect_tools)) {
        SettingsClickableItem(stringResource(R.string.nb_menu_dns), stringResource(R.string.settings_link_dns_desc), Icons.Default.Troubleshoot) {
            context.startActivity(Intent(context, DnsActivity::class.java))
        }
    }
}
