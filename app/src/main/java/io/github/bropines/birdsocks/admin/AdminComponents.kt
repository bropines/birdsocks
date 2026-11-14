package io.github.bropines.birdsocks.admin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ProxyRoute
import io.github.bropines.birdsocks.admin.api.ProxyUnusableException
import io.github.bropines.birdsocks.ui.parseRfc3339Millis
import io.nayuki.qrcodegen.DataTooLongException
import io.nayuki.qrcodegen.QrCode

/** What went wrong, in the user's words; the server's own message after it when it sent one. */
fun describeError(context: Context, e: Throwable): String {
    val base = when (e) {
        is AdminApiException.Unauthorized -> context.getString(R.string.admin_err_unauthorized)
        is AdminApiException.Forbidden -> context.getString(R.string.admin_err_forbidden)
        is AdminApiException.NotFound -> context.getString(R.string.admin_err_not_found)
        is AdminApiException.Conflict -> context.getString(R.string.admin_err_conflict)
        is AdminApiException.BadRequest -> context.getString(R.string.admin_err_bad_request)
        is AdminApiException.RateLimited -> context.getString(R.string.admin_err_rate_limited)
        is AdminApiException.Server -> context.getString(R.string.admin_err_server, e.status)
        is AdminApiException.Unexpected -> context.getString(R.string.admin_err_unexpected, e.status)
        is AdminApiException.Decode -> context.getString(R.string.admin_err_decode, e.what)
        is AdminApiException.Network -> when ((e.cause as? ProxyUnusableException)?.reason) {
            ProxyRoute.Reason.SOCKS_AUTH -> context.getString(R.string.admin_err_proxy_socks_auth)
            ProxyRoute.Reason.BAD_URL -> context.getString(R.string.admin_err_proxy_bad)
            ProxyRoute.Reason.BYEDPI_DOWN -> context.getString(R.string.admin_err_proxy_byedpi)
            null -> context.getString(R.string.admin_err_network, e.detail)
        }
        else -> e.message ?: e.javaClass.simpleName
    }
    val server = (e as? AdminApiException)?.apiMessage
    return if (server.isNullOrBlank()) base else "$base\n$server"
}

/** Copies [text]; marked sensitive so Android 13+ hides it from the clipboard preview. */
fun copySecret(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** An API date as the phone shows dates, or "" for none (and for Go's zero time). */
fun formatApiDate(stamp: String?): String {
    val millis = parseRfc3339Millis(stamp) ?: return ""
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(millis))
}

/**
 * [text] as a square QR code, dark on light whatever the theme: a camera
 * looks for dark on light. Modules are whole pixels, centred, with the
 * standard's four-module quiet zone.
 */
@Composable
fun QrCodeImage(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { try { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) } catch (_: DataTooLongException) { null } }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (qr == null) {
            Text(text, color = Color.Black, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        } else {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .semantics { this.contentDescription = contentDescription }
                    .drawBehind {
                        val quiet = 4
                        val module = maxOf(1, (minOf(size.width, size.height) / (qr.size + 2 * quiet)).toInt()).toFloat()
                        val left = ((size.width - module * qr.size) / 2).toInt().toFloat()
                        val top = ((size.height - module * qr.size) / 2).toInt().toFloat()
                        for (y in 0 until qr.size) for (x in 0 until qr.size) {
                            if (qr.getModule(x, y)) drawRect(Color.Black, Offset(left + x * module, top + y * module), Size(module, module))
                        }
                    }
            )
        }
    }
}
