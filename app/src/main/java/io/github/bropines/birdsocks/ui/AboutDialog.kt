package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.birdsocks.BuildConfig
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.AppIcons
import io.github.bropines.birdsocks.core.BirdSocksApp
import io.github.bropines.birdsocks.core.Changelog
import io.github.bropines.birdsocks.core.UpdateChannel
import io.github.bropines.birdsocks.core.Updater
import io.github.bropines.birdsocks.ui.settings.AppIconImage
import kotlinx.coroutines.launch

/**
 * About BirdSocks: the versions and the update state first, then the project's
 * pages, the credits and the licenses. Opened from the Info button on the main
 * screen and from Settings → About.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    // The screen's context: everything below draws in the dialog's own window, where a
    // stringResource() follows the system language. Its strings, and those of the blocks
    // it calls, are read through this one instead — see wrapContextWithLocale().
    val context = LocalContext.current
    val selfUpdate = remember { UpdateChannel.selfUpdate(context) }
    val core = remember { runCatching { Appctr.coreVersion() }.getOrDefault("?") }
    var showChangelog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIconImage(remember { AppIcons.current(context) }, 56.dp) },
        title = { Text(context.getString(R.string.app_name)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Identity and update state, one block a step above the dialog's own ground.
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(context.getString(R.string.about_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"), style = MaterialTheme.typography.bodyMedium)
                        Text(context.getString(R.string.about_core_version, core), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // A copy a store keeps up to date has no GitHub check to offer.
                        if (selfUpdate) {
                            Spacer(Modifier.height(12.dp))
                            UpdateBlock(context)
                        }
                    }
                }

                ButtonPair(
                    Triple(context.getString(R.string.about_whats_new), Icons.Default.NewReleases) { showChangelog = true },
                    Triple(context.getString(R.string.about_releases), Icons.AutoMirrored.Filled.OpenInNew) { openUrl(context, Updater.RELEASES_URL) }
                )
                ButtonPair(
                    Triple(context.getString(R.string.nb_about_source), Icons.Default.Code) { openUrl(context, Updater.REPO_URL) },
                    Triple(context.getString(R.string.about_docs), Icons.AutoMirrored.Filled.MenuBook) { openUrl(context, "https://docs.netbird.io/") }
                )

                Credits(context)

                FilledTonalButton(onClick = { openUrl(context, Updater.DONATE_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.about_donate))
                }

                OutlinedButton(onClick = { context.startActivity(android.content.Intent(context, LicensesActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Gavel, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.nb_about_licenses))
                }
                Text(context.getString(R.string.about_license_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) } }
    )

    if (showChangelog) ChangelogDialog(onDismiss = { showChangelog = false })
}

/**
 * Two equal buttons in a row. A label too long for its half wraps rather than
 * truncating, and IntrinsicSize.Min keeps the pair the height of the taller.
 */
@Composable
private fun ButtonPair(vararg buttons: Triple<String, ImageVector, () -> Unit>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, icon, onClick) ->
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Who made this and what it stands on, each a tap from their page. */
@Composable
private fun Credits(context: android.content.Context) {
    val credits = listOf(
        Triple(R.string.about_credit_claude, "https://www.anthropic.com/claude", Icons.Default.AutoAwesome),
        Triple(R.string.about_credit_bropines, "https://github.com/bropines", Icons.Default.Person),
        Triple(R.string.about_credit_netbird, "https://github.com/netbirdio/netbird", Icons.Default.Hub),
        Triple(R.string.about_credit_hev, "https://github.com/heiher/hev-socks5-tunnel", Icons.Default.VpnLock),
        Triple(R.string.about_credit_byedpi, "https://github.com/hufrea/byedpi", Icons.Default.Shield),
        Triple(R.string.about_credit_anet, "https://github.com/wlynxg/anet", Icons.Default.Lan),
    )
    Column {
        Text(
            context.getString(R.string.about_credits),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                credits.forEach { (label, url, icon) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // A one-line credit is 36dp: under what a finger is owed.
                            .heightIn(min = 48.dp)
                            .clickable { openUrl(context, url) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(context.getString(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * The update state and its one action: check, download, install. A newer
 * release brings its notes; a debug build and a release without an APK for
 * this device are sent to the release page instead.
 */
@Composable
private fun UpdateBlock(context: android.content.Context) {
    val state by Updater.state.collectAsStateOr { Updater.State.Idle }
    val check: () -> Unit = { BirdSocksApp.scope.launch { Updater.check(context) } }

    when (val s = state) {
        Updater.State.Idle -> FilledTonalButton(onClick = check, modifier = Modifier.fillMaxWidth()) {
            Text(context.getString(R.string.update_check_now))
        }
        Updater.State.Checking -> FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
            Text(context.getString(R.string.update_checking))
        }
        is Updater.State.UpToDate -> {
            StatusLine(Icons.Default.CheckCircle, context.getString(R.string.update_up_to_date))
            FilledTonalButton(onClick = check, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.update_check_now)) }
        }
        is Updater.State.Failed -> {
            StatusLine(Icons.Default.ErrorOutline, context.getString(s.reason), MaterialTheme.colorScheme.error)
            s.detail?.takeIf { it.isNotBlank() }?.let { HelpText(it) }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { if (s.release != null) download(context) else check() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(context.getString(R.string.update_retry)) }
        }
        is Updater.State.Available, is Updater.State.Downloading, is Updater.State.Ready -> {
            val release = (s as? Updater.State.Available)?.release
                ?: (s as? Updater.State.Downloading)?.release
                ?: (s as Updater.State.Ready).release
            StatusLine(Icons.Default.Download, context.getString(R.string.update_new_version, release.version))
            ReleaseNotes(release, context)
            Spacer(Modifier.height(8.dp))
            when {
                !Updater.canInstall || release.apkUrl == null -> {
                    HelpText(context.getString(if (!Updater.canInstall) R.string.update_dev_build else R.string.update_no_apk))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openUrl(context, release.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.update_open_page))
                    }
                }
                s is Updater.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(context.getString(R.string.update_downloading, s.percent), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Button(
                        onClick = {
                            if (s is Updater.State.Ready) Updater.install(context, s.apk) else download(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (s is Updater.State.Ready) Icons.Default.SystemUpdate else Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(if (s is Updater.State.Ready) R.string.update_install else R.string.update_download))
                    }
                    Spacer(Modifier.height(4.dp))
                    HelpText(context.getString(R.string.update_verify_desc))
                }
            }
        }
    }
}

private fun download(context: android.content.Context) {
    // Asked before the download, so the user is not sent away after it.
    if (Updater.canInstallPackages(context)) BirdSocksApp.scope.launch { Updater.download(context) }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

/** The release's notes, folded behind a button: the dialog stays short until they are wanted. */
@Composable
private fun ReleaseNotes(release: Updater.Release, context: android.content.Context) {
    val section = remember(release) { Changelog.releaseSection(release.version, release.notes) } ?: return
    var open by rememberSaveable(release.version) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(context.getString(R.string.update_notes))
    }
    if (open) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            ChangelogSectionBody(section)
        }
    }
}

