package io.github.bropines.birdsocks.admin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.AppTopBar
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.json.JsonElement

/**
 * JSON's colours, from the Material scheme: keys primary, strings tertiary,
 * numbers and literals secondary, punctuation outline, anything that is not
 * JSON underlined in the error colour. Search matches get a background, the
 * current one a stronger one. Offsets are not moved: the text is the text.
 */
class JsonHighlight(
    private val colors: ColorScheme,
    private val matches: List<Int> = emptyList(),
    private val matchLength: Int = 0,
    private val currentMatch: Int = -1,
    private val problemOffset: Int? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(annotate(text.text), OffsetMapping.Identity)

    fun annotate(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        for (t in JsonText.tokens(text)) {
            val style = when (t.kind) {
                JsonText.Kind.KEY -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)
                JsonText.Kind.STRING -> SpanStyle(color = colors.tertiary)
                JsonText.Kind.NUMBER, JsonText.Kind.LITERAL -> SpanStyle(color = colors.secondary)
                JsonText.Kind.PUNCT -> SpanStyle(color = colors.outline)
                JsonText.Kind.ERROR -> SpanStyle(color = colors.error, textDecoration = TextDecoration.Underline)
                JsonText.Kind.SPACE -> null
            } ?: continue
            addStyle(style, t.start, t.end)
        }
        if (matchLength > 0) matches.forEachIndexed { i, start ->
            val end = (start + matchLength).coerceAtMost(text.length)
            addStyle(SpanStyle(background = if (i == currentMatch) colors.primaryContainer else colors.tertiaryContainer), start, end)
        }
        problemOffset?.takeIf { it in text.indices }?.let { addStyle(SpanStyle(background = colors.errorContainer, color = colors.onErrorContainer), it, it + 1) }
    }
}

/**
 * The JSON editor over a whole window: [JsonEditor] in a full-screen dialog,
 * with the parent's locals so its strings follow the app's language.
 */
@Composable
fun JsonEditorDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    subtitle: String? = null,
    readOnly: Boolean = false,
    check: (JsonElement) -> String? = { null },
) {
    val parent = rememberParentLocals()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        parent.Provide { JsonEditor(title, initial, onSave, onClose, subtitle, readOnly, check) }
    }
}

/**
 * A JSON document to read or edit: highlighting, the first problem with its
 * line and column (tap it to go there), Format, search with next/previous,
 * undo/redo in whole steps, and a discard question when leaving with
 * changes. Save is offered only for valid JSON that [check] (the area's own
 * rules) does not object to, and only when it differs from [initial]; what
 * is saved is the text, for the area to turn into a PlannedChange.
 */
@Composable
fun JsonEditor(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    subtitle: String? = null,
    readOnly: Boolean = false,
    check: (JsonElement) -> String? = { null },
) {
    val colors = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(initial)) }
    val history = remember { EditHistory(initial to TextRange(0)) }
    var revision by remember { mutableIntStateOf(0) } // recomposes the undo/redo buttons
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var discarding by remember { mutableStateOf(false) }

    val text = value.text
    val problem = remember(text) { JsonText.check(text) }
    val ruleProblem = remember(text, problem) { if (problem == null) JsonText.parse(text)?.let(check) else null }
    val matches = remember(text, query, searching) { if (searching) JsonText.find(text, query) else emptyList() }
    val dirty = text != initial
    val highlight = remember(text, matches, current, problem, colors) {
        JsonHighlight(colors, matches, query.length, current.coerceAtMost(matches.lastIndex), problem?.offset)
    }

    fun set(next: TextFieldValue, boundary: Boolean = false) {
        if (next.text != value.text) history.push(next.text to next.selection, boundary) else history.replace(next.text to next.selection)
        value = next
        revision++
    }
    fun restore(state: Pair<String, TextRange>?) {
        state ?: return
        value = TextFieldValue(state.first, state.second)
        revision++
    }
    fun goTo(offset: Int, length: Int = 0) {
        value = value.copy(selection = TextRange(offset, (offset + length).coerceAtMost(value.text.length)))
    }
    fun close() = if (dirty && !readOnly) discarding = true else onClose()

    BackHandler { close() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                AppTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = { close() },
                    actions = {
                        IconButton(onClick = { searching = !searching }) { Icon(Icons.Default.Search, stringResource(R.string.admin_json_search)) }
                        if (!readOnly) {
                            IconButton(onClick = { restore(history.undo()) }, enabled = revision >= 0 && history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.admin_json_undo)) }
                            IconButton(onClick = { restore(history.redo()) }, enabled = revision >= 0 && history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.admin_json_redo)) }
                            IconButton(onClick = { JsonText.format(text)?.let { set(TextFieldValue(it, TextRange(0)), boundary = true) } }, enabled = problem == null) {
                                Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, stringResource(R.string.admin_json_format))
                            }
                            IconButton(onClick = { onSave(text) }, enabled = dirty && problem == null && ruleProblem == null) {
                                Icon(Icons.Default.Check, stringResource(R.string.action_save))
                            }
                        }
                    },
                )
                if (searching) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            query, { query = it; current = 0; JsonText.find(text, it).firstOrNull()?.let { o -> goTo(o, it.length) } },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.admin_json_search)) },
                            supportingText = { Text(if (matches.isEmpty()) stringResource(R.string.admin_json_no_matches) else "${current.coerceAtMost(matches.lastIndex) + 1} / ${matches.size}") },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { if (matches.isNotEmpty()) { current = (current - 1 + matches.size) % matches.size; goTo(matches[current], query.length) } }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.admin_json_previous))
                        }
                        IconButton(onClick = { if (matches.isNotEmpty()) { current = (current + 1) % matches.size; goTo(matches[current], query.length) } }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.admin_json_next))
                        }
                        IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
                    }
                }
            }
        },
        bottomBar = {
            val (line, col) = remember(text, value.selection) { JsonText.lineColumn(text, value.selection.start) }
            val message = when {
                problem != null -> stringResource(R.string.admin_json_problem, problem.line, problem.column, problem.message)
                ruleProblem != null -> ruleProblem
                else -> null
            }
            Surface(color = if (message != null) colors.errorContainer else colors.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = problem != null) { problem?.let { goTo(it.offset) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message ?: stringResource(R.string.admin_json_valid),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message != null) colors.onErrorContainer else colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.admin_json_position, line, col), style = MaterialTheme.typography.labelSmall, color = colors.outline)
                }
            }
        },
    ) { padding ->
        BasicTextField(
            value = value,
            onValueChange = { if (!readOnly) set(it) else value = value.copy(selection = it.selection) },
            readOnly = readOnly,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyMedium.fontSize, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = highlight,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }

    if (discarding) {
        ConfirmDialog(
            title = stringResource(R.string.admin_json_discard_title),
            text = stringResource(R.string.admin_json_discard_text),
            confirmLabel = stringResource(R.string.admin_json_discard),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { discarding = false; onClose() },
            onDismiss = { discarding = false },
            destructive = true,
        )
    }
}

@Preview
@Composable
private fun JsonEditorPreview() = BirdSocksTheme {
    JsonEditor(
        title = "office",
        subtitle = "Policy",
        initial = "{\n  \"name\": \"office\",\n  \"enabled\": true,\n  \"rules\": [ { \"ports\": [\"22\", 443] } ]\n}",
        onSave = {},
        onClose = {},
    )
}
