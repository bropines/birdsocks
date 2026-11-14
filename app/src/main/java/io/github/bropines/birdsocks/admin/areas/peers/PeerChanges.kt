package io.github.bropines.birdsocks.admin.areas.peers

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys

/**
 * The Peers area's writes as [PlannedChange]s. [ctx] is the screen's (it
 * carries the app's language); [self] whether the peer is this device.
 */
object PeerChanges {
    val RENAME = ChangeKind("peer.rename", Capabilities.PEERS, Op.UPDATE, ChangeClass.LOW, allowsSelf = true)
    val LOGIN_EXPIRATION = ChangeKind("peer.login_expiration", Capabilities.PEERS, Op.UPDATE, ChangeClass.MEDIUM, allowsSelf = true)
    val APPROVE = ChangeKind("peer.approve", Capabilities.PEERS, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("peer.delete", Capabilities.PEERS, Op.DELETE, ChangeClass.HIGH)

    fun target(peer: ApiPeer, self: Boolean) = ChangeTarget(TargetType.PEER, peer.id, peer.name, isThisDevice = self)

    private fun yesNo(ctx: Context, v: Boolean) = ctx.getString(if (v) R.string.admin_value_on else R.string.admin_value_off)

    fun rename(ctx: Context, peer: ApiPeer, newName: String, self: Boolean, isUndo: Boolean = false): PlannedChange {
        val name = newName.trim()
        return PlannedChange(
            change = AdminChange(
                RENAME, target(peer, self),
                title = ctx.getString(R.string.admin_peer_rename_unlock, peer.name),
                effect = ctx.getString(R.string.admin_peer_rename_effect, name),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_peer_name), peer.name, name)),
            ),
            apply = { it.updatePeer(peer.id, ApiPeerRequest.of(peer).copy(name = name)) },
            verify = { it.peer(peer.id).name == name },
            undo = if (isUndo) null else rename(ctx, peer.copy(name = name), peer.name, self, isUndo = true),
            refreshes = listOf(AdminKeys.PEERS),
            isUndo = isUndo,
        )
    }

    /** Switching it on is LOW (the switch was the confirm); off keeps the machine signed in for good: MEDIUM. */
    fun setLoginExpiration(ctx: Context, peer: ApiPeer, enabled: Boolean, self: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            LOGIN_EXPIRATION, target(peer, self),
            title = ctx.getString(R.string.admin_peer_expiry_unlock, peer.name),
            effect = ctx.getString(if (enabled) R.string.admin_peer_expiry_on_effect else R.string.admin_peer_expiry_off_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_peer_login_expiration), yesNo(ctx, peer.loginExpirationEnabled), yesNo(ctx, enabled))),
            changeClass = if (enabled) ChangeClass.LOW else ChangeClass.MEDIUM,
        ),
        apply = { it.updatePeer(peer.id, ApiPeerRequest.of(peer).copy(loginExpirationEnabled = enabled)) },
        verify = { it.peer(peer.id).loginExpirationEnabled == enabled },
        undo = if (isUndo) null else setLoginExpiration(ctx, peer.copy(loginExpirationEnabled = enabled), !enabled, self, isUndo = true),
        refreshes = listOf(AdminKeys.PEERS),
        isUndo = isUndo,
    )

    /** Cloud: lets a peer that waits for an admin into the network. */
    fun approve(ctx: Context, peer: ApiPeer): PlannedChange = PlannedChange(
        change = AdminChange(
            APPROVE, target(peer, false),
            title = ctx.getString(R.string.admin_peer_approve_title, peer.name),
            effect = ctx.getString(R.string.admin_peer_approve_effect),
        ),
        apply = { it.updatePeer(peer.id, ApiPeerRequest.of(peer).copy(approvalRequired = false)) },
        verify = { !it.peer(peer.id).approvalRequired },
        refreshes = listOf(AdminKeys.PEERS),
        done = ctx.getString(R.string.admin_peer_approved, peer.name),
    )

    /** Never this device's own peer: the runner refuses it. The name is typed back. */
    fun delete(ctx: Context, peer: ApiPeer, self: Boolean): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(peer, self),
            title = ctx.getString(R.string.admin_peer_delete_title, peer.name),
            effect = ctx.getString(R.string.admin_peer_delete_text),
        ),
        apply = { it.deletePeer(peer.id) },
        verify = { api -> try { api.peer(peer.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = listOf(AdminKeys.PEERS, AdminKeys.GROUPS),
        done = ctx.getString(R.string.admin_done_deleted, peer.name),
    )
}
