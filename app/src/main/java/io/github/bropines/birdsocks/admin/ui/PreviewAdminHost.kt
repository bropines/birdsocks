package io.github.bropines.birdsocks.admin.ui

import io.github.bropines.birdsocks.admin.AdminAccount
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminNote
import io.github.bropines.birdsocks.admin.AdminRoute
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerMinimum
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.state.AdminData
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.state.DataKey
import io.github.bropines.birdsocks.admin.state.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * An [AdminHost] that does nothing, for previews and screenshot tests: no
 * API, every change refused quietly, the data whatever [seed] puts in.
 * `PreviewAdminHost.demo()` has a small network to draw on.
 */
class PreviewAdminHost(
    override val writesAllowed: Boolean = true,
    override val user: ApiUser? = ApiUser(id = "u-you", email = "you@example.com", name = "You", role = "owner", isCurrent = true),
    override val account: AdminAccount? = AdminAccount("default", "https://api.netbird.io:443", "preview", "https://api.netbird.io:443/api"),
    seed: AdminData.() -> Unit = {},
) : AdminHost {
    override val caps: Capabilities? = Capabilities(emptyMap(), fromServer = false, role = user?.role.orEmpty())
    override val api: NetbirdApi? = null
    override val data: AdminData = AdminData().apply(seed)
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val changeBusy: Boolean = false
    private val states = HashMap<String, AreaState>()

    override fun can(module: String, op: Op): Boolean = caps?.can(module, op) == true && (op == Op.READ || writesAllowed)
    override fun isSelf(peer: ApiPeer): Boolean = peer.id == SELF_PEER_ID
    override fun load(vararg keys: DataKey<*>, force: Boolean) = Unit
    override fun propose(change: PlannedChange): Boolean = false
    override fun notify(note: AdminNote) = Unit
    override fun navigate(route: AdminRoute) = Unit
    override fun back() = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <T : AreaState> areaState(id: String, create: (AdminHost) -> T): T = states.getOrPut(id) { create(this) } as T

    companion object {
        const val SELF_PEER_ID = "p-phone"

        /** A few peers, groups, keys and users, one of each state worth drawing. */
        fun demo(writesAllowed: Boolean = true) = PreviewAdminHost(writesAllowed) {
            val office = ApiGroupMinimum("g-office", "office", peersCount = 2)
            set(AdminKeys.PEERS, Loadable(Listing(listOf(
                ApiPeer(id = SELF_PEER_ID, name = "phone", ip = "100.64.0.2", os = "android", connected = true, groups = listOf(office)),
                ApiPeer(id = "p-laptop", name = "laptop", ip = "100.64.0.7", os = "linux", connected = true, groups = listOf(office)),
                ApiPeer(id = "p-nas", name = "nas", ip = "100.64.0.9", os = "linux", loginExpired = true),
                ApiPeer(id = "p-new", name = "new-tablet", ip = "100.64.0.12", os = "android", approvalRequired = true),
            )), loadedAt = 1))
            set(AdminKeys.GROUPS, Loadable(Listing(listOf(
                ApiGroup("g-all", ApiGroup.ALL, peersCount = 4, issued = "api"),
                ApiGroup("g-office", "office", peersCount = 2, issued = "api", peers = listOf(ApiPeerMinimum(SELF_PEER_ID, "phone"), ApiPeerMinimum("p-laptop", "laptop"))),
            )), loadedAt = 1))
            set(AdminKeys.SETUP_KEYS, Loadable(Listing(listOf(
                ApiSetupKey(id = "k1", name = "servers", type = ApiSetupKey.TYPE_REUSABLE, valid = true, state = "valid", usedTimes = 3, usageLimit = 10, expires = "2099-01-01T00:00:00Z", autoGroups = listOf("g-office")),
                ApiSetupKey(id = "k2", name = "old", type = ApiSetupKey.TYPE_ONE_OFF, valid = false, revoked = true, state = "revoked"),
            )), loadedAt = 1))
            set(AdminKeys.USERS, Loadable(Listing(listOf(
                ApiUser(id = "u-you", email = "you@example.com", name = "You", role = "owner", status = "active", isCurrent = true),
                ApiUser(id = "u-ann", email = "ann@example.com", name = "Ann", role = "user", status = "active", pendingApproval = true),
            )), loadedAt = 1))
        }
    }
}
