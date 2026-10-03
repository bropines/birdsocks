package io.github.bropines.birdsocks.core

import android.content.Context
import appctr.Appctr
import appctr.StreamHandler
import appctr.Subscription
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.models.NbLoginResponse
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbNetworks
import io.github.bropines.birdsocks.models.NbActiveProfile
import io.github.bropines.birdsocks.models.NbProfile
import io.github.bropines.birdsocks.models.NbProfiles
import io.github.bropines.birdsocks.models.NbStatus
import io.github.bropines.birdsocks.models.NbWaitSsoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * The NetBird daemon's API, as the app uses it. Every call goes through the
 * bridge's generic Call (appctr/rpc.go): a daemon.proto method by name, its
 * request and response as protobuf JSON. Calls block on the daemon, so they
 * run on Dispatchers.IO; a failure throws with the daemon's own message.
 */
object Netbird {
    /**
     * The user the daemon files profiles under: the USER it is started with
     * (appctr/core.go). The default profile needs none; every other one lives
     * in files/netbird/<user>/.
     */
    private const val USER = "birdsocks"
    private const val DEFAULT_PROFILE = "default"

    private fun userFor(profile: String) = if (profile == DEFAULT_PROFILE) "" else USER

    suspend fun call(method: String, request: String = "", timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) { Appctr.call(method, request, timeoutMs) }

    private suspend inline fun <reified T> callAs(method: String, request: String = "", timeoutMs: Long = 15_000): T =
        AppJson.decodeFromString(call(method, request, timeoutMs))

    suspend fun status(): NbStatus = callAs("Status", """{"getFullPeerStatus":true}""", 5_000)

    /**
     * Registers this device: with [setupKey] it is done when this returns;
     * without one the answer asks for an SSO login — open its URL, then [waitSso].
     * [managementUrl] switches the profile to that server first.
     */
    suspend fun login(setupKey: String?, managementUrl: String?, hostname: String): NbLoginResponse =
        callAs("Login", buildJsonObject {
            if (!setupKey.isNullOrBlank()) put("setupKey", setupKey.trim())
            if (!managementUrl.isNullOrBlank()) put("managementUrl", managementUrl.trim())
            put("hostname", hostname)
            // PKCE, the way desktop clients and NetBird's own app sign in: the
            // browser comes back to the daemon on localhost. Without it a Linux
            // daemon uses the device code flow, whose polling breaks behind
            // reverse proxies that rewrite 4xx bodies; the daemon still falls
            // back to it when the server offers no PKCE.
            put("isUnixDesktopClient", true)
        }.toString(), 60_000)

    /** Blocks until the user finishes signing in in the browser; the daemon gives up after its own timeout. */
    suspend fun waitSso(userCode: String, hostname: String): NbWaitSsoResponse =
        callAs("WaitSSOLogin", buildJsonObject {
            put("userCode", userCode)
            put("hostname", hostname)
        }.toString(), 20 * 60_000)

    /** Connects; async, so it returns before the engine is up — the status stream tells the rest. */
    suspend fun up() { call("Up", """{"async":true}""", 30_000) }

    suspend fun down() { call("Down", "", 20_000) }

    /** Deregisters this peer on the server and forgets its keys. */
    suspend fun logout() { call("Logout", "", 20_000) }

    suspend fun networks(): List<NbNetwork> = callAs<NbNetworks>("ListNetworks").routes

    /** Selects networks; for an exit node the daemon drops every other exit node itself. */
    suspend fun selectNetworks(ids: List<String>, append: Boolean = true) {
        call("SelectNetworks", buildJsonObject {
            putJsonArray("networkIDs") { ids.forEach { add(it) } }
            put("append", append)
        }.toString())
    }

    suspend fun deselectNetworks(ids: List<String>) {
        call("DeselectNetworks", buildJsonObject {
            putJsonArray("networkIDs") { ids.forEach { add(it) } }
        }.toString())
    }

    /** The active profile's settings, or [profile]'s. */
    suspend fun config(profile: String? = null): NbConfig {
        val name = profile ?: activeProfile()
        return callAs("GetConfig", buildJsonObject {
            put("profileName", name)
            put("username", userFor(name))
        }.toString())
    }

    /** Changes the active profile's settings; they apply on the next connect (see [reconnect]). */
    suspend fun setConfig(fields: JsonObjectBuilder.() -> Unit) {
        val name = activeProfile()
        call("SetConfig", buildJsonObject {
            put("profileName", name)
            put("username", userFor(name))
            fields()
        }.toString())
    }

    // --- Profiles: one per account ---

    suspend fun profiles(): List<NbProfile> =
        callAs<NbProfiles>("ListProfiles", buildJsonObject { put("username", USER) }.toString()).profiles

    suspend fun activeProfile(): String =
        callAs<NbActiveProfile>("GetActiveProfile").profileName.ifEmpty { DEFAULT_PROFILE }

    suspend fun addProfile(name: String) {
        call("AddProfile", buildJsonObject {
            put("username", USER)
            put("profileName", name.trim())
        }.toString())
    }

    /** Makes [name] the active profile and connects it; one not logged in yet asks for a login. */
    suspend fun switchProfile(name: String) {
        runCatching { down() }
        call("SwitchProfile", buildJsonObject {
            put("profileName", name)
            put("username", userFor(name))
        }.toString())
        NetbirdState.profileFlow.value = name
        up()
    }

    /** Removes [name], its keys and settings; never the active or the default one. */
    suspend fun removeProfile(name: String) {
        call("RemoveProfile", buildJsonObject {
            put("username", USER)
            put("profileName", name)
        }.toString())
    }

    suspend fun renameProfile(name: String, newName: String) {
        call("RenameProfile", buildJsonObject {
            put("username", USER)
            put("handle", name)
            put("newProfileName", newName.trim())
        }.toString())
    }

    /** Down and Up: what a changed setting needs to take effect. */
    suspend fun reconnect() {
        runCatching { down() }
        up()
    }

    /** Opens a server stream; [onMessage] and [onEnd] run on the bridge's goroutine thread. */
    fun subscribe(method: String, request: String, onMessage: (String) -> Unit, onEnd: (String) -> Unit): Subscription =
        Appctr.subscribe(method, request, object : StreamHandler {
            override fun onMessage(json: String) = onMessage(json)
            override fun onEnd(err: String) = onEnd(err)
        })
}

/**
 * What the app knows about the daemon right now, for every screen and the
 * notification. The service writes it; everything else reads.
 */
object NetbirdState {
    enum class Daemon { Stopped, Starting, Running, Stopping }

    internal val daemonFlow = MutableStateFlow(Daemon.Stopped)
    val daemon: StateFlow<Daemon> = daemonFlow.asStateFlow()

    internal val statusFlow = MutableStateFlow<NbStatus?>(null)
    /** The last status the daemon streamed, null while it is not running. */
    val status: StateFlow<NbStatus?> = statusFlow.asStateFlow()

    internal val profileFlow = MutableStateFlow<String?>(null)
    /** The active NetBird profile's name, null until the daemon has said. */
    val profile: StateFlow<String?> = profileFlow.asStateFlow()

    internal val errorFlow = MutableStateFlow<String?>(null)
    /** Why the daemon is not running when it should be: a failed start, a crash. */
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    fun dismissError() { errorFlow.value = null }

    val isRunning: Boolean get() = daemonFlow.value == Daemon.Running
}

/**
 * Signing in, run in the app's scope so that leaving the screen, or the
 * browser taking the foreground, does not cancel the wait for it.
 */
object LoginFlow {
    sealed interface State {
        data object Idle : State
        data object Working : State
        /** The browser has to finish it: [url] opens the page; [userCode], empty for PKCE, is what a device-code page asks for. */
        data class Browser(val url: String, val userCode: String) : State
        data class Failed(val message: String) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    fun reset() { stateFlow.value = State.Idle }

    /**
     * Logs in against [managementUrl] (null keeps the profile's) with [setupKey], or
     * through the browser when there is none, then connects.
     */
    fun start(context: Context, managementUrl: String?, setupKey: String?) {
        val app = context.applicationContext
        if (stateFlow.value is State.Working || stateFlow.value is State.Browser) return
        stateFlow.value = State.Working
        BirdSocksApp.scope.launchIO {
            try {
                if (!NetbirdService.awaitRunning(app)) {
                    stateFlow.value = State.Failed(NetbirdState.error.value ?: "The NetBird daemon did not start")
                    return@launchIO
                }
                val hostname = GlobalSettings.getDeviceName(app)
                val answer = Netbird.login(setupKey, managementUrl, hostname)
                if (answer.needsSSOLogin) {
                    stateFlow.value = State.Browser(answer.verificationURIComplete.ifEmpty { answer.verificationURI }, answer.userCode)
                    Netbird.waitSso(answer.userCode, hostname)
                }
                Netbird.up()
                stateFlow.value = State.Idle
            } catch (e: Exception) {
                stateFlow.value = State.Failed(e.message ?: e.toString())
            }
        }
    }
}
