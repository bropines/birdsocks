package io.github.bropines.birdsocks.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.TunVpnService
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the home-screen widgets in step with the app — the only code that writes their state.
 *
 * A pass builds each widget's model once (WidgetModels.kt) and repaints an instance only when
 * its serialized model differs from what it shows: Glance itself republishes identical output
 * on every update, so this comparison is what makes following the status stream cheap. Passes
 * are serialized, and requests that arrive while one is queued fold into it.
 *
 * Triggers: [NetbirdState] (the daemon, the connection, the profile), the VPN, the app's
 * language, an exit-node choice, the refresh button, and the system's own update (placement,
 * the 30-minute period, a locale change). The daemon lives in this process, so when the
 * process is gone the service is too, and a widget drawn from a fresh process is right.
 */
object WidgetUpdates {
    private const val TAG = "WidgetUpdates"

    /** The exit-node list is read from the daemon at most this often, unless asked for. */
    private const val NETWORKS_MS = 60_000L

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()
    private val queued = AtomicBoolean(false)
    private val forceQueued = AtomicBoolean(false)

    // Guarded by [lock]. Empty after a process start, so the first pass repaints every instance.
    private val painted = HashMap<Int, String>()
    private var networks: List<NbNetwork> = emptyList()
    private var networksAt = 0L
    /** The daemon and connection state the network list was read in: a change reads it again. */
    private var networksKey: Pair<NetbirdState.Daemon, String?>? = null

    private var localeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Follows the app's state for the process's lifetime; called once from the Application. */
    @OptIn(FlowPreview::class)
    fun watch(context: Context) {
        val app = context.applicationContext
        scope.launch {
            combine(NetbirdState.daemon, NetbirdState.status, NetbirdState.profile, TunVpnService.running) { d, s, p, vpn ->
                listOf(d, s?.state?.name, p, vpn, s?.fullStatus?.localPeerState?.address, s?.fullStatus?.peers?.count { it.connected })
            }
                .distinctUntilChanged()
                .debounce(300)
                .collect { request(app) }
        }
        // The app's own language, picked in Settings: every word on a widget is resolved in it.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "app_locale") request(app, force = true)
        }
        localeListener = listener
        app.getSharedPreferences("global_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
    }

    /** Asks for a pass soon; a pass already waiting absorbs this one. [force] repaints every instance. */
    fun request(context: Context, force: Boolean = false, networks: Boolean = false) {
        if (networks) networksAt = 0L
        if (force) forceQueued.set(true)
        if (!queued.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    /** Runs a pass now, after any pass in progress; [rereadNetworks] asks the daemon for the exit nodes. */
    suspend fun refresh(context: Context, force: Boolean = false, rereadNetworks: Boolean = false) {
        lock.withLock {
            queued.set(false)
            val forced = forceQueued.getAndSet(false) || force
            if (rereadNetworks) networksAt = 0L
            try {
                pass(context.applicationContext, forced)
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed: ${e.message}")
            }
        }
    }

    /** The system's update for [receiver]: a forced repaint, with the broadcast held open until it is done. */
    internal fun repaintFromSystem(receiver: BroadcastReceiver, context: Context) {
        val result = receiver.goAsync()
        val app = context.applicationContext
        scope.launch {
            try { refresh(app, force = true) } finally { result.finish() }
        }
    }

    private suspend fun pass(context: Context, force: Boolean) {
        val manager = GlanceAppWidgetManager(context)
        val serviceIds = manager.getGlanceIds(ServiceToggleWidget::class.java)
        val exitIds = manager.getGlanceIds(ExitNodeWidget::class.java)
        if (serviceIds.isEmpty() && exitIds.isEmpty()) return

        val res = wrapContextWithLocale(context)
        val locale = res.resources.configuration.locales[0].toLanguageTag()
        val daemon = NetbirdState.daemon.value
        val status = NetbirdState.status.value?.takeIf { daemon == NetbirdState.Daemon.Running }
        val state = status?.state?.name
        val running = daemon == NetbirdState.Daemon.Running

        // The daemon's network list, for the exit nodes: read when due, gone with the daemon.
        val now = System.currentTimeMillis()
        val key = daemon to state
        if (!running) {
            networks = emptyList()
            networksKey = null
        } else if (key != networksKey || now - networksAt >= NETWORKS_MS) {
            runCatching { Netbird.networks() }.onSuccess {
                networks = it
                networksAt = now
                networksKey = key
            }.onFailure { Log.w(TAG, "networks: ${it.message}") }
        }
        val rows = exitRows(networks, status?.fullStatus?.peers.orEmpty())
        val selected = rows.firstOrNull { it.selected }
        val exitLabel = selected?.let { res.getString(R.string.widget_exit_format, it.id) }.orEmpty()

        if (serviceIds.isNotEmpty()) {
            val widgetStatus = widgetStatus(daemon, state)
            val on = daemon == NetbirdState.Daemon.Starting || daemon == NetbirdState.Daemon.Running
            val local = status?.fullStatus?.localPeerState
            val profile = NetbirdState.profile.value?.takeIf { it != "default" }.orEmpty()
            val model = ServiceWidgetModel(
                on = on,
                status = widgetStatus,
                statusText = res.getString(statusRes(widgetStatus, TunVpnService.running.value, context)),
                name = local?.fqdn?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: profile,
                exitLabel = exitLabel,
                address = if (widgetStatus == WidgetStatus.CONNECTED) local?.address.orEmpty() else "",
                toggleLabel = res.getString(if (on) R.string.main_status_action_stop else R.string.main_status_action_start),
                refreshLabel = res.getString(R.string.widget_refresh),
                copyLabel = res.getString(R.string.widget_copy_ip),
                locale = locale,
            )
            val encoded = encodeModel(model)
            val widget = ServiceToggleWidget()
            serviceIds.forEach { publish(context, manager, widget, it, encoded, force) }
        }

        if (exitIds.isNotEmpty()) {
            val model = ExitWidgetModel(
                title = res.getString(R.string.nb_exit_node),
                subtitle = exitLabel.ifEmpty { res.getString(R.string.widget_exit_inactive) },
                active = selected != null,
                running = running,
                directLabel = res.getString(R.string.widget_exit_direct),
                emptyText = res.getString(if (running) R.string.widget_exit_none else R.string.widget_exit_stopped),
                refreshLabel = res.getString(R.string.widget_refresh),
                offlineLabel = res.getString(R.string.widget_offline),
                rows = rows,
                locale = locale,
            )
            val encoded = encodeModel(model)
            val widget = ExitNodeWidget()
            exitIds.forEach { publish(context, manager, widget, it, encoded, force) }
        }
    }

    /**
     * Compare-and-set: writes [encoded] and repaints only when the instance shows something
     * else — or when [force]d, or when this process has not painted it yet.
     */
    private suspend fun publish(
        context: Context,
        manager: GlanceAppWidgetManager,
        widget: GlanceAppWidget,
        id: GlanceId,
        encoded: String,
        force: Boolean,
    ) {
        val key = manager.getAppWidgetId(id)
        val shown = painted[key]
        if (!force && shown == encoded) return
        var repaint = force || shown == null
        updateAppWidgetState(context, WidgetStateDef, id) { prefs: Preferences ->
            // Handing back the same preferences writes nothing.
            if (prefs[WidgetStateKeys.MODEL] == encoded) prefs
            else {
                repaint = true
                prefs.toMutablePreferences().apply { this[WidgetStateKeys.MODEL] = encoded }
            }
        }
        if (repaint) widget.update(context, id)
        painted[key] = encoded
    }

    private fun statusRes(status: WidgetStatus, vpn: Boolean, context: Context): Int = when (status) {
        WidgetStatus.STOPPED -> R.string.main_status_state_off
        WidgetStatus.STARTING -> R.string.main_status_starting
        WidgetStatus.STOPPING -> R.string.nb_status_stopping
        WidgetStatus.CONNECTING -> R.string.main_status_connecting
        WidgetStatus.NEEDS_LOGIN -> R.string.main_status_needs_login
        WidgetStatus.CONNECTED -> when {
            !vpn -> R.string.main_status_active
            io.github.bropines.birdsocks.core.GlobalSettings.isTunRoot(context) -> R.string.nb_tun_tile_active_root
            else -> R.string.nb_tun_tile_active
        }
    }
}
