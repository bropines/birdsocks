package io.github.bropines.birdsocks.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import appctr.Appctr
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.models.NbConnState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.ui.cardStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What other apps may make BirdSocks do, and the one place that does it: the
 * broadcasts of [AutomationReceiver] (Tasker, MacroDroid, adb) and the
 * `birdsocks://` action links (ui/DeepLinks, confirmed by the user unless they
 * carry the token) both end in [run].
 *
 * - **Nothing without the token.** The receiver is exported with no
 *   permission, so any app can reach it: with the switch off or no token set
 *   every request is refused, and the token is compared in constant time.
 * - **Every value from outside is allow-listed** ([commandOf]): a closed set
 *   of actions, modes and on/off words; names are length-capped and free of
 *   control characters, and match an existing exit node or profile or nothing.
 *   ByeDPI flags go through [ByeDpiFlags] where ByeDPI starts.
 * - **STATUS_CHANGED goes to one app, by name.** An authorized GET_STATUS
 *   names it (`reply_to`); then every change goes to that package and to no
 *   other, until the switch goes off or the token changes. An implicit
 *   broadcast would tell every installed app whether and where this device
 *   connects; one kept inside BirdSocks would reach nobody.
 */
object Automation {
    private const val TAG = "Automation"

    /** The same for the debug build: its package differs, its actions do not. */
    const val ACTION_PREFIX = "io.github.bropines.birdsocks.action."
    const val ACTION_STATUS_CHANGED = ACTION_PREFIX + "STATUS_CHANGED"

    const val KEY_ENABLED = "automation_enabled"
    const val KEY_TOKEN = "automation_token"
    private const val KEY_STATUS_TO = "automation_status_to"

    /** A shorter token counts as none: a few characters would be guessed by trying. */
    const val MIN_TOKEN_LENGTH = 16

    sealed interface Command {
        data object Connect : Command
        /** Like a manual stop: nothing brings it back until something starts it. */
        data object Disconnect : Command
        data object Toggle : Command
        data object Restart : Command
        /** [replyTo]: the package STATUS_CHANGED goes to from now on. */
        data class Status(val replyTo: String? = null) : Command
        /** [target]: an exit node's name, or its peer's name, FQDN or NetBird IP; null turns it off. */
        data class ExitNode(val target: String?) : Command
        data class Account(val name: String) : Command
        data class Tun(val on: Boolean) : Command
        /** [mode]: one of GlobalSettings.CONTROL_*; [byeDpiFlags] only with ByeDPI. */
        data class ServerConnection(val mode: String, val byeDpiFlags: String?) : Command
    }

    /** What came of a command: [message] for the log and an ordered broadcast's result. */
    class Outcome(val ok: Boolean, val message: String, val extras: Bundle? = null)

    // --- Settings ---

    fun isEnabled(context: Context): Boolean = GlobalSettings.getBoolean(context, KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        GlobalSettings.setBoolean(context, KEY_ENABLED, on)
        if (!on) setStatusReceiver(context, "")
    }

    fun token(context: Context): String = GlobalSettings.getString(context, KEY_TOKEN, "")

    /** A new token forgets the app status went to: it was let in by the old one. */
    fun setToken(context: Context, token: String) {
        val value = token.trim()
        if (value == token(context)) return
        GlobalSettings.setString(context, KEY_TOKEN, value)
        setStatusReceiver(context, "")
    }

    fun isTokenUsable(token: String): Boolean = token.length >= MIN_TOKEN_LENGTH

    /** 32 characters without the look-alikes (0/O, 1/l/I), so it survives being retyped. */
    fun generateToken(length: Int = 32): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    /** The package STATUS_CHANGED goes to, "" for none. */
    fun statusReceiver(context: Context): String = GlobalSettings.getString(context, KEY_STATUS_TO, "")

    fun setStatusReceiver(context: Context, pkg: String) {
        GlobalSettings.setString(context, KEY_STATUS_TO, pkg)
        if (pkg.isNotEmpty()) watch(context)
    }

    // --- Who may ---

    enum class Refusal { DISABLED, NO_TOKEN, BAD_TOKEN }

    /** Null when [provided] lets a request in. */
    fun refusal(context: Context, provided: String?): Refusal? {
        if (!isEnabled(context)) return Refusal.DISABLED
        val token = token(context)
        if (!isTokenUsable(token)) return Refusal.NO_TOKEN
        return if (sameSecret(provided.orEmpty(), token)) null else Refusal.BAD_TOKEN
    }

    /**
     * Constant time whatever the inputs: both sides are hashed to 32 bytes
     * first, so neither the length nor a matching prefix shows in the timing.
     */
    private fun sameSecret(a: String, b: String): Boolean {
        val x = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(Charsets.UTF_8))
        val y = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8))
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    // --- Reading requests: everything from outside is checked here ---

    /** The command a broadcast asks for, or null when its action or extras are not ones we take. */
    fun commandOf(intent: Intent): Command? {
        val action = intent.action ?: return null
        if (!action.startsWith(ACTION_PREFIX)) return null
        return when (action.removePrefix(ACTION_PREFIX)) {
            "CONNECT" -> Command.Connect
            "DISCONNECT" -> Command.Disconnect
            "TOGGLE" -> Command.Toggle
            "RESTART" -> Command.Restart
            "GET_STATUS" -> intent.getStringExtra("reply_to").let { raw -> if (raw == null) Command.Status() else packageName(raw)?.let { Command.Status(it) } }
            "SET_EXIT_NODE" -> exitNode(intent.getStringExtra("peer") ?: intent.getStringExtra("exit_node"))
            "SWITCH_ACCOUNT" -> account(intent.getStringExtra("name") ?: intent.getStringExtra("account"))
            "SET_TUN" -> tun(extra(intent, "on") ?: extra(intent, "enabled"))
            "SET_SERVER_CONNECTION" -> serverConnection(intent.getStringExtra("mode"), intent.getStringExtra("flags"))
            else -> null
        }
    }

    /** "none" or "off" turns the exit node off; anything else names one. */
    fun exitNode(raw: String?): Command.ExitNode? {
        val v = clean(raw, 255) ?: return null
        return Command.ExitNode(if (v.lowercase() in setOf("none", "off")) null else v)
    }

    fun account(raw: String?): Command.Account? = clean(raw, 128)?.let { Command.Account(it) }

    /** true/false, on/off, yes/no, 1/0, as a boolean, a number or a string. */
    fun tun(raw: Any?): Command.Tun? = when (raw) {
        is Boolean -> Command.Tun(raw)
        is Number -> Command.Tun(raw.toLong() != 0L)
        is String -> when (raw.trim().lowercase()) {
            "true", "on", "yes", "1" -> Command.Tun(true)
            "false", "off", "no", "0" -> Command.Tun(false)
            else -> null
        }
        else -> null
    }

    private fun serverConnection(rawMode: String?, rawFlags: String?): Command.ServerConnection? {
        val mode = rawMode?.trim()?.lowercase()
            ?.takeIf { it in setOf(GlobalSettings.CONTROL_DIRECT, GlobalSettings.CONTROL_PROXY, GlobalSettings.CONTROL_BYEDPI) }
            ?: return null
        if (rawFlags == null || mode != GlobalSettings.CONTROL_BYEDPI) return Command.ServerConnection(mode, null)
        // The flags themselves are allow-listed where ByeDPI starts (ByeDpiFlags).
        return clean(rawFlags, 512)?.let { Command.ServerConnection(mode, it) }
    }

    /** Trimmed, non-empty, at most [max] characters and no control characters; null otherwise. */
    fun clean(raw: String?, max: Int): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c.isISOControl() } }

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private fun packageName(raw: String?): String? = raw?.trim()?.takeIf { it.length <= 255 && PACKAGE_NAME.matches(it) }

    @Suppress("DEPRECATION") // Bundle.get: the type is the sender's choice, every one is handled
    private fun extra(intent: Intent, key: String): Any? = intent.extras?.get(key)

    // --- Doing it ---

    /** Runs [command] with the app's real code paths; returns once it is done or under way. */
    suspend fun run(context: Context, command: Command): Outcome = try {
        when (command) {
            Command.Connect -> connect(context)
            Command.Disconnect -> disconnect(context)
            Command.Toggle -> if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) connect(context) else disconnect(context)
            Command.Restart -> restart(context)
            is Command.Status -> status(context, command.replyTo)
            is Command.ExitNode -> setExitNode(context, command.target)
            is Command.Account -> switchAccount(context, command.name)
            is Command.Tun -> setTun(context, command.on)
            is Command.ServerConnection -> setServerConnection(context, command)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, e.message ?: e.toString())
    }

    /**
     * For a screen: runs [command] in the app's scope, says a failure in a
     * toast, and asks for the VPN permission when the VPN will need it — an
     * activity may open that dialog, a broadcast may not.
     */
    fun runFromActivity(activity: Activity, command: Command) {
        val app = activity.applicationContext
        // Read before running: the start below changes the state at once.
        val starts = command == Command.Connect || (command is Command.Tun && command.on) ||
            (command == Command.Toggle && NetbirdState.daemon.value == NetbirdState.Daemon.Stopped)
        // Main.immediate: up to its first suspension it runs here, while the
        // activity is in front — where Android lets a foreground service start.
        BirdSocksApp.scope.launch {
            val outcome = run(app, command)
            log(if (outcome.ok) "INFO" else "WARN", "${describe(command)} (app): ${outcome.message}")
            if (!outcome.ok) Toast.makeText(app, app.getString(R.string.automation_failed, outcome.message), Toast.LENGTH_LONG).show()
        }
        if (starts) askVpnConsent(activity)
    }

    /** VPN mode on and no permission yet: Android's dialog, through the see-through trampoline. */
    fun askVpnConsent(activity: Activity) {
        if (!GlobalSettings.isTunModeEnabled(activity) || !TunVpnService.nativeLoaded) return
        if (VpnService.prepare(activity) != null) activity.startActivity(Intent(activity, TunPermissionActivity::class.java))
    }

    private suspend fun connect(context: Context): Outcome {
        // A stop under way first finishes: a start in the middle of it is lost.
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopping) {
            withTimeoutOrNull(15_000) { NetbirdState.daemon.first { it == NetbirdState.Daemon.Stopped } }
        }
        return when (NetbirdState.daemon.value) {
            NetbirdState.Daemon.Stopped -> startService(context) { NetbirdService.start(context) } ?: Outcome(true, "starting")
            NetbirdState.Daemon.Running -> when (NetbirdState.status.value?.state) {
                NbConnState.Connected -> Outcome(true, "connected already")
                // Running but down (disconnected by hand): connecting is Up.
                NbConnState.Idle -> { Netbird.up(); Outcome(true, "connecting") }
                NbConnState.NeedsLogin, NbConnState.LoginFailed, NbConnState.SessionExpired -> Outcome(false, "sign-in needed: open BirdSocks")
                else -> Outcome(true, "connecting")
            }
            else -> Outcome(true, "starting")
        }
    }

    private fun disconnect(context: Context): Outcome {
        val stopped = NetbirdState.daemon.value == NetbirdState.Daemon.Stopped
        NetbirdService.stop(context)
        return Outcome(true, if (stopped) "stopped already" else "stopping")
    }

    private fun restart(context: Context): Outcome {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) {
            return startService(context) { NetbirdService.start(context) } ?: Outcome(true, "starting")
        }
        // The service already runs in the foreground: a plain start reaches it
        // from the background, where a foreground-service start may be refused.
        return startService(context) {
            context.startService(Intent(context, NetbirdService::class.java).setAction(NetbirdService.ACTION_RESTART))
        } ?: Outcome(true, "restarting")
    }

    /**
     * Starts the service; null when it went, the refusal otherwise. Android 12+
     * refuses a foreground service started from the background unless the app
     * is exempt from battery optimization; the main screen then says so.
     */
    private fun startService(context: Context, start: () -> Unit): Outcome? {
        val daemonBefore = NetbirdState.daemon.value
        val wantedBefore = GlobalSettings.wasRunning(context)
        return try {
            start()
            null
        } catch (e: Exception) {
            Log.w(TAG, "start refused", e)
            if (daemonBefore == NetbirdState.Daemon.Stopped) NetbirdState.daemonFlow.value = NetbirdState.Daemon.Stopped
            GlobalSettings.setWasRunning(context, wantedBefore)
            NetbirdState.errorFlow.value = context.getString(R.string.automation_start_refused)
            Outcome(false, "Android refused to start BirdSocks from the background (${e.javaClass.simpleName}); exempt it from battery optimization")
        }
    }

    private suspend fun status(context: Context, replyTo: String?): Outcome {
        if (replyTo != null) {
            if (isInstalled(context, replyTo)) setStatusReceiver(context, replyTo)
            else log("WARN", "reply_to $replyTo is not an installed app")
        }
        val extras = statusExtras(context)
        statusReceiver(context).takeIf { it.isNotEmpty() }?.let { send(context, it, extras) }
        return Outcome(true, extras.getString("state").orEmpty(), extras)
    }

    /** Waits up to [timeoutMs] for the daemon to report Connected. */
    private suspend fun awaitConnected(timeoutMs: Long): Boolean {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) return false
        return withTimeoutOrNull(timeoutMs) {
            NetbirdState.status.first { it?.state == NbConnState.Connected || NetbirdState.daemon.value == NetbirdState.Daemon.Stopped }
        }?.state == NbConnState.Connected
    }

    private suspend fun setExitNode(context: Context, target: String?): Outcome {
        // Sent right after CONNECT, it waits for the connection rather than failing.
        if (!awaitConnected(20_000)) return Outcome(false, "not connected")
        // Exit nodes arrive with the network map, a moment after Connected.
        var exits: List<NbNetwork> = emptyList()
        var match: NbNetwork? = null
        for (attempt in 0 until 10) {
            exits = runCatching { Netbird.networks() }.getOrDefault(emptyList()).filter { it.isExitNode }
            if (target == null) break
            match = findExitNode(exits, target, NetbirdState.status.value)
            if (match != null) break
            delay(1000)
        }
        if (target == null) {
            val selected = exits.filter { it.selected }
            if (selected.isNotEmpty()) Netbird.deselectNetworks(selected.map { it.id })
            return Outcome(true, "exit node off")
        }
        val found = match ?: return Outcome(
            false,
            "no exit node \"$target\"" + if (exits.isEmpty()) "; none is offered" else "; offered: ${exits.joinToString { it.id }}"
        )
        // For an exit node the daemon drops the one selected before.
        if (!found.selected) Netbird.selectNetworks(listOf(found.id))
        return Outcome(true, "exit node ${found.id}")
    }

    /**
     * The exit node [target] names: an exit node's own name first, then a
     * peer's (name, FQDN, NetBird IP). ListNetworks says nothing of routing
     * peers and a peer lists only the routes chosen through it, so an unused
     * exit node is found by its peer only when its name carries the peer's
     * (exit-laptop for laptop).
     */
    internal fun findExitNode(exits: List<NbNetwork>, target: String, status: NbStatus?): NbNetwork? {
        val t = target.trim().trimEnd('.')
        exits.firstOrNull { it.id.equals(t, ignoreCase = true) }?.let { return it }
        val peer = status?.fullStatus?.peers?.firstOrNull { p ->
            p.fqdn.trimEnd('.').equals(t, ignoreCase = true) || p.shortName.equals(t, ignoreCase = true) ||
                p.address == t || (p.ipv6Address.isNotEmpty() && p.ipv6Address.equals(t, ignoreCase = true))
        } ?: return null
        exits.firstOrNull { it.selected && it.routedBy(peer) }?.let { return it }
        val name = peer.shortName.lowercase()
        return exits.filter { name in it.id.lowercase() }.singleOrNull()
    }

    /** Profiles live in the daemon: a stopped BirdSocks is started to switch. */
    private suspend fun switchAccount(context: Context, name: String): Outcome {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) {
            startService(context) { NetbirdService.start(context) }?.let { return it }
        }
        if (!NetbirdService.awaitRunning(context)) return Outcome(false, "BirdSocks did not start")
        val profiles = Netbird.profiles()
        val profile = profiles.firstOrNull { it.name == name } ?: profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return Outcome(false, "no account \"$name\"; there are: ${profiles.joinToString { it.name }}")
        if (profile.name == Netbird.activeProfile()) return Outcome(true, "account ${profile.name} already")
        Netbird.switchProfile(profile.name)
        return Outcome(true, "account ${profile.name}")
    }

    /** As Settings → Tunnel mode does it: the VPN follows the running daemon. */
    private fun setTun(context: Context, on: Boolean): Outcome {
        if (!on) {
            GlobalSettings.setTunModeEnabled(context, false)
            TunVpnService.stop(waitMs = 0)
            return Outcome(true, "VPN mode off")
        }
        if (!TunVpnService.nativeLoaded) return Outcome(false, "VPN mode is unavailable in this build")
        GlobalSettings.setTunModeEnabled(context, true)
        TunVpnService.clearConsentNotice(context)
        // Without the VPN permission this posts the notification that asks for it.
        TunVpnService.start(context)
        val consent = VpnService.prepare(context) == null
        return Outcome(true, if (consent) "VPN mode on" else "VPN mode on; the VPN permission is asked for in a notification")
    }

    /** A start option: saved, and the daemon restarted when it runs, as the restart banner does. */
    private fun setServerConnection(context: Context, command: Command.ServerConnection): Outcome {
        GlobalSettings.setControlMode(context, command.mode)
        command.byeDpiFlags?.let { GlobalSettings.setByeDpiFlags(context, it) }
        val note = if (command.mode == GlobalSettings.CONTROL_PROXY && GlobalSettings.getControlProxyHost(context).isBlank()) {
            "; no proxy is set in Settings, so it goes direct"
        } else ""
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) return Outcome(true, "server connection ${command.mode}$note")
        return restart(context).takeIf { !it.ok } ?: Outcome(true, "server connection ${command.mode}, restarting$note")
    }

    // --- Status ---

    /** running, state, profile, exit_node, tun and ip, as STATUS_CHANGED carries them. */
    suspend fun statusExtras(context: Context): Bundle {
        val daemon = NetbirdState.daemon.value
        val status = NetbirdState.status.value
        val exit = if (status?.state == NbConnState.Connected) {
            runCatching { Netbird.networks().firstOrNull { it.isExitNode && it.selected }?.id }.getOrNull().orEmpty()
        } else ""
        return Bundle().apply {
            putBoolean("running", daemon == NetbirdState.Daemon.Running)
            putString("state", stateName(daemon, status))
            putString("profile", NetbirdState.profile.value.orEmpty())
            putString("exit_node", exit)
            putBoolean("tun", TunVpnService.running.value)
            putString("ip", status?.fullStatus?.localPeerState?.address.orEmpty())
        }
    }

    /** STOPPED, STARTING, CONNECTING, CONNECTED, NEEDS_LOGIN, IDLE, OFFLINE or STOPPING: the main screen's word. */
    private fun stateName(daemon: NetbirdState.Daemon, status: NbStatus?): String =
        cardStateOf(daemon, status, NetbirdState.network.value).name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    private fun send(context: Context, pkg: String, extras: Bundle) {
        runCatching { context.sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(pkg).putExtras(extras)) }
            .onFailure { Log.w(TAG, "status to $pkg: ${it.message}") }
    }

    @Suppress("DEPRECATION") // the flags overload is API 33
    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private val watching = AtomicBoolean(false)

    /**
     * Sends STATUS_CHANGED whenever the connection, the account, the exit node
     * or the VPN changes, while an app is named to receive it. Started at
     * launch when one is, or once one is named.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        if (statusReceiver(app).isEmpty() || !watching.compareAndSet(false, true)) return
        BirdSocksApp.scope.launch {
            combine(NetbirdState.daemon, NetbirdState.status, NetbirdState.profile, TunVpnService.running) { daemon, status, profile, tun ->
                listOf(stateName(daemon, status), profile, tun, status?.fullStatus?.peers?.firstOrNull { it.isExitNode }?.fqdn)
            }
                .distinctUntilChanged()
                // What is there when watching starts was just sent, or nobody asked yet.
                .drop(1)
                .collectLatest {
                    // A connect passes through several states at once: send where it settles.
                    delay(1000)
                    val to = statusReceiver(app)
                    if (to.isNotEmpty() && isEnabled(app)) send(app, to, statusExtras(app))
                }
        }
    }

    // --- Log ---

    /** What a command is, for the log. */
    fun describe(command: Command): String = when (command) {
        is Command.Status -> "GET_STATUS"
        is Command.ExitNode -> "SET_EXIT_NODE ${command.target ?: "none"}"
        is Command.Account -> "SWITCH_ACCOUNT ${command.name}"
        is Command.Tun -> "SET_TUN ${command.on}"
        is Command.ServerConnection -> "SET_SERVER_CONNECTION ${command.mode}"
        else -> command.toString().uppercase()
    }

    fun log(level: String, message: String) {
        Log.i(TAG, message)
        runCatching { Appctr.logAndroid(level, "CORE", "Automation: $message") }
    }
}
