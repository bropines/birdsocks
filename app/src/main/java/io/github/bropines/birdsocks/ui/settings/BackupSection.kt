package io.github.bropines.birdsocks.ui.settings

import android.content.Context
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Backup
import io.github.bropines.birdsocks.core.BackupFormat
import io.github.bropines.birdsocks.core.BackupRefused
import io.github.bropines.birdsocks.core.BirdSocksApp
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.ui.SettingsCard
import io.github.bropines.birdsocks.ui.SettingsClickableItem
import io.github.bropines.birdsocks.ui.theme.findActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Settings → Backup: BirdSocks' settings to a JSON file, everything to an
 * encrypted one, and either of them back. Saving and restoring run in the
 * app's scope (core/Backup.kt), so leaving the screen does not cut a restore
 * in half; reading a picked file only looks, and runs in the screen's.
 */
@Composable
internal fun BackupSection(env: SettingsEnv) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Backup.busy.collectAsState()
    val currentEnv by rememberUpdatedState(env)

    var askExportPassword by remember { mutableStateOf(false) }
    // Held only across the file picker, and cleared once the backup is written.
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var settingsFile by remember { mutableStateOf<BackupFormat.SettingsFile?>(null) }
    var locked by remember { mutableStateOf<ByteArray?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<BackupFormat.Full?>(null) }

    fun failed(res: Int, e: Throwable) {
        if (e !is CancellationException) toast(context, context.getString(res, describe(context, e)))
    }

    val saveSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) BirdSocksApp.scope.launch {
            runCatching { Backup.exportSettings(context, uri) }
                .onSuccess { toast(context, context.getString(R.string.backup_saved)) }
                .onFailure { failed(R.string.backup_save_failed, it) }
        }
    }
    val saveFull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = exportPassword
        exportPassword = null
        when {
            uri == null -> password?.fill('\u0000')
            password == null -> {
                // The screen was rebuilt while the picker was open; the empty file it made is no backup.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                toast(context, context.getString(R.string.backup_password_lost))
            }
            else -> BirdSocksApp.scope.launch {
                runCatching { Backup.exportFull(context, uri, password) }
                    .onSuccess { toast(context, context.getString(R.string.backup_saved)) }
                    .onFailure { failed(R.string.backup_save_failed, it) }
            }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { Backup.open(context, uri) }
                .onSuccess { opened ->
                    when (opened) {
                        is Backup.Opened.Settings -> settingsFile = opened.file
                        is Backup.Opened.Locked -> {
                            wrongPassword = false
                            locked = opened.data
                        }
                    }
                }
                .onFailure { failed(R.string.backup_restore_failed, it) }
        }
    }

    /** Runs a restore to its end whatever the screen does, then lets the screen follow it. */
    fun restore(banner: Boolean, work: suspend () -> Backup.Restored) {
        BirdSocksApp.scope.launch {
            runCatching { work() }
                .onSuccess { r ->
                    toast(context, context.getString(R.string.backup_restored))
                    // Settings the daemon reads when it starts: offer the restart, as a change by hand does.
                    if (banner) currentEnv.startSetting {}
                    if (r.localeChanged) {
                        val lang = GlobalSettings.getString(context, "app_locale", "sys")
                        AppCompatDelegate.setApplicationLocales(
                            if (lang == "sys") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
                        )
                    }
                    // The theme and the language this screen was opened with; the open section survives it.
                    if (r.appearanceChanged || r.localeChanged) {
                        context.findActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }?.recreate()
                    }
                }
                .onFailure { failed(R.string.backup_restore_failed, it) }
        }
    }

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

    SettingsCard(stringResource(R.string.backup_card_save)) {
        SettingsClickableItem(
            title = stringResource(R.string.backup_settings_title),
            subtitle = stringResource(R.string.backup_settings_desc),
            icon = Icons.Default.Description,
            enabled = !busy
        ) { saveSettings.launch("birdsocks-settings-${today()}.json") }
        SettingsClickableItem(
            title = stringResource(R.string.backup_full_title),
            subtitle = stringResource(R.string.backup_full_desc),
            icon = Icons.Default.EnhancedEncryption,
            enabled = !busy
        ) { askExportPassword = true }
    }

    SettingsCard(stringResource(R.string.backup_card_restore)) {
        SettingsClickableItem(
            title = stringResource(R.string.backup_restore_title),
            subtitle = stringResource(R.string.backup_restore_desc),
            icon = Icons.Default.SettingsBackupRestore,
            enabled = !busy
        ) { pickBackup.launch(arrayOf("*/*")) }
    }

    // Dialog strings are resolved here, in the screen's composition — see wrapContextWithLocale().
    val strCancel = stringResource(R.string.action_cancel)
    val strPassword = stringResource(R.string.backup_password_label)
    val strShowPassword = stringResource(R.string.backup_password_show)
    val strRestore = stringResource(R.string.backup_restore_action)

    if (askExportPassword) {
        val strTitle = stringResource(R.string.backup_password_title)
        val strText = stringResource(R.string.backup_password_text)
        val strRepeat = stringResource(R.string.backup_password_repeat)
        val strMismatch = stringResource(R.string.backup_password_mismatch)
        val strContinue = stringResource(R.string.backup_password_continue)
        PasswordDialog(
            title = strTitle, text = strText, label = strPassword, showLabel = strShowPassword,
            repeatLabel = strRepeat, mismatch = strMismatch, error = null, busy = false,
            confirm = strContinue, cancel = strCancel,
            onDismiss = { askExportPassword = false }
        ) { password ->
            askExportPassword = false
            exportPassword = password
            saveFull.launch("birdsocks-full-${today()}.backup")
        }
    }

    locked?.let { data ->
        val strTitle = stringResource(R.string.backup_unlock_title)
        val strText = stringResource(R.string.backup_unlock_text)
        val strOpen = stringResource(R.string.backup_unlock_action)
        val strWrong = stringResource(R.string.backup_wrong_password)
        PasswordDialog(
            title = strTitle, text = strText, label = strPassword, showLabel = strShowPassword,
            repeatLabel = null, mismatch = "", error = if (wrongPassword) strWrong else null, busy = busy,
            confirm = strOpen, cancel = strCancel,
            onDismiss = { locked = null }
        ) { password ->
            wrongPassword = false
            scope.launch {
                runCatching { Backup.unlock(context, data, password) }
                    .onSuccess {
                        locked = null
                        full = it
                    }
                    .onFailure {
                        if (it is BackupRefused && it.reason == BackupRefused.Reason.WRONG_PASSWORD) {
                            wrongPassword = true
                        } else {
                            locked = null
                            failed(R.string.backup_restore_failed, it)
                        }
                    }
            }
        }
    }

    settingsFile?.let { file ->
        val strTitle = stringResource(R.string.backup_settings_confirm_title)
        val strText = stringResource(R.string.backup_settings_confirm_text, describe(context, file.manifest))
        AlertDialog(
            onDismissRequest = { settingsFile = null },
            icon = { Icon(Icons.Default.SettingsBackupRestore, null) },
            title = { Text(strTitle) },
            text = { Text(strText, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    settingsFile = null
                    restore(banner = true) { Backup.restoreSettings(context, file.prefs, secrets = false) }
                }) { Text(strRestore) }
            },
            dismissButton = { TextButton(onClick = { settingsFile = null }) { Text(strCancel) } }
        )
    }

    full?.let { backup ->
        var everything by remember(backup) { mutableStateOf(false) }
        val strTitle = stringResource(R.string.backup_scope_title)
        val strText = stringResource(R.string.backup_scope_text, describe(context, backup.manifest), backup.accounts)
        val strSettings = stringResource(R.string.backup_scope_settings)
        val strSettingsDesc = stringResource(R.string.backup_scope_settings_desc)
        val strEverything = stringResource(R.string.backup_scope_everything)
        val strEverythingDesc = stringResource(R.string.backup_scope_everything_desc)
        val strWarning = stringResource(R.string.backup_scope_warning)
        AlertDialog(
            onDismissRequest = { full = null },
            icon = { Icon(Icons.Default.SettingsBackupRestore, null) },
            title = { Text(strTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strText, style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                        ScopeOption(!everything, strSettings, strSettingsDesc) { everything = false }
                        ScopeOption(everything, strEverything, strEverythingDesc) { everything = true }
                    }
                    if (everything) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(strWarning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    full = null
                    if (everything) restore(banner = false) { Backup.restoreEverything(context, backup) }
                    else restore(banner = true) { Backup.restoreSettings(context, backup.prefs, secrets = true) }
                }) { Text(strRestore) }
            },
            dismissButton = { TextButton(onClick = { full = null }) { Text(strCancel) } }
        )
    }
}

/** One of the two choices of what to restore: a radio row with a line under its title. */
@Composable
private fun ScopeOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A password, and with [repeatLabel] the same again: the one for a new backup
 * cannot be recovered, so a typo in it would lose the backup. Strings come in
 * resolved, as for every dialog here.
 */
@Composable
private fun PasswordDialog(
    title: String,
    text: String,
    label: String,
    showLabel: String,
    repeatLabel: String?,
    mismatch: String,
    error: String?,
    busy: Boolean,
    confirm: String,
    cancel: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val differs = repeatLabel != null && repeat.isNotEmpty() && repeat != password
    val ready = !busy && password.isNotEmpty() && (repeatLabel == null || repeat == password)
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(label) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    visualTransformation = transformation,
                    keyboardOptions = keyboard,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, showLabel)
                        }
                    },
                    supportingText = error?.let { { Text(it) } },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                if (repeatLabel != null) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(repeatLabel) },
                        singleLine = true,
                        isError = differs,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        supportingText = if (differs) { { Text(mismatch) } } else null,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onConfirm(password.toCharArray()) }) { Text(confirm) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(cancel) } }
    )
}

/** For file names: 2026-10-04. */
private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun toast(context: Context, text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()

/** "From <date>, BirdSocks <version>". */
private fun describe(context: Context, m: BackupFormat.Manifest): String {
    val locale = context.resources.configuration.locales[0]
    val date = if (m.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(m.createdAt)) else "?"
    return context.getString(R.string.backup_from, date, m.versionName.ifEmpty { "?" })
}

/** Why a backup was not saved or restored, in words. */
private fun describe(context: Context, e: Throwable): String = when (e) {
    is BackupRefused -> when (e.reason) {
        BackupRefused.Reason.NOT_A_BACKUP -> context.getString(R.string.backup_err_not_backup)
        BackupRefused.Reason.OTHER_APP -> context.getString(R.string.backup_err_other_app, e.detail)
        BackupRefused.Reason.FORMAT_TOO_NEW -> context.getString(R.string.backup_err_format_new)
        BackupRefused.Reason.APP_TOO_NEW -> context.getString(R.string.backup_err_app_new, e.detail)
        BackupRefused.Reason.WRONG_PASSWORD -> context.getString(R.string.backup_wrong_password)
        BackupRefused.Reason.FORBIDDEN_ENTRY -> context.getString(R.string.backup_err_entry, e.detail)
        BackupRefused.Reason.TOO_LARGE -> context.getString(R.string.backup_err_too_large)
        BackupRefused.Reason.DAEMON_BUSY -> context.getString(R.string.backup_err_daemon)
    }
    else -> e.message ?: e.toString()
}
