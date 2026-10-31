package io.github.bropines.birdsocks.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import appctr.Appctr
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * VPN (TUN) mode, with two engines ([GlobalSettings.getTunEngine]):
 *
 * - **Native** (the default): the interface takes the device's NetBird
 *   address and goes to the daemon ([Appctr.attachVPN]), which hands the
 *   apps' packets to WireGuard as they are and ends the rest itself
 *   (netstack/vpn.go). Peers reach the phone's apps with their own address.
 * - **Through the proxy**: the interface on an address of its own, fed into
 *   BirdSocks' SOCKS5 proxy by hev-socks5-tunnel (native, JNI).
 *
 * - **Follows the daemon.** NetbirdService starts it once the daemon answers
 *   ([start]) and stops it before every daemon stop, restart or after a
 *   crash ([stop]): without the daemon the SOCKS port is gone, and a VPN
 *   left up would be a black hole for every app and for DNS.
 * - **DNS** is one address, [TunRoutes.DNS_IP], routed into the tunnel; hev
 *   carries its queries to the proxy, which answers them itself with the DNS
 *   proxy's chain (netstack/tundns.go) — NetBird's names first, fallbacks
 *   raced after, never a NetBird name to a public server.
 * - **Routes** come from NetBird's state ([TunRoutes]); a change rebuilds the
 *   VPN, debounced: the new interface is established first, then hev moves
 *   to it, so the VPN network is not dropped in between.
 * - **This app is always outside it** — a loop guard: the daemon's own
 *   sockets go over the real network. Android may still report this VPN as
 *   the app's default network; NetbirdService then reads the network beneath.
 */
class TunVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.github.bropines.birdsocks.TUN_START"
        const val ACTION_STOP = "io.github.bropines.birdsocks.TUN_STOP"
        const val TUN_MTU = 1500
        /** The native interface's MTU unless the profile sets NetBird's: WireGuard's own default. */
        const val NATIVE_MTU = 1280
        private const val TAG = "TunVpnService"
        private const val CHANNEL_ID = "tun"
        private const val NOTIF_CONSENT = 7
        private const val NOTIF_REVOKED = 8
        private const val SELF_PREFIX = "io.github.bropines.birdsocks"
        /** A status-driven rebuild waits this long for the state to settle. */
        private const val REBUILD_DEBOUNCE_MS = 1500L

        private val runningFlow = MutableStateFlow(false)
        /** True while the VPN interface is up and hev runs on it. */
        val running: StateFlow<Boolean> = runningFlow.asStateFlow()

        private val routesFlow = MutableStateFlow<TunRoutes.RouteSet?>(null)
        /** The routes the running VPN was established with. */
        val routes: StateFlow<TunRoutes.RouteSet?> = routesFlow.asStateFlow()

        @Volatile private var instance: WeakReference<TunVpnService>? = null

        // JNI interface (hev-socks5-tunnel)
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray

        var nativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                nativeLoaded = true
            } catch (e: LinkageError) {
                // UnsatisfiedLinkError when the .so is missing, NoSuchMethodError
                // when RegisterNatives in JNI_OnLoad cannot find one of the
                // externals (an R8 keep-rule mismatch). TUN mode is unavailable
                // then; the app must not die because this class was touched.
                Log.e(TAG, "libhev-socks5-tunnel failed to load, TUN mode unavailable: $e")
            }
        }

        /** Whether VPN mode can run with the chosen engine: hev is a native library that may be missing. */
        fun isAvailable(context: Context): Boolean =
            GlobalSettings.isTunNative(context) || GlobalSettings.isTunRoot(context) || nativeLoaded

        /** The root engine's state, for the screens: it has no VpnService of its own. */
        internal fun reportRoot(up: Boolean, set: TunRoutes.RouteSet?) {
            runningFlow.value = up
            routesFlow.value = if (up) set else null
        }

        /**
         * The device's NetBird addresses for an interface on them, IPv4 and IPv6
         * (or null), without prefixes: from the status, else the ones last seen
         * under this profile. Null when none is known.
         */
        fun selfAddresses(context: Context): Pair<String, String?>? {
            val profile = NetbirdState.profile.value ?: "default"
            val local = NetbirdState.status.value?.fullStatus?.localPeerState
            val v4 = local?.ip.orEmpty().substringBefore('/').trim()
            val v6 = local?.ipv6.orEmpty().substringBefore('/').trim()
            if (v4.isNotEmpty()) {
                val live = if (v6.isNotEmpty()) "$v4,$v6" else v4
                if (live != GlobalSettings.getTunSelf(context, profile)) GlobalSettings.setTunSelf(context, profile, live)
                return v4 to v6.ifEmpty { null }
            }
            val cached = GlobalSettings.getTunSelf(context, profile).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val c4 = cached.firstOrNull { ':' !in it } ?: return null
            return c4 to cached.firstOrNull { ':' in it }
        }

        /**
         * Brings the VPN up, or makes it re-read its settings, when TUN mode
         * is on and the daemon runs; otherwise nothing. Without the VPN
         * permission a notification asks for it (no activity starts from
         * the background).
         */
        fun start(context: Context) {
            if (!GlobalSettings.isTunModeEnabled(context) || !isAvailable(context) || !NetbirdState.isRunning) return
            if (GlobalSettings.isTunRoot(context)) {
                // No VpnService: a running one makes way for the root engine.
                instance?.get()?.takeIf { !it.stopping }?.requestApply(0)
                RootTun.start(context)
                return
            }
            // Away from the root engine: its helper takes its interface and rules down.
            if (RootTun.running) RootTun.stop(0)
            if (VpnService.prepare(context) != null) {
                postConsentNeeded(context)
                return
            }
            // A running instance re-reads its inputs; one that is stopping cannot.
            instance?.get()?.takeIf { !it.stopping }?.let { it.requestApply(0); return }
            try {
                context.startService(Intent(context, TunVpnService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                Log.w(TAG, "start refused: ${e.message}")
                Appctr.logAndroid("WARN", "CORE", "VPN: Android refused to start it: ${e.message}")
            }
        }

        /**
         * Takes the VPN down and waits (up to [waitMs]) until it is: the
         * daemon may stop right after. Safe to call when nothing runs.
         */
        fun stop(waitMs: Long = 3000) {
            instance?.get()?.shutdown(waitMs)
            RootTun.stop(waitMs)
        }

        /** Whether another app's VPN carries this app's traffic now (this one excludes itself). */
        fun otherVpnActive(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        private fun channel(context: Context): NotificationManager {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.nb_tun_channel)).build()
            )
            return context.getSystemService(NotificationManager::class.java)
        }

        private fun postConsentNeeded(context: Context) {
            val open = PendingIntent.getActivity(
                context, 7, Intent(context, TunPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.nb_tun_consent_title))
                .setContentText(context.getString(R.string.nb_tun_consent_text))
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { channel(context).notify(NOTIF_CONSENT, n) }
        }

        /** The consent notification goes once the permission is there. */
        fun clearConsentNotice(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_CONSENT)
        }
    }

    /** Establishing, swapping and stopping run here, one at a time. */
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingApply: ScheduledFuture<*>? = null

    // Executor-confined state (the key is read from the status collector too).
    private var tunFd: ParcelFileDescriptor? = null
    private var hevRunning = false
    /** The daemon has the interface (native engine). */
    private var daemonAttached = false
    @Volatile private var establishedKey: String? = null
    /** Native: no NetBird address known yet; a status with one brings the VPN up. */
    @Volatile private var waitingForAddress = false
    /** NetBird's interface MTU from the profile: 0 when it sets none, -1 until read. */
    @Volatile private var profileMtu = -1

    /** Set by [shutdown]: this instance is on its way out, a start needs a new one. */
    @Volatile private var stopping = false

    /** ListNetworks, re-read when the daemon's networks revision moves. */
    @Volatile private var networks: List<NbNetwork> = emptyList()
    @Volatile private var networksLoaded = false
    private var networksAsked: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        scope.launch { NetbirdState.status.collect(::onStatus) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        when (intent?.action) {
            ACTION_STOP -> {
                executor.execute { stopTunnel() }
                stopSelf()
                return START_NOT_STICKY
            }
            VpnService.SERVICE_INTERFACE -> {
                // The system: always-on VPN, at boot or when set. The tunnel
                // needs the daemon; it comes up once the daemon answers.
                Log.i(TAG, "Started by the system (always-on)")
                GlobalSettings.setTunModeEnabled(this, true)
                if (!NetbirdState.isRunning) runCatching { NetbirdService.start(this) }
                    .onFailure { Log.w(TAG, "could not start the daemon: ${it.message}") }
            }
        }
        if (!isAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        requestApply(0)
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took the slot, or the user revoked it in system settings.
        Log.i(TAG, "VPN revoked")
        Appctr.logAndroid("WARN", "CORE", "VPN: another VPN took the slot; the proxy goes on")
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.nb_tun_revoked_text))
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setAutoCancel(true)
            .build()
        runCatching { channel(this).notify(NOTIF_REVOKED, n) }
        shutdown(0)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        scope.cancel()
        executor.execute { stopTunnel() }
        executor.shutdown()
        super.onDestroy()
    }

    /** Stops the tunnel and the service; waits up to [waitMs] for the tunnel. */
    fun shutdown(waitMs: Long) {
        stopping = true
        val done = runCatching { executor.submit { stopTunnel() } }.getOrNull()
        if (waitMs > 0) runCatching { done?.get(waitMs, TimeUnit.MILLISECONDS) }
        stopSelf()
    }

    /** Re-evaluates the VPN after [delayMs]; a newer request replaces a pending one. */
    fun requestApply(delayMs: Long) {
        synchronized(this) {
            pendingApply?.cancel(false)
            pendingApply = runCatching { executor.schedule({ apply() }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
        }
    }

    private fun onStatus(status: NbStatus?) {
        if (status == null) return
        // ListNetworks says what is selected: re-read it when the networks
        // move (a selection bumps the revision too) or the engine connects.
        val ask = "${status.fullStatus.networksRevision}|${status.state == NbConnState.Connected}"
        if (ask != networksAsked) {
            networksAsked = ask
            scope.launch {
                runCatching { Netbird.config() }.onSuccess { profileMtu = it.mtu.toInt() }
                runCatching { Netbird.networks() }
                    .onSuccess { networks = it; networksLoaded = true; requestApplyIfChanged() }
            }
        }
        requestApplyIfChanged()
    }

    private fun requestApplyIfChanged() {
        if (establishedKey == null) {
            // Not up: start() and the settings decide, unless it waits for an address.
            if (waitingForAddress && selfAddresses() != null) requestApply(0)
            return
        }
        if (inputs().key != establishedKey) requestApply(REBUILD_DEBOUNCE_MS)
    }

    /**
     * The device's NetBird addresses for the native interface, IPv4 and
     * IPv6 (or null), without prefixes: from the status, else the ones last
     * seen under this profile. Null when none is known.
     */
    private fun selfAddresses(): Pair<String, String?>? = selfAddresses(this)

    /** The native interface's MTU: NetBird's, so no packet is larger than WireGuard takes. */
    private fun nativeMtu(): Int = profileMtu.takeIf { it in 576..NATIVE_MTU } ?: NATIVE_MTU

    // -------------------------------------------------------------------------
    // The VPN, on the executor
    // -------------------------------------------------------------------------

    private class Inputs(
        val routes: TunRoutes.RouteSet,
        val key: String,
        /** Native engine: the interface's addresses (null until NetBird gave one) and MTU. */
        val native: Boolean,
        val self: Pair<String, String?>?,
        val mtu: Int,
    )

    /** Everything the Builder and hev are given, and its comparable key. */
    private fun inputs(): Inputs {
        val status = NetbirdState.status.value
        val profile = NetbirdState.profile.value ?: "default"
        val local = status?.fullStatus?.localPeerState
        val live = TunRoutes.overlayPrefixes(local?.ip.orEmpty(), local?.ipv6.orEmpty())
        val cached = GlobalSettings.getTunOverlay(this, profile).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (live.isNotEmpty() && live != cached) GlobalSettings.setTunOverlay(this, profile, live.joinToString(","))
        val routes = TunRoutes.compute(
            local?.ip.orEmpty(), local?.ipv6.orEmpty(), cached, networks,
            GlobalSettings.isTunRouteAll(this), GlobalSettings.isTunIpv6Enabled(this)
        )
        val native = GlobalSettings.isTunNative(this)
        val self = if (native) selfAddresses() else null
        val mtu = if (native) nativeMtu() else TUN_MTU
        val common = listOf(
            routes.key,
            GlobalSettings.getTunExcludedApps(this).sorted().joinToString(","),
            GlobalSettings.getTunExcludedCIDRs(this),
            GlobalSettings.getTunUla(this),
        )
        val engine = if (native) listOf("native", self.toString(), mtu.toString())
        else listOf(
            "socks",
            GlobalSettings.getTunAddress(this),
            GlobalSettings.getSocksAddress(this),
            GlobalSettings.getSocksUser(this),
            GlobalSettings.getSocksPass(this).hashCode().toString(),
        )
        return Inputs(routes, (common + engine).joinToString("|"), native, self, mtu)
    }

    private fun apply() {
        try {
            if (!GlobalSettings.isTunModeEnabled(this) || GlobalSettings.isTunRoot(this)) {
                // Off, or the root engine's turn (RootTun): no VpnService.
                stopTunnel()
                stopSelf()
                return
            }
            if (!NetbirdState.isRunning) {
                // NetbirdService brings it back once the daemon answers.
                stopTunnel()
                return
            }
            if (VpnService.prepare(this) != null) {
                Log.w(TAG, "No VPN permission")
                stopTunnel()
                postConsentNeeded(this)
                stopSelf()
                return
            }
            if (!networksLoaded) {
                // What is selected decides the default route: read it before the
                // first establish, or a selected exit node costs a rebuild at once.
                runBlocking { withTimeoutOrNull(3000) { runCatching { Netbird.networks() }.getOrNull() } }
                    ?.let { networks = it; networksLoaded = true }
            }
            if (GlobalSettings.isTunNative(this) && profileMtu < 0) {
                runBlocking { withTimeoutOrNull(3000) { runCatching { Netbird.config() }.getOrNull() } }
                    ?.let { profileMtu = it.mtu.toInt() }
            }
            val inputs = inputs()
            if (inputs.key == establishedKey && tunFd != null) return
            if (inputs.native && inputs.self == null) {
                // The interface takes the NetBird address: none until the first
                // connect of this account. A status with one brings it up.
                if (!waitingForAddress) Appctr.logAndroid("INFO", "CORE", "VPN: waiting for this device's NetBird address")
                waitingForAddress = true
                stopTunnel()
                return
            }
            waitingForAddress = false
            establish(inputs)
        } catch (e: Exception) {
            Log.e(TAG, "apply failed", e)
            Appctr.logAndroid("ERROR", "CORE", "VPN: ${e.message}")
        }
    }

    private fun establish(inputs: Inputs) {
        val r = inputs.routes
        val builder = Builder().setSession(getString(R.string.app_name)).setMtu(inputs.mtu)
        // An IPv6 address keeps IPv6 alive for the apps inside: a family with
        // no address in a VPN is blocked for them. NetBird's own when it has one.
        val v6 = inputs.self?.second ?: GlobalSettings.getTunUla(this)
        if (inputs.native) {
            // The device's own NetBird address: what apps send reaches peers as
            // it is, and what peers send reaches the apps with their address.
            builder.addAddress(inputs.self!!.first, 32)
        } else {
            val (addr, prefix) = parseAddress(GlobalSettings.getTunAddress(this))
            builder.addAddress(addr, prefix)
        }
        builder.addAddress(v6, 128).addDnsServer(TunRoutes.DNS_IP)
        for (p in r.v4) addRoute(builder, p)
        for (p in r.v6) addRoute(builder, p)
        if (r.defaultV4) builder.addRoute("0.0.0.0", 0)
        if (r.defaultV6) builder.addRoute("::", 0)
        // Inherit the network's meteredness; a VPN is metered by default from Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        applyAppExclusions(builder)
        if (r.defaultV4) applyRouteExclusions(builder)

        val fd = builder.establish()
        if (fd == null) {
            // No permission any more, or another app is always-on.
            Log.e(TAG, "establish() returned null")
            Appctr.logAndroid("ERROR", "CORE", "VPN: Android did not establish the interface")
            stopTunnel()
            stopSelf()
            return
        }
        val swap = tunFd != null
        val started = if (inputs.native) handToDaemon(fd, inputs.self!!.first, v6, inputs.mtu) else startHev(fd)
        if (!started) {
            runCatching { fd.close() }
            stopTunnel()
            return
        }
        // The new interface carries the traffic; the old one goes.
        runCatching { tunFd?.close() }
        tunFd = fd
        establishedKey = inputs.key
        routesFlow.value = r
        runningFlow.value = true
        val how = r.defaultReason?.let { "all traffic ($it)" } ?: "NetBird ranges"
        val engine = if (inputs.native) "on ${inputs.self!!.first}" else "through the proxy"
        Log.i(TAG, "VPN ${if (swap) "rebuilt" else "up"} $engine: ${r.v4 + r.v6}, $how")
        Appctr.logAndroid("INFO", "CORE", "VPN ${if (swap) "rebuilt" else "up"} $engine: $how; routes ${(r.v4 + r.v6).joinToString(" ")}")
    }

    /**
     * Native engine: the daemon takes a duplicate of the interface and lets
     * go of the one before; hev stops when it ran on the old one.
     */
    private fun handToDaemon(fd: ParcelFileDescriptor, v4: String, v6: String, mtu: Int): Boolean {
        val dup = try {
            fd.dup()
        } catch (e: Exception) {
            Appctr.logAndroid("ERROR", "CORE", "VPN: ${e.message}")
            return false
        }
        try {
            // attachVPN closes the duplicate whatever happens.
            Appctr.attachVPN(filesDir.absolutePath, dup.detachFd().toLong(), mtu.toLong(), "$v4,$v6")
        } catch (e: Exception) {
            Log.e(TAG, "attachVPN: ${e.message}")
            Appctr.logAndroid("ERROR", "CORE", "VPN: the daemon did not take the interface: ${e.message}")
            return false
        }
        daemonAttached = true
        stopHev()
        return true
    }

    /**
     * Through the proxy: hev moves to the new interface. Stop it first, then
     * close the old fd: lwip reads and writes the raw fd number until
     * TProxyStopService has joined its thread, and a number closed early is
     * reused at once by another thread's socket.
     */
    private fun startHev(fd: ParcelFileDescriptor): Boolean {
        val config = File(cacheDir, "tun.conf")
        if (!writeHevConfig(config)) return false
        stopHev()
        TProxyStartService(config.absolutePath, fd.fd)
        hevRunning = true
        detachFromDaemon()
        return true
    }

    private fun stopHev() {
        if (hevRunning) {
            runCatching { TProxyStopService() }.onFailure { Log.e(TAG, "TProxyStopService: $it") }
            hevRunning = false
        }
    }

    /** The daemon lets go of the interface; a daemon already gone has nothing to let go of. */
    private fun detachFromDaemon() {
        if (!daemonAttached) return
        daemonAttached = false
        runCatching { Appctr.detachVPN(filesDir.absolutePath) }
            .onFailure { Log.d(TAG, "detachVPN: ${it.message}") }
    }

    private fun stopTunnel() {
        val wasUp = tunFd != null
        stopHev()
        detachFromDaemon()
        runCatching { tunFd?.close() }
        tunFd = null
        establishedKey = null
        if (wasUp) {
            // Only this tunnel's state: the root engine may already report its own.
            routesFlow.value = null
            runningFlow.value = false
            Log.i(TAG, "VPN down")
            Appctr.logAndroid("INFO", "CORE", "VPN down")
        }
    }

    private fun addRoute(builder: Builder, cidr: String) {
        val host = cidr.substringBefore('/')
        val bits = cidr.substringAfter('/').toIntOrNull() ?: return
        runCatching { builder.addRoute(host, bits) }.onFailure { Log.w(TAG, "route $cidr: ${it.message}") }
    }

    /** "198.18.0.1/32" → address and prefix; a bare address is a /32. */
    private fun parseAddress(raw: String): Pair<String, Int> {
        val host = raw.substringBefore('/').trim()
        val prefix = raw.substringAfter('/', "32").trim().toIntOrNull()?.takeIf { it in 1..32 } ?: 32
        return if (TunRoutes.normalize("$host/32") != null && ':' !in host) host to prefix
        else GlobalSettings.DEFAULT_TUN_ADDRESS.substringBefore('/') to 32
    }

    /** This app's packages always bypass the tunnel (a loop otherwise), then the user's list. */
    private fun applyAppExclusions(builder: Builder) {
        val own = runCatching {
            packageManager.getInstalledApplications(0).map { it.packageName }.filter { it.startsWith(SELF_PREFIX) }
        }.getOrDefault(emptyList()) + packageName
        for (pkg in own.toSet()) runCatching { builder.addDisallowedApplication(pkg) }
        for (pkg in GlobalSettings.getTunExcludedApps(this)) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Excluded app not installed: $pkg")
            }
        }
    }

    /**
     * The "Excluded ranges" setting: subnets kept on the real network. Android
     * carves them out of the default route only since 13 (excludeRoute).
     */
    private fun applyRouteExclusions(builder: Builder) {
        val cidrs = GlobalSettings.getTunExcludedCIDRs(this).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (cidrs.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "Excluded ranges need Android 13+, ignoring: $cidrs")
            return
        }
        for (cidr in cidrs) {
            val p = TunRoutes.normalize(cidr) ?: continue
            runCatching {
                builder.excludeRoute(android.net.IpPrefix(android.net.InetAddresses.parseNumericAddress(p.substringBefore('/')), p.substringAfter('/').toInt()))
            }.onFailure { Log.w(TAG, "Excluded range ignored: $cidr (${it.message})") }
        }
    }

    /**
     * hev's YAML: the MTU and the SOCKS5 proxy, UDP over UDP ASSOCIATE. No
     * mapdns: its fake-IP pool defaults to 100.64.0.0/10, NetBird's range.
     * Strings are single-quoted, a quote doubled.
     */
    private fun writeHevConfig(file: File): Boolean = try {
        val addr = GlobalSettings.getSocksAddress(this)
        val host = NetAddr.dialableHost(addr)
        val port = NetAddr.port(addr) ?: GlobalSettings.DEFAULT_SOCKS_PORT
        val user = GlobalSettings.getSocksUser(this)
        val pass = GlobalSettings.getSocksPass(this)
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val cfg = buildString {
            append("tunnel:\n  mtu: $TUN_MTU\n\n")
            append("socks5:\n  address: ${q(host)}\n  port: $port\n  udp: 'udp'\n")
            if (user.isNotEmpty() && pass.isNotEmpty()) append("  username: ${q(user)}\n  password: ${q(pass)}\n")
        }
        file.writeText(cfg)
        true
    } catch (e: Exception) {
        Log.e(TAG, "hev config not written: ${e.message}")
        false
    }
}
