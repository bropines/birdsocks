package io.github.bropines.birdsocks.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * One object's details as a bottom sheet: a title row with [badges], an
 * optional [subtitle], then [content] in a scrolling column. The parent's
 * locals are provided inside, so stringResource() is safe in [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    badges: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val parent = rememberParentLocals()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        parent.Provide {
            DetailSheetBody(title, subtitle, badges, content)
        }
    }
}

/** The sheet's body alone, for previews and for a pane on a wide screen. */
@Composable
fun DetailSheetBody(title: String, subtitle: String?, badges: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { badges() }
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * A plain yes/no question before something that is not itself a change (a
 * discard, leaving an editor). Changes confirm through their own gates —
 * propose them instead. Strings come in resolved.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = if (destructive) Icons.Default.Warning else null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/**
 * Asks for [expected] to be typed back before [onConfirm] is offered. For a
 * step outside the change gates (the gates have their own typed name).
 * [prompt] says what to type, e.g. "Type office to continue".
 */
@Composable
fun TypeBackDialog(
    title: String,
    text: String,
    prompt: String,
    expected: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable(expected) { mutableStateOf("") }
    val matches = typed.trim() == expected.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                Spacer(Modifier.height(8.dp))
                Text(prompt, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    typed, { typed = it }, singleLine = true,
                    isError = typed.isNotEmpty() && !matches,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = matches, onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Preview
@Composable
private fun DetailSheetBodyPreview() = BirdSocksTheme {
    Surface {
        DetailSheetBody("laptop", "100.64.0.7", badges = { StatusBadge("online", MaterialTheme.colorScheme.primaryContainer) }) {
            DetailField(stringResource(R.string.admin_peer_os), "linux · 0.83.1")
            DetailField(stringResource(R.string.admin_peer_dns), "laptop.netbird.cloud", mono = true)
        }
    }
}
