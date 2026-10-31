package io.github.bropines.birdsocks.core

import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

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
 */
object RootTun {
    private const val TAG = "RootTun"
    /** Its own routing table and rule priority: clear of netd (10000+) and of TailSocks (52/53, 100-200). */
    const val TABLE = 25203
    const val PREF = 90
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

    /** Whether `su` grants root to this app; blocks for up to ten seconds, so off the main thread. */
    fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val done = p.waitFor(10, TimeUnit.SECONDS)
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
        if (p != null) {
            if (waitMs <= 0 || !runCatching { p.waitFor(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) p.destroy()
        }
        TunVpnService.reportRoot(false, null)
    }

    val running: Boolean get() = synchronized(lock) { process?.isAlive == true }

    private fun ensureWatcher(context: Context) {
        synchronized(lock) {
            if (watcher?.isActive == true) return
            watcher = scope.launch { NetbirdState.status.collect { onStatus(context, it) } }
        }
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
            if (config == lastConfig && process?.isAlive == true) return
            if (process?.isAlive != true && !spawn(context)) return
            runCatching {
                stdin!!.write(config); stdin!!.write("\n"); stdin!!.flush()
                lastConfig = config
            }.onFailure {
                Appctr.logAndroid("ERROR", "CORE", "Root VPN: the helper did not take the config: ${it.message}")
            }
        }
    }

    /** Starts the helper through su; under [lock]. */
    private fun spawn(context: Context): Boolean {
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
                    line.startsWith("ready:") -> TunVpnService.reportRoot(true, lastRoutes)
                    line.startsWith("down:") -> TunVpnService.reportRoot(false, null)
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
        Appctr.logAndroid(if (code == 0) "INFO" else "WARN", "CORE", "Root VPN: helper exited ($code)")
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
        val pm = context.packageManager
        val ownUids = runCatching {
            pm.getInstalledApplications(0).filter { it.packageName.startsWith(SELF_PREFIX) }.map { it.uid }
        }.getOrDefault(emptyList())
        val excludedUids = GlobalSettings.getTunExcludedApps(context).mapNotNull { pkg ->
            try { pm.getApplicationInfo(pkg, 0).uid } catch (_: PackageManager.NameNotFoundException) { null }
        }
        val cidrs = GlobalSettings.getTunExcludedCIDRs(context).split(',').mapNotNull { TunRoutes.normalize(it.trim()) }
        val domain = runCatching { File("/proc/self/attr/current").readText().trimEnd('\u0000', '\n') }.getOrDefault("")
        val mtu = profileMtu.takeIf { it in 576..TunVpnService.NATIVE_MTU } ?: TunVpnService.NATIVE_MTU
        return buildJsonObject {
            put("name", if (context.packageName.endsWith(".dev")) "birdsocksd0" else "birdsocks0")
            put("mtu", mtu)
            put("addrs", JsonArray(listOfNotNull(self.first, self.second).map { JsonPrimitive(it) }))
            put("routes", JsonArray((routes.v4 + routes.v6).map { JsonPrimitive(it) }))
            put("default4", routes.defaultV4)
            put("default6", routes.defaultV6)
            put("exclude_uids", JsonArray((ownUids + excludedUids).distinct().map { JsonPrimitive(it) }))
            put("exclude_cidrs", JsonArray(cidrs.map { JsonPrimitive(it) }))
            put("app_uid", android.os.Process.myUid())
            put("app_domain", domain)
            put("table", TABLE)
            put("pref", PREF)
            put("dns", TunRoutes.DNS_IP)
            put("socket", File(context.filesDir, "vpn.sock").absolutePath)
        }.toString()
    }
}
