package io.github.bropines.birdsocks.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.ui.ExposeActivity
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import kotlinx.serialization.json.put

/**
 * Settings → Access to this phone. The two switches that decide whether peers
 * reach the phone's own services sit together, since one overrides the other:
 * Access to the phone is BirdSocks' (it lets peers reach the phone's own
 * services, read at start), Block inbound is NetBird's (in the profile, it
 * drops whatever peers open, and wins).
 */
@Composable
internal fun AccessSection(env: SettingsEnv) {
    val context = LocalContext.current
    val cfg = env.config
    var inbound by remember { mutableStateOf(GlobalSettings.isInboundAccess(context)) }

    SettingsCard(stringResource(R.string.settings_card_inbound)) {
        SettingsSwitchItem(stringResource(R.string.nb_settings_inbound), stringResource(R.string.nb_settings_inbound_desc), Icons.AutoMirrored.Filled.CallReceived, inbound) {
            env.startSetting { GlobalSettings.setInboundAccess(context, it); inbound = it }
        }
        if (cfg == null) {
            HelpText(stringResource(R.string.nb_settings_netbird_off), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        } else {
            SettingsSwitchItem(stringResource(R.string.nb_settings_block_inbound), stringResource(R.string.nb_settings_block_inbound_desc), Icons.Default.Block, cfg.blockInbound) {
                env.setConfig { put("blockInbound", it) }
            }
        }
        // Said in red when it is happening: both on, and the first does nothing.
        val overridden = inbound && cfg?.blockInbound == true
        HelpText(
            stringResource(R.string.settings_inbound_override),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = if (overridden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsClickableItem(stringResource(R.string.nb_expose_title), stringResource(R.string.settings_link_publish_desc), Icons.Default.Public) {
            context.startActivity(Intent(context, ExposeActivity::class.java))
        }
    }
}
