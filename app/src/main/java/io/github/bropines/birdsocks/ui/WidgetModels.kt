package io.github.bropines.birdsocks.ui

import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbPeer
import kotlinx.serialization.Serializable

// What the widgets draw, decided outside their composition. Each widget renders one model,
// stored as a string in its Glance state: every word already in the app's language, every
// decision taken. The serialized model is also the fingerprint: a refresh repaints an
// instance only when the string changed. The functions here are pure, for the unit tests.

/** Bumped whenever a model's shape changes: a model stored by another build is then rebuilt, not misread. */
internal const val WIDGET_SCHEMA = 1

/** How a status reads at a glance: the colour of its dot and of the round toggle. */
enum class WidgetTone { ON, OFF, BUSY, WARN }

/** What the switch says about the connection; the words are those of the tile and the main card. */
enum class WidgetStatus(val tone: WidgetTone) {
    STOPPED(WidgetTone.OFF),
    STARTING(WidgetTone.BUSY),
    STOPPING(WidgetTone.BUSY),
    CONNECTING(WidgetTone.BUSY),
    CONNECTED(WidgetTone.ON),
    NEEDS_LOGIN(WidgetTone.WARN),
}

/** The status line from the daemon's state and, while it runs, the connection's (NbConnState by name). */
internal fun widgetStatus(daemon: NetbirdState.Daemon, state: String?): WidgetStatus = when (daemon) {
    NetbirdState.Daemon.Stopped -> WidgetStatus.STOPPED
    NetbirdState.Daemon.Starting -> WidgetStatus.STARTING
    NetbirdState.Daemon.Stopping -> WidgetStatus.STOPPING
    NetbirdState.Daemon.Running -> when (state) {
        "Connected" -> WidgetStatus.CONNECTED
        "NeedsLogin", "LoginFailed", "SessionExpired" -> WidgetStatus.NEEDS_LOGIN
        else -> WidgetStatus.CONNECTING
    }
}

/** One exit node on the widget. [via] is the routing peer's short name; [online] null when no peer is known. */
@Serializable
data class ExitRow(val id: String, val via: String = "", val selected: Boolean = false, val online: Boolean? = null)

/** How many rows a model keeps; the widget scrolls through them. */
internal const val EXIT_ROWS_MAX = 30

/**
 * The exit-node rows: selected first, then those whose routing peer is connected, then by
 * name; at most [limit]. Only exit nodes (0.0.0.0/0 or ::/0) are listed.
 */
internal fun exitRows(networks: List<NbNetwork>, peers: List<NbPeer>, limit: Int = EXIT_ROWS_MAX): List<ExitRow> =
    networks.filter { it.isExitNode && it.id.isNotEmpty() }
        .distinctBy { it.id }
        .map { n ->
            val peer = peers.firstOrNull { n.routedBy(it) }
            ExitRow(n.id, peer?.shortName.orEmpty(), n.selected, peer?.connected)
        }
        .sortedWith(
            compareByDescending<ExitRow> { it.selected }
                .thenByDescending { it.online == true }
                .thenBy { it.id.lowercase() }
        )
        .take(limit)

/** The switch, everything it draws. */
@Serializable
data class ServiceWidgetModel(
    val schema: Int = WIDGET_SCHEMA,
    /** Whether the toggle shows the service as on: starting and running count. */
    val on: Boolean = false,
    val status: WidgetStatus = WidgetStatus.STOPPED,
    val statusText: String = "",
    /** This device's NetBird name, or the account, when known; otherwise "". */
    val name: String = "",
    /** "Exit: <name>" while an exit node is selected, otherwise "". */
    val exitLabel: String = "",
    /** This device's NetBird IPv4 while connected, otherwise "". */
    val address: String = "",
    val toggleLabel: String = "",
    val refreshLabel: String = "",
    val copyLabel: String = "",
    val locale: String = "",
)

/** The exit-node widget, everything it draws. */
@Serializable
data class ExitWidgetModel(
    val schema: Int = WIDGET_SCHEMA,
    val title: String = "",
    /** "Exit: <name>" or "Not active". */
    val subtitle: String = "",
    val active: Boolean = false,
    /** Whether the daemon runs: without it nothing can be chosen, and [emptyText] says so. */
    val running: Boolean = false,
    val directLabel: String = "",
    val emptyText: String = "",
    val refreshLabel: String = "",
    /** The words after the routing peer of an exit node whose peer is not connected. */
    val offlineLabel: String = "",
    val rows: List<ExitRow> = emptyList(),
    val locale: String = "",
)

internal fun encodeModel(model: ServiceWidgetModel): String = AppJson.encodeToString(ServiceWidgetModel.serializer(), model)
internal fun encodeModel(model: ExitWidgetModel): String = AppJson.encodeToString(ExitWidgetModel.serializer(), model)

/** The stored switch model, or null when there is none or another build stored it. */
internal fun decodeServiceModel(encoded: String?): ServiceWidgetModel? =
    encoded?.let { runCatching { AppJson.decodeFromString(ServiceWidgetModel.serializer(), it) }.getOrNull() }
        ?.takeIf { it.schema == WIDGET_SCHEMA }

/** The stored exit-node model, or null when there is none or another build stored it. */
internal fun decodeExitModel(encoded: String?): ExitWidgetModel? =
    encoded?.let { runCatching { AppJson.decodeFromString(ExitWidgetModel.serializer(), it) }.getOrNull() }
        ?.takeIf { it.schema == WIDGET_SCHEMA }
