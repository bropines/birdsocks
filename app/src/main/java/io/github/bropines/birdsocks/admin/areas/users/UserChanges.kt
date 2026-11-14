package io.github.bropines.birdsocks.admin.areas.users

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys

/**
 * The Users area's writes. For now the two the home screen's attention list
 * offers inline — approving and rejecting a user who waits — the Users area
 * adds the rest here.
 */
object UserChanges {
    val APPROVE = ChangeKind("user.approve", Capabilities.USERS, Op.UPDATE, ChangeClass.MEDIUM)
    val REJECT = ChangeKind("user.reject", Capabilities.USERS, Op.DELETE, ChangeClass.HIGH)

    fun target(user: ApiUser) = ChangeTarget(TargetType.USER, user.id, displayName(user), isYou = user.isCurrent)

    fun displayName(user: ApiUser) = user.name.ifEmpty { user.email.ifEmpty { user.id } }

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
}
