package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.ui.JsonText
import io.github.bropines.birdsocks.admin.ui.LineDiff
import kotlinx.serialization.json.JsonObject

/*
 * The checks a policy change passes before it is offered for saving, in
 * order; each runs on what the one before left:
 *
 *  LOCAL   our own validation (NetBird has no validate endpoint): what the
 *          server would refuse, groups and posture checks that do not exist.
 *  FRESH   the server's copy read again: NetBird has no ETags, so a change
 *          made elsewhere since the editor opened is found here; a clean
 *          three-way merge is offered, an overlapping one refuses.
 *  DIFF    the line diff of the document before and after.
 *  RISKS   what the change opens that was not open before.
 *  ACCESS  whether this device loses the last rule that grants it access.
 *
 * The review is pure; the state holder does the reads and hands the results in.
 */

enum class ReviewStep { LOCAL, FRESH, DIFF, RISKS, ACCESS }

/**
 * The server's copy changed since the editor read it. [merged] is the
 * candidate with both sides' changes when they do not overlap; null when
 * they do ([conflictLines] in the server's document).
 */
data class Conflict(val theirs: PolicyForm?, val merged: PolicyForm?, val conflictLines: List<IntRange> = emptyList())

data class PolicyReview(
    /** What the change is about: one policy's name, or null for the whole set. */
    val title: String?,
    val local: List<PolicyProblem> = emptyList(),
    val setProblems: List<SetProblem> = emptyList(),
    val conflict: Conflict? = null,
    val before: String = "",
    val after: String = "",
    val risks: List<RiskFinding> = emptyList(),
    val access: AccessReport = AccessReport(emptyList(), emptyList(), skipped = true),
) {
    val changed: Boolean get() = before != after
    val localOk: Boolean get() = local.isEmpty() && setProblems.isEmpty()

    /** The first step that stops the change; null when it may be offered. */
    val stoppedAt: ReviewStep?
        get() = when {
            !localOk -> ReviewStep.LOCAL
            conflict != null -> ReviewStep.FRESH
            !changed -> ReviewStep.DIFF
            else -> null
        }

    /**
     * Whether the gates may let it through: the checks that block passed, and
     * a cut-off was acknowledged by the person ([acceptCutOff]). Risks warn.
     */
    fun passed(acceptCutOff: Boolean): Boolean = stoppedAt == null && (!access.cutOff || acceptCutOff)

    val added: Int get() = LineDiff.added(LineDiff.diff(LineDiff.lines(before), LineDiff.lines(after)))
    val removed: Int get() = LineDiff.removed(LineDiff.diff(LineDiff.lines(before), LineDiff.lines(after)))
}

object PolicyPipeline {

    /**
     * One policy saved: [base] is what the editor opened (null for a new
     * one), [candidate] the edit, [fresh] the server's copy read now (null
     * for a new one), [current] every policy as read now.
     */
    fun review(base: PolicyForm?, candidate: PolicyForm, fresh: PolicyForm?, current: List<PolicyForm>, net: PolicyNet): PolicyReview {
        val cand = PolicyEdits.fillRuleNames(candidate)
        val local = PolicyValidation.problems(cand, net)
        if (local.isNotEmpty()) return PolicyReview(cand.name, local = local)
        val conflict = if (base != null && fresh != null && !PolicyJson.same(base, fresh)) merge(base, cand, fresh) else null
        if (conflict != null) return PolicyReview(cand.name, conflict = conflict)
        val beforeText = fresh?.let(PolicyJson::text).orEmpty()
        val afterText = PolicyJson.text(cand)
        val after = PolicyAccess.replace(current, listOf(cand))
        return PolicyReview(
            title = cand.name,
            before = beforeText,
            after = afterText,
            risks = PolicyLint.introduced(fresh, cand, net),
            access = PolicyAccess.check(current, after, net),
        )
    }

    /** One policy deleted: nothing to validate; the access check is what matters. */
    fun reviewDelete(target: PolicyForm, current: List<PolicyForm>, net: PolicyNet): PolicyReview {
        val fresh = current.firstOrNull { it.id == target.id }
        return PolicyReview(
            title = target.name,
            conflict = if (fresh == null) Conflict(null, null) else null,
            before = PolicyJson.text(fresh ?: target),
            after = "",
            access = PolicyAccess.check(current, current.filter { it.id != target.id }, net),
        )
    }

    /** One policy switched on or off. */
    fun reviewToggle(target: PolicyForm, on: Boolean, current: List<PolicyForm>, net: PolicyNet): PolicyReview {
        val fresh = current.firstOrNull { it.id == target.id } ?: return PolicyReview(target.name, conflict = Conflict(null, null))
        return review(fresh, fresh.copy(enabled = on), fresh, current, net)
    }

    /**
     * The whole set saved: [loaded] is what the document was made from,
     * [current] the server's copies read now, [plan] the split.
     */
    fun reviewSet(loaded: List<PolicyForm>, current: List<PolicyForm>, plan: SetPlan, net: PolicyNet): PolicyReview {
        if (!plan.ok) return PolicyReview(null, setProblems = plan.problems)
        if (PolicyJson.textAll(loaded) != PolicyJson.textAll(current)) return PolicyReview(null, conflict = Conflict(null, null))
        val after = plan.result(current)
        val byId = current.associateBy { it.id }
        return PolicyReview(
            title = null,
            before = PolicyJson.textAll(current),
            after = PolicyJson.textAll(after),
            risks = plan.creates.flatMap { PolicyLint.introduced(null, it, net) } + plan.updates.flatMap { PolicyLint.introduced(byId[it.after.id], it.after, net) },
            access = PolicyAccess.check(current, after, net),
        )
    }

    /**
     * Three-way merge of the documents as text: the editor's base, the
     * candidate and the server's copy. A merge that does not parse back is a conflict.
     */
    fun merge(base: PolicyForm, mine: PolicyForm, theirs: PolicyForm): Conflict {
        val m = LineDiff.merge3(LineDiff.lines(PolicyJson.text(base)), LineDiff.lines(PolicyJson.text(mine)), LineDiff.lines(PolicyJson.text(theirs)))
        val merged = m.lines?.joinToString("\n")?.let { JsonText.parse(it) as? JsonObject }?.let(PolicyJson::fromJson)
        return Conflict(theirs, merged, m.conflicts)
    }
}
