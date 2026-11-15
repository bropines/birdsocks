package io.github.bropines.birdsocks.admin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.safety.AdminAuditLog
import io.github.bropines.birdsocks.admin.safety.ChangeFlow
import io.github.bropines.birdsocks.admin.safety.ChangeOutcome
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.SafeChangeRunner
import io.github.bropines.birdsocks.admin.safety.SafetyContext
import io.github.bropines.birdsocks.admin.safety.UnlockFailedException
import io.github.bropines.birdsocks.admin.safety.refusalText
import io.github.bropines.birdsocks.admin.safety.unlockFailureText
import io.github.bropines.birdsocks.admin.secure.AdminWriteGate
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.admin.secure.SecretUnavailableException
import io.github.bropines.birdsocks.admin.state.AdminCache
import io.github.bropines.birdsocks.admin.state.AdminData
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.DataKey
import io.github.bropines.birdsocks.admin.state.Loadable
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.NetbirdState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The panel's session for the active account, and the [AdminHost] every
 * area gets. It owns what areas share and nothing area-specific:
 *  - the session: account, credential, current user, [caps];
 *  - [data]: the shared lists ([AdminKeys]), seeded from the sealed cache so
 *    the panel opens at once, refreshed in place;
 *  - the change gates ([flow], the runner, the local audit log);
 *  - navigation ([route]) and notes;
 *  - per-area state holders ([areaState]), so this class does not grow with
 *    every area.
 */
class AdminViewModel(app: Application) : AndroidViewModel(app), AdminHost {
    enum class Phase { LOADING, NO_DAEMON, BAD_URL, SETUP, READY }
    enum class AuthMode { TOKEN, DEVICE }

    /** Why the setup screen shows: a resource, and the error behind it. */
    data class Note(val res: Int, val error: Throwable? = null)

    sealed interface DeviceSignIn {
        data object Idle : DeviceSignIn
        data object Working : DeviceSignIn
        data class Browser(val url: String) : DeviceSignIn
        data class Failed(val error: Throwable) : DeviceSignIn
    }

    private val context get() = getApplication<Application>()
    private val vault get() = AdminSession.vault(context)
    private val cache by lazy { AdminCache.of(context.filesDir) }
    private val audit by lazy { AdminAuditLog.of(context.filesDir) }

    var phase by mutableStateOf(Phase.LOADING); private set
    override var account by mutableStateOf<AdminAccount?>(null); private set
    var authMode by mutableStateOf<AuthMode?>(null); private set
    override var user by mutableStateOf<ApiUser?>(null); private set
    override var caps by mutableStateOf<Capabilities?>(null); private set
    var readOnly by mutableStateOf(true); private set
    var hasToken by mutableStateOf(false); private set
    var setupReason by mutableStateOf<Note?>(null); private set
    var deviceSignIn by mutableStateOf<DeviceSignIn>(DeviceSignIn.Idle); private set
    /** The note on screen, if any; the activity clears it once shown. */
    var note by mutableStateOf<AdminNote?>(null)
    var route by mutableStateOf(AdminRoute.HOME); private set

    override val data = AdminData()
    override val scope: CoroutineScope get() = viewModelScope
    override var api: NetbirdApi? = null; private set
    private var ownTokenId: String? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private val areaStates = HashMap<String, AreaState>()

    private val runner = SafeChangeRunner(api = { api }, audit = audit, context = ::safetyContext)
    val flow = ChangeFlow(viewModelScope, runner, ::onChangeFinished)

    val lockState: LockState get() = AdminWriteGate.lockState(context)

    override val writesAllowed: Boolean
        get() = !readOnly && lockState != LockState.NO_SCREEN_LOCK && caps?.readOnly == false

    override val changeBusy: Boolean get() = flow.busy

    override fun can(module: String, op: Op): Boolean =
        caps?.can(module, op) == true && (op == Op.READ || writesAllowed)

    /** This device's peer in the API's list: matched by address, else by DNS label. */
    override fun isSelf(peer: ApiPeer): Boolean {
        val local = NetbirdState.status.value?.fullStatus?.localPeerState ?: return false
        if (local.address.isNotEmpty() && peer.ip == local.address.substringBefore('/')) return true
        return peer.dnsLabel.isNotEmpty() && local.fqdn.substringBefore('.').equals(peer.dnsLabel.substringBefore('.'), ignoreCase = true)
    }

    /** Why nothing can be changed, when nothing can; for the home screen. */
    val readOnlyReason: Int?
        get() = when {
            caps?.readOnly == true -> R.string.admin_overview_ro_role
            lockState == LockState.NO_SCREEN_LOCK -> R.string.admin_overview_ro_lock
            readOnly -> R.string.admin_overview_ro_switch
            else -> null
        }

    private fun safetyContext() = SafetyContext(
        accountId = account?.id.orEmpty(),
        readOnly = readOnly || caps?.readOnly != false,
        lockState = lockState,
        ownUserId = user?.id,
        ownTokenId = ownTokenId,
        tokenAuth = authMode == AuthMode.TOKEN,
        selfPeerIds = data.items(AdminKeys.PEERS).filter(::isSelf).map { it.id }.toSet(),
        can = { m, op -> caps?.can(m, op) == true },
    )

    // ================================================================ arriving

    fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            phase = Phase.LOADING
            val acc = runCatching { AdminSession.activeAccount(context) }.getOrElse { phase = Phase.NO_DAEMON; return@launch }
            if (account?.id != acc.id) resetAccountState()
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
        // Open on the cache at once when it knows who we are; the server answers in place.
        val cached = withContext(Dispatchers.IO) { runCatching { cache.read(acc.id) }.getOrDefault(emptyMap()) }
        val cachedUser = cached[CURRENT_USER]?.let { runCatching { AppJson.decodeFromJsonElement(ApiUser.serializer(), it.value) }.getOrNull() }
        if (cachedUser != null && phase != Phase.READY) {
            data.seed(AdminKeys.ALL, cached)
            api = client
            user = cachedUser
            caps = Capabilities.of(cachedUser)
            phase = Phase.READY
        } else if (phase != Phase.READY) {
            phase = Phase.LOADING
        }
        // Checked a moment ago in this process (the panel reopened, a deep link): the
        // cached user stands, and a 401 on the next read still sends to setup.
        val recent = checkedAt[acc.id]?.let { System.currentTimeMillis() - it < FRESH_MS } == true
        if (recent && cachedUser != null && phase == Phase.READY) {
            ownTokenId = ownTokens[acc.id]
            load(*AdminKeys.HOME.toTypedArray())
            return
        }
        try {
            val me = client.currentUser()
            checkedAt[acc.id] = System.currentTimeMillis()
            api = client
            user = me
            caps = Capabilities.of(me)
            setupReason = null
            if (cachedUser == null) data.seed(AdminKeys.ALL, cached)
            phase = Phase.READY
            saveCurrentUser(acc.id, me)
            // What the home screen shows, where it is stale; areas read their own on entry.
            load(*AdminKeys.HOME.toTypedArray())
            if (authMode == AuthMode.TOKEN) learnOwnToken(client, me)
        } catch (e: AdminApiException.Unauthorized) {
            onUnauthorized(acc)
        } catch (e: Exception) {
            if (phase == Phase.READY) {
                // On the cache with the server unreachable: stay, and say why.
                notify(AdminNote.error(e))
            } else {
                api = client
                setupReason = Note(R.string.admin_setup_reason_failed, error = e)
                phase = Phase.SETUP
            }
        }
    }

    /** Best effort: which token this is, so the runner can refuse revoking it. Needs the PATs read permission. */
    private fun learnOwnToken(client: NetbirdApi, me: ApiUser) {
        if (caps?.can(Capabilities.PATS, Op.READ) == false) return
        val acc = account?.id
        if (acc != null && ownTokens.containsKey(acc)) { ownTokenId = ownTokens[acc]; return }
        viewModelScope.launch {
            ownTokenId = runCatching { client.guessOwnTokenId(me.id) }.getOrNull()
            if (acc != null) ownTokenId?.let { ownTokens[acc] = it }
        }
    }

    /** The credential changed or was refused: the next open asks the server again. */
    private fun forget(accountId: String) {
        checkedAt.remove(accountId)
        ownTokens.remove(accountId)
    }

    /** A 401: this device's token is dropped for the stored one; a rejected stored token sends to setup. */
    private suspend fun onUnauthorized(acc: AdminAccount) {
        forget(acc.id)
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

    // ================================================================ data

    override fun load(vararg keys: DataKey<*>, force: Boolean) {
        for (key in keys) loadOne(key, force)
    }

    private fun <T> loadOne(key: DataKey<T>, force: Boolean) {
        val client = api ?: return
        if (!canRead(key.module)) return
        val current = data[key]
        if (current.loading) return
        // Fresh for a while, from disk too: opening the panel or an area again within
        // FRESH_MS asks the server nothing; the refresh button and pull always do.
        val fresh = current.value != null && System.currentTimeMillis() - current.loadedAt < FRESH_MS
        if (!force && fresh) return
        data.update(key) { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val value = key.load(client)
                data.set(key, Loadable(value, loadedAt = System.currentTimeMillis()))
                if (key.cacheable) scheduleSave()
            } catch (e: AdminApiException.Forbidden) {
                caps = caps?.denied(key.module, Op.READ)
                data.update(key) { it.copy(loading = false, error = e) }
            } catch (e: AdminApiException.Unauthorized) {
                data.update(key) { it.copy(loading = false, error = e) }
                account?.let { onUnauthorized(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                data.update(key) { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                data.update(key) { it.copy(loading = false, error = e) }
            }
        }
    }

    /** Everything read so far, again; the home screen's keys always. */
    fun refreshLoaded() {
        val keys = AdminKeys.ALL.filter { k -> k in AdminKeys.HOME || data[k].value != null || data[k].error != null }
        load(*keys.toTypedArray(), force = true)
    }

    val refreshing: Boolean get() = AdminKeys.ALL.any { data[it].loading }

    /** Writes the cacheable values a little after the last read, off the main thread. */
    private fun scheduleSave() {
        val acc = account ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            val snapshot = data.snapshot(AdminKeys.ALL)
            withContext(Dispatchers.IO) { runCatching { cache.write(acc.id, snapshot) } }
        }
    }

    private fun saveCurrentUser(accountId: String, me: ApiUser) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { cache.write(accountId, mapOf(CURRENT_USER to AdminCache.Entry(System.currentTimeMillis(), AppJson.encodeToJsonElement(ApiUser.serializer(), me)))) }
        }
    }

    private fun resetAccountState() {
        data.clear()
        areaStates.values.forEach { runCatching { it.onCleared() } }
        areaStates.clear()
        api = null
        user = null
        caps = null
        ownTokenId = null
        route = AdminRoute.HOME
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : AreaState> areaState(id: String, create: (AdminHost) -> T): T = areaStates.getOrPut(id) { create(this) } as T

    // ================================================================ navigation and notes

    override fun navigate(route: AdminRoute) {
        this.route = route
    }

    override fun back() {
        route = AdminRoute.HOME
    }

    override fun notify(note: AdminNote) {
        this.note = note
    }

    // ================================================================ changes

    override fun propose(change: PlannedChange): Boolean = flow.propose(change)

    private fun onChangeFinished(planned: PlannedChange, outcome: ChangeOutcome) {
        val kind = planned.change.kind
        when (outcome) {
            is ChangeOutcome.Applied -> {
                val undo = planned.undo
                note = when (outcome.verified) {
                    false -> AdminNote({ ctx -> ctx.getString(R.string.admin_change_mismatch, planned.change.title) })
                    else -> AdminNote(
                        message = { ctx -> planned.done ?: ctx.getString(R.string.admin_change_done, planned.change.title) },
                        action = undo?.let { u -> AdminNote.Action({ ctx -> ctx.getString(R.string.admin_undo) }) { propose(u) } },
                    )
                }
                if (planned.refreshes.isNotEmpty()) load(*planned.refreshes.toTypedArray(), force = true)
            }
            is ChangeOutcome.Refused -> note = AdminNote({ ctx -> refusalText(ctx, outcome.reason) })
            is ChangeOutcome.Failed -> when (val e = outcome.error) {
                is UnlockFailedException -> note = AdminNote({ ctx -> unlockFailureText(ctx, e.result.reason) })
                is AdminApiException.Forbidden -> {
                    caps = caps?.denied(kind.module, kind.op)
                    note = AdminNote.error(e)
                }
                is AdminApiException.Unauthorized -> account?.let { acc -> viewModelScope.launch { onUnauthorized(acc) } }
                else -> note = AdminNote.error(e)
            }
        }
    }

    // ================================================================ setup

    /** Asks GET /api/users/current with [token] without storing it. */
    suspend fun testToken(token: String): Result<ApiUser> {
        val acc = account ?: return Result.failure(IllegalStateException("no account"))
        val client = AdminSession.api(context, acc, ApiAuth.Token(token.trim())) ?: return Result.failure(IllegalStateException("no API"))
        return runCatching { client.currentUser() }
    }

    fun saveToken(token: String, readOnlyChoice: Boolean) {
        val acc = account ?: return
        if (!vault.putToken(acc.id, token)) { note = AdminNote.res(R.string.admin_token_not_saved); return }
        forget(acc.id)
        vault.setReadOnly(acc.id, readOnlyChoice)
        readOnly = readOnlyChoice
        hasToken = true
        AdminSession.setJwt(acc.id, null)
        // Another token may be another user: nothing of the old session is kept.
        resetAccountState()
        account = acc
        phase = Phase.LOADING
        viewModelScope.launch { connect(acc) }
    }

    fun forgetToken() {
        val acc = account ?: return
        vault.forget(acc.id)
        forget(acc.id)
        AdminSession.setJwt(acc.id, null)
        runCatching { cache.clear(acc.id) }
        hasToken = false
        readOnly = true
        resetAccountState()
        account = acc
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

    companion object {
        /** A value read this recently is not read again when a screen opens. */
        /** How long a list read from the server is used as it is before an open reads it again. */
        internal const val FRESH_MS = 5 * 60_000L
        /** When this process last heard the server confirm each account's credential. */
        private val checkedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        /** The access token each account signs in with, once learned (for the "token in use" guard). */
        private val ownTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** The cache entry for who the credential is, so the panel opens before the server answers. */
        private const val CURRENT_USER = "current_user"
    }
}
