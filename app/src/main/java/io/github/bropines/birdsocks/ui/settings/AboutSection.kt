package io.github.bropines.birdsocks.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.birdsocks.BuildConfig
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.HelpText
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.openUrl

/** Settings → About: versions, documentation, source, licenses. */
@Composable
internal fun AboutSection(env: SettingsEnv) {
    val context = LocalContext.current
    val core = remember { runCatching { Appctr.coreVersion() }.getOrDefault("?") }
    SettingsCard(stringResource(R.string.nb_settings_about)) {
        Text("BirdSocks ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text("NetBird $core", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        env.status?.daemonVersion?.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.nb_settings_daemon_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        HelpText(stringResource(R.string.nb_settings_about_desc))
        SettingsClickableItem(stringResource(R.string.nb_about_docs), "docs.netbird.io", Icons.AutoMirrored.Filled.MenuBook) {
            openUrl(context, "https://docs.netbird.io/")
        }
        SettingsClickableItem(stringResource(R.string.nb_about_source), "github.com/bropines/birdsocks", Icons.Default.Code) {
            openUrl(context, "https://github.com/bropines/birdsocks")
        }
        var showLicenses by remember { mutableStateOf(false) }
        // The licenses in full, as MIT and BSD ask a binary to carry them (assets/third_party_licenses.txt).
        var fullTexts by remember { mutableStateOf<String?>(null) }
        SettingsClickableItem(stringResource(R.string.nb_about_licenses), stringResource(R.string.nb_about_licenses_desc), Icons.Default.Gavel) { showLicenses = true }
        if (showLicenses) {
            AlertDialog(
                onDismissRequest = { showLicenses = false },
                title = { Text(stringResource(R.string.nb_about_licenses)) },
                text = { Text(stringResource(R.string.nb_about_licenses_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.action_close)) } },
                dismissButton = {
                    TextButton(onClick = {
                        showLicenses = false
                        fullTexts = runCatching { context.assets.open("third_party_licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
                    }) { Text(stringResource(R.string.nb_about_licenses_full)) }
                }
            )
        }
        fullTexts?.let { text ->
            AlertDialog(
                onDismissRequest = { fullTexts = null },
                title = { Text(stringResource(R.string.nb_about_licenses)) },
                text = { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { fullTexts = null }) { Text(stringResource(R.string.action_close)) } }
            )
        }
    }
}
