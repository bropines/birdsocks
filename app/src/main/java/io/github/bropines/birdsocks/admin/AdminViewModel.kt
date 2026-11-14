package io.github.bropines.birdsocks.admin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiAccount
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.ApiCreateSetupKey
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerRequest
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.secure.AdminWriteGate
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.admin.secure.SecretUnavailableException
import io.github.bropines.birdsocks.admin.secure.UnlockFailure
import io.github.bropines.birdsocks.admin.secure.UnlockResult
import io.github.bropines.birdsocks.core.NetbirdState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The panel's state for the active account. Lists load in parallel and fail
 * apart: a 403 on setup keys hides that tab and leaves peers on screen.
 */
class AdminViewModel(app: Application) : AndroidViewModel(app) {
    enum class Phase { LOADING, NO_DAEMON, BAD_URL, SETUP, READY }
    enum class AuthMode { TOKEN, DEVICE }

    /** Something to tell the user once: a resource with an argument, or an error to describe. */
    data class Note(val res: Int, val arg: String? = null, val error: Throwable? = null)

    sealed interface DeviceSignIn {
        data object Idle : DeviceSignIn
        data object Working : DeviceSignIn
        data class Browser(val url: String) : DeviceSignIn
        data class Failed(val error: Throwable) : DeviceSignIn
    }

    private val context get() = getApplication<Application>()
    private val vault get() = AdminSession.vault(context)

    var phase by mutableStateOf(Phase.LOADING); private set
    var account by mutableStateOf<AdminAccount?>(null); private set
    var authMode by mutableStateOf<AuthMode?>(null); private set
    var user by mutableStateOf<ApiUser?>(null); private set
    var caps by mutableStateOf<Capabilities?>(null); private set
    var readOnly by mutableStateOf(true); private set
    var hasToken by mutableStateOf(false); private set
    /** Why the setup screen is showing when it was not asked for: a rejected token, an unreadable one. */
    var setupReason by mutableStateOf<Note?>(null); private set
    var deviceSignIn by mutableStateOf<DeviceSignIn>(DeviceSignIn.Idle); private set

    var accountInfo by mutableStateOf<ApiAccount?>(null); private set
    var peers by mutableStateOf<Listing<ApiPeer>?>(null); private set
    var keys by mutableStateOf<Listing<ApiSetupKey>?>(null); private set
    var groups by mutableStateOf<Listing<ApiGroup>?>(null); private set
    /** Per list, the error that kept it from loading. */
    var listErrors by mutableStateOf<Map<String, Throwable>>(emptyMap()); private set
    var refreshing by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var note by mutableStateOf<Note?>(null)

    private var api: NetbirdApi? = null
    private var loadJob: Job? = null

    val lockState: LockState get() = AdminWriteGate.lockState(context)

    /** Whether a change may be offered at all; each one also checks its own permission. */
    val writesAllowed: Boolean
        get() = !readOnly && lockState != LockState.NO_SCREEN_LOCK && caps?.readOnly == false

    fun can(module: String, op: Op): Boolean =
        caps?.can(module, op) == true && (op == Op.READ || writesAllowed)

    /** This device's peer in the API's list: never deleted from here. */
    fun isSelf(peer: ApiPeer): Boolean {
        val local = NetbirdState.status.value?.fullStatus?.localPeerState ?: return false
        if (local.address.isNotEmpty() && peer.ip == local.address) return true
        return peer.dnsLabel.isNotEmpty() && local.fqdn.substringBefore('.').equals(peer.dnsLabel.substringBefore('.'), ignoreCase = true)
    }

    // --- Arriving ---

    fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            phase = Phase.LOADING
            if (!NetbirdState.isRunning) { phase = Phase.NO_DAEMON; return@launch }
            val acc = runCatching { AdminSession.activeAccount(context) }.getOrElse { phase = Phase.NO_DAEMON; return@launch }
            account = acc
            if (acc.apiBase == null) { phase = Phase.BAD_URL; return@launch }
            readOnly = vault.isReadOnly(acc.id)
            hasToken = vault.hasToken(acc.id)
            connect(acc)
        }
    }

    /** Signs in with what there is: this device's token in memory first, then the stored one, else setup. */
    private suspend fun connect(acc: AdminAccount) {
        val jwt = AdminSession.jwt(acc.id)
        val auth: ApiAuth = when {
            jwt != null -> { authMode = AuthMode.DEVICE; ApiAuth.Bearer(jwt) }
            hasToken -> {
                val token = try {
                    vault.token(acc.id)
                } catch (e: SecretUnavailableException) {
                    setupReason = Note(R.string.admin_setup_reason_unreadable)
                    phase = Phase.SETUP
                    return
                } ?: run { phase = Phase.SETUP; return }
                authMode = AuthMode.TOKEN
                ApiAuth.Token(token)
            }
            else -> { phase = Phase.SETUP; return }
        }
        val client = AdminSession.api(context, acc, auth) ?: run { phase = Phase.BAD_URL; return }
        phase = Phase.LOADING
        try {
            val me = client.currentUser()
            api = client
            user = me
            caps = Capabilities.of(me)
            setupReason = null
            phase = Phase.READY
            refreshAll()
        } catch (e: AdminApiException.Unauthorized) {
            onUnauthorized(acc)
        } catch (e: Exception) {
            // The server could not be asked: the panel shows why, the credential stays.
            api = client
            setupReason = Note(R.string.admin_setup_reason_failed, error = e)
            phase = Phase.SETUP
        }
    }

    /** A 401: this device's token is dropped for the stored one; a rejected stored token sends to setup. */
    private suspend fun onUnauthorized(acc: AdminAccount) {
        if (authMode == AuthMode.DEVICE) {
            AdminSession.setJwt(acc.id, null)
            if (hasToken) { connect(acc); return }
            setupReason = Note(R.string.admin_setup_reason_device_rejected)
        } else {
            setupReason = Note(R.string.admin_setup_reason_rejected)
        }
        api = null
        phase = Phase.SETUP
    }

    fun refreshAll() {
        val client = api ?: return
        val acc = account ?: return
        viewModelScope.launch {
            refreshing = true
            val errors = mutableMapOf<String, Throwable>()
            suspend fun <T> load(module: String, block: suspend () -> T): T? = try {
                block()
            } catch (e: AdminApiException.Forbidden) {
                caps = caps?.denied(module, Op.READ); null
            } catch (e: AdminApiException.Unauthorized) {
                errors[module] = e; null
            } catch (e: Exception) {
                errors[module] = e; null
            }
            val c = caps
            val p = async { if (c?.can(Capabilities.PEERS, Op.READ) != false) load(Capabilities.PEERS) { client.peers() } else null }
            val k = async { if (c?.can(Capabilities.SETUP_KEYS, Op.READ) != false) load(Capabilities.SETUP_KEYS) { client.setupKeys() } else null }
            val g = async { if (c?.can(Capabilities.GROUPS, Op.READ) != false) load(Capabilities.GROUPS) { client.groups() } else null }
            val a = async { if (c?.can(Capabilities.ACCOUNTS, Op.READ) != false) load(Capabilities.ACCOUNTS) { client.accounts() } else null }
            peers = p.await()
            keys = k.await()
            groups = g.await()
            accountInfo = a.await()?.items?.firstOrNull()
            listErrors = errors
            refreshing = false
            if (errors.values.any { it is AdminApiException.Unauthorized }) onUnauthorized(acc)
        }
    }

    // --- Setup ---

    /** Asks GET /api/users/current with [token] without storing it. */
    suspend fun testToken(token: String): Result<ApiUser> {
        val acc = account ?: return Result.failure(IllegalStateException("no account"))
        val client = AdminSession.api(context, acc, ApiAuth.Token(token.trim())) ?: return Result.failure(IllegalStateException("no API"))
        return runCatching { client.currentUser() }
    }

    fun saveToken(token: String, readOnlyChoice: Boolean) {
        val acc = account ?: return
        if (!vault.putToken(acc.id, token)) { note = Note(R.string.admin_token_not_saved); return }
        vault.setReadOnly(acc.id, readOnlyChoice)
        readOnly = readOnlyChoice
        hasToken = true
        AdminSession.setJwt(acc.id, null)
        viewModelScope.launch { connect(acc) }
    }

    fun forgetToken() {
        val acc = account ?: return
        vault.forget(acc.id)
        AdminSession.setJwt(acc.id, null)
        hasToken = false
        readOnly = true
        api = null
        user = null
        caps = null
        peers = null; keys = null; groups = null; accountInfo = null
        setupReason = null
        phase = Phase.SETUP
    }

    fun changeReadOnly(value: Boolean) {
        val acc = account ?: return
        vault.setReadOnly(acc.id, value)
        readOnly = value
    }

    /** "Use this device's sign-in": experimental; the token lives in memory until a 401. */
    fun startDeviceSignIn() {
        val acc = account ?: return
        if (deviceSignIn is DeviceSignIn.Working || deviceSignIn is DeviceSignIn.Browser) return
        deviceSignIn = DeviceSignIn.Working
        viewModelScope.launch {
            try {
                val token = AdminSession.deviceSignIn { url -> deviceSignIn = DeviceSignIn.Browser(url) }
                AdminSession.setJwt(acc.id, token)
                deviceSignIn = DeviceSignIn.Idle
                connect(acc)
            } catch (e: Exception) {
                deviceSignIn = DeviceSignIn.Failed(e)
            }
        }
    }

    fun signOutDevice() {
        val acc = account ?: return
        AdminSession.setJwt(acc.id, null)
        start()
    }

    // --- Changes ---

    /**
     * One change: refused when the panel is read-only or the role may not,
     * then the screen lock, then the call and a refresh. A 403 narrows what
     * the panel offers from then on.
     */
    private fun change(activity: FragmentActivity, module: String, op: Op, label: String, done: Note?, block: suspend (NetbirdApi) -> Unit) {
        val client = api ?: return
        if (!can(module, op)) { note = Note(R.string.admin_write_refused); return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                when (val r = AdminWriteGate.unlock(activity, activity.getString(R.string.admin_unlock_title), label)) {
                    UnlockResult.Granted -> {
                        block(client)
                        done?.let { note = it }
                        refreshAll()
                    }
                    UnlockResult.Cancelled -> Unit
                    is UnlockResult.Failed -> note = Note(
                        when (r.reason) {
                            UnlockFailure.NO_SCREEN_LOCK -> R.string.admin_unlock_no_lock
                            UnlockFailure.LOCKOUT -> R.string.admin_unlock_lockout
                            else -> R.string.admin_unlock_failed
                        }
                    )
                }
            } catch (e: AdminApiException.Forbidden) {
                caps = caps?.denied(module, op)
                note = Note(R.string.admin_error_generic, error = e)
            } catch (e: AdminApiException.Unauthorized) {
                account?.let { onUnauthorized(it) }
            } catch (e: Exception) {
                note = Note(R.string.admin_error_generic, error = e)
            } finally {
                busy = false
            }
        }
    }

    fun renamePeer(activity: FragmentActivity, peer: ApiPeer, name: String) =
        change(activity, Capabilities.PEERS, Op.UPDATE, activity.getString(R.string.admin_peer_rename_unlock, peer.name), Note(R.string.admin_done_saved)) {
            it.updatePeer(peer.id, ApiPeerRequest.of(peer).copy(name = name.trim()))
        }

    fun setLoginExpiration(activity: FragmentActivity, peer: ApiPeer, enabled: Boolean) =
        change(activity, Capabilities.PEERS, Op.UPDATE, activity.getString(R.string.admin_peer_expiry_unlock, peer.name), Note(R.string.admin_done_saved)) {
            it.updatePeer(peer.id, ApiPeerRequest.of(peer).copy(loginExpirationEnabled = enabled))
        }

    fun deletePeer(activity: FragmentActivity, peer: ApiPeer) {
        if (isSelf(peer)) { note = Note(R.string.admin_peer_self_guard); return }
        change(activity, Capabilities.PEERS, Op.DELETE, activity.getString(R.string.admin_peer_delete_unlock, peer.name), Note(R.string.admin_done_deleted, peer.name)) {
            it.deletePeer(peer.id)
        }
    }

    /** The created key goes to [onCreated] once; it is never stored. */
    fun createKey(activity: FragmentActivity, request: ApiCreateSetupKey, onCreated: (ApiSetupKey) -> Unit) =
        change(activity, Capabilities.SETUP_KEYS, Op.CREATE, activity.getString(R.string.admin_key_create_unlock, request.name), null) {
            onCreated(it.createSetupKey(request))
        }

    fun revokeKey(activity: FragmentActivity, key: ApiSetupKey) =
        change(activity, Capabilities.SETUP_KEYS, Op.UPDATE, activity.getString(R.string.admin_key_revoke_unlock, key.name), Note(R.string.admin_done_revoked, key.name)) {
            it.revokeSetupKey(key)
        }
}
