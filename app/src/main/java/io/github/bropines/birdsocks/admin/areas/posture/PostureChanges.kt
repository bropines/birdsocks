package io.github.bropines.birdsocks.admin.areas.posture

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.api.Urls
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import kotlinx.serialization.json.JsonObject

/**
 * The Posture checks area's writes, through the raw JSON calls so a check
 * type this app does not draw survives. An edit of a check that policies use
 * changes who those policies admit: HIGH, with the policies named. NetBird
 * refuses to delete a check a policy still names; the screen says so first.
 */
object PostureChanges {
    val CREATE = ChangeKind("posture.create", Capabilities.POLICIES, Op.CREATE, ChangeClass.MEDIUM)
    val UPDATE = ChangeKind("posture.update", Capabilities.POLICIES, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("posture.delete", Capabilities.POLICIES, Op.DELETE, ChangeClass.HIGH)

    private fun path(id: String) = "/posture-checks/${Urls.seg(id)}"

    private fun target(f: PostureForm) = ChangeTarget(TargetType.POSTURE_CHECK, f.id.orEmpty(), f.name)

    suspend fun read(api: NetbirdApi, id: String): PostureForm =
        PostureJson.fromJson(api.rawGet(path(id)) as? JsonObject ?: throw AdminApiException.Decode("posture check"))

    /** [usedBy] names the policies that use it, for the warning. */
    fun save(ctx: Context, f: PostureForm, base: PostureForm?, usedBy: List<String>): PlannedChange {
        val create = base == null || f.isNew
        return PlannedChange(
            change = AdminChange(
                if (create) CREATE else UPDATE, target(f),
                title = ctx.getString(if (create) R.string.admin_posture_create_title else R.string.admin_posture_save_title, f.name),
                effect = ctx.getString(if (create) R.string.admin_posture_create_effect else R.string.admin_posture_save_effect),
                warnings = if (usedBy.isEmpty()) emptyList() else listOf(ctx.getString(R.string.admin_posture_used_warning, usedBy.joinToString(", "))),
                changeClass = if (!create && usedBy.isNotEmpty()) ChangeClass.HIGH else ChangeClass.MEDIUM,
                textDiff = (base?.let(PostureJson::text) ?: "") to PostureJson.text(f),
            ),
            apply = { api ->
                if (create) api.rawSend("POST", "/posture-checks", PostureJson.request(f))
                else {
                    // No ETags: refuse when someone changed it since the editor read it.
                    if (!PostureJson.same(read(api, f.id!!), base!!)) throw AdminApiException.Conflict(ctx.getString(R.string.admin_posture_stale))
                    api.rawSend("PUT", path(f.id), PostureJson.request(f))
                }
            },
            verify = if (create) null else { api -> PostureJson.same(read(api, f.id!!), f) },
            undo = if (create || base == null) null else save(ctx, base, f, usedBy).let { u ->
                PlannedChange(u.change, u.apply, u.verify, null, u.refreshes, null, isUndo = true)
            },
            refreshes = listOf(AdminKeys.POSTURE_CHECKS),
            done = ctx.getString(if (create) R.string.admin_posture_created else R.string.admin_posture_saved, f.name),
        )
    }

    fun delete(ctx: Context, f: PostureForm): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(f),
            title = ctx.getString(R.string.admin_posture_delete_title, f.name),
            effect = ctx.getString(R.string.admin_posture_delete_effect),
            textDiff = PostureJson.text(f) to "",
        ),
        apply = { api -> api.rawSend("DELETE", path(f.id!!), null) },
        verify = { api -> try { read(api, f.id!!); false } catch (_: AdminApiException.NotFound) { true } },
        undo = PlannedChange(
            change = AdminChange(
                CREATE, target(f.copy(id = null)),
                title = ctx.getString(R.string.admin_posture_create_title, f.name),
                effect = ctx.getString(R.string.admin_posture_create_effect),
            ),
            apply = { api -> api.rawSend("POST", "/posture-checks", PostureJson.request(f)) },
            refreshes = listOf(AdminKeys.POSTURE_CHECKS),
            isUndo = true,
        ),
        refreshes = listOf(AdminKeys.POSTURE_CHECKS),
        done = ctx.getString(R.string.admin_done_deleted, f.name),
    )
}
