package io.github.bropines.birdsocks.admin.areas.users

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessToken
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessTokenGenerated
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessTokenRequest
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.ApiUserCreateRequest
import io.github.bropines.birdsocks.admin.api.ApiUserInvite
import io.github.bropines.birdsocks.admin.api.ApiUserInviteCreateRequest
import io.github.bropines.birdsocks.admin.api.ApiUserInviteRegenerateResponse
import io.github.bropines.birdsocks.admin.api.ApiUserRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeClassifier
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys

/**
 * The writes of the Users and Service users areas: approve and reject (the
 * home screen's attention list offers those inline too), role, block,
 * auto-groups, delete, service users, personal access tokens and link
 * invites. Secrets a write returns (a token, an invite link) go to a
 * callback once and are never stored.
 */
object UserChanges {
    val APPROVE = ChangeKind("user.approve", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val REJECT = ChangeKind("user.reject", Capabilities.USERS, Op.DELETE, ChangeClass.HIGH)
    val ROLE = ChangeKind("user.role", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val BLOCK = ChangeKind("user.block", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val AUTO_GROUPS = ChangeKind("user.auto_groups", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("user.delete", Capabilities.USERS, Op.DELETE, ChangeClass.HIGH)
    val CREATE_SERVICE = ChangeKind("user.create_service", Capabilities.USERS, Op.CREATE, ChangeClass.MEDIUM)

    /** Creating a token harms nobody, your own user included. */
    val TOKEN_CREATE = ChangeKind("token.create", Capabilities.PATS, Op.CREATE, ChangeClass.MEDIUM, allowsSelf = true)
    /** Any token but the panel's own: the runner refuses that one. */
    val TOKEN_DELETE = ChangeKind("token.delete", Capabilities.PATS, Op.DELETE, ChangeClass.HIGH)
    /** The token the panel may be signed in with, deleted on purpose after a warning. */
    val TOKEN_DELETE_OWN = ChangeKind("token.delete_own", Capabilities.PATS, Op.DELETE, ChangeClass.HIGH, allowsSelf = true)

    val INVITE_CREATE = ChangeKind("invite.create", Capabilities.USERS, Op.CREATE, ChangeClass.MEDIUM)
    val INVITE_REGENERATE = ChangeKind("invite.regenerate", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val INVITE_DELETE = ChangeKind("invite.delete", Capabilities.USERS, Op.DELETE, ChangeClass.MEDIUM)

    fun target(user: ApiUser) = ChangeTarget(TargetType.USER, user.id, displayName(user), isYou = user.isCurrent)

    fun displayName(user: ApiUser) = user.name.ifEmpty { user.email.ifEmpty { user.id } }

    private fun refreshesFor(user: ApiUser) = if (user.isServiceUser) listOf(AdminKeys.USERS, AdminKeys.SERVICE_USERS) else listOf(AdminKeys.USERS)

    /** The user as the server has it now: a PUT replaces role, groups and block together. */
    private suspend fun fresh(api: NetbirdApi, user: ApiUser): ApiUser =
        api.users().items.firstOrNull { it.id == user.id } ?: throw AdminApiException.NotFound(null)

    fun approve(ctx: Context, user: ApiUser): PlannedChange = PlannedChange(
        change = AdminChange(
            APPROVE, target(user),
            title = ctx.getString(R.string.admin_user_approve_title, displayName(user)),
            effect = ctx.getString(R.string.admin_user_approve_effect),
        ),
        apply = { it.approveUser(user.id) },
        verify = { api -> api.users().items.firstOrNull { it.id == user.id }?.pendingApproval == false },
        refreshes = listOf(AdminKeys.USERS),
        done = ctx.getString(R.string.admin_user_approved, displayName(user)),
    )

    /** Refuses the sign-up and removes the user; the name is typed back. */
    fun reject(ctx: Context, user: ApiUser): PlannedChange = PlannedChange(
        change = AdminChange(
            REJECT, target(user),
            title = ctx.getString(R.string.admin_user_reject_title, displayName(user)),
            effect = ctx.getString(R.string.admin_user_reject_effect),
        ),
        apply = { it.rejectUser(user.id) },
        verify = { api ->
            try { api.users().items.none { it.id == user.id } } catch (_: AdminApiException.NotFound) { true }
        },
        refreshes = listOf(AdminKeys.USERS),
        done = ctx.getString(R.string.admin_user_rejected, displayName(user)),
    )

    /**
     * A new role. Raising someone to a role that may change the network is
     * HIGH; making them owner hands them the account and is HIGH with a warning.
     */
    fun setRole(ctx: Context, user: ApiUser, role: String, isUndo: Boolean = false): PlannedChange {
        val transfer = UserRules.isOwnerTransfer(user.role, role)
        return PlannedChange(
            change = AdminChange(
                ROLE, target(user),
                title = ctx.getString(R.string.admin_user_role_title, displayName(user)),
                effect = ctx.getString(if (transfer) R.string.admin_user_role_owner_effect else R.string.admin_user_role_effect, role),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_user_role), user.role, role)),
                warnings = listOfNotNull(if (transfer) ctx.getString(R.string.admin_user_owner_transfer_warning) else null),
                changeClass = if (transfer) ChangeClass.HIGH else ChangeClassifier.userRole(user.role, role),
            ),
            apply = { api -> api.updateUser(user.id, ApiUserRequest.of(fresh(api, user)).copy(role = role)) },
            verify = { api -> fresh(api, user).role == role },
            // Taking ownership back is the new owner's call, not an undo.
            undo = if (isUndo || transfer) null else setRole(ctx, user.copy(role = role), user.role, isUndo = true),
            refreshes = refreshesFor(user),
            isUndo = isUndo,
        )
    }

    /** Blocking signs the user out and stops their peers; unblocking lets them back (HIGH: a protection comes off). */
    fun setBlocked(ctx: Context, user: ApiUser, blocked: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            BLOCK, target(user),
            title = ctx.getString(if (blocked) R.string.admin_user_block_title else R.string.admin_user_unblock_title, displayName(user)),
            effect = ctx.getString(if (blocked) R.string.admin_user_block_effect else R.string.admin_user_unblock_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_user_blocked), user.isBlocked.toString(), blocked.toString())),
            changeClass = ChangeClassifier.protection(user.isBlocked, blocked),
        ),
        apply = { api -> api.updateUser(user.id, ApiUserRequest.of(fresh(api, user)).copy(isBlocked = blocked)) },
        verify = { api -> fresh(api, user).isBlocked == blocked },
        undo = if (isUndo) null else setBlocked(ctx, user.copy(isBlocked = blocked), !blocked, isUndo = true),
        refreshes = refreshesFor(user),
        isUndo = isUndo,
    )

    /** The groups the user's new peers join. [names] resolves ids for the review. */
    fun setAutoGroups(ctx: Context, user: ApiUser, groups: List<String>, names: (List<String>) -> List<String>, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            AUTO_GROUPS, target(user),
            title = ctx.getString(R.string.admin_user_groups_title, displayName(user)),
            effect = ctx.getString(R.string.admin_user_groups_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_user_auto_groups), names(user.autoGroups).joinToString(", "), names(groups).joinToString(", "))),
        ),
        apply = { api -> api.updateUser(user.id, ApiUserRequest.of(fresh(api, user)).copy(autoGroups = groups)) },
        verify = { api -> fresh(api, user).autoGroups.toSet() == groups.toSet() },
        undo = if (isUndo) null else setAutoGroups(ctx, user.copy(autoGroups = groups), user.autoGroups, names, isUndo = true),
        refreshes = refreshesFor(user),
        isUndo = isUndo,
    )

    /** Removes the user; their peers stay but lose what came with the user. No way back. */
    fun delete(ctx: Context, user: ApiUser): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(user),
            title = ctx.getString(R.string.admin_user_delete_title, displayName(user)),
            effect = ctx.getString(if (user.isServiceUser) R.string.admin_svc_delete_effect else R.string.admin_user_delete_effect),
        ),
        apply = { it.deleteUser(user.id) },
        verify = { api -> api.users().items.none { it.id == user.id } },
        refreshes = refreshesFor(user),
        done = ctx.getString(R.string.admin_done_deleted, displayName(user)),
    )

    /** A service user: an identity for scripts, with tokens and no sign-in of its own. */
    fun createServiceUser(ctx: Context, name: String, role: String, groups: List<String>, groupNames: List<String>): PlannedChange {
        val request = ApiUserCreateRequest(name = name, role = role, autoGroups = groups, isServiceUser = true)
        return PlannedChange(
            change = AdminChange(
                CREATE_SERVICE, ChangeTarget(TargetType.USER, "", name),
                title = ctx.getString(R.string.admin_svc_create_title, name),
                effect = ctx.getString(R.string.admin_svc_create_effect, role),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_user_name), null, name),
                    DiffLine(ctx.getString(R.string.admin_user_role), null, role),
                    groupNames.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_user_auto_groups), null, it.joinToString(", ")) },
                ),
                changeClass = ChangeClassifier.userRole(null, role),
            ),
            apply = { it.createUser(request) },
            refreshes = listOf(AdminKeys.USERS, AdminKeys.SERVICE_USERS),
        )
    }

    // --- personal access tokens ---

    fun tokenTarget(owner: ApiUser, token: ApiPersonalAccessToken) =
        ChangeTarget(TargetType.TOKEN, token.id, token.name.ifEmpty { token.id }, ownerId = owner.id)

    /** A new token for [owner]; [onCreated] gets the plain token once, and nothing keeps it. */
    fun newToken(ctx: Context, owner: ApiUser, name: String, days: Int, onCreated: (ApiPersonalAccessTokenGenerated) -> Unit): PlannedChange {
        require(days in ApiPersonalAccessTokenRequest.MIN_DAYS..ApiPersonalAccessTokenRequest.MAX_DAYS)
        return PlannedChange(
            change = AdminChange(
                TOKEN_CREATE, ChangeTarget(TargetType.TOKEN, "", name, ownerId = owner.id),
                title = ctx.getString(R.string.admin_pat_create_title, name, displayName(owner)),
                effect = ctx.getString(R.string.admin_pat_create_effect, displayName(owner), owner.role, days),
                diff = listOf(
                    DiffLine(ctx.getString(R.string.admin_pat_name), null, name),
                    DiffLine(ctx.getString(R.string.admin_pat_days), null, days.toString()),
                ),
            ),
            apply = { onCreated(it.createToken(owner.id, ApiPersonalAccessTokenRequest(name, days))) },
        )
    }

    /**
     * Deletes a token. When it may be the one the panel signs in with
     * ([maybeOwn]), the change says so, is HIGH, and is the only kind the
     * runner lets through for it.
     */
    fun removeToken(ctx: Context, owner: ApiUser, token: ApiPersonalAccessToken, maybeOwn: Boolean, onDeleted: () -> Unit = {}): PlannedChange = PlannedChange(
        change = AdminChange(
            if (maybeOwn) TOKEN_DELETE_OWN else TOKEN_DELETE, tokenTarget(owner, token),
            title = ctx.getString(R.string.admin_pat_delete_title, token.name.ifEmpty { token.id }, displayName(owner)),
            effect = ctx.getString(R.string.admin_pat_delete_effect),
            warnings = listOfNotNull(if (maybeOwn) ctx.getString(R.string.admin_pat_own_warning) else null),
            changeClass = ChangeClass.HIGH,
        ),
        apply = { it.deleteToken(owner.id, token.id); onDeleted() },
        verify = { api -> api.tokens(owner.id).items.none { it.id == token.id } },
        done = ctx.getString(R.string.admin_done_deleted, token.name.ifEmpty { token.id }),
    )

    // --- invites (links) ---

    fun inviteTarget(invite: ApiUserInvite) = ChangeTarget(TargetType.INVITE, invite.id, invite.email.ifEmpty { invite.name.ifEmpty { invite.id } })

    /** A link invite; [onCreated] gets the invite with its token once. A role that may change the network is HIGH. */
    fun createInvite(ctx: Context, request: ApiUserInviteCreateRequest, groupNames: List<String>, onCreated: (ApiUserInvite) -> Unit): PlannedChange = PlannedChange(
        change = AdminChange(
            INVITE_CREATE, ChangeTarget(TargetType.INVITE, "", request.email),
            title = ctx.getString(R.string.admin_invite_create_title, request.email),
            effect = ctx.getString(R.string.admin_invite_create_effect, request.role, ((request.expiresIn ?: DEFAULT_INVITE_SEC) / 3600).toInt()),
            diff = listOfNotNull(
                DiffLine(ctx.getString(R.string.admin_user_email), null, request.email),
                DiffLine(ctx.getString(R.string.admin_user_name), null, request.name),
                DiffLine(ctx.getString(R.string.admin_user_role), null, request.role),
                groupNames.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_user_auto_groups), null, it.joinToString(", ")) },
            ),
            warnings = listOf(ctx.getString(R.string.admin_invite_bearer_warning)),
            changeClass = ChangeClassifier.userRole(null, request.role),
        ),
        apply = { onCreated(it.createUserInvite(request)) },
        refreshes = listOf(AdminKeys.USER_INVITES),
    )

    /** A new link for the same invite; the old one stops working. */
    fun regenerateInvite(ctx: Context, invite: ApiUserInvite, onCreated: (ApiUserInviteRegenerateResponse) -> Unit): PlannedChange = PlannedChange(
        change = AdminChange(
            INVITE_REGENERATE, inviteTarget(invite),
            title = ctx.getString(R.string.admin_invite_regenerate_title, inviteTarget(invite).name),
            effect = ctx.getString(R.string.admin_invite_regenerate_effect),
            warnings = listOf(ctx.getString(R.string.admin_invite_bearer_warning)),
        ),
        apply = { onCreated(it.regenerateUserInvite(invite.id)) },
        refreshes = listOf(AdminKeys.USER_INVITES),
    )

    fun deleteInvite(ctx: Context, invite: ApiUserInvite): PlannedChange = PlannedChange(
        change = AdminChange(
            INVITE_DELETE, inviteTarget(invite),
            title = ctx.getString(R.string.admin_invite_delete_title, inviteTarget(invite).name),
            effect = ctx.getString(R.string.admin_invite_delete_effect),
        ),
        apply = { it.deleteUserInvite(invite.id) },
        verify = { api -> api.userInvites().items.none { it.id == invite.id } },
        refreshes = listOf(AdminKeys.USER_INVITES),
        done = ctx.getString(R.string.admin_done_deleted, inviteTarget(invite).name),
    )

    /** The server's default when an invite names no expiry: 72 hours. */
    const val DEFAULT_INVITE_SEC = 259_200L
}
