package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.EventLog
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbEvent
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

class EventsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { EventsScreen(onBack = { finish() }) } }
    }
}

/**
 * What NetBird reported lately: network, DNS, sign-in and connectivity
 * events, newest first. Warnings and worse also arrive as notifications.
 */
@Composable
fun EventsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val events by EventLog.events.collectAsState()
    var notify by remember { mutableStateOf(GlobalSettings.isEventNotifications(context)) }
    var filter by remember { mutableStateOf<String?>(null) }
    val categories = listOf("NETWORK", "DNS", "AUTHENTICATION", "CONNECTIVITY", "SYSTEM")
    val shown = events.asReversed().filter { filter == null || it.category == filter }

    Scaffold(topBar = {
        AppTopBar(title = stringResource(R.string.nb_events_title), onBack = onBack, actions = {
            IconButton(onClick = { notify = !notify; GlobalSettings.setEventNotifications(context, notify) }) {
                Icon(if (notify) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                    stringResource(if (notify) R.string.nb_events_notify_on else R.string.nb_events_notify_off))
            }
            IconButton(onClick = { EventLog.clear() }) { Icon(Icons.Default.ClearAll, stringResource(R.string.nb_events_clear)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.nb_events_all)) }) }
                items(categories) { c ->
                    FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c }, label = { Text(categoryLabel(c)) })
                }
            }
            if (shown.isEmpty()) {
                EmptyState(icon = Icons.Default.EventNote, text = stringResource(R.string.nb_events_empty))
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { EventRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: NbEvent) {
    var open by remember { mutableStateOf(false) }
    val tint = when (e.severity) {
        "CRITICAL", "ERROR" -> MaterialTheme.colorScheme.error
        "WARNING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(onClick = { open = !open }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            leadingContent = {
                Icon(when (e.severity) {
                    "CRITICAL", "ERROR" -> Icons.Default.Error
                    "WARNING" -> Icons.Default.Warning
                    else -> Icons.Default.Info
                }, null, tint = tint)
            },
            overlineContent = { Text("${categoryLabel(e.category)} · ${eventTime(e.timestamp)}") },
            headlineContent = { Text(e.userMessage.ifEmpty { e.message }) },
            supportingContent = if (open && (e.metadata.isNotEmpty() || e.userMessage.isNotEmpty())) {
                {
                    Column {
                        if (e.userMessage.isNotEmpty() && e.message != e.userMessage) Text(e.message, style = MaterialTheme.typography.bodySmall)
                        e.metadata.toSortedMap().forEach { (k, v) ->
                            Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else null,
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
private fun categoryLabel(c: String): String = stringResource(when (c) {
    "NETWORK" -> R.string.nb_events_cat_network
    "DNS" -> R.string.nb_events_cat_dns
    "AUTHENTICATION" -> R.string.nb_events_cat_auth
    "CONNECTIVITY" -> R.string.nb_events_cat_connectivity
    else -> R.string.nb_events_cat_system
})

/** The event's local time; today's without the date. */
private fun eventTime(ts: String?): String {
    val at = ts?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return ""
    val local = at.atZone(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val fmt = if (local.toLocalDate() == today) java.time.format.FormatStyle.MEDIUM else java.time.format.FormatStyle.SHORT
    return if (local.toLocalDate() == today) local.toLocalTime().format(java.time.format.DateTimeFormatter.ofLocalizedTime(fmt))
    else local.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(fmt))
}
