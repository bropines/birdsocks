package io.github.bropines.birdsocks.admin.areas.policies

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Every policy as one JSON document: an array, each element a policy in the
 * document form. Saving splits the array back into per-policy writes:
 * elements with a known id that changed are updates, elements without an id
 * are creates, and policies whose id is no longer there are deletes.
 */

/** A reason the array cannot be split; [index] is the element's position. */
data class SetProblem(val kind: Kind, val index: Int? = null, val value: String = "", val policy: PolicyProblem? = null) {
    enum class Kind {
        /** The document is not an array. */
        NOT_ARRAY,
        /** An element is not an object. */
        NOT_OBJECT,
        /** Two elements carry the same id. */
        DUPLICATE_ID,
        /** An id the server does not have: remove it to create a new policy. */
        UNKNOWN_ID,
        /** The element would be refused: [policy] says why. */
        INVALID,
    }
}

/** One update: the server's copy and the edited one. */
data class SetUpdate(val before: PolicyForm, val after: PolicyForm)

/** What saving the array does, write by write. */
data class SetPlan(
    val creates: List<PolicyForm> = emptyList(),
    val updates: List<SetUpdate> = emptyList(),
    val deletes: List<PolicyForm> = emptyList(),
    val unchanged: Int = 0,
    val problems: List<SetProblem> = emptyList(),
) {
    val ok: Boolean get() = problems.isEmpty()
    val empty: Boolean get() = creates.isEmpty() && updates.isEmpty() && deletes.isEmpty()
    val writes: Int get() = creates.size + updates.size + deletes.size

    /** The whole set after the plan: what the access check compares with the current one. */
    fun result(current: List<PolicyForm>): List<PolicyForm> =
        PolicyAccess.replace(current, updates.map { it.after } + creates, deletes.mapNotNull { it.id }.toSet())
}

object PolicySet {

    /** The current set as the document the user edits, in the server's order. */
    fun text(current: List<PolicyForm>): String = PolicyJson.textAll(current)

    /**
     * Splits [edited] against [current] (the server's copies, read fresh).
     * Elements are validated with [net]; any problem leaves the plan's writes
     * empty, so a half-valid document is never half applied.
     */
    fun plan(current: List<PolicyForm>, edited: JsonElement, net: PolicyNet = PolicyNet()): SetPlan {
        val array = edited as? JsonArray ?: return SetPlan(problems = listOf(SetProblem(SetProblem.Kind.NOT_ARRAY)))
        val problems = mutableListOf<SetProblem>()
        val byId = current.filter { !it.isNew }.associateBy { it.id!! }
        val seen = mutableSetOf<String>()
        val creates = mutableListOf<PolicyForm>()
        val updates = mutableListOf<SetUpdate>()
        var unchanged = 0
        array.forEachIndexed { i, e ->
            val o = e as? JsonObject
            if (o == null) {
                problems += SetProblem(SetProblem.Kind.NOT_OBJECT, i)
                return@forEachIndexed
            }
            val form = PolicyJson.fromJson(o)
            PolicyValidation.problems(form, net).forEach { problems += SetProblem(SetProblem.Kind.INVALID, i, form.name, it) }
            val id = form.id
            when {
                id == null -> creates += form
                !seen.add(id) -> problems += SetProblem(SetProblem.Kind.DUPLICATE_ID, i, id)
                id !in byId -> problems += SetProblem(SetProblem.Kind.UNKNOWN_ID, i, id)
                PolicyJson.same(byId.getValue(id), form) -> unchanged++
                else -> updates += SetUpdate(byId.getValue(id), form)
            }
        }
        val deletes = current.filter { !it.isNew && it.id !in seen }
        if (problems.isNotEmpty()) return SetPlan(problems = problems)
        return SetPlan(creates, updates, deletes, unchanged)
    }
}
