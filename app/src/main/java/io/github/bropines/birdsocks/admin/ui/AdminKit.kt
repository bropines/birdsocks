package io.github.bropines.birdsocks.admin.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.describeError
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/*
 * The panel's small shared pieces. Everything takes plain values and no-op
 * defaults, so a preview draws it on demo data.
 */

/**
 * The parent composition's Context, Configuration and Resources, to provide
 * again inside a dialog or sheet: those are windows of their own, where
 * stringResource() can follow the system language rather than the app's
 * (HyperOS does). The kit's sheets and dialogs do this for their content, so
 * stringResource() is safe inside them; a raw AlertDialog or ModalBottomSheet
 * of an area's own must do it too, or resolve its strings before opening.
 */
class ParentLocals(private val context: Context, private val configuration: Configuration, private val resources: Resources) {
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalResources provides resources, content = content)
    }
}

@Composable
fun rememberParentLocals(): ParentLocals {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(context, configuration, resources) { ParentLocals(context, configuration, resources) }
}

/** A small label on a row: "this device", "pending". [color] is a container colour. */
@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A label over a value, with an icon: the overview's rows. */
@Composable
fun InfoRow(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(value, style = MaterialTheme.typography.bodyLarge) }
}

/** A label over a value in a detail sheet; nothing at all for a blank value. */
@Composable
fun DetailField(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/** A partial list's note: some elements could not be read and are not shown. */
@Composable
fun IncompleteNote(issues: Int, modifier: Modifier = Modifier) {
    if (issues <= 0) return
    Text(
        pluralStringResource(R.plurals.admin_list_incomplete, issues, issues),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun <T> IncompleteNote(listing: Listing<T>?, modifier: Modifier = Modifier) = IncompleteNote(listing?.issues?.size ?: 0, modifier)

/**
 * What went wrong with the last read, in words, with a retry: the whole
 * screen when nothing could be shown ([ErrorState]), a card over the list
 * when an older copy is still on screen ([ErrorCard]).
 */
@Composable
fun ErrorCard(error: Throwable, onRetry: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(describeError(context, error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.admin_retry)) }
        }
    }
}

/** A list that did not load at all: why, and a way to try again. */
@Composable
fun ErrorState(error: Throwable, onRetry: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(describeError(context, error), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.admin_retry)) }
    }
}

/**
 * A [Loadable] on screen: a spinner before the first answer, [ErrorState]
 * when it failed with nothing to show, otherwise [content] with the value —
 * and an [ErrorCard] above it when a refresh failed over an older copy
 * (draw it yourself with [staleError] if [content] is a list that scrolls).
 */
@Composable
fun <T> LoadableContent(loadable: Loadable<T>, onRetry: () -> Unit = {}, content: @Composable (value: T, staleError: Throwable?) -> Unit) {
    val value = loadable.value
    when {
        value != null -> content(value, loadable.error)
        loadable.error != null -> ErrorState(loadable.error, onRetry)
        else -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    }
}

@Preview
@Composable
private fun KitPreview() = BirdSocksTheme {
    Surface {
        Column(Modifier.padding(16.dp)) {
            InfoRow(Icons.Default.Dns, "Server", "https://api.netbird.io:443")
            DetailField("Address", "100.64.0.7", mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge("this device", MaterialTheme.colorScheme.primaryContainer)
                StatusBadge("pending", MaterialTheme.colorScheme.tertiaryContainer)
            }
            IncompleteNote(2)
            ErrorCard(AdminApiException.Server(503, "upstream"))
        }
    }
}
