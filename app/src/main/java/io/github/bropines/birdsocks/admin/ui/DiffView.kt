package io.github.bropines.birdsocks.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * A line diff of two texts, unchanged runs folded to [context] lines around
 * each change: removed lines on the error colour, added ones on the primary,
 * with old and new line numbers. [foldedLabel] words "12 unchanged lines"
 * (resolved by the caller, which may be inside a dialog); the default is a
 * bare "⋯ 12".
 */
@Composable
fun TextDiffView(
    before: String,
    after: String,
    modifier: Modifier = Modifier,
    context: Int = 3,
    foldedLabel: (Int) -> String = { "⋯ $it" },
) {
    val rows = remember(before, after, context) { LineDiff.rows(LineDiff.diff(LineDiff.lines(before), LineDiff.lines(after)), context) }
    DiffRows(rows, modifier, foldedLabel)
}

/**
 * Rows already computed ([LineDiff.rows]); scrolls on its own, sideways too,
 * up to [maxHeight] tall — so it may sit inside a scrolling column or dialog.
 */
@Composable
fun DiffRows(rows: List<DiffRow>, modifier: Modifier = Modifier, foldedLabel: (Int) -> String = { "⋯ $it" }, maxHeight: Dp = 480.dp) {
    val removedBg = MaterialTheme.colorScheme.errorContainer
    val addedBg = MaterialTheme.colorScheme.primaryContainer
    val numberColor = MaterialTheme.colorScheme.outline
    val hScroll = rememberScrollState()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        LazyColumn(Modifier.padding(vertical = 6.dp)) {
            itemsIndexed(rows) { _, row ->
                val e = row.edit
                if (e == null) {
                    Text(
                        foldedLabel(row.skipped),
                        style = MaterialTheme.typography.labelSmall,
                        color = numberColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                } else {
                    val (bg, sign) = when (e.kind) {
                        LineEditKind.DELETE -> removedBg to "-"
                        LineEditKind.INSERT -> addedBg to "+"
                        LineEditKind.EQUAL -> Color.Transparent to " "
                    }
                    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp)) {
                        Text(e.oldLine?.toString().orEmpty(), Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = numberColor, textAlign = TextAlign.End)
                        Text(e.newLine?.toString().orEmpty(), Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = numberColor, textAlign = TextAlign.End)
                        Text(
                            "$sign ${e.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            modifier = Modifier.padding(start = 8.dp).horizontalScroll(hScroll),
                        )
                    }
                }
            }
        }
    }
}

/** A summary line for a diff: "+3 −1". */
fun diffSummary(before: String, after: String): String {
    val edits = LineDiff.diff(LineDiff.lines(before), LineDiff.lines(after))
    return "+${LineDiff.added(edits)} −${LineDiff.removed(edits)}"
}

@Preview
@Composable
private fun TextDiffViewPreview() = BirdSocksTheme {
    Column(Modifier.padding(16.dp)) {
        TextDiffView(
            before = "{\n  \"name\": \"office\",\n  \"enabled\": true,\n  \"peers\": []\n}",
            after = "{\n  \"name\": \"office-2\",\n  \"enabled\": true,\n  \"peers\": [\"a\"]\n}",
        )
    }
}
