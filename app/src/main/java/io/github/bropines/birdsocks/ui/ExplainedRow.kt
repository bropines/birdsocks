package io.github.bropines.birdsocks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * One row of the settings family: [content] on a tinted surface that is a
 * button as a whole. [onClick] is the row's action; a long press folds [help]
 * — the state the row's `HelpText` is given — out and in, with a tick, unless
 * [onLongClick] does something of its own. A row that is switched off still
 * takes the press and decides in [onClick]: it keeps its place under a thumb.
 *
 * Every settings row used to repeat this surface, its clip, its long press and
 * its haptics inline; this is the one place for them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplainedRow(
    onClick: () -> Unit,
    help: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    border: BorderStroke? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = shape,
        color = color,
        border = border,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClickLabel = onClickLabel,
                onClick = onClick,
                onLongClick = onLongClick ?: {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    help.value = !help.value
                }
            ),
        content = content
    )
}
