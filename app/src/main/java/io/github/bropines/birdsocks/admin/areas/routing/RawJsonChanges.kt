package io.github.bropines.birdsocks.admin.areas.routing

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.Urls
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.state.DataKey
import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * An object edited as JSON, the way the server holds it: the document is
 * read raw (fields no model here knows included), edited, and PUT back raw,
 * so nothing the panel does not understand is lost. Without Compose.
 */
object RawJson {
    /** What GET answers with and PUT does not take, dropped from the body. */
    val SERVER_FIELDS = setOf("id")

    /** `[{"id":"g1","name":…}]` → `["g1"]`: a GET lists groups as objects, a PUT takes ids. Anything else stays. */
    fun idsOf(el: JsonElement?): JsonElement? {
        val arr = el as? JsonArray ?: return el
        return JsonArray(arr.map { item -> (item as? JsonObject)?.get("id")?.takeIf { it is JsonPrimitive } ?: item })
    }

    /** [edited] as the body of a PUT: [drop] left out, [idFields] turned from objects into ids. */
    fun body(edited: JsonObject, drop: Set<String> = SERVER_FIELDS, idFields: Set<String> = emptySet()): JsonObject =
        JsonObject(edited.filterKeys { it !in drop }.mapValues { (k, v) -> if (k in idFields) idsOf(v)!! else v })

    /** The top-level keys whose values differ, added or removed ones included, in a stable order. */
    fun changedKeys(before: JsonObject, after: JsonObject): List<String> =
        (before.keys + after.keys).distinct().filter { before[it] != after[it] }

    /** A value on one line for a diff row; null for a missing one. */
    fun short(el: JsonElement?): String? = when (el) {
        null -> null
        is JsonPrimitive -> if (el.isString) el.content else el.toString()
        else -> el.toString().let { if (it.length > 120) it.take(117) + "…" else it }
    }

    /** An API path from segments, each one percent-encoded: `path("routes", id)` → `/routes/…`. */
    fun path(vararg segments: String): String = segments.joinToString("") { "/" + Urls.seg(it) }

    /** The object's `id`, or "" when it has none. */
    fun id(obj: JsonObject): String = (obj["id"] as? JsonPrimitive)?.jsonPrimitive?.content.orEmpty()
}

/**
 * The PlannedChange for an object saved from the JSON editor. Kinds are the
 * areas' own (`route.json`); the class is MEDIUM unless the area raises it.
 */
object RawJsonChanges {
    /**
     * PUT [after] to [path] in place of [before]. The verify re-reads and
     * compares the fields that were changed; undo puts [before] back.
     */
    fun edit(
        ctx: Context,
        kind: ChangeKind,
        target: ChangeTarget,
        path: String,
        before: JsonObject,
        after: JsonObject,
        refreshes: List<DataKey<*>>,
        idFields: Set<String> = emptySet(),
        changeClass: ChangeClass = kind.defaultClass,
        warnings: List<String> = emptyList(),
        isUndo: Boolean = false,
    ): PlannedChange {
        val body = RawJson.body(after, idFields = idFields)
        val changed = RawJson.changedKeys(RawJson.body(before, idFields = idFields), body)
        return PlannedChange(
            change = AdminChange(
                kind, target,
                title = ctx.getString(R.string.admin_routing_json_title, target.name),
                effect = ctx.getString(R.string.admin_routing_json_effect, changed.joinToString(", ")),
                diff = changed.map { DiffLine(it, RawJson.short(before[it]), RawJson.short(after[it])) },
                warnings = warnings,
                changeClass = changeClass,
                textDiff = JsonText.format(before) to JsonText.format(after),
            ),
            apply = { it.rawSend("PUT", path, body) },
            verify = { api ->
                val now = api.rawGet(path) as? JsonObject
                now != null && RawJson.body(now, idFields = idFields).let { fresh -> changed.all { fresh[it] == body[it] } }
            },
            undo = if (isUndo) null else edit(ctx, kind, target, path, after, before, refreshes, idFields, changeClass, emptyList(), isUndo = true),
            refreshes = refreshes,
            isUndo = isUndo,
        )
    }
}
