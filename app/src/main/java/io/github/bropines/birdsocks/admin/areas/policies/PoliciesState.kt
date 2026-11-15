package io.github.bropines.birdsocks.admin.areas.policies

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.AdminNote
import io.github.bropines.birdsocks.admin.AreaState
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the editor shows: the form, or the same policy as JSON. */
enum class EditorMode { VISUAL, JSON }

/**
 * A policy open in the editor. [base] is the server's copy it was opened
 * from (null for a new one); [draft] is the edit, the only state the visual
 * and JSON modes share — each mode reads it and writes it back whole.
 */
class PolicyEditor(val base: PolicyForm?, draft: PolicyForm) {
    var draft by mutableStateOf(draft)
    var mode by mutableStateOf(EditorMode.VISUAL)
    val dirty: Boolean get() = base == null || !PolicyJson.same(base, draft)
}

/** A change waiting for its review: what the pipeline is about, and how to build the PlannedChange. */
sealed interface PendingChange {
    data class Save(val candidate: PolicyForm, val base: PolicyForm?) : PendingChange
    data class Toggle(val policy: PolicyForm, val on: Boolean) : PendingChange
    data class Delete(val policy: PolicyForm) : PendingChange
    data class ApplySet(val loaded: List<PolicyForm>, val plan: SetPlan) : PendingChange
}

/**
 * The pipeline's result on screen. [fresh] is the server's copy the review
 * compared with (the write re-checks it); [current] every policy as read then.
 */
data class ReviewState(
    val pending: PendingChange,
    val review: PolicyReview? = null,
    val fresh: PolicyForm? = null,
    val error: Throwable? = null,
) {
    val running: Boolean get() = review == null && error == null
}

/**
 * The Policies area's state: the editor, the review under way, the
 * whole-set document, network resources for the pickers. Reads only; the
 * writes are [PolicyChanges].
 */
class PoliciesState(host: AdminHost) : AreaState(host) {
    var editor by mutableStateOf<PolicyEditor?>(null)
    var opening by mutableStateOf(false)
    var review by mutableStateOf<ReviewState?>(null)
    /** The whole set as one document: what it was made from, null when closed. */
    var setLoaded by mutableStateOf<List<PolicyForm>?>(null)
    var setText by mutableStateOf("")
    var resources by mutableStateOf<List<ApiNetworkResource>?>(null)
    private var job: Job? = null

    fun net(): PolicyNet {
        val d = host.data
        val peers = d[AdminKeys.PEERS].value?.items
        return PolicyNet.of(
            groups = d[AdminKeys.GROUPS].value?.items,
            peers = peers,
            postureCheckIds = d[AdminKeys.POSTURE_CHECKS].value?.items?.map { it.id }?.toSet(),
            self = peers?.firstOrNull(host::isSelf),
            resourceNames = resources.orEmpty().associate { it.id to it.name.ifEmpty { it.address } },
        )
    }

    /** The cached policies as forms (the list's view; the editor reads fresh). */
    fun cached(): List<PolicyForm> = host.data.items(AdminKeys.POLICIES).map(PolicyJson::fromApi)

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = host.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.notify(AdminNote.error(e))
            } finally {
                opening = false
            }
        }
    }

    /** Opens [id] in the editor from a fresh read, so unknown fields come along; null opens a new policy. */
    fun open(id: String?) {
        if (id == null) {
            editor = PolicyEditor(null, PolicyEdits.newPolicy())
            return
        }
        val api = host.api ?: return
        opening = true
        launch { PolicyChanges.read(api, id).let { editor = PolicyEditor(it, it) } }
    }

    /** Opens a ready policy (a template's) as a new one. */
    fun openNew(p: PolicyForm) {
        editor = PolicyEditor(null, p)
    }

    fun close() {
        editor = null
    }

    /** Opens the whole set as one document, from a fresh read. */
    fun openSet() {
        val api = host.api ?: return
        opening = true
        launch {
            val all = PolicyChanges.readAll(api)
            setLoaded = all
            setText = PolicySet.text(all)
        }
    }

    fun closeSet() {
        setLoaded = null
        setText = ""
    }

    /** Reads every network's resources once, for the resource picker. */
    fun loadResources() {
        if (resources != null) return
        val api = host.api ?: return
        host.scope.launch {
            resources = runCatching {
                api.networks().items.flatMap { n -> runCatching { api.networkResources(n.id).items }.getOrDefault(emptyList()) }
            }.getOrNull() ?: emptyList()
        }
    }

    /** Runs the pipeline for [pending]: reads the server's copies again, then checks. */
    fun check(pending: PendingChange) {
        val api = host.api ?: return
        review = ReviewState(pending)
        job?.cancel()
        job = host.scope.launch {
            review = try {
                val current = PolicyChanges.readAll(api)
                val net = net()
                when (pending) {
                    is PendingChange.Save -> {
                        val fresh = pending.base?.id?.let { id -> current.firstOrNull { it.id == id } }
                        ReviewState(pending, PolicyPipeline.review(pending.base, pending.candidate, fresh, current, net).let { r ->
                            // A policy deleted elsewhere since it was opened: nothing to update.
                            if (pending.base != null && fresh == null) r.copy(conflict = Conflict(null, null)) else r
                        }, fresh)
                    }
                    is PendingChange.Toggle -> ReviewState(pending, PolicyPipeline.reviewToggle(pending.policy, pending.on, current, net), current.firstOrNull { it.id == pending.policy.id })
                    is PendingChange.Delete -> ReviewState(pending, PolicyPipeline.reviewDelete(pending.policy, current, net), current.firstOrNull { it.id == pending.policy.id })
                    is PendingChange.ApplySet -> ReviewState(pending, PolicyPipeline.reviewSet(pending.loaded, current, pending.plan, net))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReviewState(pending, error = e)
            }
        }
    }

    /** The JSON text of the whole set split into a plan, or the problems. */
    fun planSet(text: String): SetPlan {
        val loaded = setLoaded ?: return SetPlan()
        val parsed = JsonText.parse(text) ?: return SetPlan(problems = listOf(SetProblem(SetProblem.Kind.NOT_ARRAY)))
        return PolicySet.plan(loaded, parsed, net())
    }

    /** Builds the change the review is about and hands it to the gates; true when it went on. */
    fun propose(ctx: Context, acceptCutOff: Boolean): Boolean {
        val r = review ?: return false
        val rv = r.review ?: return false
        val net = net()
        val change = when (val p = r.pending) {
            is PendingChange.Save -> PolicyChanges.save(ctx, p.candidate, r.fresh, rv, acceptCutOff, net)
            is PendingChange.Toggle -> PolicyChanges.setEnabled(ctx, r.fresh ?: return false, p.on, rv, acceptCutOff)
            is PendingChange.Delete -> PolicyChanges.delete(ctx, r.fresh ?: return false, rv, acceptCutOff, net)
            is PendingChange.ApplySet -> PolicyChanges.applySet(ctx, p.loaded, p.plan, rv, acceptCutOff)
        }
        val ok = host.propose(change)
        if (ok) {
            review = null
            when (r.pending) {
                is PendingChange.Save -> editor = null
                is PendingChange.ApplySet -> closeSet()
                else -> Unit
            }
        }
        return ok
    }

    /** The conflict's clean merge becomes the draft, checked again. */
    fun takeMerge() {
        val r = review ?: return
        val merged = r.review?.conflict?.merged ?: return
        val theirs = r.review.conflict.theirs ?: return
        val ed = editor ?: return
        editor = PolicyEditor(theirs, merged)
        check(PendingChange.Save(merged, theirs))
        ed.mode.let { editor?.mode = it }
    }

    fun dismissReview() {
        job?.cancel()
        review = null
    }

    override fun onCleared() {
        job?.cancel()
    }
}
