package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.*
import io.github.bropines.birdsocks.models.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A titled group of settings rows. [note] is one line under the title saying
 * how a change here applies (a restart, a reconnect), for the groups where
 * that is not obvious.
 */
@Composable
fun SettingsCard(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = if (note == null) 12.dp else 2.dp)
            )
            if (note != null) HelpText(note, Modifier.padding(bottom = 12.dp), lines = 1)
            content()
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SettingsClickableItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val help = remember(subtitle) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { if (enabled) onClick() },
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); help.value = !help.value }
        )
    ) {
        ListItem(
            supportingContent = { HelpText(subtitle, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline, expanded = help, tapToExpand = false) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) }
    }
}

/**
 * Explanatory text that starts folded: [lines] lines at most, an ⓘ at the end
 * when there is more. Tapping the ⓘ unfolds it in place; a parent may drive
 * [expanded] itself, for instance from a long press on its row. Screens used to
 * carry whole paragraphs under every control; this keeps the first sentence in
 * view and the rest one tap away.
 */
@Composable
fun HelpText(
    text: String,
    modifier: Modifier = Modifier,
    lines: Int = 2,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    expanded: MutableState<Boolean> = remember(text) { mutableStateOf(false) },
    /**
     * False inside a row that is itself a button: the text then leaves taps
     * to the row, and only the ⓘ (or the row's long press) unfolds it —
     * a folded description takes most of a row, which left the row a sliver
     * to tap.
     */
    tapToExpand: Boolean = true,
) {
    var cut by remember(text) { mutableStateOf(false) }
    val open = expanded.value
    // On its own, the whole folded text is the tap target, not only the ⓘ:
    // at 16dp the icon was a third of the minimum touch size, and "tap the
    // explanation to read it" is what the fold promises. No ripple — it is
    // text, not a button.
    val expandLabel = stringResource(if (open) R.string.help_collapse else R.string.help_expand)
    Row(
        modifier = modifier
            .animateContentSize()
            .then(
                if (tapToExpand) Modifier.clickable(
                    enabled = cut || open,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = expandLabel
                ) { expanded.value = !open } else Modifier
            ),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            maxLines = if (open) Int.MAX_VALUE else lines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) cut = it.hasVisualOverflow },
            modifier = Modifier.weight(1f)
        )
        if (cut || open) {
            val icon = if (open) Icons.Default.ExpandLess else Icons.Default.Info
            if (tapToExpand) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color.copy(alpha = 0.7f),
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 1.dp)
                        .size(16.dp)
                )
            } else {
                // Its own target, two lines tall, so the row keeps the rest.
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = expandLabel) { expanded.value = !open },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = expandLabel, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Long press on a settings row: the full explanation, in the parent's locale. */
@Composable
private fun SettingsHelpDialog(title: String, text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(android.R.string.ok)) } }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val help = remember(subtitle) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { if (enabled) onCheckedChange(!checked) },
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); help.value = !help.value }
        )
    ) {
        ListItem(
            supportingContent = { HelpText(subtitle, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline, expanded = help, tapToExpand = false) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SettingsEditItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String = "",
    description: String = "",
    enabled: Boolean = true,
    onAction: (() -> String)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(showDialog) { if (showDialog) text = value }
    val notSet = stringResource(R.string.settings_not_set)
    // A dialog/sheet opens its own window whose LocalContext ignores the app
    // locale, so its strings are resolved through this parent context instead —
    // see wrapContextWithLocale().
    val ctx = LocalContext.current
    // What the row shows under its title: the description while there is no
    // value (or the row is locked), otherwise the value. A shown description
    // folds like every other explanation; a hidden one opens on a long press.
    val showsDescription = description.isNotEmpty() && (!enabled || value.isEmpty())
    val supporting = if (showsDescription) description else if (value.isEmpty()) placeholder.ifEmpty { notSet } else value
    val help = remember(supporting) { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    if (showHelp) SettingsHelpDialog(title, description.ifEmpty { placeholder.ifEmpty { notSet } }) { showHelp = false }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f),
        modifier = Modifier.padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { if (enabled) showDialog = true },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (showsDescription) help.value = !help.value else showHelp = true
            }
        )
    ) {
        ListItem(
            supportingContent = {
                HelpText(
                    supporting,
                    lines = if (showsDescription) 2 else 1,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    expanded = help,
                    tapToExpand = false
                )
            },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) },
            trailingContent = if (!enabled) { { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) } } else null,
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1,
                    shape = MaterialTheme.shapes.medium,
                    label = { if (placeholder.isNotEmpty()) Text(ctx.getString(R.string.settings_field_example, placeholder)) },
                    placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                    trailingIcon = if (onAction != null && actionIcon != null) {
                        { IconButton(onClick = { text = onAction() }) { Icon(actionIcon, ctx.getString(R.string.action_generate)) } }
                    } else null
                )
            },
            confirmButton = { Button(onClick = { onSave(text); showDialog = false }) { Text(ctx.getString(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(ctx.getString(R.string.action_cancel)) } }
        )
    }
}

@Composable
fun CopyablePathItem(
    label: String,
    path: String,
    context: Context
) {
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, path))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.settings_copy_cd_format, label),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
