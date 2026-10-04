package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.ExposeFlow
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.ScrollableSlidingSegmentedChips
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

class ExposeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { ExposeScreen(onBack = { finish() }) } }
    }
}

private val PROTOCOLS = listOf("EXPOSE_HTTP" to "HTTP", "EXPOSE_HTTPS" to "HTTPS", "EXPOSE_TCP" to "TCP", "EXPOSE_UDP" to "UDP", "EXPOSE_TLS" to "TLS")

/**
 * Publishes a port of this phone through the account's NetBird reverse
 * proxy: a web server, a file share, anything listening on 127.0.0.1. The
 * proxy reaches the phone over the network, so inbound access must be on
 * (Settings → access, which this screen links to rather than repeats); the
 * address lives while BirdSocks runs, with a notification to take it down.
 */
@Composable
fun ExposeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by ExposeFlow.state.collectAsState()
    val daemon by NetbirdState.daemon.collectAsState()
    var inbound by remember { mutableStateOf(GlobalSettings.isInboundAccess(context)) }
    // Back from Settings, where the switch lives: read it again.
    LifecycleResumeEffect(Unit) {
        inbound = GlobalSettings.isInboundAccess(context)
        onPauseOrDispose { }
    }
    var port by remember { mutableStateOf("8080") }
    var protocol by remember { mutableStateOf("EXPOSE_HTTP") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf("") }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.nb_expose_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HelpText(stringResource(R.string.nb_expose_desc))
            if (!inbound) {
                Card(
                    onClick = { SettingsSections.open(context, SettingsSections.ACCESS) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.CallReceived, null) },
                        supportingContent = { HelpText(stringResource(R.string.nb_expose_inbound_in_settings)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    ) { Text(stringResource(R.string.nb_settings_inbound)) }
                }
            }
            when (val s = state) {
                is ExposeFlow.State.Live -> LiveCard(s)
                is ExposeFlow.State.Starting -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.nb_expose_starting, s.port))
                }
                is ExposeFlow.State.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.ErrorOutline, null) },
                        // "Block inbound" sits beside "Access to the phone" in Settings.
                        supportingContent = if ("block inbound" in s.reason.lowercase()) {
                            {
                                TextButton(onClick = { SettingsSections.open(context, SettingsSections.ACCESS) }, contentPadding = PaddingValues(0.dp)) {
                                    Text(stringResource(R.string.menu_settings))
                                }
                            }
                        } else null,
                        trailingContent = { IconButton(onClick = { ExposeFlow.dismiss() }) { Icon(Icons.Default.Close, null) } },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    ) { Text(exposeError(s.reason)) }
                }
                ExposeFlow.State.Idle -> Unit
            }
            val idle = state is ExposeFlow.State.Idle || state is ExposeFlow.State.Failed
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, enabled = idle,
                label = { Text(stringResource(R.string.nb_expose_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
            )
            // Five of them overflow a narrow phone: the row scrolls.
            ScrollableSlidingSegmentedChips(
                options = PROTOCOLS.map { it.second },
                selectedIndex = PROTOCOLS.indexOfFirst { it.first == protocol }.coerceAtLeast(0),
                onOptionSelected = { if (idle) protocol = PROTOCOLS[it].first },
                modifier = Modifier.fillMaxWidth().alpha(if (idle) 1f else 0.6f)
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(32) }, enabled = idle,
                label = { Text(stringResource(R.string.nb_expose_name)) }, supportingText = { Text(stringResource(R.string.nb_expose_name_desc)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.nb_expose_access), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(12) }, enabled = idle,
                label = { Text(stringResource(R.string.nb_expose_pin)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, enabled = idle,
                label = { Text(stringResource(R.string.nb_expose_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = groups, onValueChange = { groups = it }, enabled = idle,
                label = { Text(stringResource(R.string.nb_expose_groups)) }, supportingText = { Text(stringResource(R.string.nb_expose_groups_desc)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (idle) {
                Button(
                    onClick = {
                        ExposeFlow.start(port.toInt(), protocol, name, pin, password,
                            groups.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    },
                    enabled = daemon == NetbirdState.Daemon.Running && inbound && (port.toIntOrNull() ?: 0) in 1..65535,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.nb_expose_start)) }
            } else {
                OutlinedButton(onClick = { ExposeFlow.stop() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nb_expose_stop)) }
            }
        }
    }
}

@Composable
private fun LiveCard(s: ExposeFlow.State.Live) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val url = s.ready.serviceUrl
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nb_expose_live_title, s.port), style = MaterialTheme.typography.titleMedium)
            Text(url, style = MaterialTheme.typography.bodyLarge)
            if (s.ready.serviceName.isNotEmpty()) Text(s.ready.serviceName, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { clipboard.copyText(scope, url) }, label = { Text(stringResource(R.string.action_copy)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                AssistChip(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                }, label = { Text(stringResource(R.string.nb_expose_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                if (url.startsWith("http")) AssistChip(onClick = { runCatching { openUrl(context, url) } }, label = { Text(stringResource(R.string.nb_expose_open)) }, leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) })
            }
        }
    }
}

/** What went wrong, in words a user can act on; the server's own text otherwise. */
@Composable
private fun exposeError(reason: String): String {
    val r = reason.lowercase()
    return when {
        "not enabled" in r -> stringResource(R.string.nb_expose_err_disabled)
        "block inbound" in r -> stringResource(R.string.nb_expose_err_block_inbound)
        "not running" in r || "not initialized" in r -> stringResource(R.string.nb_expose_err_not_running)
        else -> reason.substringAfter("desc = ").ifEmpty { reason }
    }
}
