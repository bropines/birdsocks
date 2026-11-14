package io.github.bropines.birdsocks.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import appctr.Appctr
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File

/**
 * VPN mode's root engine: no VpnService and no VPN slot. A helper, the
 * daemon's own binary run as `libnetbird.so -root-tun` through `su`
 * (appctr/birdsocksd/roottun.go), makes a TUN interface on the device's
 * NetBird address, hands it to the daemon over its VPN socket and steers
 * traffic into it with policy routing.
 *
 * - **The helper's stdin is ours**: one JSON config per line, a new line when
 *   the routes change. When it closes — a stop, or this process dying — the
 *   helper takes every rule away again, as it does when the interface goes
 *   (the daemon died).
 * - **Follows the daemon**, like the VpnService engines: started through
 *   [TunVpnService.start] once the daemon answers, stopped before it stops.
 * - **A guest on the device**: the helper claims NetBird's ranges always, the
 *   default route and DNS only while no other VPN holds the slot (or for the
 *   apps it bypasses); [mode] says which (roottun_plan.go).
 */
object RootTun {
    private const val TAG = "RootTun"
    /** Its own routing tables, this and the next: clear of netd's (1000 + ifindex) and of other root apps'. */
    const val TABLE = 25203
    private const val TABLE_DEV = 25213
    private const val SELF_PREFIX = "io.github.bropines.birdsocks"
    private const val REBUILD_DEBOUNCE_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var lastConfig: String? = null
    private var watcher: Job? = null
    private var pending: Job? = null
    /** Between [start] and [stop]: a status with an address brings the helper up. */
    @Volatile private var wanted = false

    @Volatile private var networks: List<NbNetwork> = emptyList()
    @Volatile private var networksLoaded = false
    private var networksAsked: String? = null
    @Volatile private var profileMtu = -1
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var appContext: Context? = null
    /** Unexpected helper exits in the last minute: a helper that keeps dying is not restarted forever. */
    private val crashes = ArrayDeque<Long>()
    private var netManager: ConnectivityManager? = null

    /** What the helper claims now. */
    enum class Mode { Own, Shared, Yield, Override }
    data class State(val mode: Mode, val dns: String)

    private val stateFlow = MutableStateFlow<State?>(null)
    /** The helper's last word on what it claims; null while it is not up. */
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    /** Whether `su` grants root to this app; blocks for up to ten seconds, so off the main thread. */
    fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val done = p.waitUpTo(10_000)
        val out = if (done) p.inputStream.bufferedReader().readText() else ""
        if (!done) p.destroy()
        done && p.exitValue() == 0 && "uid=0" in out
    } catch (e: Exception) {
        false
    }

    /** Brings the helper up, or hands it the current config. */
    fun start(context: Context) {
        val app = context.applicationContext
        wanted = true
        appContext = app
        scope.launch { ensureWatcher(app); apply(app) }
    }

    /** Takes it down and waits up to [waitMs] for it to have removed its rules. */
    fun stop(waitMs: Long = 3000) {
        val p: Process?
        synchronized(lock) {
            wanted = false
            p = process
            runCatching { stdin?.apply { write("{\"op\":\"stop\"}\n"); flush() } }
            runCatching { stdin?.close() }
            stdin = null
            process = null
            lastConfig = null
            watcher?.cancel(); watcher = null
            pending?.cancel(); pending = null
        }
        unwatchNetwork()
        stateFlow.value = null
        if (p != null) {
            if (waitMs <= 0 || !p.waitUpTo(waitMs)) p.destroy()
        }
        TunVpnService.reportRoot(false, null)
    }

    val running: Boolean get() = synchronized(lock) { process?.running() == true }

    private fun ensureWatcher(context: Context) {
        synchronized(lock) {
            if (watcher?.isActive == true) return
            watcher = scope.launch { NetbirdState.status.collect { onStatus(context, it) } }
        }
        watchNetwork(context)
    }

    /**
     * Networks coming, going and being validated reach the helper's config:
     * the DNS redirect waits for Android's validation (its probe resolves
     * through the system resolver, which the redirect would send into a
     * tunnel that is not up yet), and a VPN this app can see is a reason to
     * yield.
     */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(lock) {
            if (netCallback != null) return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = schedule(context)
                override fun onLost(network: Network) = schedule(context)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule(context)
            }
            runCatching { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), cb) }
                .onSuccess { netCallback = cb; netManager = cm }
        }
    }

    private fun unwatchNetwork() {
        val (cm, cb) = synchronized(lock) {
            (netManager to netCallback).also { netCallback = null; netManager = null }
        }
        if (cm != null && cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
    }

    /** Whether some real (non-VPN) network has been validated by Android; true when it cannot tell. */
    private fun networkValidated(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@any false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrDefault(true)
    }

    private fun onStatus(context: Context, status: NbStatus?) {
        if (status == null) return
        val ask = "${status.fullStatus.networksRevision}|${status.state == NbConnState.Connected}"
        if (ask != networksAsked) {
            networksAsked = ask
            scope.launch {
                runCatching { Netbird.config() }.onSuccess { profileMtu = it.mtu.toInt() }
                runCatching { Netbird.networks() }.onSuccess { networks = it; networksLoaded = true; schedule(context) }
            }
        }
        schedule(context)
    }

    private fun schedule(context: Context) {
        synchronized(lock) {
            if (!wanted) return
            pending?.cancel()
            pending = scope.launch { delay(REBUILD_DEBOUNCE_MS); apply(context) }
        }
    }

    private suspend fun apply(context: Context) {
        sweepJob?.join()
        if (!GlobalSettings.isTunModeEnabled(context) || !GlobalSettings.isTunRoot(context) || !NetbirdState.isRunning) {
            stop(0)
            return
        }
        if (!networksLoaded) runCatching { Netbird.networks() }.onSuccess { networks = it; networksLoaded = true }
        if (profileMtu < 0) runCatching { Netbird.config() }.onSuccess { profileMtu = it.mtu.toInt() }
        val config = config(context) ?: run {
            Appctr.logAndroid("INFO", "CORE", "Root VPN: waiting for this device's NetBird address")
            return
        }
        synchronized(lock) {
            if (config == lastConfig && process?.running() == true) return
            if (process?.running() != true && !spawn(context)) return
            runCatching {
                stdin!!.write(config); stdin!!.write("\n"); stdin!!.flush()
                lastConfig = config
            }.onFailure {
                Appctr.logAndroid("ERROR", "CORE", "Root VPN: the helper did not take the config: ${it.message}")
            }
        }
    }

    /** Starts the helper through su; under [lock]. */
    @Volatile private var markerContext: Context? = null
    /** A sweep of an earlier helper's rules; a new helper waits for it, or the sweep would take the new rules. */
    @Volatile private var sweepJob: Job? = null

    private fun spawn(context: Context): Boolean {
        markerContext = context.applicationContext
        val bin = File(context.applicationInfo.nativeLibraryDir, "libnetbird.so").absolutePath
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            val w = p.outputStream.bufferedWriter()
            // exec: the shell becomes the helper, which keeps our stdin.
            w.write("exec '" + bin.replace("'", "'\\''") + "' -root-tun 2>&1\n")
            w.flush()
            process = p
            stdin = w
            lastConfig = null
            Thread({ pump(p) }, "root-tun").apply { isDaemon = true; start() }
            Appctr.logAndroid("INFO", "CORE", "Root VPN: helper started")
            true
        } catch (e: Exception) {
            Appctr.logAndroid("ERROR", "CORE", "Root VPN: su failed: ${e.message}")
            false
        }
    }

    /** The helper's lines into the log, its state into the VPN flows. */
    private fun pump(p: Process) {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                val level = when {
                    line.startsWith("error") -> "ERROR"
                    line.startsWith("warn") -> "WARN"
                    else -> "INFO"
                }
                Log.i(TAG, line)
                Appctr.logAndroid(level, "CORE", "Root VPN: $line")
                when {
                    line.startsWith("ready:") -> { TunVpnService.reportRoot(true, lastRoutes); markerContext?.let { writeMarker(it) } }
                    line.startsWith("state:") -> parseState(line)?.let { stateFlow.value = it }
                    line.startsWith("down:") -> { TunVpnService.reportRoot(false, null); stateFlow.value = null; markerContext?.let { marker(it).delete() } }
                }
            }
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        synchronized(lock) {
            if (process === p) {
                process = null; stdin = null; lastConfig = null
            }
        }
        TunVpnService.reportRoot(false, null)
        stateFlow.value = null
        Appctr.logAndroid(if (code == 0) "INFO" else "WARN", "CORE", "Root VPN: helper exited ($code)")
        // Died on its own while wanted (killed, crashed): its rules may be left
        // unsupervised. A new helper sweeps them and takes over.
        val context = appContext
        if (wanted && context != null) {
            val now = System.currentTimeMillis()
            val again = synchronized(crashes) {
                while (crashes.isNotEmpty() && now - crashes.first() > 60_000) crashes.removeFirst()
                crashes.addLast(now)
                crashes.size <= 3
            }
            if (again) {
                Appctr.logAndroid("INFO", "CORE", "Root VPN: restarting the helper")
                scope.launch { delay(2000); if (wanted) apply(context) }
            } else {
                Appctr.logAndroid("ERROR", "CORE", "Root VPN: the helper keeps exiting; not restarted until the next start")
            }
        }
    }

    /**
     * A file that says a helper has rules on the device: written on its "ready", removed
     * on its "down". A helper that died without its teardown (killed with the app, an
     * old build's SIGPIPE) leaves it behind, and [sweepIfStale] then takes its rules.
     */
    private fun marker(context: Context) = File(context.filesDir, "root-helper")

    private fun sweepConfig(context: Context): String {
        val dev = context.packageName.endsWith(".dev")
        return buildJsonObject {
            put("op", "sweep")
            put("table", if (dev) TABLE_DEV else TABLE)
            put("chain", if (dev) "BIRDSOCKSD" else "BIRDSOCKS")
            put("state_dir", if (dev) "/data/adb/birdsocks-dev" else "/data/adb/birdsocks")
        }.toString()
    }

    private fun writeMarker(context: Context) {
        runCatching { marker(context).writeText(sweepConfig(context)) }
    }

    /**
     * On the app's start: rules a helper left with no helper to watch them — the
     * interface gone with the daemon, the DNS redirect pointing nowhere — are taken
     * away through su. Only when the marker says a helper had rules, so a phone that
     * never used Root sees no su prompt.
     */
    fun sweepIfStale(context: Context) {
        val app = context.applicationContext
        if (!marker(app).exists()) return
        sweepJob = scope.launch {
            if (synchronized(lock) { process != null }) return@launch
            val bin = File(app.applicationInfo.nativeLibraryDir, "libnetbird.so").absolutePath
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su"))
                p.outputStream.bufferedWriter().use { w ->
                    w.write("exec '" + bin.replace("'", "'\\''") + "' -root-tun 2>&1\n")
                    w.write(sweepConfig(app)); w.write("\n")
                }
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitUpTo(10_000)) marker(app).delete()
                Appctr.logAndroid("INFO", "CORE", "Root VPN: rules left by an earlier helper: ${out.ifEmpty { "nothing said" }}")
            }.onFailure { Appctr.logAndroid("WARN", "CORE", "Root VPN: could not sweep an earlier helper's rules: ${it.message}") }
        }
    }

    /** "state: mode=shared dns=on capture=true (…)" → [State]. */
    internal fun parseState(line: String): State? {
        val words = line.removePrefix("state:").trim().split(' ')
        fun value(key: String) = words.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
        val mode = when (value("mode")) {
            "own" -> Mode.Own
            "shared" -> Mode.Shared
            "yield" -> Mode.Yield
            "override" -> Mode.Override
            else -> return null
        }
        return State(mode, value("dns") ?: "off")
    }

    @Volatile private var lastRoutes: TunRoutes.RouteSet? = null

    /** The helper's config line, or null while no NetBird address is known. */
    private fun config(context: Context): String? {
        val self = TunVpnService.selfAddresses(context) ?: return null
        val profile = NetbirdState.profile.value ?: "default"
        val local = NetbirdState.status.value?.fullStatus?.localPeerState
        val cached = GlobalSettings.getTunOverlay(context, profile).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val routes = TunRoutes.compute(
            local?.ip.orEmpty(), local?.ipv6.orEmpty(), cached, networks,
            GlobalSettings.isTunRouteAll(context), GlobalSettings.isTunIpv6Enabled(context)
        )
        lastRoutes = routes
        // NetBird's own ranges are the overlay (T1); everything else selected is a network (T2).
        val overlay = TunRoutes.overlayPrefixes(local?.ip.orEmpty(), local?.ipv6.orEmpty())
            .ifEmpty { cached.mapNotNull(TunRoutes::normalize) }
            .let { o -> if (o.none { ':' !in it }) o + TunRoutes.FALLBACK_OVERLAY else o }
        val dnsRoute = "${TunRoutes.DNS_IP}/32"
        val networkRoutes = (routes.v4 + routes.v6).filter { it != dnsRoute && it !in overlay }
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val ownUids = installed.filter { it.packageName.startsWith(SELF_PREFIX) }.map { it.uid }.distinct()
        val excludedUids = GlobalSettings.getTunExcludedApps(context).mapNotNull { pkg ->
            try { pm.getApplicationInfo(pkg, 0).uid } catch (_: PackageManager.NameNotFoundException) { null }
        }.distinct()
        val vpnUids = runCatching {
            pm.queryIntentServices(Intent("android.net.VpnService"), PackageManager.MATCH_ALL)
                .mapNotNull { it.serviceInfo?.applicationInfo?.uid }
        }.getOrDefault(emptyList()).distinct()
        val carryUids = installed.map { it.uid }.distinct().filter { it !in ownUids && it !in vpnUids }
        val cidrs = GlobalSettings.getTunExcludedCIDRs(context).split(',').mapNotNull { TunRoutes.normalize(it.trim()) }
        val domain = runCatching { File("/proc/self/attr/current").readText().trimEnd('\u0000', '\n') }.getOrDefault("")
        val mtu = profileMtu.takeIf { it in 576..TunVpnService.NATIVE_MTU } ?: TunVpnService.NATIVE_MTU
        val dev = context.packageName.endsWith(".dev")
        fun ints(xs: List<Int>) = JsonArray(xs.map { JsonPrimitive(it) })
        fun strs(xs: List<String>) = JsonArray(xs.map { JsonPrimitive(it) })
        return buildJsonObject {
            put("name", if (dev) "birdsocksd0" else "birdsocks0")
            put("mtu", mtu)
            put("addrs", strs(listOfNotNull(self.first, self.second)))
            put("overlay", strs(overlay))
            put("routes", strs(networkRoutes))
            put("default4", routes.defaultV4)
            put("default6", routes.defaultV6)
            put("own_uids", ints(ownUids))
            put("exclude_uids", ints(excludedUids))
            put("exclude_cidrs", strs(cidrs))
            put("carry_uids", ints(carryUids))
            put("vpn_uids", ints(vpnUids))
            put("keep_lan", GlobalSettings.isTunRootKeepLan(context))
            put("take_device", GlobalSettings.isTunRootTakeDevice(context))
            put("foreign_seen", TunVpnService.otherVpnActive(context))
            put("dns_hold", !networkValidated(context))
            put("app_uid", android.os.Process.myUid())
            put("app_domain", domain)
            put("table", if (dev) TABLE_DEV else TABLE)
            put("dns", TunRoutes.DNS_IP)
            put("socket", File(context.filesDir, "vpn.sock").absolutePath)
            put("chain", if (dev) "BIRDSOCKSD" else "BIRDSOCKS")
            put("state_dir", if (dev) "/data/adb/birdsocks-dev" else "/data/adb/birdsocks")
        }.toString()
    }
}

/** Process.isAlive, which Android has only from API 26. */
private fun Process.running(): Boolean = try {
    exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}

/** Process.waitFor(timeout), also API 26: whether it ended within [ms]. */
private fun Process.waitUpTo(ms: Long): Boolean {
    if (!running()) return true
    val waiter = Thread { runCatching { waitFor() } }.apply { isDaemon = true; start() }
    waiter.join(ms)
    return !running()
}
