package io.github.bropines.birdsocks.admin.areas.groups

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupRequest
import io.github.bropines.birdsocks.admin.api.ApiResource
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.areas.peers.GroupEdit
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys

/**
 * The Groups area's writes as [PlannedChange]s, and the membership edit the
 * Peers area shares ([applyEdits]). A group PUT replaces its members, so
 * every edit re-reads the group inside `apply` and changes only what it
 * means to: another admin's edit made meanwhile survives.
 */
object GroupChanges {
    val CREATE = ChangeKind("group.create", Capabilities.GROUPS, Op.CREATE, ChangeClass.MEDIUM)
    val RENAME = ChangeKind("group.rename", Capabilities.GROUPS, Op.UPDATE, ChangeClass.LOW)
    val PEERS = ChangeKind("group.peers", Capabilities.GROUPS, Op.UPDATE, ChangeClass.MEDIUM)
    val RESOURCES = ChangeKind("group.resources", Capabilities.GROUPS, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("group.delete", Capabilities.GROUPS, Op.DELETE, ChangeClass.HIGH)

    private val refreshes = listOf(AdminKeys.GROUPS, AdminKeys.PEERS)

    fun target(g: ApiGroup) = ChangeTarget(TargetType.GROUP, g.id, g.name)

    /** Re-reads group [id], hands its request to [edit], PUTs the result. */
    suspend fun editGroup(api: NetbirdApi, id: String, edit: (ApiGroupRequest) -> ApiGroupRequest) {
        val fresh = api.group(id)
        require(!fresh.isAll) { "the All group is not edited" }
        api.updateGroup(id, edit(ApiGroupRequest.of(fresh)))
    }

    /** Each edit on a fresh read of its group, one PUT per group. */
    suspend fun applyEdits(api: NetbirdApi, edits: List<GroupEdit>) {
        for (e in edits) editGroup(api, e.groupId) { r -> r.copy(peers = e.applyTo(r.peers.orEmpty())) }
    }

    /** Every edit holds on a re-read. */
    suspend fun verifyEdits(api: NetbirdApi, edits: List<GroupEdit>): Boolean = edits.all { e ->
        val ids = api.group(e.groupId).peers.map { it.id }.toSet()
        e.add.all { it in ids } && e.remove.none { it in ids }
    }

    /** The edits undone: what was added comes off, what was removed goes back. */
    fun inverse(edits: List<GroupEdit>): List<GroupEdit> = edits.map { it.copy(add = it.remove, remove = it.add) }

    fun create(ctx: Context, name: String, peerIds: List<String>, peerNames: List<String>, onCreated: (ApiGroup) -> Unit = {}): PlannedChange {
        val n = name.trim()
        return PlannedChange(
            change = AdminChange(
                CREATE, ChangeTarget(TargetType.GROUP, "", n),
                title = ctx.getString(R.string.admin_group_create_title, n),
                effect = ctx.getString(R.string.admin_group_create_effect),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_group_name), null, n),
                    peerNames.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_group_members), null, it.joinToString(", ")) },
                ),
            ),
            apply = { onCreated(it.createGroup(ApiGroupRequest(n, peerIds, null))) },
            refreshes = refreshes,
            done = ctx.getString(R.string.admin_group_created, n),
        )
    }

    fun rename(ctx: Context, g: ApiGroup, newName: String, isUndo: Boolean = false): PlannedChange {
        val n = newName.trim()
        return PlannedChange(
            change = AdminChange(
                RENAME, target(g),
                title = ctx.getString(R.string.admin_group_rename_title, g.name),
                effect = ctx.getString(R.string.admin_group_rename_effect, n),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_group_name), g.name, n)),
            ),
            apply = { api -> editGroup(api, g.id) { it.copy(name = n) } },
            verify = { it.group(g.id).name == n },
            undo = if (isUndo) null else rename(ctx, g.copy(name = n), g.name, isUndo = true),
            refreshes = refreshes,
            isUndo = isUndo,
        )
    }

    /**
     * The group's peers from what it has to [after]. [selfPeerId] is this
     * device's peer; taking it out warns, and [lostPolicies] (from
     * [io.github.bropines.birdsocks.admin.areas.peers.SelfReach]) makes it HIGH.
     */
    fun setPeers(
        ctx: Context,
        g: ApiGroup,
        after: List<String>,
        peerName: (String) -> String,
        selfPeerId: String?,
        lostPolicies: List<String> = emptyList(),
        isUndo: Boolean = false,
    ): PlannedChange {
        val before = g.peers.map { it.id }
        val edit = GroupEdit(g.id, g.name, (after - before.toSet()).toSet(), (before - after.toSet()).toSet())
        val selfOut = selfPeerId != null && selfPeerId in edit.remove
        return PlannedChange(
            change = AdminChange(
                PEERS, target(g),
                title = ctx.getString(R.string.admin_group_members_title, g.name),
                effect = ctx.getString(R.string.admin_group_members_effect, edit.add.size, edit.remove.size),
                diff = listOfNotNull(
                    edit.add.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_group_added), null, it.joinToString(", ", transform = peerName)) },
                    edit.remove.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_group_removed), it.joinToString(", ", transform = peerName), null) },
                ),
                warnings = listOfNotNull(
                    if (selfOut) ctx.getString(R.string.admin_group_self_removed) else null,
                    if (selfOut && lostPolicies.isNotEmpty()) ctx.getString(R.string.admin_cutoff_warning, lostPolicies.joinToString(", ")) else null,
                ),
                changeClass = if (selfOut && lostPolicies.isNotEmpty()) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { api -> applyEdits(api, listOf(edit)) },
            verify = { api -> verifyEdits(api, listOf(edit)) },
            undo = if (isUndo) null else setPeers(
                ctx, g.copy(peers = after.map { io.github.bropines.birdsocks.admin.api.ApiPeerMinimum(it, peerName(it)) }),
                before, peerName, selfPeerId, isUndo = true,
            ),
            refreshes = refreshes,
            isUndo = isUndo,
        )
    }

    /** The group's network resources to [after]; [label] names one for the review. */
    fun setResources(ctx: Context, g: ApiGroup, after: List<ApiResource>, label: (ApiResource) -> String, isUndo: Boolean = false): PlannedChange {
        val before = g.resources
        val added = after.filter { a -> before.none { it.id == a.id } }
        val removed = before.filter { b -> after.none { it.id == b.id } }
        return PlannedChange(
            change = AdminChange(
                RESOURCES, target(g),
                title = ctx.getString(R.string.admin_group_resources_title, g.name),
                effect = ctx.getString(R.string.admin_group_resources_effect, added.size, removed.size),
                diff = listOfNotNull(
                    added.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_group_added), null, it.joinToString(", ", transform = label)) },
                    removed.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_group_removed), it.joinToString(", ", transform = label), null) },
                ),
            ),
            apply = { api ->
                editGroup(api, g.id) { r ->
                    val kept = r.resources.orEmpty().filter { c -> removed.none { it.id == c.id } }
                    r.copy(resources = kept + added.filter { a -> kept.none { it.id == a.id } })
                }
            },
            verify = { api ->
                val ids = api.group(g.id).resources.map { it.id }.toSet()
                added.all { it.id in ids } && removed.none { it.id in ids }
            },
            undo = if (isUndo) null else setResources(ctx, g.copy(resources = after), before, label, isUndo = true),
            refreshes = refreshes,
            isUndo = isUndo,
        )
    }

    /** HIGH, the name typed back; [usage] says what references it, and the server refuses a linked group anyway. */
    fun delete(ctx: Context, g: ApiGroup, usage: GroupUsage, selfInGroup: Boolean): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(g),
            title = ctx.getString(R.string.admin_group_delete_title, g.name),
            effect = ctx.getString(R.string.admin_group_delete_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_group_members), g.peers.size.toString(), null)),
            warnings = usageLines(ctx, usage) + listOfNotNull(if (selfInGroup) ctx.getString(R.string.admin_group_self_removed) else null),
        ),
        apply = { it.deleteGroup(g.id) },
        verify = { api -> try { api.group(g.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = listOf(AdminKeys.GROUPS, AdminKeys.PEERS, AdminKeys.SETUP_KEYS),
        done = ctx.getString(R.string.admin_done_deleted, g.name),
    )

    /** [usage] as sentences for a warning list. */
    fun usageLines(ctx: Context, usage: GroupUsage): List<String> = buildList {
        fun line(res: Int, names: List<String>) { if (names.isNotEmpty()) add(ctx.getString(res, names.joinToString(", "))) }
        line(R.string.admin_group_used_policies, usage.policies)
        line(R.string.admin_group_used_routes, usage.routes)
        line(R.string.admin_group_used_nameservers, usage.nameservers)
        line(R.string.admin_group_used_zones, usage.dnsZones)
        line(R.string.admin_group_used_keys, usage.setupKeys)
        line(R.string.admin_group_used_users, usage.users)
        if (usage.networkRouters > 0) add(ctx.getString(R.string.admin_group_used_routers, usage.networkRouters))
        if (usage.dnsManagementDisabled) add(ctx.getString(R.string.admin_group_used_dns))
        if (usage.unchecked.isNotEmpty()) add(ctx.getString(R.string.admin_group_used_unchecked))
    }
}
