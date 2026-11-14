package io.github.bropines.birdsocks.core

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: starts and stops the service.
 *
 * Control centres differ between OEM skins (stock, MIUI/HyperOS, One UI), so the
 * tile keeps to what all of them honour: state, label and icon are set on every
 * render — HyperOS drops the manifest icon after a theme change — and the tile
 * follows [NetbirdState] while the panel is open. A click shows the state it asked
 * for at once and keeps it until the service catches up, so a slow start or stop
 * does not flip the tile back. The VPN follows the service; a tile may open an
 * activity, so a missing VPN permission is asked for here rather than left to a
 * notification.
 */
class ProxyTileService : TileService() {
    private companion object {
        const val TAG = "ProxyTileService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    /** What the last click asked for, shown until the daemon's state agrees with it. */
    private var pending: Boolean? = null
    private var last: Triple<NetbirdState.Daemon, String?, Boolean>? = null

    override fun onTileAdded() {
        super.onTileAdded()
        render(NetbirdState.daemon.value, NetbirdState.status.value?.state?.name, TunVpnService.running.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch {
            combine(NetbirdState.daemon, NetbirdState.status, TunVpnService.running) { d, s, vpn -> Triple(d, s?.state?.name, vpn) }
                .collect { (d, s, vpn) -> render(d, s, vpn) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        pending = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // A secure keyguard: the panel cannot start a foreground service until it is unlocked.
        if (isLocked && isSecure) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        val start = !isActive(NetbirdState.daemon.value)
        pending = start
        rerender()
        try {
            if (start) {
                NetbirdService.start(this)
                if (GlobalSettings.isTunModeEnabled(this) && android.net.VpnService.prepare(this) != null) {
                    open(Intent(this, TunPermissionActivity::class.java))
                }
            } else NetbirdService.stop(this)
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground-service start from here; the app can always start it.
            Log.w(TAG, "start refused: ${e.message}")
            pending = null
            rerender()
            open(Intent(this, MainActivity::class.java))
        }
    }

    /** Opens [target] and collapses the panel, across the API levels that changed how. */
    private fun open(target: Intent) {
        val intent = target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot open ${target.component?.shortClassName}: ${e.message}")
        }
    }

    /** Starting and running count as on; stopping already counts as off. */
    private fun isActive(daemon: NetbirdState.Daemon) =
        daemon == NetbirdState.Daemon.Starting || daemon == NetbirdState.Daemon.Running

    private fun rerender() {
        val (d, s, vpn) = last ?: Triple(NetbirdState.daemon.value, NetbirdState.status.value?.state?.name, TunVpnService.running.value)
        render(d, s, vpn)
    }

    private fun render(daemon: NetbirdState.Daemon, state: String?, vpn: Boolean) {
        last = Triple(daemon, state, vpn)
        val actual = isActive(daemon)
        // Drop the click's state once reality has caught up with it.
        if (pending == actual) pending = null
        val asked = pending
        val active = asked ?: actual

        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (e: Exception) {
            Log.w(TAG, "cannot set the icon: ${e.message}")
        }
        val subtitle = when {
            asked == true -> getString(R.string.main_status_starting)
            asked == false || daemon == NetbirdState.Daemon.Stopping -> getString(R.string.nb_status_stopping)
            daemon == NetbirdState.Daemon.Stopped -> null
            daemon != NetbirdState.Daemon.Running -> getString(R.string.main_status_starting)
            state == "Connected" -> getString(
                when {
                    !vpn -> R.string.main_status_active
                    GlobalSettings.isTunRoot(this) -> R.string.nb_tun_tile_active_root
                    else -> R.string.nb_tun_tile_active
                }
            )
            state == "NeedsLogin" || state == "LoginFailed" || state == "SessionExpired" -> getString(R.string.main_status_needs_login)
            else -> getString(R.string.main_status_connecting)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        // TalkBack reads the state, not the subtitle, on Android 11+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }
}
