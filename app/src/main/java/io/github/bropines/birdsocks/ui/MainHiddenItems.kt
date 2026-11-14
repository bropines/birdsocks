package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.MainItem
import io.github.bropines.birdsocks.core.MainLayoutStore

/*
 * Settings → Appearance → Main screen: the way into the editor that can be found by looking —
 * the other is a long press on the main screen itself — and what the person took off that
 * screen, still within reach.
 *
 * A tile taken off the screen may have been the way to its screen: here each opens as its tile
 * would, and one press puts it back where the editor's palette would (MainLayout.add). A block
 * opens nothing; it can only go back.
 */

/** The row that opens the main screen with its editor (ui/MainCanvas.kt). */
@Composable
fun MainLayoutRow() {
    val context = LocalContext.current
    SettingsClickableItem(
        title = stringResource(R.string.settings_main_layout_title),
        subtitle = stringResource(R.string.settings_main_layout_desc),
        icon = Icons.Default.Dashboard,
        onClick = { context.startActivity(MainActivity.editLayoutIntent(context)) },
    )
}

/** The row, with how many things are off the screen; it opens the sheet. */
@Composable
fun MainHiddenRow() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val hidden = remember(version) { MainLayoutStore.load(context).addable(mainItemsOffered) }
    var open by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(false) }
    SettingsClickableItem(
        title = stringResource(R.string.main_hidden_title),
        subtitle = if (hidden.isEmpty()) stringResource(R.string.main_hidden_none)
        else pluralStringResource(R.plurals.main_hidden_count, hidden.size, hidden.size),
        icon = Icons.Default.VisibilityOff,
        onClick = { version++; open = true },
    )
    if (open) {
        MainHiddenSheet(
            items = hidden,
            onOpen = { item ->
                open = false
                // The accounts are a sheet, not a screen: it opens here, over Settings.
                if (item == MainItem.ACCOUNTS) accounts = true else item.openAsTile(context)
            },
            onRestore = { item ->
                MainLayoutStore.restore(context, item)
                version++
            },
            onDismiss = { open = false },
        )
    }
    if (accounts) AccountSheet(onDismiss = { accounts = false })
}

/**
 * Opens [this] as its tile on the main screen would, from outside that screen. VPN mode
 * opens where it is turned: a switch pressed from a list would be too easy to press by mistake.
 */
internal fun MainItem.openAsTile(context: Context) {
    val intent: Intent? = when (this) {
        MainItem.VPN -> SettingsSections.intent(context, SettingsSections.TUNNEL)
        else -> screen(context)
    }
    intent?.let { context.startActivity(it) }
}

/** Whether [this] opens anything: the blocks do not, they live on the main screen only. */
internal val MainItem.opens: Boolean get() = isTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainHiddenSheet(
    items: List<MainItem>,
    onOpen: (MainItem) -> Unit,
    onRestore: (MainItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // Resolved here, in the screen's language: the sheet is a window of its own.
    val rows = paletteRows(items)
    val title = stringResource(R.string.main_hidden_title)
    val desc = stringResource(R.string.main_hidden_sheet_desc)
    val none = stringResource(R.string.main_hidden_none)
    val restoreLabel = stringResource(R.string.main_hidden_restore)
    val openLabel = stringResource(R.string.main_hidden_open)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MainHiddenContent(rows, title, desc, none, restoreLabel, openLabel, onOpen, onRestore, Modifier.navigationBarsPadding())
    }
}

@Composable
private fun MainHiddenContent(
    rows: List<PaletteRow>,
    title: String,
    desc: String,
    none: String,
    restoreLabel: String,
    openLabel: String,
    onOpen: (MainItem) -> Unit,
    onRestore: (MainItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (rows.isEmpty()) {
            Text(
                none,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f, fill = false)) {
            for ((group, members) in rows.groupBy { it.group }) {
                item(key = "group:$group") {
                    Text(
                        group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.animateItem().padding(start = 12.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                items(members.size, key = { members[it].item.id }) { i ->
                    val row = members[i]
                    val opens = row.item.opens
                    // A block has nothing to open: the whole row puts it back.
                    Surface(
                        onClick = { if (opens) onOpen(row.item) else onRestore(row.item) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent,
                        modifier = Modifier.animateItem().fillMaxWidth().semantics {
                            contentDescription = "${if (opens) openLabel else restoreLabel}: ${row.title}. ${row.desc}"
                        },
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    row.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { onRestore(row.item) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.AddToHomeScreen,
                                    contentDescription = "$restoreLabel: ${row.title}",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
