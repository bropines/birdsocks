package io.github.bropines.birdsocks.admin.safety

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.rememberFragmentActivity
import io.github.bropines.birdsocks.admin.secure.AdminWriteGate
import io.github.bropines.birdsocks.admin.secure.UnlockFailure
import io.github.bropines.birdsocks.admin.secure.UnlockResult
import io.github.bropines.birdsocks.admin.ui.TextDiffView

/*
 * The gates on screen. Every string here is read through the Context of the
 * composition that hosts the dialog (captured before the dialog opens): a
 * dialog is a window of its own and stringResource() there can follow the
 * system language, not the app's (see wrapContextWithLocale).
 */

/** Draws [step] and reports what the person did. The AdminActivity hosts one, over every area. */
@Composable
fun SafetyHost(
    step: SafetyStep,
    onConfirm: (typedName: String?) -> Unit,
    onCancel: () -> Unit,
    onUnlockResult: (UnlockResult) -> Unit,
) {
    val ctx = LocalContext.current
    when (step) {
        SafetyStep.Idle -> Unit
        is SafetyStep.Confirm -> ChangeConfirmDialog(ctx, step.planned.change, onConfirm, onCancel)
        is SafetyStep.Unlock -> UnlockEffect(ctx, step, onUnlockResult)
        is SafetyStep.Applying -> ApplyingDialog(ctx, step.change)
    }
}

@Composable
private fun UnlockEffect(ctx: Context, step: SafetyStep.Unlock, onResult: (UnlockResult) -> Unit) {
    if (LocalInspectionMode.current) return
    val activity = rememberFragmentActivity()
    val currentOnResult by rememberUpdatedState(onResult)
    val title = ctx.getString(R.string.admin_unlock_title)
    LaunchedEffect(step) {
        currentOnResult(AdminWriteGate.unlock(activity, title, step.planned.change.title))
    }
}

/** How risky a change is, in a word and an icon, not only a colour. */
@Composable
fun ChangeClassBadge(ctx: Context, changeClass: ChangeClass, modifier: Modifier = Modifier) {
    val (label, icon) = when (changeClass) {
        ChangeClass.LOW -> ctx.getString(R.string.admin_change_class_low) to Icons.Default.Shield
        ChangeClass.MEDIUM -> ctx.getString(R.string.admin_change_class_medium) to Icons.Default.Lock
        ChangeClass.HIGH -> ctx.getString(R.string.admin_change_class_high) to Icons.Default.ReportProblem
        ChangeClass.POLICY -> ctx.getString(R.string.admin_change_class_policy) to Icons.Default.ReportProblem
    }
    val high = changeClass == ChangeClass.HIGH || changeClass == ChangeClass.POLICY
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (high) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (high) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Before → after, a row per field: the old value struck through, the new one plain. */
@Composable
fun FieldDiffPreview(ctx: Context, lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val nothing = ctx.getString(R.string.admin_change_nothing)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ctx.getString(R.string.admin_change_diff), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { line ->
                Column {
                    Text(line.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ctx.getString(R.string.admin_change_before) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            line.before ?: nothing,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                            textDecoration = if (line.before != null) TextDecoration.LineThrough else null,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ctx.getString(R.string.admin_change_after) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            line.after ?: nothing,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** The target's name typed back, or the change's fixed phrase; matches exactly, bar spaces around it. */
@Composable
fun TypedConfirmationField(ctx: Context, expected: String, value: String, onValueChange: (String) -> Unit, phrase: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(ctx.getString(if (phrase) R.string.admin_change_type_phrase else R.string.admin_change_type_name, expected), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(ctx.getString(R.string.admin_change_type_label)) },
            isError = value.isNotEmpty() && value.trim() != expected.trim(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The review gate: what, on what, what it does; the diff and the typed name for HIGH. */
@Composable
fun ChangeConfirmDialog(ctx: Context, change: AdminChange, onConfirm: (typedName: String?) -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable(change.target.id, change.kind.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (change.isHighRisk) Icons.Default.Warning else Icons.Default.Lock, null) },
        title = { Text(change.title) },
        text = { ChangeConfirmContent(ctx, change, typed) { typed = it } },
        confirmButton = {
            val nameOk = !change.needsTypedConfirmation || typed.trim() == change.confirmText.trim()
            Button(
                onClick = { onConfirm(if (change.needsTypedConfirmation) typed else null) },
                enabled = nameOk,
                colors = if (change.isHighRisk) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(ctx.getString(R.string.admin_change_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/** The review dialog's body, apart so a preview can draw it without a dialog window. */
@Composable
fun ChangeConfirmContent(ctx: Context, change: AdminChange, typed: String, onTyped: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChangeClassBadge(ctx, change.changeClass)
        Column {
            Text(ctx.getString(R.string.admin_change_target), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(change.target.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (change.target.id.isNotEmpty() && change.target.id != change.target.name) {
                Text(change.target.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(change.effect, style = MaterialTheme.typography.bodyMedium)
        change.warnings.forEach { WarningLine(it) }
        if (change.target.isThisDevice) WarningLine(ctx.getString(R.string.admin_change_this_device))
        change.evidence?.let { ev ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ctx.getString(R.string.admin_change_checks), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                ev.notes.forEach { n ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            if (ev.passed) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                            Modifier.size(16.dp).padding(top = 2.dp),
                            tint = if (ev.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(n, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (change.diff.isNotEmpty()) FieldDiffPreview(ctx, change.diff)
        change.textDiff?.let { (before, after) -> TextDiffView(before, after, Modifier.heightIn(max = 320.dp), foldedLabel = { n -> ctx.resources.getQuantityString(R.plurals.admin_diff_folded, n, n) }) }
        if (change.needsTypedConfirmation) TypedConfirmationField(ctx, change.confirmText, typed, onTyped, phrase = change.confirmPhrase != null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LockOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_change_unlock_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ApplyingDialog(ctx: Context, change: AdminChange) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(change.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ctx.getString(R.string.admin_change_applying))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

/** Why a change was refused, in the user's words. */
fun refusalText(ctx: Context, reason: Refusal): String = ctx.getString(
    when (reason) {
        Refusal.READ_ONLY -> R.string.admin_refused_read_only
        Refusal.NO_SCREEN_LOCK -> R.string.admin_refused_no_lock
        Refusal.NOT_ALLOWED -> R.string.admin_refused_not_allowed
        Refusal.OWN_PEER -> R.string.admin_refused_own_peer
        Refusal.OWN_USER -> R.string.admin_refused_own_user
        Refusal.OWN_CREDENTIAL -> R.string.admin_refused_own_credential
        Refusal.NOT_CONFIRMED -> R.string.admin_refused_not_confirmed
        Refusal.TYPED_MISMATCH -> R.string.admin_refused_typed
        Refusal.NOT_UNLOCKED -> R.string.admin_refused_not_unlocked
        Refusal.CHECKS_NOT_PASSED -> R.string.admin_refused_checks
        Refusal.NO_SESSION -> R.string.admin_refused_no_session
    }
)

/** Why the unlock did not happen, in the user's words. */
fun unlockFailureText(ctx: Context, failure: UnlockFailure): String = ctx.getString(
    when (failure) {
        UnlockFailure.NO_SCREEN_LOCK -> R.string.admin_unlock_no_lock
        UnlockFailure.LOCKOUT -> R.string.admin_unlock_lockout
        else -> R.string.admin_unlock_failed
    }
)
