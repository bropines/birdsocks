package io.github.bropines.birdsocks.ui

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.birdsocks.R

/*
 * A peer's "Copy as…": its names and addresses, and the commands they are typed into, each
 * exactly as it would be pasted. Opened by a long press on the peer's row; the forms come
 * from the status the list already holds.
 */

internal enum class PeerCopyKind { NAME, FQDN, IPV4, IPV6, SSH, PING }

/** One ready-to-paste form. [text] is both what the row shows and what a tap copies. */
internal data class PeerCopyForm(val kind: PeerCopyKind, val text: String)

/**
 * The forms a peer has, in the order the sheet lists them. Empty values are left out, and so
 * is a name that repeats the one before it. Commands name the peer by its FQDN, which
 * NetBird's DNS resolves, and by its IPv4 where it has no name.
 */
internal fun peerCopyForms(shortName: String, fqdn: String, ipv4: String, ipv6: String): List<PeerCopyForm> {
    val name = fqdn.trim().trimEnd('.')
    val host = name.ifEmpty { ipv4 }
    return buildList {
        if (shortName.isNotEmpty() && shortName != ipv4) add(PeerCopyForm(PeerCopyKind.NAME, shortName))
        if (name.isNotEmpty() && name != shortName) add(PeerCopyForm(PeerCopyKind.FQDN, name))
        if (ipv4.isNotEmpty()) add(PeerCopyForm(PeerCopyKind.IPV4, ipv4))
        if (ipv6.isNotEmpty()) add(PeerCopyForm(PeerCopyKind.IPV6, ipv6))
        if (host.isNotEmpty()) {
            add(PeerCopyForm(PeerCopyKind.SSH, "ssh $host"))
            add(PeerCopyForm(PeerCopyKind.PING, "ping $host"))
        }
    }
}

/** The sheet of [forms] for the peer called [title]; a tap copies one and closes it. */
@Composable
internal fun PeerCopyAsSheet(title: String, forms: List<PeerCopyForm>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Resolved here, in the screen's language: the sheet is a window of its own.
    val labels = PeerCopyKind.entries.associateWith { stringResource(it.label) }
    val sheetTitle = stringResource(R.string.peer_copy_as_title, title)
    PickerSheet(
        title = sheetTitle,
        options = forms.map { PickerOption(it, labels.getValue(it.kind), it.kind.icon, supporting = it.text) },
        onPick = { form ->
            clipboard.copyText(scope, form.text)
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard, form.text), Toast.LENGTH_SHORT).show()
        },
        onDismiss = onDismiss
    )
}

private val PeerCopyKind.label: Int
    get() = when (this) {
        PeerCopyKind.NAME -> R.string.peer_copy_name
        PeerCopyKind.FQDN -> R.string.peer_copy_fqdn
        PeerCopyKind.IPV4 -> R.string.peer_copy_ipv4
        PeerCopyKind.IPV6 -> R.string.peer_copy_ipv6
        PeerCopyKind.SSH -> R.string.peer_copy_ssh
        PeerCopyKind.PING -> R.string.peer_copy_ping
    }

private val PeerCopyKind.icon
    get() = when (this) {
        PeerCopyKind.NAME -> Icons.Default.Badge
        PeerCopyKind.FQDN -> Icons.Default.Dns
        PeerCopyKind.IPV4, PeerCopyKind.IPV6 -> Icons.Default.Lan
        PeerCopyKind.SSH -> Icons.Default.Terminal
        PeerCopyKind.PING -> Icons.Default.NetworkPing
    }
