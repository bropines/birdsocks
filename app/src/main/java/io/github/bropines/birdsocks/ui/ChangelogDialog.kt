package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Changelog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What's new": the newest released section of the CHANGELOG.md bundled with
 * this build (an [Unreleased] block is skipped), in Russian from CHANGELOG_RU.md
 * when the app is and that file has the version. Shown once after an update from
 * MainActivity, and on demand from the About dialog.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var section by remember { mutableStateOf<Changelog.Section?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    // The app's language, which the theme's configuration carries (see wrapContextWithLocale).
    val language = configuration.locales[0].language
    LaunchedEffect(language) {
        section = withContext(Dispatchers.IO) { Changelog.latest(context, language) }
        loaded = true
    }
    val maxHeight = (configuration.screenHeightDp * 0.6f).dp

    // Strings come from the parent context, not stringResource(): the dialog is a window
    // of its own, where that follows the system language — see wrapContextWithLocale().
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NewReleases, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    val s = section
                    Text(if (s != null) context.getString(R.string.whats_new_title, s.version) else context.getString(R.string.about_whats_new))
                    s?.date?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                val s = section
                when {
                    !loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    }
                    s == null -> Text(context.getString(R.string.whats_new_unavailable), color = MaterialTheme.colorScheme.outline)
                    else -> ChangelogSectionBody(s)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUrl(context, if (language == "ru") Changelog.FULL_CHANGELOG_URL_RU else Changelog.FULL_CHANGELOG_URL)
                onDismiss()
            }) { Text(context.getString(R.string.whats_new_full)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        }
    )
}

/** One changelog section: its groups as headings, its items as bullets, inline `code` and **bold**. */
@Composable
fun ChangelogSectionBody(section: Changelog.Section) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurfaceVariant
    section.groups.forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        if (group.title.isNotEmpty()) {
            Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
        }
        group.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(start = (item.level * 16).dp, top = 2.dp, bottom = 2.dp)) {
                Text(if (item.level == 0) "•" else "–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                val rendered = remember(item.text, codeBg, codeFg) {
                    Changelog.inlineMarkdown(item.text, codeBackground = codeBg, codeColor = codeFg)
                }
                Text(rendered, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
