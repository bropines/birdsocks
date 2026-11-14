package io.github.bropines.birdsocks.admin.home

import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.ui.parseRfc3339Millis

/** One thing on the home screen that waits for an admin. */
sealed class AttentionItem {
    abstract val id: String

    /** A peer that may not join until approved (cloud, peer approval on). */
    data class PeerAwaiting(val peer: ApiPeer) : AttentionItem() {
        override val id get() = "peer:${peer.id}"
    }

    /** A user who signed up and waits for approval (user approval required). */
    data class UserAwaiting(val user: ApiUser) : AttentionItem() {
        override val id get() = "user:${user.id}"
    }

    /** A valid setup key that stops working within the horizon; [daysLeft] 0 is today. */
    data class KeyExpiring(val key: ApiSetupKey, val expiresAt: Long, val daysLeft: Int) : AttentionItem() {
        override val id get() = "key:${key.id}"
    }
}

/** What the attention list holds, without Compose. */
object Attention {
    const val KEY_HORIZON_DAYS = 7
    private const val DAY_MS = 86_400_000L

    /**
     * Peers awaiting approval, then users, then keys by how soon they expire.
     * A null list (not read, or not readable with this role) adds nothing.
     */
    fun items(peers: List<ApiPeer>?, users: List<ApiUser>?, keys: List<ApiSetupKey>?, now: Long, horizonDays: Int = KEY_HORIZON_DAYS): List<AttentionItem> = buildList {
        peers.orEmpty().filter { it.approvalRequired }.sortedBy { it.name.lowercase() }.forEach { add(AttentionItem.PeerAwaiting(it)) }
        users.orEmpty().filter { it.pendingApproval }.sortedBy { it.email.ifEmpty { it.name }.lowercase() }.forEach { add(AttentionItem.UserAwaiting(it)) }
        keys.orEmpty()
            .filter { it.valid && !it.revoked }
            .mapNotNull { k -> parseRfc3339Millis(k.expires)?.let { k to it } }
            .filter { (_, at) -> at >= now && at - now <= horizonDays * DAY_MS }
            .sortedBy { it.second }
            .forEach { (k, at) -> add(AttentionItem.KeyExpiring(k, at, ((at - now) / DAY_MS).toInt())) }
    }
}
