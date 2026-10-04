package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.formatFileSize
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbLocalPeer
import io.github.bropines.birdsocks.models.NbPeer
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// The peer list's rows and the details sheet, ported from TailSocks' peer UI
// and retyped for what NetBird reports: a connection state instead of an
// Online bit, ICE candidates and a relay instead of DERP regions, a latency the
// daemon measures by itself, and no OS. The sheet pages through this device
// and the peers behind it with the same page turn TailSocks has.

/** How far the finger has to travel before the details sheet turns to the next peer. In dp:
 *  in raw pixels the same flick would turn the page on one device and not on another. */
private val PEER_SWIPE_THRESHOLD = 100.dp
/** A flick faster than this turns the page whatever distance it covered. */
private val PEER_SWIPE_FLING_VELOCITY = 400.dp
/** How much more horizontal than vertical the finger has to be before the page-turn claims
 *  the gesture. A diagonal drag is left to the sheet's own drag-to-dismiss. */
private const val PEER_SWIPE_DIRECTION_RATIO = 2f
/** How much of the drag survives when there is no peer in that direction. */
private const val PEER_SWIPE_RESISTANCE = 0.25f
/** Half a pixel of residual is where a page has visibly landed. The swipe animates raw
 *  pixels, so the library's own 0.01 default — written for a 0..1 fraction — leaves a spring
 *  running for roughly another 165 ms on a phone-width displacement, and the page turn is
 *  only committed once the spring reports done. */
private const val PEER_SWIPE_SETTLE_THRESHOLD_PX = 0.5f
/** How long a copied row shows its tick before going back to the copy icon. */
private const val PEER_COPIED_ACK_MS = 1500L

/** Material's spatial spring, told what "finished" means in pixels. Every field but the
 *  threshold is the scheme's, so the motion is still the theme's and not this file's. */
private fun FiniteAnimationSpec<Float>.settlingInPixels(): FiniteAnimationSpec<Float> =
    (this as? SpringSpec<Float>)?.let {
        spring(it.dampingRatio, it.stiffness, PEER_SWIPE_SETTLE_THRESHOLD_PX)
    } ?: this

/** The dot colours, shared by the list row, the identity chip and the status strip so the
 *  three cannot drift apart. Fixed rather than theme-derived: they sit side by side and say
 *  the same thing, so they must be the same colour in both schemes. */
private val PEER_CONNECTED_GREEN = Color(0xFF4CAF50)
private val PEER_CONNECTING_AMBER = Color(0xFFFFA000)
private val PEER_IDLE_GREY = Color(0xFF9E9E9E)

/** The sheet's key for this device's page; a peer's is its WireGuard public key. */
internal const val SELF_PAGE_KEY = "self"

/**
 * One page of the details sheet. The sheet pages through this device and the peers of the
 * list behind it, and the two are different things: a peer is what the daemon knows about
 * a connection, this device is what it knows about itself.
 */
sealed interface PeerPage {
    /** Stable across refreshes: what the list and the sheet find a page by. */
    val key: String

    /** [lazy]: lazy connections are on, so an idle peer is waiting for traffic, not lost. */
    data class Peer(val peer: NbPeer, val lazy: Boolean = false) : PeerPage {
        override val key: String get() = peer.pubKey
    }

    /** [dnsLabels]: the extra names this device asks for, as set in Settings → DNS. */
    data class Self(val status: NbStatus, val dnsLabels: List<String>) : PeerPage {
        override val key: String get() = SELF_PAGE_KEY
    }
}

/** NetBird's three peer states, and this device's own state folded onto them. */
internal enum class PeerConn { Connected, Connecting, Idle }

internal val NbPeer.conn: PeerConn
    get() = when {
        connected -> PeerConn.Connected
        connecting -> PeerConn.Connecting
        else -> PeerConn.Idle
    }

internal fun selfConnOf(status: NbStatus?): PeerConn = when (status?.state) {
    NbConnState.Connected -> PeerConn.Connected
    NbConnState.Connecting -> PeerConn.Connecting
    else -> PeerConn.Idle
}

private fun PeerConn.dotColor(): Color = when (this) {
    PeerConn.Connected -> PEER_CONNECTED_GREEN
    PeerConn.Connecting -> PEER_CONNECTING_AMBER
    PeerConn.Idle -> PEER_IDLE_GREY
}

/** NetBird reports no OS, so the icon says what the peer does: an exit node routes the
 *  whole internet, a routing peer some networks, anything else is a device. */
private fun roleIcon(networks: List<String>, isSelf: Boolean): ImageVector = when {
    networks.any { it == "0.0.0.0/0" || it == "::/0" } -> Icons.Default.Public
    networks.isNotEmpty() -> Icons.Default.Hub
    isSelf -> Icons.Default.Smartphone
    else -> Icons.Default.Devices
}

/** The daemon's latency in milliseconds, as a row and a card print it: a LAN peer answers
 *  in under a millisecond, and "0 ms" would read as no answer at all. */
internal fun latencyText(ms: Double): String =
    if (ms < 10) "%.1f ms".format(ms) else "%.0f ms".format(ms)

/**
 * How the row reads a peer's path, after its address. A connected peer is either direct
 * (P2P) or relayed; an idle one has no path, only when its state last moved — for a lazy
 * peer that is when it fell asleep. A peer still connecting has neither yet: its amber dot
 * says so.
 */
private sealed interface PeerRowPath {
    data object Direct : PeerRowPath
    data object Relayed : PeerRowPath
    /** [ago] is null when the daemon gave no stamp, or one this cannot read. */
    data class Since(val ago: String?) : PeerRowPath
    data object None : PeerRowPath
}

private fun peerRowPath(context: Context, peer: NbPeer, nowMillis: Long): PeerRowPath = when (peer.conn) {
    PeerConn.Connected -> if (peer.relayed) PeerRowPath.Relayed else PeerRowPath.Direct
    PeerConn.Connecting -> PeerRowPath.None
    PeerConn.Idle -> PeerRowPath.Since(parseRfc3339Millis(peer.connStatusUpdate)?.let { agoText(context, it, nowMillis) })
}

/**
 * One peer in the list. [nowMillis] is what "since" is measured against: the moment of the
 * last status, so a row does not age between two of them.
 */
@Composable
internal fun PeerItem(peer: NbPeer, nowMillis: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val path = remember(peer, nowMillis, context) { peerRowPath(context, peer, nowMillis) }
    PeerRow(
        name = peer.shortName,
        address = peer.address,
        icon = roleIcon(peer.networks, isSelf = false),
        conn = peer.conn,
        path = path,
        latency = peer.latencyMs?.takeIf { peer.connected }?.let(::latencyText),
        isSelf = false,
        onClick = onClick
    )
}

/** This device's own row, first in the list: the name and the IPv4 address only. */
@Composable
internal fun SelfPeerItem(local: NbLocalPeer, conn: PeerConn, onClick: () -> Unit) {
    PeerRow(
        name = local.shortName,
        address = local.address,
        icon = roleIcon(local.networks, isSelf = true),
        conn = conn,
        path = PeerRowPath.None,
        latency = null,
        isSelf = true,
        onClick = onClick
    )
}

/**
 * The row both kinds of list entry share. A long press copies the address — the one thing
 * a row is most often looked up for — without opening the sheet.
 */
@Composable
private fun PeerRow(
    name: String,
    address: String,
    icon: ImageVector,
    conn: PeerConn,
    path: PeerRowPath,
    latency: String?,
    isSelf: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The dot says the state in colour alone; this says it in words, with the path.
    val spokenState = listOfNotNull(
        when (conn) {
            PeerConn.Connected -> stringResource(R.string.peer_state_connected)
            PeerConn.Connecting -> stringResource(R.string.peer_state_connecting)
            PeerConn.Idle -> stringResource(if (isSelf) R.string.peer_state_down else R.string.peer_state_idle)
        },
        when (path) {
            PeerRowPath.Direct -> stringResource(R.string.nb_peer_direct)
            PeerRowPath.Relayed -> stringResource(R.string.nb_peer_relayed)
            is PeerRowPath.Since -> path.ago
            PeerRowPath.None -> null
        },
        latency
    ).joinToString(", ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { stateDescription = spokenState },
        shape = MaterialTheme.shapes.large,
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        // Inside the Surface, which clips to its shape: the ripple keeps to the corners.
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (address.isEmpty()) null else {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboard.copyText(scope, address)
                            Toast.makeText(context, context.getString(R.string.copied_to_clipboard, address), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tint = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = tint)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // Long names travel across the row rather than being cut off.
                Text(
                    name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                )
                if (address.isNotEmpty() && address != name) {
                    PeerAddressLine(address, path, latency)
                }
            }
            if (conn != PeerConn.Idle) {
                Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(conn.dotColor()))
            }
        }
    }
}

/**
 * The address, the path and the latency on one line. The latency sits outside the part
 * that is cut short, so a narrow row loses the path before it loses the figure.
 */
@Composable
private fun PeerAddressLine(address: String, path: PeerRowPath, latency: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (pathIcon, pathLabel) = when (path) {
        PeerRowPath.Direct -> Icons.Default.Lan to stringResource(R.string.nb_peer_direct)
        PeerRowPath.Relayed -> Icons.Default.Router to stringResource(R.string.nb_peer_relayed)
        is PeerRowPath.Since -> Icons.Default.Schedule to path.ago
        PeerRowPath.None -> null to null
    }
    val text = buildAnnotatedString {
        append(address)
        if (pathIcon != null && pathLabel != null) {
            // The icon is the separator, and it is decoration — the word after it says the
            // same — so a screen reader gets a blank in its place.
            append(" ")
            appendInlineContent(PEER_PATH_ICON, " ")
            // Words in the text face, not the address's monospace, which is wider.
            withStyle(SpanStyle(fontFamily = FontFamily.Default)) {
                append(" ")
                append(pathLabel)
            }
        }
    }
    val inline = if (pathIcon == null) emptyMap() else mapOf(
        PEER_PATH_ICON to InlineTextContent(Placeholder(14.sp, 12.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Icon(pathIcon, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
            }
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            inlineContent = inline,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = muted,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (latency != null) {
            Text(
                " · $latency",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private const val PEER_PATH_ICON = "peerPath"

// --- The details sheet ---

/** Every word the sheet's own composables show, resolved in the parent composition — see
 *  wrapContextWithLocale(): inside the sheet's window a stringResource follows the system
 *  locale, not the app's. The cards' words come from the parent's context, in [peerCards]. */
private class PeerSheetStrings(
    val prev: String,
    val next: String,
    val copy: String,
    /** The in-place acknowledgement a copied row shows for a moment. */
    val copied: String,
    val expanded: String,
    val collapsed: String,
    val connected: String,
    val connecting: String,
    val idle: String,
    /** This device's state while the daemon is neither connected nor connecting. */
    val down: String,
    val direct: String,
    val relayed: String,
    val exitNode: String,
    val routingPeer: String,
    val self: String,
    val connection: String,
    val latency: String,
    val notMeasured: String,
    val notConnected: String,
    val lazyIdle: String,
    val sshServer: String,
    val accessCheck: String
)

/** The parent composition's context, configuration and resources, for the one composable the
 *  sheet borrows that resolves its own strings (SessionRow). */
private class ParentLocale(val context: Context, val configuration: Configuration, val resources: Resources)

/** Which card a detail belongs on, in the order the cards are drawn. */
private enum class PeerCardGroup { ADDRESSES, PATH, TRAFFIC, NETWORKS, SECURITY, DEVICE, SESSION, DNS }

/** One quiet row of a card. [id] is what a copy acknowledgement is pinned to, so the tick
 *  lands on the row that was tapped and on no other. An empty [label] is a list entry. */
private class PeerDetail(
    val id: String,
    val label: String,
    val value: String,
    val copyable: Boolean = false,
    val mono: Boolean = false
)

/** A card's one action, drawn under its lead whether the card is open or not. */
private class PeerCardAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One card of the sheet: what it leads with, and the quieter rows it folds away. Built once
 * per page by [peerCards]; the composable only draws it, and during a page turn it is built
 * for two pages at once, so it does no work a draw does not need.
 */
private class PeerCard(
    val group: PeerCardGroup,
    val title: String,
    /** The value carried large. */
    val lead: String,
    /** The words under the lead saying what it is; empty when the lead says it all. */
    val leadCaption: String,
    val leadMono: Boolean = false,
    /** The row the lead was read off, when it can be copied: the card offers to copy it the
     *  way an open row offers to copy itself. */
    val leadRow: PeerDetail? = null,
    /** Everything else in the group, shown when the card is open. */
    val rows: List<PeerDetail> = emptyList(),
    val action: PeerCardAction? = null
)

/** A row, or nothing when the daemon left the value empty. */
private fun detail(id: String, label: String, value: String, copyable: Boolean = false, mono: Boolean = false): PeerDetail? =
    value.takeIf { it.isNotBlank() }?.let { PeerDetail(id, label, it, copyable, mono) }

/**
 * The cards of a page, with their words. [context] is the parent composition's: the sheet's
 * own would answer in the system language.
 */
private fun peerCards(context: Context, page: PeerPage, nowMillis: Long): List<PeerCard> {
    fun s(id: Int) = context.getString(id)
    fun ago(stamp: String?) = parseRfc3339Millis(stamp)?.let { agoText(context, it, nowMillis) }.orEmpty()
    val on = s(R.string.nb_on)
    val off = s(R.string.nb_off)

    fun addresses(address: String, fqdn: String, ipv6: String): PeerCard {
        val ipv4 = detail("ipv4", "IPv4", address, copyable = true, mono = true)
        return PeerCard(
            PeerCardGroup.ADDRESSES, s(R.string.peer_group_addresses),
            lead = address.ifEmpty { "—" }, leadCaption = "IPv4", leadMono = true, leadRow = ipv4,
            rows = listOfNotNull(
                detail("fqdn", s(R.string.nb_address_name), fqdn, copyable = true),
                detail("ipv6", "IPv6", ipv6, copyable = true, mono = true)
            )
        )
    }

    fun networks(list: List<String>): PeerCard? = when (list.size) {
        0 -> null
        1 -> {
            val only = PeerDetail("net:${list[0]}", "", list[0], copyable = true, mono = true)
            PeerCard(PeerCardGroup.NETWORKS, s(R.string.peer_group_networks), list[0], s(R.string.peer_networks_one), leadMono = true, leadRow = only)
        }
        else -> PeerCard(
            PeerCardGroup.NETWORKS, s(R.string.peer_group_networks),
            lead = context.resources.getQuantityString(R.plurals.peer_networks_count, list.size, list.size),
            leadCaption = s(R.string.peer_networks_many),
            rows = list.map { PeerDetail("net:$it", "", it, copyable = true, mono = true) }
        )
    }

    return when (page) {
        is PeerPage.Peer -> {
            val p = page.peer
            val localEp = detail("local_ep", s(R.string.peer_local_endpoint), p.localIceCandidateEndpoint, copyable = true, mono = true)
            val remoteEp = detail("remote_ep", s(R.string.peer_remote_endpoint), p.remoteIceCandidateEndpoint, copyable = true, mono = true)
            val relay = detail("relay", s(R.string.nb_peer_relay), p.relayAddress, copyable = true, mono = true)
            val ice = detail(
                "ice", s(R.string.nb_peer_ice),
                listOf(p.localIceCandidateType, p.remoteIceCandidateType).filter { it.isNotEmpty() }.joinToString(" → ")
            )
            val handshake = detail("handshake", s(R.string.nb_peer_handshake), ago(p.lastWireguardHandshake))
            val since = detail("since", s(R.string.peer_status_changed), ago(p.connStatusUpdate))
            // The path card leads with what carries the traffic: the peer's endpoint for a
            // direct connection, the relay for a relayed one. The row it was read off steps
            // aside; every other row stays, because it is what the daemon said.
            val pathTitle = s(R.string.peer_group_path)
            val path = when {
                p.connected && !p.relayed && remoteEp != null ->
                    PeerCard(PeerCardGroup.PATH, pathTitle, remoteEp.value, s(R.string.nb_peer_direct), true, remoteEp, listOfNotNull(ice, localEp, relay, handshake, since))
                p.connected && p.relayed && relay != null ->
                    PeerCard(PeerCardGroup.PATH, pathTitle, relay.value, s(R.string.nb_peer_relayed), true, relay, listOfNotNull(ice, localEp, remoteEp, handshake, since))
                p.connected ->
                    PeerCard(PeerCardGroup.PATH, pathTitle, s(if (p.relayed) R.string.nb_peer_relayed else R.string.nb_peer_direct), s(R.string.peer_state_connected), rows = listOfNotNull(ice, localEp, remoteEp, relay, handshake, since))
                // No path to lead with: then when the state last moved, which for a lazy peer
                // is when it fell asleep. The connection card above already says why.
                since != null -> PeerCard(PeerCardGroup.PATH, pathTitle, since.value, since.label, rows = listOfNotNull(ice, localEp, remoteEp, relay, handshake))
                else -> PeerCard(
                    PeerCardGroup.PATH, pathTitle, "—",
                    s(if (p.connecting) R.string.peer_state_connecting else R.string.peer_state_idle),
                    rows = listOfNotNull(ice, localEp, remoteEp, relay, handshake)
                )
            }
            val traffic = if (p.bytesRx > 0 || p.bytesTx > 0) PeerCard(
                PeerCardGroup.TRAFFIC, s(R.string.peer_group_traffic),
                lead = formatFileSize(p.bytesRx + p.bytesTx), leadCaption = s(R.string.peer_traffic_total),
                rows = listOf(
                    PeerDetail("rx", s(R.string.peer_traffic_rx), formatFileSize(p.bytesRx)),
                    PeerDetail("tx", s(R.string.peer_traffic_tx), formatFileSize(p.bytesTx))
                )
            ) else null
            val security = PeerCard(
                PeerCardGroup.SECURITY, s(R.string.peer_group_security),
                lead = if (p.rosenpassEnabled) on else off, leadCaption = s(R.string.peer_rosenpass_caption),
                rows = listOfNotNull(detail("pubkey", s(R.string.nb_peer_pubkey), p.pubKey, copyable = true, mono = true))
            )
            listOfNotNull(addresses(p.address, p.fqdn, p.ipv6Address), path, traffic, networks(p.networks), security)
        }
        is PeerPage.Self -> {
            val fs = page.status.fullStatus
            val me = fs.localPeerState
            val ssh = fs.sshServerState
            val device = PeerCard(
                PeerCardGroup.DEVICE, s(R.string.peer_self),
                lead = page.status.daemonVersion.ifEmpty { "—" }, leadCaption = s(R.string.nb_details_version), leadMono = true,
                leadRow = detail("version", s(R.string.nb_details_version), page.status.daemonVersion, copyable = true, mono = true),
                rows = listOfNotNull(
                    detail("pubkey", s(R.string.nb_peer_pubkey), me.pubKey, copyable = true, mono = true),
                    me.wgPort.takeIf { it > 0 }?.let { PeerDetail("wg_port", s(R.string.nb_details_wg_port), it.toString(), mono = true) },
                    PeerDetail("serves", s(R.string.nb_details_serves), me.networks.joinToString(", ").ifEmpty { s(R.string.nb_details_none) }, mono = me.networks.isNotEmpty()),
                    PeerDetail(
                        "rosenpass", "Rosenpass",
                        when {
                            !me.rosenpassEnabled -> off
                            me.rosenpassPermissive -> s(R.string.peer_rosenpass_permissive)
                            else -> on
                        }
                    ),
                    PeerDetail("lazy", s(R.string.nb_details_lazy), if (fs.lazyConnectionEnabled) on else off),
                    fs.forwardingRules.takeIf { it > 0 }?.let { PeerDetail("forwarding", s(R.string.nb_details_forwarding), it.toString()) },
                    PeerDetail("ssh", s(R.string.nb_details_ssh), if (ssh.enabled) on else off)
                ) + ssh.sessions.mapIndexed { i, session ->
                    PeerDetail(
                        "ssh:$i", s(R.string.peer_ssh_session),
                        "${session.jwtUsername.ifEmpty { session.username }}@${session.remoteAddress}  ${session.command}".trim(),
                        mono = true
                    )
                }
            )
            // Only for an expiry the sheet cannot hand to SessionRow: a session that has none.
            val session = PeerCard(PeerCardGroup.SESSION, s(R.string.nb_session_title), s(R.string.peer_session_never), leadCaption = "")
            val labels = page.dnsLabels.joinToString(", ")
            val dns = PeerCard(
                PeerCardGroup.DNS, s(R.string.peer_group_dns),
                lead = labels.ifEmpty { s(R.string.nb_details_none) }, leadCaption = s(R.string.peer_dns_labels), leadMono = labels.isNotEmpty(),
                leadRow = detail("labels", s(R.string.peer_dns_labels), labels, copyable = true),
                // Read-only here: the labels are a login setting, and Settings → DNS says what
                // changing them takes.
                action = PeerCardAction(s(R.string.peer_dns_labels_edit), Icons.Default.Settings) {
                    SettingsSections.open(context, SettingsSections.DNS)
                }
            )
            listOf(addresses(me.address, me.fqdn, me.ipv6Address), device, session, dns)
        }
    }
}

/** How a page sits while the sheet is turning: shifted by [dx], and — from half a page out
 *  — faded and shrunk a little, so the eye follows the page arriving at the centre rather
 *  than the one leaving. */
private fun GraphicsLayerScope.peerPageTransform(dx: Float) {
    translationX = dx
    val halfPage = size.width * 0.5f
    val progress = if (halfPage > 0f) (abs(dx) / halfPage).coerceIn(0f, 1f) else 0f
    alpha = 1f - 0.45f * progress
    scaleX = 1f - 0.05f * progress
    scaleY = scaleX
}

/**
 * The details sheet, turning between pages under the finger.
 *
 * [peerAt] is the page [offset] steps along the list from the one on screen: 0 is the page
 * being shown, -1 the one a swipe to the right brings in, +1 the one a swipe to the left
 * brings in, and null where the list ends. The sheet asks for at most two steps either way
 * — the two pages a turn has on screen at once, plus whether the arriving page should carry
 * its own arrows. It takes the pages rather than prev/next callbacks because the neighbour
 * has to be visible under the finger: a callback can only be fired once the gesture is over.
 *
 * [onSelectPage] turns to a page this sheet was handed; by the time it is called the turn
 * has already carried that page to the centre.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PeerDetailsModal(
    peerAt: (offset: Int) -> PeerPage?,
    /** This device's overlay address, the near end of a connection that has no ICE
     *  endpoints to show; empty before the daemon has one. */
    selfAddress: String,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onSelectPage: (PeerPage) -> Unit
) {
    val page = peerAt(0) ?: return
    val prevPage = peerAt(-1)
    val nextPage = peerAt(1)

    // The parent's, all of these: the sheet's own window answers in the system language.
    val context = LocalContext.current
    val locale = ParentLocale(context, LocalConfiguration.current, LocalResources.current)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    // Which cards are open. Not keyed on the page: both pages of a turn draw the same set,
    // so the arriving page lands with the cards the finger left open and nothing jumps.
    var expandedGroups by remember { mutableStateOf(emptySet<PeerCardGroup>()) }
    val onToggleGroup: (PeerCardGroup) -> Unit = { group ->
        expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group
    }
    // The row whose value was just copied, for as long as its tick shows. Keyed on the page:
    // a tick must not survive onto another peer's row of the same kind.
    var copiedDetail by remember(page.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedDetail) {
        if (copiedDetail != null) {
            delay(PEER_COPIED_ACK_MS)
            copiedDetail = null
        }
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    // Page-turn state. Both pages are on screen for the whole gesture — the current one at
    // swipeOffset, the arriving one a page width further along in the drag direction — so
    // the turn is one continuous motion and the swap at its end is a rename, not a movement.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { PEER_SWIPE_THRESHOLD.toPx() }
    val flingVelocityPx = with(density) { PEER_SWIPE_FLING_VELOCITY.toPx() }
    val maxOverscrollPx = with(density) { 56.dp.toPx() }
    /** Where the current page sits, in pixels from the centre. Read in the draw phase only
     *  (inside graphicsLayer), so a drag moves the pages without recomposing them. */
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    /** Which page is sliding in alongside the current one: -1 the previous, +1 the next, 0
     *  nothing. Read in composition — it decides whether the neighbour is composed at all —
     *  so it changes a couple of times per gesture rather than every frame. */
    var incomingStep by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // The system animator duration scale is the closest thing to a reduced-motion flag, and
    // 0 is how "remove animations" reaches an app.
    val animateSwipe = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.animation.ValueAnimator.areAnimatorsEnabled()
    } else {
        remember {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }
    }
    // Material's own spatial springs: the default one finishes a turn, the fast one snaps
    // back a drag that decided nothing. The gesture node below is keyed on nothing, so
    // everything it needs from a later composition reaches it through updated state.
    val turnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>().settlingInPixels()
    val returnSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.fastSpatialSpec<Float>().settlingInPixels()
    val currentTurnSpec by rememberUpdatedState(turnSpec)
    val currentReturnSpec by rememberUpdatedState(returnSpec)
    val currentPrev by rememberUpdatedState(prevPage)
    val currentNext by rememberUpdatedState(nextPage)
    val currentOnSelect by rememberUpdatedState(onSelectPage)
    // At the ends of the list the drag rubber-bands and springs back, so "there is no next
    // peer" is something you can feel.
    fun resistedOffset(travelled: Float): Float {
        val canMove = (travelled > 0f && currentPrev != null) || (travelled < 0f && currentNext != null)
        return if (canMove) travelled
        else (travelled * PEER_SWIPE_RESISTANCE).coerceIn(-maxOverscrollPx, maxOverscrollPx)
    }

    val strings = PeerSheetStrings(
        prev = stringResource(R.string.peer_details_prev),
        next = stringResource(R.string.peer_details_next),
        copy = stringResource(R.string.action_copy),
        copied = stringResource(R.string.peer_copied),
        expanded = stringResource(R.string.peer_cd_expanded),
        collapsed = stringResource(R.string.peer_cd_collapsed),
        connected = stringResource(R.string.peer_state_connected),
        connecting = stringResource(R.string.peer_state_connecting),
        idle = stringResource(R.string.peer_state_idle),
        down = stringResource(R.string.peer_state_down),
        direct = stringResource(R.string.nb_peer_direct),
        relayed = stringResource(R.string.nb_peer_relayed),
        exitNode = stringResource(R.string.nb_exit_node),
        routingPeer = stringResource(R.string.peer_role_routing),
        self = stringResource(R.string.peer_self),
        connection = stringResource(R.string.peer_group_connection),
        latency = stringResource(R.string.nb_peer_latency),
        notMeasured = stringResource(R.string.peer_conn_not_measured),
        notConnected = stringResource(R.string.peer_conn_not_connected),
        lazyIdle = stringResource(R.string.peer_conn_lazy),
        sshServer = stringResource(R.string.nb_details_ssh),
        accessCheck = stringResource(R.string.nb_trace_peer_action)
    )
    // The toast stays for the case where the copied row has scrolled off under the finger;
    // the tick on the row itself is the acknowledgement for everyone else.
    val onCopyDetail: (PeerDetail) -> Unit = { row ->
        clipboard.copyText(scope, row.value)
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, row.label.ifEmpty { row.value }), Toast.LENGTH_SHORT).show()
        copiedDetail = row.id
    }
    /** An arrow exists exactly when there is a page to turn to, and turns to that page. */
    fun turnTo(target: PeerPage?): (() -> Unit)? = target?.let { { onSelectPage(it) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                // The page leaving stops at the edge of the sheet, which on a tablet is
                // narrower than the window it sits in.
                .clipToBounds()
                // Keyed on nothing: a page change, or a refresh that flips whether there is
                // a page on either side, must not restart this node mid-drag.
                .pointerInput(Unit) {
                    fun pageWidth(): Float = size.width.toFloat().takeIf { it > 0f } ?: 1f
                    // The current page moving right uncovers the sheet's left half, which is
                    // where the previous page lives, and the other way round.
                    fun stepFor(offset: Float): Int = when {
                        offset > 0f -> -1
                        offset < 0f -> 1
                        else -> incomingStep
                    }
                    fun place(travelled: Float) {
                        val offset = resistedOffset(travelled).coerceIn(-pageWidth(), pageWidth())
                        swipeOffset = offset
                        val step = stepFor(offset)
                        if (step != incomingStep) incomingStep = step
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var overSlop = 0f
                        // Claimed only once the finger is clearly moving sideways; a diagonal
                        // drag is left to the sheet's own drag-to-dismiss.
                        val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            if (abs(over.x) > abs(over.y) * PEER_SWIPE_DIRECTION_RATIO) {
                                change.consume()
                                overSlop = over.x
                            }
                        }
                        if (drag != null) {
                            // A settle still in flight is abandoned where it stands: the
                            // finger picks the motion up from there.
                            settleJob?.cancel()
                            var travelled = swipeOffset + overSlop
                            val tracker = VelocityTracker()
                            tracker.addPosition(drag.uptimeMillis, drag.position)
                            place(travelled)
                            val finished = horizontalDrag(drag.id) { change ->
                                travelled = (travelled + change.positionChange().x)
                                    .coerceIn(-pageWidth(), pageWidth())
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                place(travelled)
                            }
                            // Distance or velocity: a fast flick that never covers the
                            // threshold turns the page, and so does a slow crawl past it.
                            val velocity = tracker.calculateVelocity().x
                            val direction = when {
                                abs(velocity) > flingVelocityPx -> if (velocity > 0f) 1 else -1
                                travelled > swipeThresholdPx -> 1
                                travelled < -swipeThresholdPx -> -1
                                else -> 0
                            }
                            val target = when {
                                !finished -> null
                                direction > 0 -> currentPrev
                                direction < 0 -> currentNext
                                else -> null
                            }
                            val exitTo = if (direction > 0) pageWidth() else -pageWidth()
                            // A rubber-banded page moved at a quarter of the finger's speed,
                            // so it must not be handed the whole of it on release.
                            val resisted = (swipeOffset > 0f && currentPrev == null) ||
                                (swipeOffset < 0f && currentNext == null)
                            val settleVelocity = if (resisted) velocity * PEER_SWIPE_RESISTANCE else velocity
                            // The neighbour follows the committed direction, not the last
                            // place(): a flick back the other way decides before crossing zero.
                            if (target != null) incomingStep = -direction
                            // Settled from the composition scope: this one dies with the
                            // gesture, and the settle outlives the finger.
                            settleJob = scope.launch {
                                if (target != null) {
                                    if (animateSwipe) {
                                        // The step is re-derived every frame: these springs are
                                        // underdamped and the page can swing back across zero.
                                        animate(swipeOffset, exitTo, settleVelocity, currentTurnSpec) { value, _ ->
                                            swipeOffset = value
                                            val step = stepFor(value)
                                            if (step != incomingStep) incomingStep = step
                                        }
                                    }
                                    // The arriving page is already centred: one snapshot makes
                                    // it the current one, so no frame shows the old page back.
                                    Snapshot.withMutableSnapshot {
                                        swipeOffset = 0f
                                        incomingStep = 0
                                        currentOnSelect(target)
                                    }
                                } else {
                                    if (animateSwipe) {
                                        animate(swipeOffset, 0f, settleVelocity, currentReturnSpec) { value, _ ->
                                            swipeOffset = value
                                            val step = stepFor(value)
                                            if (step != incomingStep) incomingStep = step
                                        }
                                    }
                                    swipeOffset = 0f
                                    incomingStep = 0
                                }
                            }
                        }
                    }
                }
        ) {
            // The neighbour, drawn a page width away in the drag direction and moving with
            // the current one. Measured against the sheet rather than with it, so more open
            // cards on the next page cannot resize the sheet under the finger.
            val step = incomingStep
            val incoming = if (step != 0) peerAt(step) else null
            if (incoming != null) {
                PeerDetailsPage(
                    page = incoming,
                    context = context,
                    locale = locale,
                    strings = strings,
                    selfAddress = selfAddress,
                    nowMillis = nowMillis,
                    expanded = expandedGroups,
                    // Nothing has been copied off a page nobody has landed on.
                    copied = null,
                    onToggleGroup = onToggleGroup,
                    // The arriving page's own neighbours, so its arrows are already right
                    // when it lands.
                    onPrev = turnTo(peerAt(step - 1)),
                    onNext = turnTo(peerAt(step + 1)),
                    onCopyDetail = onCopyDetail,
                    modifier = Modifier
                        .matchParentSize()
                        // There for the eye during the turn, not for a screen reader, which
                        // reaches the same page through the one that lands.
                        .clearAndSetSemantics { }
                        .graphicsLayer { peerPageTransform(swipeOffset + step * size.width) }
                )
            }
            PeerDetailsPage(
                page = page,
                context = context,
                locale = locale,
                strings = strings,
                selfAddress = selfAddress,
                nowMillis = nowMillis,
                expanded = expandedGroups,
                copied = copiedDetail,
                onToggleGroup = onToggleGroup,
                onPrev = turnTo(prevPage),
                onNext = turnTo(nextPage),
                onCopyDetail = onCopyDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { peerPageTransform(swipeOffset) }
            )
        }
    }
}

/**
 * One page's worth of sheet content. Two of these are alive during a page turn, so it owns
 * no state of its own and resolves no strings of its own: everything it shows is handed to
 * it by [PeerDetailsModal], which composes outside the sheet's window.
 */
@Composable
private fun PeerDetailsPage(
    page: PeerPage,
    context: Context,
    locale: ParentLocale,
    strings: PeerSheetStrings,
    selfAddress: String,
    nowMillis: Long,
    /** The cards currently open, shared by both pages of a turn. */
    expanded: Set<PeerCardGroup>,
    /** The row whose value was just put on the clipboard, while its acknowledgement shows. */
    copied: String?,
    onToggleGroup: (PeerCardGroup) -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onCopyDetail: (PeerDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    // A cache, not state: the cards only change with the page, and during a turn this runs
    // for two pages at once.
    val cards = remember(page, nowMillis, context) { peerCards(context, page, nowMillis) }
    val sessionExpiry = (page as? PeerPage.Self)?.status?.sessionExpiresAt?.takeIf { parseRfc3339Millis(it) != null }
    // Everything scrolls together, header included: the sheet is capped at 85% of the
    // screen, and in landscape a fixed header left the cards no room at all.
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        item(key = "identity") {
            PeerIdentityRow(page, strings, onPrev, onNext)
        }
        // The whole state, in words, outside the identity row: between the two arrows there
        // is not enough width for chips that are allowed to wrap.
        item(key = "status-strip") {
            PeerStatusStrip(page, strings, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (page is PeerPage.Peer) {
            item(key = "connection") {
                PeerConnectionCard(page, selfAddress, strings, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        // One item per card, keyed on the group: a card's reveal must not re-lay the others.
        items(cards, key = { it.group.name }) { card ->
            val cardModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            if (card.group == PeerCardGroup.SESSION && sessionExpiry != null) {
                // SessionRow resolves its own strings and has no way to be handed them, so it
                // gets the parent's locale back for itself; see wrapContextWithLocale().
                CompositionLocalProvider(
                    LocalContext provides locale.context,
                    LocalConfiguration provides locale.configuration,
                    LocalResources provides locale.resources
                ) {
                    Box(cardModifier) { SessionRow(sessionExpiry) }
                }
            } else {
                PeerInfoCard(
                    card = card,
                    strings = strings,
                    expanded = card.group in expanded,
                    copied = copied,
                    onToggle = { onToggleGroup(card.group) },
                    onCopyDetail = onCopyDetail,
                    modifier = cardModifier
                )
            }
        }
        // At the bottom: the one thing the sheet can do about a peer is ask the firewall.
        if (page is PeerPage.Peer && page.peer.address.isNotEmpty()) {
            item(key = "access-check") {
                FilledTonalButton(
                    onClick = { context.startActivity(TraceActivity.intent(context, page.peer.address)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 46.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Policy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.accessCheck, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The arrows either side of the name chip, and under the chip what the peer is to the
 *  network: an exit node, a routing peer, or this device. */
@Composable
private fun PeerIdentityRow(page: PeerPage, strings: PeerSheetStrings, onPrev: (() -> Unit)?, onNext: (() -> Unit)?) {
    val name: String
    val icon: ImageVector
    val conn: PeerConn
    val badge: String?
    when (page) {
        is PeerPage.Peer -> {
            name = page.peer.shortName
            icon = roleIcon(page.peer.networks, isSelf = false)
            conn = page.peer.conn
            badge = when {
                page.peer.isExitNode -> strings.exitNode
                page.peer.networks.isNotEmpty() -> strings.routingPeer
                else -> null
            }
        }
        is PeerPage.Self -> {
            val me = page.status.fullStatus.localPeerState
            name = me.shortName
            icon = roleIcon(me.networks, isSelf = true)
            conn = selfConnOf(page.status)
            badge = strings.self
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onPrev != null) {
            IconButton(onClick = onPrev) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings.prev)
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                // Weighted, or a long name pushes the status dot out of the chip.
                Text(
                    name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(conn.dotColor()))
            }
            if (badge != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        badge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (onNext != null) {
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings.next)
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/**
 * The page's state as words, in a row that wraps instead of clipping: connected or not, how
 * the traffic goes, and what protects it. All of it comes out of the status the sheet
 * already has.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerStatusStrip(page: PeerPage, strings: PeerSheetStrings, modifier: Modifier = Modifier) {
    val conn = when (page) {
        is PeerPage.Peer -> page.peer.conn
        is PeerPage.Self -> selfConnOf(page.status)
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PeerStatusChip(
            label = when (conn) {
                PeerConn.Connected -> strings.connected
                PeerConn.Connecting -> strings.connecting
                PeerConn.Idle -> if (page is PeerPage.Self) strings.down else strings.idle
            },
            icon = Icons.Default.Circle,
            iconTint = conn.dotColor(),
            // The same dot as in the identity chip and the list row, at the same size.
            iconSize = 10.dp
        )
        when (page) {
            is PeerPage.Peer -> {
                if (page.peer.connected) {
                    PeerStatusChip(
                        label = if (page.peer.relayed) strings.relayed else strings.direct,
                        icon = if (page.peer.relayed) Icons.Default.Router else Icons.Default.Lan,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }
                if (page.peer.rosenpassEnabled) {
                    PeerStatusChip("Rosenpass", Icons.Default.Shield, MaterialTheme.colorScheme.primary)
                }
            }
            is PeerPage.Self -> {
                val fs = page.status.fullStatus
                if (fs.localPeerState.rosenpassEnabled) {
                    PeerStatusChip("Rosenpass", Icons.Default.Shield, MaterialTheme.colorScheme.primary)
                }
                if (fs.sshServerState.enabled) {
                    PeerStatusChip(strings.sshServer, Icons.Default.Terminal, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * One chip of the status strip. It states something instead of doing something, so it is
 * not a chip: an AssistChip with an empty onClick is still a button to a screen reader.
 * This is the chip's own shape, height, border and label style on a plain Surface.
 */
@Composable
private fun PeerStatusChip(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    iconSize: Dp = AssistChipDefaults.IconSize
) {
    Surface(
        shape = AssistChipDefaults.shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clearAndSetSemantics { contentDescription = label }
    ) {
        Row(
            // defaultMinSize: at a large font scale the pill grows with the label.
            modifier = Modifier.defaultMinSize(minHeight = AssistChipDefaults.Height).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AssistChipDefaults.HorizontalSpacing)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How far a card shrinks under the finger. */
private const val PEER_CARD_PRESSED_SCALE = 0.97f
/** The side of the shaped box a card's icon sits in. */
private val PEER_CARD_ICON_BOX = 40.dp

/**
 * The card shrinking a little under the finger on the scheme's fast spatial spring, over and
 * above the ripple. The scale is read in the draw phase, so a press does not recompose.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PEER_CARD_PRESSED_SCALE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "peerCardPress"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The one card surface of the sheet: tonal, rounded, and — when it has something to do —
 * alive under the finger. A card with no [onClick] is a plain Card, not a disabled clickable
 * one, which a screen reader would call "disabled".
 */
@Composable
private fun PeerSheetCard(
    onClick: (() -> Unit)?,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColorFor(containerColor))
    if (onClick == null) {
        Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, content = content)
    } else {
        val interaction = remember { MutableInteractionSource() }
        Card(
            onClick = onClick,
            interactionSource = interaction,
            modifier = modifier.pressScale(interaction),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            content = content
        )
    }
}

/** A card's icon in a Material shape: the expressive idiom for "this is what the card is
 *  about". The shape is the card's own, so the eye tells the cards apart before reading. */
@Composable
private fun PeerCardIcon(
    icon: ImageVector,
    shape: Shape,
    containerColor: Color,
    tint: Color,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier.size(PEER_CARD_ICON_BOX).clip(shape).background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) content()
        else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/** Which Material shape frames each card's icon. Distinct per card on purpose. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerCardGroup.iconShape(): Shape = when (this) {
    PeerCardGroup.ADDRESSES -> MaterialShapes.Cookie4Sided
    PeerCardGroup.PATH -> MaterialShapes.Arrow
    PeerCardGroup.TRAFFIC -> MaterialShapes.Gem
    PeerCardGroup.NETWORKS -> MaterialShapes.Clover4Leaf
    PeerCardGroup.SECURITY -> MaterialShapes.Pentagon
    PeerCardGroup.DEVICE -> MaterialShapes.Square
    PeerCardGroup.SESSION -> MaterialShapes.Circle
    PeerCardGroup.DNS -> MaterialShapes.Sunny
}.toShape()

/** The icon that names each card. */
private fun PeerCardGroup.icon(): ImageVector = when (this) {
    PeerCardGroup.ADDRESSES -> Icons.Default.Lan
    PeerCardGroup.PATH -> Icons.AutoMirrored.Filled.AltRoute
    PeerCardGroup.TRAFFIC -> Icons.Default.SwapVert
    PeerCardGroup.NETWORKS -> Icons.Default.Hub
    PeerCardGroup.SECURITY -> Icons.Default.Shield
    PeerCardGroup.DEVICE -> Icons.Default.Smartphone
    PeerCardGroup.SESSION -> Icons.Default.Timer
    PeerCardGroup.DNS -> Icons.Default.Dns
}

/**
 * The connection as the daemon measured it: the latency as the figure, and under it the two
 * ends it runs between — this device's ICE endpoint to the peer's while connected directly,
 * the two overlay addresses otherwise — because a bare "24 ms" says nothing about what was 24 ms
 * away. NetBird measures by itself, so the card is a statement, not a button; while the
 * peer is connecting the loading indicator runs where its icon was.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerConnectionCard(page: PeerPage.Peer, selfAddress: String, strings: PeerSheetStrings, modifier: Modifier = Modifier) {
    val peer = page.peer
    val latency = peer.latencyMs?.takeIf { peer.connected }?.let(::latencyText)
    val caption = when {
        latency != null -> strings.latency
        peer.connected -> strings.notMeasured
        peer.connecting -> strings.connecting
        page.lazy -> strings.lazyIdle
        else -> strings.notConnected
    }
    val ends = if (peer.connected && peer.localIceCandidateEndpoint.isNotEmpty() && peer.remoteIceCandidateEndpoint.isNotEmpty()) {
        peer.localIceCandidateEndpoint to peer.remoteIceCandidateEndpoint
    } else selfAddress.ifEmpty { null } to peer.address
    PeerSheetCard(
        onClick = null,
        // A measured latency is the one number in the sheet worth looking at, so it gets the
        // accent container; before that the card is quieter, but a step above the others.
        containerColor = if (latency != null) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) {}
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        strings.connection,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        latency ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(caption, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
                }
                Spacer(Modifier.width(12.dp))
                PeerCardIcon(
                    icon = Icons.Default.NetworkPing,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    content = if (!peer.connecting) null else {
                        {
                            // Handed the box's own content colour: LoadingIndicator defaults to
                            // the scheme's primary, which is the colour of the box it sits in.
                            LoadingIndicator(modifier = Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                )
            }
            if (ends.second.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val near = ends.first
                    if (near != null) {
                        Text(
                            near,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowRightAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        ends.second,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

/**
 * One card of details: an icon in a shape, the heading, the lead carried large with its
 * caption under it, and the quiet rows folded away. A tap opens and closes the fold on the
 * scheme's spatial spring, the chevron turning to say which way it will go. A card with
 * nothing to fold is a statement and does not react.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerInfoCard(
    card: PeerCard,
    strings: PeerSheetStrings,
    expanded: Boolean,
    copied: String?,
    onToggle: () -> Unit,
    onCopyDetail: (PeerDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    val foldable = card.rows.isNotEmpty()
    val open = foldable && expanded
    val chevron by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "peerCardChevron"
    )
    val tint = MaterialTheme.colorScheme.primary
    val stateWords = if (open) strings.expanded else strings.collapsed
    val copyableLead = card.leadRow?.takeIf { it.copyable }
    PeerSheetCard(
        onClick = if (foldable) onToggle else null,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics { if (foldable) stateDescription = stateWords }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeerCardIcon(icon = card.group.icon(), shape = card.group.iconShape(), containerColor = tint.copy(alpha = 0.16f), tint = tint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Two lines: an IPv6 endpoint or a key does not fit one line of titleLarge
                    // beside the icon and the chevron on a narrow phone.
                    Text(
                        card.lead,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (card.leadMono) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (card.leadMono) 18.sp else MaterialTheme.typography.titleLarge.fontSize,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (card.leadCaption.isNotEmpty()) {
                        Text(card.leadCaption, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
                    }
                }
                if (copyableLead != null) {
                    Spacer(Modifier.width(4.dp))
                    PeerLeadCopyButton(copied = copied == copyableLead.id, strings = strings, onCopy = { onCopyDetail(copyableLead) })
                }
                if (foldable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = chevron },
                        tint = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            }
            card.action?.let { action ->
                TextButton(onClick = action.onClick, modifier = Modifier.padding(start = PEER_CARD_ICON_BOX + 2.dp, top = 4.dp)) {
                    Icon(action.icon, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(action.label)
                }
            }
            if (foldable) {
                // Composed only while open or animating, so a closed card is as cheap as its
                // header, and two pages' worth of closed cards are cheap during a turn.
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec())
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.08f))
                        Spacer(Modifier.height(4.dp))
                        card.rows.forEach { row ->
                            PeerDetailRow(row = row, copied = copied == row.id, strings = strings, onCopy = onCopyDetail)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The copy button beside a card's lead. Its own button rather than a long press on the
 * card: the card's tap is the fold. After a tap the icon is a tick for a moment.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerLeadCopyButton(copied: Boolean, strings: PeerSheetStrings, onCopy: () -> Unit) {
    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
        Crossfade(targetState = copied, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "peerLeadCopyAck") { done ->
            if (done) {
                Icon(Icons.Default.Check, contentDescription = strings.copied, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ContentCopy, contentDescription = strings.copy, modifier = Modifier.size(18.dp), tint = LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * One quiet row of an open card: the label small over the value. A row worth copying copies
 * on a tap, and for a moment its icon becomes a tick with "copied" beside it — the
 * acknowledgement lands where the finger was, not only in a toast.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeerDetailRow(row: PeerDetail, copied: Boolean, strings: PeerSheetStrings, onCopy: (PeerDetail) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (row.copyable) Modifier.clickable(onClickLabel = strings.copy) { onCopy(row) } else Modifier)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (row.label.isNotEmpty()) {
                Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                row.value,
                fontFamily = if (row.mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (row.mono) 13.sp else 14.sp,
                fontWeight = if (row.mono) FontWeight.Normal else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = if (row.label.isNotEmpty()) 2.dp else 0.dp)
            )
        }
        if (row.copyable) {
            Spacer(Modifier.width(8.dp))
            Crossfade(targetState = copied, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "peerCopyAck") { done ->
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(strings.copied, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
