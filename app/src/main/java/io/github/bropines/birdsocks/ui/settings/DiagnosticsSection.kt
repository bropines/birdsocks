package io.github.bropines.birdsocks.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.ui.DiagnosticsActivity
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.LogsActivity
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsEditItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Settings → Diagnostics & developer: how much the daemon logs and with what
 * environment, whether the server may ask for a debug bundle, and the way to
 * the Diagnostics screen and the logs.
 */
@Composable
internal fun DiagnosticsSection(env: SettingsEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = env.config

    SettingsCard(stringResource(R.string.settings_card_developer)) {
        var level by remember { mutableStateOf(GlobalSettings.getLogLevel(context)) }
        val levels = listOf("info", "debug", "trace")
        Text(stringResource(R.string.nb_settings_log_level), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        SlidingSegmentedChips(levels, levels.indexOf(level).coerceAtLeast(0), { i ->
            // Saved for the next start, and applied to the running daemon at once.
            GlobalSettings.setLogLevel(context, levels[i]); level = levels[i]
            if (env.running) scope.launch { runCatching { Netbird.setLogLevel(levels[i]) } }
        }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        var extraEnv by remember { mutableStateOf(GlobalSettings.getExtraEnv(context)) }
        SettingsEditItem(stringResource(R.string.nb_settings_env), extraEnv.lines().filter { it.isNotBlank() }.joinToString(", "), Icons.Default.Code,
            placeholder = "NB_RELAY_TRANSPORT=ws", description = stringResource(R.string.nb_settings_env_desc)
        ) { env.startSetting { GlobalSettings.setExtraEnv(context, it.split(',', '\n').joinToString("\n") { l -> l.trim() }); extraEnv = GlobalSettings.getExtraEnv(context) } }
        // A profile setting: next to the bundle it serves rather than with the network's.
        if (cfg == null) {
            HelpText(stringResource(R.string.nb_settings_netbird_off), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        } else {
            SettingsSwitchItem(stringResource(R.string.nb_settings_remote_jobs), stringResource(R.string.nb_settings_remote_jobs_desc), Icons.Default.BugReport, cfg.remoteJobsAllowed) {
                env.setConfig { put("remoteJobsAllowed", it) }
            }
        }
    }

    SettingsCard(stringResource(R.string.settings_sect_tools)) {
        SettingsClickableItem(stringResource(R.string.nb_menu_diagnostics), stringResource(R.string.settings_link_diagnostics_desc), Icons.Default.MonitorHeart) {
            context.startActivity(DiagnosticsActivity.intent(context))
        }
        SettingsClickableItem(stringResource(R.string.logs_title), stringResource(R.string.settings_link_logs_desc), Icons.AutoMirrored.Filled.List) {
            context.startActivity(LogsActivity.intent(context, "ALL"))
        }
    }
}
