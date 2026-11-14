package io.github.bropines.birdsocks.admin.areas.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminNote
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.ui.JsonEditorDialog
import io.github.bropines.birdsocks.admin.ui.JsonText
import io.github.bropines.birdsocks.admin.ui.rememberParentLocals
import io.github.bropines.birdsocks.ui.HelpText
import kotlinx.serialization.json.JsonObject

/**
 * A form in a dialog, for the routing areas: the content scrolls and has the
 * parent's locals (stringResource() is safe in it), [problems] are listed
 * above the buttons, and the optional JSON and Delete buttons sit on the left.
 * Strings passed in are resolved.
 */
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    problems: List<String> = emptyList(),
    onJson: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val parent = rememberParentLocals()
    val cancel = stringResource(R.string.action_cancel)
    val json = stringResource(R.string.admin_routing_json)
    val delete = stringResource(R.string.admin_routing_delete)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            parent.Provide {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    content()
                    problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (onJson != null || onDelete != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onJson != null) TextButton(onClick = onJson) { Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Text(" $json") }
                        if (onDelete != null) TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Text(" $delete")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel) } },
    )
}

/** A labelled switch with an optional line under it. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, help: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked, onChange, enabled = enabled)
        }
        if (help != null) HelpText(help)
    }
}

/** A section title in a list with an optional add button. */
@Composable
fun SectionTitle(text: String, onAdd: (() -> Unit)? = null, addLabel: String = "") {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (onAdd != null) TextButton(onClick = onAdd) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" $addLabel") }
    }
}

/** Small chips of text on a row, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Badges(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/**
 * One object to open in the JSON editor: where it lives under /api, its name,
 * and how a saved document becomes a change. [fallback] is shown read-only when
 * the server's copy cannot be read (previews, no connection): editing a copy
 * the model rebuilt would drop the fields it does not know.
 */
class JsonTarget(
    val title: String,
    val subtitle: String,
    val path: String,
    val fallback: JsonObject,
    val editable: Boolean,
    val build: (before: JsonObject, after: JsonObject) -> PlannedChange,
)

/**
 * The JSON editor over [target]: reads the object raw, then edits it; Save
 * proposes the area's change and closes when it was taken.
 */
@Composable
fun RawJsonEditor(host: AdminHost, target: JsonTarget, onClose: () -> Unit) {
    var doc by remember(target.path) { mutableStateOf<JsonObject?>(null) }
    var readOnly by remember(target.path) { mutableStateOf(!target.editable) }
    val notObject = stringResource(R.string.admin_routing_json_not_object)
    LaunchedEffect(target.path) {
        val api = host.api
        doc = if (api == null) {
            readOnly = true
            target.fallback
        } else {
            try {
                api.rawGet(target.path) as? JsonObject ?: target.fallback.also { readOnly = true }
            } catch (e: Exception) {
                host.notify(AdminNote.error(e))
                readOnly = true
                target.fallback
            }
        }
    }
    val before = doc ?: return
    JsonEditorDialog(
        title = target.title,
        subtitle = target.subtitle,
        initial = JsonText.format(before),
        readOnly = readOnly,
        check = { if (it !is JsonObject) notObject else null },
        onSave = { text ->
            val after = JsonText.parse(text) as? JsonObject ?: return@JsonEditorDialog
            if (host.propose(target.build(before, after))) onClose()
        },
        onClose = onClose,
    )
}
