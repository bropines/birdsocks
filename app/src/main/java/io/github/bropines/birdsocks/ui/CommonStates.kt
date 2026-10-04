package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import kotlinx.coroutines.launch

/**
 * What a screen that needs NetBird shows while BirdSocks is stopped: one
 * line and a Start button that starts it from here, instead of sending the
 * user back to the main screen. [onStarted] runs once the daemon answers —
 * also when it was started elsewhere (the tile, the notification) while this
 * is on screen. [footer] takes a second way out where a screen has one.
 */
@Composable
fun DaemonStoppedState(
    onStarted: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daemon by NetbirdState.daemon.collectAsState()
    val currentOnStarted by rememberUpdatedState(onStarted)
    LaunchedEffect(daemon) { if (daemon == NetbirdState.Daemon.Running) currentOnStarted() }
    val starting = daemon == NetbirdState.Daemon.Starting

    Column(
        modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.state_stopped_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(enabled = !starting, onClick = { scope.launch { NetbirdService.awaitRunning(context) } }) {
            if (starting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_status_starting))
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_status_action_start))
            }
        }
        footer()
    }
}
