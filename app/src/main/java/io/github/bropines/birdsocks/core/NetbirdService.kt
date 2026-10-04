package io.github.bropines.birdsocks.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import appctr.Appctr
import appctr.DaemonListener
import appctr.StartOptions
import appctr.Subscription
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The foreground service that keeps the NetBird daemon alive: it starts
 * libnetbird.so (appctr/core.go), follows its status stream into
 * [NetbirdState], keeps the notification current, and tells the daemon which
 * DNS servers the network has. The daemon connects by itself once it has a
 * profile it is logged into; signing in is [LoginFlow]'s.
 */
class NetbirdService : Service() {
    companion object {
        private const val TAG = "NetbirdService"
        const val ACTION_START = "START_ACTION"
        const val ACTION_STOP = "STOP_ACTION"
        const val ACTION_RESTART = "RESTART_ACTION"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "status"
        private const val SESSION_CHANNEL_ID = "session"
        private const val SESSION_NOTIF_ID = 2

        fun start(context: Context) {
            GlobalSettings.setWasRunning(context, true)
            NetbirdState.errorFlow.value = null
            if (NetbirdState.daemonFlow.value == NetbirdState.Daemon.Stopped) {
                NetbirdState.daemonFlow.value = NetbirdState.Daemon.Starting
            }
            ContextCompat.startForegroundService(context, Intent(context, NetbirdService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            GlobalSettings.setWasRunning(context, false)
            if (NetbirdState.daemonFlow.value == NetbirdState.Daemon.Stopped) return
            context.startService(Intent(context, NetbirdService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Restarts the daemon inside the running service, for settings it reads at
         * start. A stop and a start from outside raced: the new daemon could start
         * in the service instance that was being destroyed, and its onDestroy killed it.
         */
        fun restart(context: Context) {
            if (NetbirdState.daemonFlow.value == NetbirdState.Daemon.Stopped) return start(context)
            ContextCompat.startForegroundService(context, Intent(context, NetbirdService::class.java).setAction(ACTION_RESTART))
        }

        /** Starts the service unless it runs, and waits up to 20 s for the daemon to answer. */
        suspend fun awaitRunning(context: Context): Boolean {
            if (NetbirdState.isRunning) return true
            if (NetbirdState.daemonFlow.value != NetbirdState.Daemon.Starting) start(context)
            return withTimeoutOrNull(20_000) {
                NetbirdState.daemon.first { it == NetbirdState.Daemon.Running || it == NetbirdState.Daemon.Stopped }
            } == NetbirdState.Daemon.Running
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var statusSub: Subscription? = null
    private lateinit var connectivity: ConnectivityManager
    @Volatile private var stopping = false
    private var shownText: String? = null

    /** The default network last seen; a different one is a switch the daemon must hear about. */
    @Volatile private var defaultNetwork: Network? = null
    private var lostJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = writeDnsServers(lp)
        override fun onAvailable(network: Network) {
            connectivity.getLinkProperties(network)?.let(::writeDnsServers)
            lostJob?.cancel()
            val previous = defaultNetwork
            defaultNetwork = network
            // NetBird's netstack mode watches no network: without this a
            // Wi-Fi ↔ mobile switch is noticed only when the old sockets time
            // out. The first network after registering is no switch.
            NetbirdState.networkFlow.value = true
            if (previous != null && previous != network) {
                Appctr.logAndroid("INFO", "CORE", "Default network changed: telling the daemon")
                Appctr.networkChanged()
            } else if (previous == null && networkWasLost) {
                networkWasLost = false
                Appctr.logAndroid("INFO", "CORE", "A network is back: telling the daemon")
                Appctr.networkChanged()
            }
        }
        override fun onLost(network: Network) {
            if (network != defaultNetwork) return
            defaultNetwork = null
            // A switch reports the old network lost just before the new one
            // arrives; only a loss that stays is "no network".
            lostJob?.cancel()
            lostJob = scope.launch {
                kotlinx.coroutines.delay(2000)
                if (defaultNetwork == null) {
                    networkWasLost = true
                    NetbirdState.networkFlow.value = false
                    shownText = null
                    NetbirdState.statusFlow.value?.let(::updateNotification)
                    Appctr.logAndroid("INFO", "CORE", "No network: telling the daemon")
                    Appctr.networkLost()
                }
            }
        }
    }
    @Volatile private var networkWasLost = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        // The default network as apps see it — never this app's own VPN, which
        // it does not have, but possibly another app's: its DNS is what the
        // device uses then, and so the daemon too.
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "no default network callback: ${it.message}") }
        Appctr.setDaemonListener(object : DaemonListener {
            override fun onExit(err: String) = onDaemonExit(err)
        })
    }

    /** The newest start; stopping for an older one must not end a service asked to run since. */
    @Volatile private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Foreground first: Android kills a foreground-service start that has
        // not called startForeground within a few seconds, whatever it does next.
        goForeground(getString(R.string.nb_notif_starting))
        when (intent?.action) {
            ACTION_STOP -> stopDaemon()
            ACTION_RESTART -> restartDaemon()
            // A null intent is the system restarting a killed service: bring the
            // daemon back only if the user had it on.
            null -> if (GlobalSettings.wasRunning(this)) startDaemon() else stopDaemon()
            else -> startDaemon()
        }
        return START_STICKY
    }

    private fun startDaemon() {
        stopping = false
        if (startJob?.isActive == true || Appctr.isRunning()) return
        NetbirdState.daemonFlow.value = NetbirdState.Daemon.Starting
        startJob = scope.launch {
            try {
                connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }?.let(::writeDnsServers)
                Appctr.start(startOptions())
                Appctr.waitReady(15_000)
                NetbirdState.daemonFlow.value = NetbirdState.Daemon.Running
                NetbirdState.profileFlow.value = runCatching { Netbird.activeProfile() }.getOrNull()
                followStatus()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                Appctr.logAndroid("ERROR", "CORE", "NetBird did not start: ${e.message}")
                NetbirdState.errorFlow.value = getString(R.string.nb_error_start, e.message ?: e.toString())
                runCatching { Appctr.stop() }
                finish()
            }
        }
    }

    private fun startOptions() = StartOptions().apply {
        nativeLibDir = applicationInfo.nativeLibraryDir
        dataDir = filesDir.absolutePath
        socksHost = if (GlobalSettings.isSocksLanShared(this@NetbirdService)) "0.0.0.0" else GlobalSettings.getSocksHost(this@NetbirdService)
        socksPort = GlobalSettings.getSocksPort(this@NetbirdService).toLong()
        socksUser = GlobalSettings.getSocksUser(this@NetbirdService)
        socksPass = GlobalSettings.getSocksPass(this@NetbirdService)
        logLevel = GlobalSettings.getLogLevel(this@NetbirdService)
        dnsProxy = if (GlobalSettings.isDnsProxyEnabled(this@NetbirdService)) GlobalSettings.getDnsProxyAddress(this@NetbirdService) else ""
        dnsUpstream = GlobalSettings.getDnsUpstream(this@NetbirdService)
        relayQUIC = GlobalSettings.isRelayQuic(this@NetbirdService)
        lazyConn = GlobalSettings.getLazyConn(this@NetbirdService)
        hostname = GlobalSettings.getDeviceName(this@NetbirdService)
        androidVersion = Build.VERSION.RELEASE
        model = Build.MODEL
        manufacturer = Build.MANUFACTURER
        androidSdk = Build.VERSION.SDK_INT.toLong()
        env = buildString {
            if (GlobalSettings.isForceRelay(this@NetbirdService)) appendLine("NB_FORCE_RELAY=true")
            append(GlobalSettings.getExtraEnv(this@NetbirdService))
        }
    }

    /** Mirrors the daemon's status stream into NetbirdState and the notification. */
    private fun followStatus() {
        statusSub?.cancel()
        statusSub = Netbird.subscribe(
            "SubscribeStatus", """{"getFullPeerStatus":true}""",
            onMessage = { json ->
                val status = runCatching { AppJson.decodeFromString<NbStatus>(json) }.getOrNull() ?: return@subscribe
                // The active profile can change under the app (a CLI, a switch
                // that half failed): re-read it whenever the connection state moves.
                if (status.status != NetbirdState.statusFlow.value?.status) {
                    scope.launch { runCatching { Netbird.activeProfile() }.onSuccess { NetbirdState.profileFlow.value = it } }
                }
                val previous = NetbirdState.statusFlow.value
                NetbirdState.statusFlow.value = status
                updateNotification(status)
                watchSession(previous, status)
            },
            onEnd = { err ->
                if (err.isNotEmpty() && !stopping && Appctr.isRunning()) {
                    // The stream broke but the daemon lives: open it again.
                    scope.launch { kotlinx.coroutines.delay(1000); if (!stopping) followStatus() }
                }
            }
        )
    }

    private fun restartDaemon() {
        stopping = true
        NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopping
        scope.launch {
            startJob?.cancel()
            statusSub?.cancel()
            statusSub = null
            runCatching { Appctr.stop() }
            NetbirdState.statusFlow.value = null
            NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopped
            startDaemon()
        }
    }

    private fun stopDaemon() {
        stopping = true
        NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopping
        scope.launch {
            startJob?.cancel()
            statusSub?.cancel()
            // SIGTERM: the daemon takes the tunnel down itself before it exits.
            runCatching { Appctr.stop() }
            finish()
        }
    }

    /** When the daemon died on its own lately; three deaths in five minutes and it stays down. */
    private val crashTimes = ArrayDeque<Long>()

    private fun onDaemonExit(err: String) {
        if (stopping) return
        // Not asked for: a crash, or the system killing the process.
        Appctr.logAndroid("ERROR", "CORE", "The NetBird daemon exited: ${err.ifEmpty { "no error" }}")
        val now = System.currentTimeMillis()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > 5 * 60_000) crashTimes.removeFirst()
        statusSub?.cancel()
        statusSub = null
        NetbirdState.statusFlow.value = null
        if (crashTimes.size <= 3 && GlobalSettings.wasRunning(this)) {
            // It keeps its profile and keys on disk: a new process logs back in by itself.
            Appctr.logAndroid("WARN", "CORE", "Restarting the daemon (${crashTimes.size}/3 in 5 min)")
            NetbirdState.daemonFlow.value = NetbirdState.Daemon.Starting
            scope.launch {
                kotlinx.coroutines.delay(2000)
                if (!stopping) startDaemon()
            }
            return
        }
        NetbirdState.errorFlow.value = getString(R.string.nb_error_exit, err.ifEmpty { "exit 0" })
        finish()
    }

    private fun finish() {
        statusSub?.cancel()
        statusSub = null
        NetbirdState.statusFlow.value = null
        NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopped
        // Only if no start came in since: then onDestroy, which kills the
        // daemon, cannot run under a daemon that start just launched.
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        Appctr.setDaemonListener(null)
        if (Appctr.isRunning()) runCatching { Appctr.stop() }
        if (NetbirdState.daemonFlow.value != NetbirdState.Daemon.Stopped) {
            NetbirdState.statusFlow.value = null
            NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopped
        }
        scope.cancel()
        super.onDestroy()
    }

    /** The network's resolvers, for the daemon's own lookups (appctr/birdsocksd/resolver.go). */
    private fun writeDnsServers(lp: LinkProperties) {
        val servers = lp.dnsServers.mapNotNull { it.hostAddress }
        if (servers.isEmpty()) return
        runCatching { Appctr.setDNSServers(filesDir.absolutePath, servers.joinToString("\n")) }
            .onFailure { Log.w(TAG, "dns servers: ${it.message}") }
    }

    // --- Session expiry ---

    private var sessionJob: Job? = null
    private var sessionWatched: String? = null

    /**
     * Warns ten minutes before the server signs this device out, and says so
     * when it has: an expired session stops the engine and with it the proxy,
     * so every app behind it loses the network.
     */
    private fun watchSession(previous: NbStatus?, status: NbStatus) {
        if (status.state.needsLogin && previous?.state == NbConnState.Connected) {
            postSessionNotice(getString(R.string.nb_session_expired), extend = false)
        }
        val expiry = status.sessionExpiresAt
        if (expiry == sessionWatched) return
        sessionWatched = expiry
        sessionJob?.cancel()
        val until = expiry?.let { io.github.bropines.birdsocks.ui.parseRfc3339Millis(it) } ?: return
        sessionJob = scope.launch {
            val warnAt = until - 10 * 60_000
            kotlinx.coroutines.delay((warnAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (NetbirdState.statusFlow.value?.sessionExpiresAt == expiry) {
                val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
                postSessionNotice(getString(R.string.nb_session_expiring, clock), extend = true)
            }
        }
    }

    private fun postSessionNotice(text: String, extend: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SESSION_CHANNEL_ID, getString(R.string.nb_session_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EXTEND, extend),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { if (extend) addAction(0, getString(R.string.nb_session_extend), open) }
            .build()
        nm.notify(SESSION_NOTIF_ID, n)
    }

    // --- Notification ---

    private fun updateNotification(status: NbStatus) {
        val peers = status.fullStatus.peers
        val text = if (!NetbirdState.networkFlow.value && !status.state.needsLogin) getString(R.string.nb_status_offline) else when (status.state) {
            NbConnState.Connected -> getString(
                R.string.nb_notif_connected,
                status.fullStatus.localPeerState.address,
                peers.size, peers.count { it.connected }
            )
            NbConnState.Connecting -> getString(R.string.nb_notif_connecting)
            NbConnState.NeedsLogin, NbConnState.LoginFailed, NbConnState.SessionExpired -> getString(R.string.nb_notif_login)
            else -> getString(R.string.nb_notif_idle)
        }
        if (text == shownText) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
        shownText = text
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(shownText ?: text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NetbirdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }
}
