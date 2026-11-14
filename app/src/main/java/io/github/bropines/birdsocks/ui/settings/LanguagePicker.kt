package io.github.bropines.birdsocks.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.AppLanguages
import io.github.bropines.birdsocks.core.AppLocale
import io.github.bropines.birdsocks.ui.PickerOption
import io.github.bropines.birdsocks.ui.PickerSheet
import io.github.bropines.birdsocks.ui.theme.findActivity

/**
 * The app's language: one row naming the current choice, and a sheet with the system's
 * language and every one in [AppLanguages]. A row rather than chips: chips held three, and
 * Weblate keeps adding languages. Each one is named in its own language, with its name in
 * the current one under it.
 *
 * The pick is applied when the sheet has slid away: it recreates the screen, which would
 * otherwise cut the sheet off mid-slide. The open section survives it: the hub keeps it in
 * saved state.
 */
@Composable
internal fun LanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLocale.current(context)) }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    // Resolved here: the sheet is a window of its own — see wrapContextWithLocale().
    val systemLabel = stringResource(R.string.settings_lang_sys)
    val title = stringResource(R.string.settings_lang_title)
    val options = languageOptions(systemLabel)

    val shown = if (current in AppLanguages.TAGS) AppLanguages.nativeName(current) else systemLabel
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button) { picking = true }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Language, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (picking) {
        PickerSheet(
            title = title,
            options = options,
            selected = if (current in AppLanguages.TAGS) current else AppLanguages.SYSTEM,
            onPick = { if (it != current) picked = it },
            onDismiss = {
                picking = false
                picked?.let { tag ->
                    picked = null
                    current = tag
                    AppLocale.set(context, tag)
                    context.findActivity()?.recreate()
                }
            }
        )
    }
}

/** The sheet's rows: the system's language, then each of [AppLanguages], named in the composition's language too. */
@Composable
private fun languageOptions(systemLabel: String): List<PickerOption<String>> {
    val reader = LocalConfiguration.current.locales[0]
    return buildList {
        add(PickerOption(AppLanguages.SYSTEM, systemLabel, Icons.Default.PhoneAndroid))
        AppLanguages.TAGS.forEach { tag ->
            add(PickerOption(tag, AppLanguages.nativeName(tag), Icons.Default.Translate, supporting = AppLanguages.nameFor(tag, reader)))
        }
    }
}
