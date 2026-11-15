package io.github.bropines.birdsocks.admin.areas.policies

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.api.Urls
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeEvidence
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The Policies area's writes. Every one of them carries the pipeline's
 * findings ([PolicyReview]) as evidence and re-reads the server's copy right
 * before writing: NetBird has no ETags, so a policy changed elsewhere since
 * the review is refused rather than overwritten.
 *
 * Bodies go through the raw JSON calls so fields this app does not model
 * survive a save ([PolicyJson.request]).
 */
object PolicyChanges {
    val SAVE = ChangeKind("policy.save", Capabilities.POLICIES, Op.UPDATE, ChangeClass.POLICY)
    val CREATE = ChangeKind("policy.create", Capabilities.POLICIES, Op.CREATE, ChangeClass.POLICY)
    val TOGGLE = ChangeKind("policy.toggle", Capabilities.POLICIES, Op.UPDATE, ChangeClass.POLICY)
    val DELETE = ChangeKind("policy.delete", Capabilities.POLICIES, Op.DELETE, ChangeClass.POLICY)
    val SET = ChangeKind("policy.set", Capabilities.POLICIES, Op.UPDATE, ChangeClass.POLICY)

    private fun target(p: PolicyForm) = ChangeTarget(TargetType.POLICY, p.id.orEmpty(), p.name)

    private fun path(id: String) = "/policies/${Urls.seg(id)}"

    /** The server's copy of one policy now, in the document form. */
    suspend fun read(api: NetbirdApi, id: String): PolicyForm =
        PolicyJson.fromJson(PolicyJson.normalize(api.rawGet(path(id)) as? JsonObject ?: throw AdminApiException.Decode("policy")))

    /** Every policy now, in the server's order. */
    suspend fun readAll(api: NetbirdApi): List<PolicyForm> =
        (api.rawGet("/policies") as? JsonArray ?: throw AdminApiException.Decode("policy list"))
            .mapNotNull { (it as? JsonObject)?.let { o -> PolicyJson.fromJson(PolicyJson.normalize(o)) } }

    /** Compared by meaning, so an undo is not refused over the rule ids the server just gave. */
    private suspend fun requireUnchanged(ctx: Context, api: NetbirdApi, expected: PolicyForm) {
        val now = read(api, expected.id!!)
        if (PolicyMeaning.of(now) != PolicyMeaning.of(PolicyEdits.fillRuleNames(expected))) throw AdminApiException.Conflict(ctx.getString(R.string.admin_policy_stale))
    }

    /** The pipeline's findings in words, for the review. */
    fun evidence(ctx: Context, review: PolicyReview, acceptCutOff: Boolean): ChangeEvidence {
        val notes = buildList {
            add(ctx.getString(R.string.admin_policy_ev_local))
            add(ctx.getString(R.string.admin_policy_ev_fresh))
            add(ctx.getString(R.string.admin_policy_ev_diff, review.added, review.removed))
            add(if (review.risks.isEmpty()) ctx.getString(R.string.admin_policy_ev_no_risks) else ctx.resources.getQuantityString(R.plurals.admin_policy_ev_risks, review.risks.size, review.risks.size))
            add(
                when {
                    review.access.skipped -> ctx.getString(R.string.admin_policy_ev_access_skipped)
                    review.access.cutOff -> ctx.getString(R.string.admin_policy_ev_access_cut)
                    review.access.lost.isNotEmpty() -> ctx.resources.getQuantityString(R.plurals.admin_policy_ev_access_lost, review.access.lost.size, review.access.lost.size)
                    else -> ctx.getString(R.string.admin_policy_ev_access_kept)
                }
            )
        }
        return ChangeEvidence(review.passed(acceptCutOff), notes)
    }

    private fun warnings(ctx: Context, review: PolicyReview): List<String> = buildList {
        if (review.access.cutOff) add(ctx.getString(R.string.admin_policy_cutoff_warning))
        review.risks.forEach { add(PolicyText.risk(ctx, it)) }
    }

    /**
     * Saves [candidate]: a create when it is new, otherwise a PUT over
     * [fresh] (the copy the review compared with), refused when the server's
     * copy changed since. The undo of an update puts [fresh] back.
     */
    fun save(ctx: Context, candidate: PolicyForm, fresh: PolicyForm?, review: PolicyReview, acceptCutOff: Boolean, net: PolicyNet = PolicyNet(), isUndo: Boolean = false): PlannedChange {
        val cand = PolicyEdits.fillRuleNames(candidate)
        val create = cand.isNew || fresh == null
        return PlannedChange(
            change = AdminChange(
                if (create) CREATE else SAVE, target(cand),
                title = ctx.getString(if (create) R.string.admin_policy_create_title else R.string.admin_policy_save_title, cand.name),
                effect = PolicyText.summary(ctx, cand, net),
                warnings = warnings(ctx, review),
                evidence = evidence(ctx, review, acceptCutOff),
                textDiff = review.before to review.after,
                diff = if (fresh != null && fresh.enabled != cand.enabled) listOf(DiffLine(ctx.getString(R.string.admin_policy_enabled), PolicyText.onOff(ctx, fresh.enabled), PolicyText.onOff(ctx, cand.enabled))) else emptyList(),
            ),
            apply = { api ->
                if (create) {
                    api.rawSend("POST", "/policies", PolicyJson.request(PolicyJson.toJson(cand), create = true))
                } else {
                    requireUnchanged(ctx, api, fresh!!)
                    api.rawSend("PUT", path(cand.id!!), PolicyJson.request(PolicyJson.toJson(cand), create = false))
                }
            },
            verify = if (create) null else { api -> PolicyMeaning.of(read(api, cand.id!!)) == PolicyMeaning.of(cand) },
            undo = if (isUndo || create || fresh == null) null else save(ctx, fresh, cand, undoReview(review), acceptCutOff = true, net = net, isUndo = true),
            refreshes = listOf(AdminKeys.POLICIES),
            done = ctx.getString(if (create) R.string.admin_policy_created else R.string.admin_policy_saved, cand.name),
            isUndo = isUndo,
        )
    }

    /** Switches [fresh] on or off; switching off can cut this device off, so it runs the access check like a save. */
    fun setEnabled(ctx: Context, fresh: PolicyForm, on: Boolean, review: PolicyReview, acceptCutOff: Boolean, isUndo: Boolean = false): PlannedChange {
        val cand = fresh.copy(enabled = on)
        return PlannedChange(
            change = AdminChange(
                TOGGLE, target(fresh),
                title = ctx.getString(if (on) R.string.admin_policy_enable_title else R.string.admin_policy_disable_title, fresh.name),
                effect = ctx.getString(if (on) R.string.admin_policy_on_effect else R.string.admin_policy_off_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_policy_enabled), PolicyText.onOff(ctx, fresh.enabled), PolicyText.onOff(ctx, on))),
                warnings = warnings(ctx, review),
                evidence = evidence(ctx, review, acceptCutOff),
            ),
            apply = { api ->
                requireUnchanged(ctx, api, fresh)
                api.rawSend("PUT", path(fresh.id!!), PolicyJson.request(PolicyJson.toJson(cand), create = false))
            },
            verify = { api -> read(api, fresh.id!!).enabled == on },
            undo = if (isUndo) null else setEnabled(ctx, cand, !on, undoReview(review), acceptCutOff = true, isUndo = true),
            refreshes = listOf(AdminKeys.POLICIES),
            isUndo = isUndo,
        )
    }

    /** Deletes [fresh]; the undo creates it again (with new ids). */
    fun delete(ctx: Context, fresh: PolicyForm, review: PolicyReview, acceptCutOff: Boolean, net: PolicyNet = PolicyNet()): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(fresh),
            title = ctx.getString(R.string.admin_policy_delete_title, fresh.name),
            effect = ctx.getString(R.string.admin_policy_delete_effect),
            warnings = warnings(ctx, review),
            evidence = evidence(ctx, review, acceptCutOff),
            textDiff = review.before to review.after,
        ),
        apply = { api ->
            requireUnchanged(ctx, api, fresh)
            api.rawSend("DELETE", path(fresh.id!!), null)
        },
        verify = { api -> try { read(api, fresh.id!!); false } catch (_: AdminApiException.NotFound) { true } },
        undo = save(ctx, fresh.copy(id = null), null, undoReview(review), acceptCutOff = true, net = net, isUndo = true),
        refreshes = listOf(AdminKeys.POLICIES),
        done = ctx.getString(R.string.admin_done_deleted, fresh.name),
    )

    /**
     * The whole set at once: [plan]'s creates, updates and deletes in that
     * order, after checking that no policy changed on the server since
     * [loaded] was read. One confirm phrase for all of them.
     */
    fun applySet(ctx: Context, loaded: List<PolicyForm>, plan: SetPlan, review: PolicyReview, acceptCutOff: Boolean): PlannedChange {
        val phrase = ctx.getString(R.string.admin_policy_set_phrase)
        return PlannedChange(
            change = AdminChange(
                SET, ChangeTarget(TargetType.POLICY, "", ctx.getString(R.string.admin_policy_set_target)),
                title = ctx.getString(R.string.admin_policy_set_title),
                effect = ctx.getString(R.string.admin_policy_set_effect, plan.creates.size, plan.updates.size, plan.deletes.size),
                warnings = warnings(ctx, review),
                evidence = evidence(ctx, review, acceptCutOff),
                textDiff = review.before to review.after,
                confirmPhrase = phrase,
                diff = plan.creates.map { DiffLine(ctx.getString(R.string.admin_policy_set_create), null, it.name) } +
                    plan.updates.map { DiffLine(ctx.getString(R.string.admin_policy_set_update), it.before.name, it.after.name) } +
                    plan.deletes.map { DiffLine(ctx.getString(R.string.admin_policy_set_delete), it.name, null) },
            ),
            apply = { api ->
                if (PolicyJson.textAll(readAll(api)) != PolicyJson.textAll(loaded)) throw AdminApiException.Conflict(ctx.getString(R.string.admin_policy_stale))
                plan.creates.forEach { api.rawSend("POST", "/policies", PolicyJson.request(PolicyJson.toJson(PolicyEdits.fillRuleNames(it)), create = true)) }
                plan.updates.forEach { api.rawSend("PUT", path(it.after.id!!), PolicyJson.request(PolicyJson.toJson(PolicyEdits.fillRuleNames(it.after)), create = false)) }
                plan.deletes.forEach { api.rawSend("DELETE", path(it.id!!), null) }
            },
            verify = { api ->
                val now = readAll(api).associateBy { it.id }
                plan.updates.all { u -> now[u.after.id]?.let { PolicyMeaning.of(it) == PolicyMeaning.of(PolicyEdits.fillRuleNames(u.after)) } == true } &&
                    plan.deletes.none { it.id in now }
            },
            refreshes = listOf(AdminKeys.POLICIES),
        )
    }

    /** An undo puts back what was there: its checks are the forward change's, with nothing to block. */
    private fun undoReview(r: PolicyReview) = r.copy(before = r.after, after = r.before, risks = emptyList(), access = AccessReport(emptyList(), emptyList(), skipped = true))
}

/**
 * What a policy means, without ids and without fields the server may
 * reorder: what verify compares after a write.
 */
object PolicyMeaning {
    fun of(p: PolicyForm): List<Any?> = listOf(
        p.name, p.description, p.enabled, p.postureChecks.sorted(),
        p.rules.map { r ->
            listOf(
                r.name, r.enabled, r.action, r.bidirectional, r.protocol, r.ports.sortedBy { it.start }.map { it.toString() },
                r.sources.sorted(), r.sourceResource, r.destinations.sorted(), r.destinationResource,
                r.authorizedGroups.mapValues { it.value.sorted() }.toSortedMap(),
            )
        },
    )
}
