package io.github.bropines.birdsocks.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Shared pieces of the screens: empty lists, time words, log colouring,
// sheets and the clipboard.

/**
 * A list with nothing in it: an icon, one line, and — when one thing would change that, such
 * as clearing a search — the button that does it. [modifier] fills the screen by default; in
 * a LazyColumn, where there is no height to fill, pass fillParentMaxSize() or a padding.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * An RFC 3339 stamp as the daemon writes one (Go's time.Time: fractional seconds optional,
 * "Z" or an offset) in epoch milliseconds; null for Go's zero time and anything unreadable.
 * By hand rather than through java.time, which is API 26 and this app runs from 24.
 */
internal fun parseRfc3339Millis(stamp: String?): Long? {
    if (stamp.isNullOrBlank() || stamp.startsWith("0001-01-01")) return null
    val m = RFC3339.matchEntire(stamp.trim()) ?: return null
    val base = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(m.groupValues[1])?.time
    }.getOrNull() ?: return null
    val millis = m.groupValues[2].drop(1).padEnd(3, '0').take(3).toInt()
    val zone = m.groupValues[3]
    val offset = if (zone == "Z" || zone == "z") 0L else {
        val sign = if (zone[0] == '-') -1 else 1
        sign * (zone.substring(1, 3).toLong() * 60 + zone.substring(4, 6).toLong()) * 60_000L
    }
    return base + millis - offset
}

private val RFC3339 = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

/** "2 h ago", in the app's language: [context] must be the locale-wrapped one. */
internal fun agoText(context: Context, thenMillis: Long, nowMillis: Long): String {
    val res = context.resources
    val minutes = ((nowMillis - thenMillis) / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> res.getString(R.string.ago_just_now)
        hours < 1 -> res.getString(R.string.ago_minutes, minutes.toInt())
        days < 1 -> res.getString(R.string.ago_hours, hours.toInt())
        days < 60 -> res.getQuantityString(R.plurals.ago_days, days.toInt(), days.toInt())
        days < 365 -> res.getString(R.string.ago_months, (days / 30).toInt())
        else -> res.getQuantityString(R.plurals.ago_years, (days / 365).toInt(), (days / 365).toInt())
    }
}

fun highlightLogMessage(
    timestamp: String,
    category: String,
    message: String,
    defaultColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(style = SpanStyle(color = Color(0xFF757575))) {
            append(timestamp)
            append(" ")
        }

        val catColor = when (category) {
            "ERROR" -> Color(0xFFEF5350)
            "CORE" -> Color(0xFF42A5F5)
            "NETBIRD" -> Color(0xFF66BB6A)
            "LOGCAT" -> Color(0xFF26A69A)
            else -> Color(0xFFFFA726)
        }
        withStyle(style = SpanStyle(color = catColor, fontWeight = FontWeight.Bold)) {
            append("[")
            append(category)
            append("] ")
        }

        val ipRegex = """\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b""".toRegex()
        val ipv6Regex = """\b([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""".toRegex()
        val keyValueRegex = """\b([a-zA-Z0-9_\-]+)=([^\s]+)\b""".toRegex()
        
        val successKeywords = setOf("success", "successful", "ok", "online", "active", "connected", "working", "available", "healthy")
        val errorKeywords = setOf("error", "failed", "fail", "blocked", "offline", "unavailable", "unreachable", "exception", "panic", "warning", "warn")

        val text = message
        var lastIdx = 0
        
        val matches = (ipRegex.findAll(text) + ipv6Regex.findAll(text) + keyValueRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()

        val nonOverlappingMatches = mutableListOf<MatchResult>()
        for (match in matches) {
            if (nonOverlappingMatches.isEmpty() || match.range.first >= nonOverlappingMatches.last().range.last + 1) {
                nonOverlappingMatches.add(match)
            }
        }

        for (match in nonOverlappingMatches) {
            val start = match.range.first
            val end = match.range.last + 1
            
            appendWithKeywords(text.substring(lastIdx, start), defaultColor, successKeywords, errorKeywords)
            
            val matchText = match.value
            if (ipRegex.matches(matchText) || ipv6Regex.matches(matchText)) {
                withStyle(style = SpanStyle(color = Color(0xFF80DEEA), fontWeight = FontWeight.Medium)) {
                    append(matchText)
                }
            } else {
                val parts = matchText.split("=", limit = 2)
                if (parts.size == 2) {
                    withStyle(style = SpanStyle(color = Color(0xFFFFCC80))) {
                        append(parts[0])
                        append("=")
                    }
                    val valText = parts[1]
                    val valColor = when {
                        valText.lowercase() in successKeywords -> Color(0xFFA5D6A7)
                        valText.lowercase() in errorKeywords -> Color(0xFFEF9A9A)
                        valText.all { it.isDigit() || it == '.' || it == ':' || it == 'm' || it == 's' } -> Color(0xFFB39DDB)
                        else -> Color(0xFFEEEEEE)
                    }
                    withStyle(style = SpanStyle(color = valColor)) {
                        append(valText)
                    }
                } else {
                    append(matchText)
                }
            }
            lastIdx = end
        }
        
        if (lastIdx < text.length) {
            appendWithKeywords(text.substring(lastIdx), defaultColor, successKeywords, errorKeywords)
        }
    }
}

private fun AnnotatedString.Builder.appendWithKeywords(
    text: String,
    defaultColor: Color,
    successKeywords: Set<String>,
    errorKeywords: Set<String>
) {
    val wordRegex = """\b[a-zA-Z_]+\b""".toRegex()
    var lastIdx = 0
    val matches = wordRegex.findAll(text).toList()
    
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        if (start > lastIdx) {
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(text.substring(lastIdx, start))
            }
        }
        
        val word = match.value
        val lowerWord = word.lowercase()
        val wordColor = when {
            lowerWord in successKeywords -> Color(0xFF81C784)
            lowerWord in errorKeywords -> Color(0xFFE57373)
            else -> defaultColor
        }
        withStyle(style = SpanStyle(color = wordColor, fontWeight = if (wordColor != defaultColor) FontWeight.Bold else FontWeight.Normal)) {
            append(word)
        }
        lastIdx = end
    }
    
    if (lastIdx < text.length) {
        withStyle(style = SpanStyle(color = defaultColor)) {
            append(text.substring(lastIdx))
        }
    }
}

/**
 * A modal sheet's state that opens all the way and hides, with no half-open
 * stop — what every sheet in the app asks for. Material3 1.5 deprecated
 * rememberFullSheetState() for this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberFullSheetState(): SheetState =
    rememberBottomSheetState(
        // A demo draws the sheet already open: the renderer keeps the first frame, before the slide in.
        initialValue = if (LocalDemo.current != null) SheetValue.Expanded else SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

/**
 * Puts [text] on the clipboard from a callback: LocalClipboard, which
 * replaced LocalClipboardManager, is a suspend API.
 */
fun Clipboard.copyText(scope: CoroutineScope, text: String) {
    scope.launch { setClipEntry(ClipEntry(ClipData.newPlainText("BirdSocks", text))) }
}

/** The clipboard's text, or null when it holds none. */
suspend fun Clipboard.readText(context: Context): String? =
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
