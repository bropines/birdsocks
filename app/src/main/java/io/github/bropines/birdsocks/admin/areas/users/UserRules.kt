package io.github.bropines.birdsocks.admin.areas.users

import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessToken
import io.github.bropines.birdsocks.admin.api.ApiRoles
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.ui.parseRfc3339Millis

/*
 * What the Users and Service users areas decide without a screen: a user's
 * status, who may be acted on, which tokens expire soon and which one the
 * panel itself signs in with. Tested on the JVM.
 */

/** A user's state as the list shows it. */
enum class UserStatus { PENDING, BLOCKED, INVITED, ACTIVE }

/** Why a user is not offered a change. */
enum class UserLock {
    /** The user the panel acts as: the runner refuses it too. */
    OWN,
    /** The only owner: demoting, blocking or deleting them leaves the account without one. */
    LAST_OWNER,
}

object UserRules {

    fun status(u: ApiUser): UserStatus = when {
        u.pendingApproval -> UserStatus.PENDING
        u.isBlocked || u.status == ApiUser.STATUS_BLOCKED -> UserStatus.BLOCKED
        u.status == ApiUser.STATUS_INVITED -> UserStatus.INVITED
        else -> UserStatus.ACTIVE
    }

    fun owners(users: List<ApiUser>): Int = users.count { it.role == ApiRoles.OWNER && !it.isServiceUser }

    /** [u] is an owner and no other owner is listed. */
    fun isLastOwner(u: ApiUser, users: List<ApiUser>): Boolean = u.role == ApiRoles.OWNER && owners(users) <= 1

    /** Why [u] may not be changed from here, or null. */
    fun lock(u: ApiUser, users: List<ApiUser>, ownUserId: String?): UserLock? = when {
        u.isCurrent || (ownUserId != null && u.id == ownUserId) -> UserLock.OWN
        isLastOwner(u, users) -> UserLock.LAST_OWNER
        else -> null
    }

    /**
     * Whether moving [u] from their role to [role] is allowed: never your own
     * user, never the last owner to anything else.
     */
    fun canSetRole(u: ApiUser, role: String, users: List<ApiUser>, ownUserId: String?): Boolean = when (lock(u, users, ownUserId)) {
        UserLock.OWN -> false
        UserLock.LAST_OWNER -> role == ApiRoles.OWNER
        null -> role != u.role
    }

    /** Blocking and deleting: neither yourself nor the last owner. */
    fun canBlockOrDelete(u: ApiUser, users: List<ApiUser>, ownUserId: String?): Boolean = lock(u, users, ownUserId) == null

    /** Making someone owner hands them the account: NetBird keeps one owner and demotes the old one. */
    fun isOwnerTransfer(before: String, after: String): Boolean = after == ApiRoles.OWNER && before != ApiRoles.OWNER

    /** The roles a picker offers: the known ones, plus [current] if the server has one we do not know. */
    fun roleChoices(current: String?): List<String> =
        if (current.isNullOrEmpty() || current in ApiRoles.KNOWN) ApiRoles.KNOWN else ApiRoles.KNOWN + current

    /** The roles an invite or a service user may get: everything but owner. */
    val grantableRoles: List<String> = ApiRoles.KNOWN.filter { it != ApiRoles.OWNER }

    /** Waiting users first, then blocked, then by name. */
    val attentionOrder: Comparator<ApiUser> =
        compareBy<ApiUser> { status(it).ordinal }.thenBy { it.name.ifEmpty { it.email }.lowercase() }
}

object TokenRules {
    /** A token that stops working within this many days is flagged. */
    const val SOON_DAYS = 14
    private const val DAY_MS = 86_400_000L

    /** Whole days until [t] expires (negative once it has); null when it never does or the date does not read. */
    fun daysLeft(t: ApiPersonalAccessToken, now: Long): Int? {
        val at = parseRfc3339Millis(t.expirationDate) ?: return null
        val diff = at - now
        return if (diff >= 0) (diff / DAY_MS).toInt() else -(((-diff) + DAY_MS - 1) / DAY_MS).toInt()
    }

    fun isExpired(t: ApiPersonalAccessToken, now: Long): Boolean = parseRfc3339Millis(t.expirationDate)?.let { it < now } == true

    fun expiringSoon(t: ApiPersonalAccessToken, now: Long, days: Int = SOON_DAYS): Boolean =
        !isExpired(t, now) && daysLeft(t, now)?.let { it <= days } == true

    /**
     * Which of [tokens] the panel signs in with, from a list it has just read
     * with that very token: the server marks `last_used` on every request, so
     * the one used within [windowMs] of [now], when exactly one was.
     */
    fun guessOwn(tokens: List<ApiPersonalAccessToken>, now: Long, windowMs: Long = 120_000): String? =
        tokens.filter { t -> parseRfc3339Millis(t.lastUsed)?.let { now - it in -windowMs..windowMs } == true }.singleOrNull()?.id

    /**
     * Whether deleting [t] may cut the panel off: it is the token in use, or —
     * when that cannot be told and the panel signs in with a token — any token
     * of the user the panel acts as.
     */
    fun maybeOwn(t: ApiPersonalAccessToken, ownerId: String, ownUserId: String?, ownTokenId: String?, tokenAuth: Boolean): Boolean {
        if (!tokenAuth || ownUserId == null || ownerId != ownUserId) return false
        return ownTokenId == null || ownTokenId == t.id
    }
}
