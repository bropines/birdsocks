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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: starts and stops the service. State and label are set
 * on every render — OEM control centres (MIUI/HyperOS, One UI) do not keep
 * them — and follow [NetbirdState] while the panel is open. The VPN follows
 * the service; a tile may open an activity, so a missing VPN permission is
 * asked for here rather than left to a notification.
 */
class ProxyTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch {
            combine(NetbirdState.daemon, NetbirdState.status, TunVpnService.running) { d, s, vpn -> Triple(d, s, vpn) }
                .collect { (d, s, vpn) -> render(d, s?.state?.name, vpn) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // A secure keyguard: the panel cannot start a foreground service until it is unlocked.
        if (isLocked && isSecure) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        try {
            if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) {
                NetbirdService.start(this)
                if (GlobalSettings.isTunModeEnabled(this) && android.net.VpnService.prepare(this) != null) {
                    open(Intent(this, TunPermissionActivity::class.java))
                }
            } else NetbirdService.stop(this)
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground-service start from here; the app can always start it.
            Log.w("ProxyTileService", "start refused: ${e.message}")
            openApp()
        }
    }

    private fun openApp() = open(Intent(this, MainActivity::class.java))

    private fun open(target: Intent) {
        val intent = target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(daemon: NetbirdState.Daemon, state: String?, vpn: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (daemon == NetbirdState.Daemon.Stopped) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                daemon == NetbirdState.Daemon.Stopped -> null
                daemon != NetbirdState.Daemon.Running -> getString(R.string.main_status_starting)
                state == "Connected" -> getString(if (vpn) R.string.nb_tun_tile_active else R.string.main_status_active)
                state == "NeedsLogin" || state == "LoginFailed" || state == "SessionExpired" -> getString(R.string.main_status_needs_login)
                else -> getString(R.string.main_status_connecting)
            }
        }
        tile.updateTile()
    }
}
