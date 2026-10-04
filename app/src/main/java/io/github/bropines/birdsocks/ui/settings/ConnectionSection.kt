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
