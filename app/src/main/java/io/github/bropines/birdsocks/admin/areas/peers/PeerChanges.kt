package io.github.bropines.birdsocks.admin.areas.peers

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
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
import io.github.bropines.birdsocks.admin.areas.groups.GroupChanges
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
    val SSH = ChangeKind("peer.ssh", Capabilities.PEERS, Op.UPDATE, ChangeClass.MEDIUM, allowsSelf = true)
    val INACTIVITY_EXPIRATION = ChangeKind("peer.inactivity_expiration", Capabilities.PEERS, Op.UPDATE, ChangeClass.MEDIUM, allowsSelf = true)
    /** Membership lives on groups: it needs the right to update groups. */
    val GROUPS = ChangeKind("peer.groups", Capabilities.GROUPS, Op.UPDATE, ChangeClass.MEDIUM, allowsSelf = true)
    val BULK_GROUPS = ChangeKind("peer.bulk_groups", Capabilities.GROUPS, Op.UPDATE, ChangeClass.MEDIUM, allowsSelf = true)

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

    /**
     * The peer's SSH server: on lets policies with netbird-ssh rules reach a
     * shell on it (MEDIUM); off is LOW, the switch was the confirm.
     */
    fun setSsh(ctx: Context, peer: ApiPeer, enabled: Boolean, self: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            SSH, target(peer, self),
            title = ctx.getString(R.string.admin_peer_ssh_title, peer.name),
            effect = ctx.getString(if (enabled) R.string.admin_peer_ssh_on_effect else R.string.admin_peer_ssh_off_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_peer_ssh), yesNo(ctx, peer.sshEnabled), yesNo(ctx, enabled))),
            changeClass = if (enabled) ChangeClass.MEDIUM else ChangeClass.LOW,
        ),
        apply = { it.updatePeer(peer.id, ApiPeerRequest.of(it.peer(peer.id)).copy(sshEnabled = enabled)) },
        verify = { it.peer(peer.id).sshEnabled == enabled },
        undo = if (isUndo) null else setSsh(ctx, peer.copy(sshEnabled = enabled), !enabled, self, isUndo = true),
        refreshes = listOf(AdminKeys.PEERS),
        isUndo = isUndo,
    )

    /** Like login expiration: on is LOW, off keeps an idle peer connected for good, MEDIUM. */
    fun setInactivityExpiration(ctx: Context, peer: ApiPeer, enabled: Boolean, self: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            INACTIVITY_EXPIRATION, target(peer, self),
            title = ctx.getString(R.string.admin_peer_inactivity_title, peer.name),
            effect = ctx.getString(if (enabled) R.string.admin_peer_inactivity_on_effect else R.string.admin_peer_inactivity_off_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_peer_inactivity), yesNo(ctx, peer.inactivityExpirationEnabled), yesNo(ctx, enabled))),
            changeClass = if (enabled) ChangeClass.LOW else ChangeClass.MEDIUM,
        ),
        apply = { it.updatePeer(peer.id, ApiPeerRequest.of(it.peer(peer.id)).copy(inactivityExpirationEnabled = enabled)) },
        verify = { it.peer(peer.id).inactivityExpirationEnabled == enabled },
        undo = if (isUndo) null else setInactivityExpiration(ctx, peer.copy(inactivityExpirationEnabled = enabled), !enabled, self, isUndo = true),
        refreshes = listOf(AdminKeys.PEERS),
        isUndo = isUndo,
    )

    /**
     * The peer's groups from what it has to [after] (ids), one group PUT per
     * group that changes. MEDIUM; this device leaving a group warns, and
     * leaving the groups an enabled policy reaches it through
     * ([lostPolicies], from [SelfReach]) makes it HIGH.
     */
    fun setGroups(
        ctx: Context,
        peer: ApiPeer,
        after: List<String>,
        groups: List<ApiGroup>,
        self: Boolean,
        lostPolicies: List<String> = emptyList(),
        isUndo: Boolean = false,
    ): PlannedChange {
        val before = peer.groups.map { it.id }
        val edits = PeerGroups.forPeer(peer.id, before, after, groups)
        val added = edits.filter { it.add.isNotEmpty() }.map { it.groupName }
        val removed = edits.filter { it.remove.isNotEmpty() }.map { it.groupName }
        val cutoff = self && lostPolicies.isNotEmpty()
        val byId = groups.associateBy { it.id }
        return PlannedChange(
            change = AdminChange(
                GROUPS, target(peer, self),
                title = ctx.getString(R.string.admin_peer_groups_title, peer.name),
                effect = ctx.getString(R.string.admin_peer_groups_effect),
                diff = listOfNotNull(
                    added.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_peer_groups_added), null, it.joinToString(", ")) },
                    removed.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_peer_groups_removed), it.joinToString(", "), null) },
                ),
                warnings = listOfNotNull(
                    if (self && removed.isNotEmpty()) ctx.getString(R.string.admin_peer_groups_self_warning) else null,
                    if (cutoff) ctx.getString(R.string.admin_cutoff_warning, lostPolicies.joinToString(", ")) else null,
                ),
                changeClass = if (cutoff) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { api -> GroupChanges.applyEdits(api, edits) },
            verify = { api -> GroupChanges.verifyEdits(api, edits) },
            undo = if (isUndo) null else setGroups(
                ctx, peer.copy(groups = after.map { ApiGroupMinimum(it, byId[it]?.name ?: it) }), before, groups, self, isUndo = true,
            ),
            refreshes = listOf(AdminKeys.PEERS, AdminKeys.GROUPS),
            isUndo = isUndo,
        )
    }

    /**
     * Groups added to and removed from several peers at once: one PUT per
     * group, each on a fresh read. [selfPeerId] in the selection and a removal
     * warns; with [lostPolicies] it is HIGH, the count typed back.
     */
    fun bulkGroups(
        ctx: Context,
        peers: List<ApiPeer>,
        add: List<String>,
        remove: List<String>,
        groups: List<ApiGroup>,
        selfPeerId: String?,
        lostPolicies: List<String> = emptyList(),
    ): PlannedChange {
        val edits = PeerGroups.bulk(peers, add, remove, groups)
        val count = ctx.resources.getQuantityString(R.plurals.admin_peers_count, peers.size, peers.size)
        val self = selfPeerId != null && peers.any { it.id == selfPeerId }
        val selfLeaves = self && edits.any { selfPeerId in it.remove }
        val cutoff = selfLeaves && lostPolicies.isNotEmpty()
        return PlannedChange(
            change = AdminChange(
                BULK_GROUPS, ChangeTarget(TargetType.PEER, "", count, isThisDevice = self),
                title = ctx.getString(R.string.admin_bulk_groups_title, count),
                effect = ctx.getString(R.string.admin_bulk_groups_effect, edits.size),
                diff = edits.map { e ->
                    val n = (e.add + e.remove).size
                    if (e.add.isNotEmpty()) DiffLine(e.groupName, null, "+$n") else DiffLine(e.groupName, "−$n", null)
                },
                warnings = listOfNotNull(
                    if (selfLeaves) ctx.getString(R.string.admin_peer_groups_self_warning) else null,
                    if (cutoff) ctx.getString(R.string.admin_cutoff_warning, lostPolicies.joinToString(", ")) else null,
                ),
                changeClass = if (cutoff) ChangeClass.HIGH else ChangeClass.MEDIUM,
                confirmPhrase = if (cutoff) count else null,
            ),
            apply = { api -> GroupChanges.applyEdits(api, edits) },
            verify = { api -> GroupChanges.verifyEdits(api, edits) },
            undo = PlannedChange(
                change = AdminChange(
                    BULK_GROUPS, ChangeTarget(TargetType.PEER, "", count, isThisDevice = self),
                    title = ctx.getString(R.string.admin_bulk_groups_undo_title, count),
                    effect = ctx.getString(R.string.admin_bulk_groups_effect, edits.size),
                ),
                apply = { api -> GroupChanges.applyEdits(api, GroupChanges.inverse(edits)) },
                verify = { api -> GroupChanges.verifyEdits(api, GroupChanges.inverse(edits)) },
                refreshes = listOf(AdminKeys.PEERS, AdminKeys.GROUPS),
                isUndo = true,
            ),
            refreshes = listOf(AdminKeys.PEERS, AdminKeys.GROUPS),
        )
    }
}
