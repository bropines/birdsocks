package io.github.bropines.birdsocks.ui

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R

/**
 * When the session ends, and renewing it: the server signs SSO peers out
 * after its expiry (24 h by default), and renewal keeps the connection up.
 */
@Composable
fun SessionRow(expiresAt: String) {
    val context = LocalContext.current
    val extend by io.github.bropines.birdsocks.core.ExtendFlow.state.collectAsState()
    val until = parseRfc3339Millis(expiresAt) ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) }
    }
    if (extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser) {
        val url = (extend as io.github.bropines.birdsocks.core.ExtendFlow.State.Browser).url
        LaunchedEffect(url) { openUrl(context, url) }
    }
    val left = until - now
    val soon = left < 60 * 60_000
    val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
    Card(
        onClick = { io.github.bropines.birdsocks.core.ExtendFlow.start(context) },
        colors = CardDefaults.cardColors(containerColor = if (soon) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer)
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary) },
            overlineContent = { Text(stringResource(R.string.nb_session_title)) },
            headlineContent = { Text(stringResource(R.string.nb_session_until, clock, durationWords(context, left))) },
            supportingContent = {
                when (val e = extend) {
                    is io.github.bropines.birdsocks.core.ExtendFlow.State.Failed -> Text(e.message, color = MaterialTheme.colorScheme.error)
                    is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser -> Text(stringResource(R.string.nb_login_waiting))
                    else -> Text(stringResource(R.string.nb_session_extend_hint))
                }
            },
            trailingContent = {
                if (extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Working || extend is io.github.bropines.birdsocks.core.ExtendFlow.State.Browser) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else Icon(Icons.Default.Refresh, stringResource(R.string.nb_session_extend))
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

/** "19 h", "45 min": how long until a moment. */
fun durationWords(context: Context, millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) context.getString(R.string.nb_hours, minutes / 60) else context.getString(R.string.nb_minutes, minutes)
}
