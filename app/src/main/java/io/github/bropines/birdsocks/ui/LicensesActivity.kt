package io.github.bropines.birdsocks.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import kotlinx.serialization.Serializable

/**
 * The open-source software in this APK and its licenses. The list is
 * assets/licenses.json, written by scripts/licenses/generate.py from what the
 * build actually links (the Go module lists in the native libraries, the
 * release runtime classpath, the C libraries ndk-build compiles) and committed,
 * so the screen needs no network and the build no generator.
 */
class LicensesActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BirdSocksTheme { LicensesScreen(onBack = { finish() }) } }
    }
}

@Serializable
internal data class LicenseDoc(
    val sections: List<LicenseSection> = emptyList(),
    val texts: Map<String, String> = emptyMap(),
)

@Serializable
internal data class LicenseSection(val id: String, val components: List<LicenseComponent> = emptyList())

@Serializable
internal data class LicenseComponent(
    val name: String,
    val version: String = "",
    val url: String = "",
    val license: String = "",
    val texts: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
)

internal fun loadLicenses(context: Context): LicenseDoc? = runCatching {
    context.assets.open("licenses.json").use { AppJson.decodeFromString<LicenseDoc>(it.readBytes().decodeToString()) }
}.getOrNull()

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val doc = remember { loadLicenses(context) }
    LicensesContent(doc, onBack)
}

/** The screen without its loading, for previews. [initialOpen] opens a component's text at once, as "section/name". */
@Composable
internal fun LicensesContent(doc: LicenseDoc?, onBack: () -> Unit, initialOpen: String? = null) {
    var openName by rememberSaveable { mutableStateOf(initialOpen) }
    val total = doc?.sections?.sumOf { it.components.size } ?: 0
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.licenses_title),
                subtitle = if (total > 0) stringResource(R.string.licenses_count, total) else null,
                onBack = onBack
            )
        }
    ) { padding ->
        if (doc == null) {
            Text(
                stringResource(R.string.licenses_load_failed),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.error
            )
            return@Scaffold
        }
        fun keyOf(section: LicenseSection, c: LicenseComponent) = section.id + "/" + c.name
        val open = openName?.let { key ->
            doc.sections.firstNotNullOfOrNull { s -> s.components.firstOrNull { keyOf(s, it) == key } }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            item(key = "intro") {
                HelpText(stringResource(R.string.licenses_help), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
            for (section in doc.sections) {
                item(key = "h-" + section.id) {
                    Text(
                        sectionTitle(section.id),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
                    )
                }
                item(key = "s-" + section.id) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            section.components.forEachIndexed { i, c ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                LicenseRow(c) { openName = keyOf(section, c) }
                            }
                        }
                    }
                }
            }
        }
        if (open != null) LicenseSheet(open, doc.texts) { openName = null }
    }
}

@Composable
private fun sectionTitle(id: String): String = stringResource(
    when (id) {
        "app" -> R.string.licenses_section_app
        "go" -> R.string.licenses_section_go
        "android" -> R.string.licenses_section_android
        "native" -> R.string.licenses_section_native
        else -> R.string.licenses_section_other
    }
)

/** Module paths break nowhere on their own; let them wrap after a slash. */
private fun String.breakable(): String = replace("/", "/​")

@Composable
private fun LicenseRow(c: LicenseComponent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name.breakable(), style = MaterialTheme.typography.bodyMedium)
            if (c.version.isNotEmpty() || c.includes.isNotEmpty()) {
                Text(
                    c.version.ifEmpty { stringResource(R.string.licenses_parts, c.includes.size) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(c.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
    }
}

/** A component's licence in a sheet over the list. Strings are read here, in the screen's language, not in the sheet's window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(c: LicenseComponent, texts: Map<String, String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val includesLabel = context.getString(R.string.licenses_includes)
    val pageLabel = context.getString(R.string.licenses_open_page)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(c.name.breakable(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val line = listOf(c.version, c.license).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (c.includes.isNotEmpty()) item {
                Column {
                    Text(includesLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(c.includes.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (c.url.startsWith("http")) item {
                OutlinedButton(onClick = { openUrl(context, c.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(pageLabel)
                }
            }
            items(c.texts) { id ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            texts[id].orEmpty().trimEnd(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
