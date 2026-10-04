package io.github.bropines.birdsocks.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tools behind Diagnostics: the logs, the firewall's access check, a
 * packet capture and NetBird's debug bundle. The last three ask the daemon,
 * so they wait for [running]; the logs are what to read when it will not run.
 */
@Composable
fun DiagnosticToolsCard(running: Boolean) {
    val context = LocalContext.current
    var showCapture by remember { mutableStateOf(false) }
    var showBundle by remember { mutableStateOf(false) }
    if (showCapture) CaptureDialog(onDismiss = { showCapture = false })
    if (showBundle) DebugBundleDialog(onDismiss = { showBundle = false })

    SettingsCard(stringResource(R.string.diag_tools)) {
        SettingsClickableItem(stringResource(R.string.logs_title), stringResource(R.string.diag_tool_logs_desc), Icons.AutoMirrored.Filled.List) {
            context.startActivity(LogsActivity.intent(context, "ALL"))
        }
        SettingsClickableItem(stringResource(R.string.nb_trace_title), stringResource(R.string.diag_tool_trace_desc), Icons.Default.Policy, enabled = running) {
            context.startActivity(TraceActivity.intent(context))
        }
        SettingsClickableItem(stringResource(R.string.nb_capture_title), stringResource(R.string.nb_capture_desc), Icons.Default.Sensors, enabled = running) {
            showCapture = true
        }
        SettingsClickableItem(stringResource(R.string.nb_bundle_title), stringResource(R.string.nb_bundle_desc), Icons.Default.Inventory2, enabled = running) {
            showBundle = true
        }
    }
}

/**
 * NetBird's debug bundle: the daemon's logs, status, routes and system
 * details in one archive — uploaded to NetBird for support (the key goes to
 * the clipboard) or saved as a zip. Anonymized by default.
 */
@Composable
fun DebugBundleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var anonymize by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var bundlePath by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val path = bundlePath ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> java.io.File(path).inputStream().use { it.copyTo(out) } }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                result = if (failure == null) context.getString(R.string.logs_saved)
                else context.getString(R.string.nb_bundle_failed, failure.message.orEmpty())
            }
        }
    }

    fun build(upload: Boolean) {
        busy = true
        result = null
        scope.launch {
            runCatching { Netbird.debugBundle(anonymize, upload) }
                .onSuccess { b ->
                    bundlePath = b.path
                    when {
                        upload && b.uploadedKey.isNotEmpty() -> {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NetBird debug key", b.uploadedKey))
                            result = context.getString(R.string.nb_bundle_uploaded, b.uploadedKey)
                        }
                        upload -> result = context.getString(R.string.nb_bundle_failed, b.uploadFailureReason)
                        else -> save.launch("netbird-debug-${System.currentTimeMillis()}.zip")
                    }
                }
                .onFailure { result = context.getString(R.string.nb_bundle_failed, it.message ?: "") }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Inventory2, null) },
        title = { Text(stringResource(R.string.nb_bundle_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nb_bundle_desc), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = anonymize, onCheckedChange = { anonymize = it }, enabled = !busy)
                    Text(stringResource(R.string.nb_bundle_anonymize))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { build(upload = true) }) { Text(stringResource(R.string.nb_bundle_upload)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { build(upload = false) }) { Text(stringResource(R.string.nb_bundle_save)) } }
    )
}

private val CAPTURE_SECONDS = listOf(10, 30, 60, 120)

/**
 * Records the tunnel's packets for a while as a .pcap file — what Wireshark
 * opens — and saves it where the user picks.
 */
@Composable
fun CaptureDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seconds by remember { mutableStateOf(30) }
    var filter by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    val file = remember { java.io.File(context.cacheDir, "capture.pcap") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onFailure { message = it.message }
            file.delete()
            launch(Dispatchers.Main) { if (message == null) onDismiss() }
        }
    }
    var sub by remember { mutableStateOf<appctr.Subscription?>(null) }
    DisposableEffect(Unit) { onDispose { sub?.cancel() } }

    fun start() {
        running = true; bytes = 0; message = null
        scope.launch {
            // Opening the stream talks to the daemon: off the main thread, and a refusal
            // (no daemon, no engine yet) is a message here rather than a crash.
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val out = java.io.FileOutputStream(file)
                    runCatching {
                        Netbird.capture(seconds, filter,
                            onData = { chunk -> synchronized(out) { out.write(chunk); bytes += chunk.size } },
                            onEnd = { err ->
                                synchronized(out) { runCatching { out.close() } }
                                scope.launch(Dispatchers.Main) {
                                    running = false
                                    sub = null
                                    // Whatever was recorded is worth saving, however the capture ended.
                                    when {
                                        file.length() > 24 -> save.launch("birdsocks-${System.currentTimeMillis()}.pcap")
                                        err.isEmpty() || err == Netbird.STREAM_DONE -> message = context.getString(R.string.nb_capture_empty)
                                        else -> message = err.substringAfter("desc = ")
                                    }
                                }
                            })
                    }.onFailure { runCatching { out.close() } }.getOrThrow()
                }
            }
            opened.onSuccess { sub = it }.onFailure {
                running = false
                message = it.message?.substringAfter("desc = ") ?: it.toString()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = { Icon(Icons.Default.Sensors, null) },
        title = { Text(stringResource(R.string.nb_capture_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nb_capture_desc), style = MaterialTheme.typography.bodySmall)
                SlidingSegmentedChips(
                    options = CAPTURE_SECONDS.map { stringResource(R.string.nb_capture_seconds, it) },
                    selectedIndex = CAPTURE_SECONDS.indexOf(seconds).coerceAtLeast(0),
                    onOptionSelected = { if (!running) seconds = CAPTURE_SECONDS[it] },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, enabled = !running, singleLine = true,
                    label = { Text(stringResource(R.string.nb_capture_filter)) },
                    supportingText = { Text(stringResource(R.string.nb_capture_filter_desc)) }
                )
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.nb_capture_progress, bytes / 1024), style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (running) TextButton(enabled = sub != null, onClick = { sub?.cancel() }) { Text(stringResource(R.string.nb_capture_stop)) }
            else TextButton(onClick = ::start) { Text(stringResource(R.string.nb_capture_start)) }
        },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
