package io.github.bropines.birdsocks.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import appctr.Appctr
import io.github.bropines.birdsocks.BuildConfig
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.BirdSocksApp
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.UpdateChannel
import io.github.bropines.birdsocks.core.Updater
import io.github.bropines.birdsocks.ui.AboutDialog
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.LicensesDialog
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.SettingsSwitchItem
import io.github.bropines.birdsocks.ui.openUrl
import kotlinx.coroutines.launch

/**
 * Settings → About: versions, the update check and its switch, documentation,
 * source, licenses. The About dialog of the main screen opens from here too,
 * and the licenses are its dialog.
 */
@Composable
internal fun AboutSection(env: SettingsEnv) {
    val context = LocalContext.current
    val core = remember { runCatching { Appctr.coreVersion() }.getOrDefault("?") }
    var showAbout by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.nb_settings_about)) {
        Text("BirdSocks ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text("NetBird $core", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        env.status?.daemonVersion?.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.nb_settings_daemon_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        HelpText(stringResource(R.string.nb_settings_about_desc))
        SettingsClickableItem(stringResource(R.string.about_open), stringResource(R.string.about_open_desc), Icons.Default.Info) { showAbout = true }
        // A copy a store keeps up to date has neither.
        if (remember { UpdateChannel.selfUpdate(context) }) {
            var onLaunch by remember { mutableStateOf(GlobalSettings.isUpdateCheckOnLaunch(context)) }
            SettingsSwitchItem(stringResource(R.string.update_check_on_launch), stringResource(R.string.update_check_on_launch_desc), Icons.Default.Update, onLaunch) {
                GlobalSettings.setUpdateCheckOnLaunch(context, it); onLaunch = it
            }
            // The answer, and the download it may offer, are the About dialog's.
            SettingsClickableItem(stringResource(R.string.update_check_now), stringResource(R.string.update_check_now_desc), Icons.Default.SystemUpdate) {
                BirdSocksApp.scope.launch { Updater.check(context) }
                showAbout = true
            }
        }
        SettingsClickableItem(stringResource(R.string.nb_about_docs), "docs.netbird.io", Icons.AutoMirrored.Filled.MenuBook) {
            openUrl(context, "https://docs.netbird.io/")
        }
        SettingsClickableItem(stringResource(R.string.nb_about_source), "github.com/bropines/birdsocks", Icons.Default.Code) {
            openUrl(context, Updater.REPO_URL)
        }
        SettingsClickableItem(stringResource(R.string.about_donate), "boosty.to/pinus", Icons.Default.Favorite) {
            openUrl(context, Updater.DONATE_URL)
        }
        SettingsClickableItem(stringResource(R.string.nb_about_licenses), stringResource(R.string.nb_about_licenses_desc), Icons.Default.Gavel) { showLicenses = true }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}
