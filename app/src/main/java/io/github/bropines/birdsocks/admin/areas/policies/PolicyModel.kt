package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * A policy as the editors see it, without Compose or Android.
 *
 * NetBird keeps each policy as its own REST object. The editors work on a
 * [PolicyForm]: the fields they know, typed, plus whatever else the object
 * carries in `extra` (on the policy and on each rule), so a field a newer
 * server added survives a visual edit and a trip through the JSON mode.
 *
 * The JSON the user edits is the *document* form: the request body plus the
 * ids (policy and rules), groups as their ids. [PolicyJson.normalize] turns a
 * GET answer (groups as objects) into it; [PolicyJson.request] turns it into
 * what POST and PUT take.
 */

/** A port or a range of ports; a single port has [start] == [end]. */
data class PortSpan(val start: Int, val end: Int = start) {
    val single: Boolean get() = start == end
    val width: Int get() = end - start + 1
    override fun toString(): String = if (single) "$start" else "$start-$end"
}

/** A network resource or a peer as a rule's one source or destination. */
data class ResourceRef(val id: String, val type: String) {
    val isPeer: Boolean get() = type == TYPE_PEER

    companion object {
        const val TYPE_PEER = "peer"
    }
}

/** One rule, typed; [extra] keeps the fields this form does not know. */
data class RuleForm(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val action: String = ACCEPT,
    val bidirectional: Boolean = true,
    val protocol: String = ALL,
    val ports: List<PortSpan> = emptyList(),
    /** Port entries that are not numbers: kept so the check names them and the JSON keeps them. */
    val badPorts: List<String> = emptyList(),
    /** The rule wrote its ports as `port_ranges`: keep doing so even when every range is one port. */
    val portsAsRanges: Boolean = false,
    val sources: List<String> = emptyList(),
    val sourceResource: ResourceRef? = null,
    val destinations: List<String> = emptyList(),
    val destinationResource: ResourceRef? = null,
    /** netbird-ssh: per source group, the local users it may log in as. */
    val authorizedGroups: Map<String, List<String>> = emptyMap(),
    val extra: JsonObject = EMPTY,
) {
    val isAccept: Boolean get() = action == ACCEPT
    val takesPorts: Boolean get() = protocol == TCP || protocol == UDP

    companion object {
        const val ACCEPT = "accept"
        const val DROP = "drop"
        const val ALL = "all"
        const val TCP = "tcp"
        const val UDP = "udp"
        const val ICMP = "icmp"
        const val SSH = "netbird-ssh"
        val ACTIONS = listOf(ACCEPT, DROP)
        val PROTOCOLS = listOf(ALL, TCP, UDP, ICMP, SSH)
    }
}

/** One policy, typed; [id] is null for one not created yet. */
data class PolicyForm(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val postureChecks: List<String> = emptyList(),
    val rules: List<RuleForm> = emptyList(),
    val extra: JsonObject = EMPTY,
) {
    val isNew: Boolean get() = id.isNullOrEmpty()
}

internal val EMPTY = JsonObject(emptyMap())

object PolicyJson {

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private val POLICY_KEYS = listOf("id", "name", "description", "enabled", "source_posture_checks", "rules")
    private val RULE_KEYS = listOf(
        "id", "name", "description", "enabled", "action", "bidirectional", "protocol", "ports", "port_ranges",
        "sources", "sourceResource", "destinations", "destinationResource", "authorized_groups",
    )

    // ------------------------------------------------------------ server → document

    /**
     * A GET answer as the document the editors show: groups by id, null
     * fields dropped, `sources` left out when a resource stands in for them.
     */
    fun normalize(server: JsonObject): JsonObject = toJson(fromJson(server))

    /** A typed policy from the shared list (fields it does not model are not there: the editor reads fresh). */
    fun fromApi(p: ApiPolicy): PolicyForm = fromJson(AppJson.encodeToJsonElement(ApiPolicy.serializer(), p) as JsonObject)

    // ------------------------------------------------------------ document ↔ form

    /** Reads a policy, tolerant of either shape: groups as ids or as objects with an id. */
    fun fromJson(o: JsonObject): PolicyForm = PolicyForm(
        id = o.str("id")?.takeIf { it.isNotEmpty() },
        name = o.str("name").orEmpty(),
        description = o.str("description").orEmpty(),
        enabled = o.bool("enabled") ?: false,
        postureChecks = o.ids("source_posture_checks"),
        rules = (o["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::ruleFromJson) },
        extra = o.without(POLICY_KEYS),
    )

    fun ruleFromJson(o: JsonObject): RuleForm {
        val good = mutableListOf<PortSpan>()
        val bad = mutableListOf<String>()
        (o["ports"] as? JsonArray).orEmpty().forEach { e ->
            val s = (e as? JsonPrimitive)?.content?.trim().orEmpty()
            val n = s.toIntOrNull()
            if (n != null) good += PortSpan(n) else bad += s
        }
        val ranges = (o["port_ranges"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val start = (r["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            PortSpan(start, (r["end"] as? JsonPrimitive)?.intOrNull ?: start)
        }
        return RuleForm(
            id = o.str("id")?.takeIf { it.isNotEmpty() },
            name = o.str("name").orEmpty(),
            description = o.str("description").orEmpty(),
            enabled = o.bool("enabled") ?: false,
            action = o.str("action") ?: RuleForm.ACCEPT,
            bidirectional = o.bool("bidirectional") ?: false,
            protocol = o.str("protocol") ?: RuleForm.ALL,
            ports = good + ranges,
            badPorts = bad,
            portsAsRanges = ranges.isNotEmpty(),
            sources = o.ids("sources"),
            sourceResource = resource(o["sourceResource"]),
            destinations = o.ids("destinations"),
            destinationResource = resource(o["destinationResource"]),
            authorizedGroups = (o["authorized_groups"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            },
            extra = o.without(RULE_KEYS),
        )
    }

    /** The document form of [p]: the known fields in a fixed order, then the rest as they came. */
    fun toJson(p: PolicyForm): JsonObject = buildJsonObject {
        p.id?.let { put("id", it) }
        put("name", p.name)
        put("description", p.description)
        put("enabled", p.enabled)
        put("source_posture_checks", strings(p.postureChecks))
        put("rules", JsonArray(p.rules.map(::ruleToJson)))
        p.extra.forEach { (k, v) -> put(k, v) }
    }

    fun ruleToJson(r: RuleForm): JsonObject = buildJsonObject {
        r.id?.let { put("id", it) }
        put("name", r.name)
        put("description", r.description)
        put("enabled", r.enabled)
        put("action", r.action)
        put("bidirectional", r.bidirectional)
        put("protocol", r.protocol)
        val asRanges = r.portsAsRanges || r.ports.any { !it.single }
        if (!asRanges && (r.ports.isNotEmpty() || r.badPorts.isNotEmpty())) {
            put("ports", strings(r.ports.map { it.start.toString() } + r.badPorts))
        }
        if (asRanges && r.ports.isNotEmpty()) {
            put("port_ranges", buildJsonArray { r.ports.forEach { s -> add(buildJsonObject { put("start", s.start); put("end", s.end) }) } })
            if (r.badPorts.isNotEmpty()) put("ports", strings(r.badPorts))
        }
        if (r.sourceResource != null) put("sourceResource", resourceJson(r.sourceResource)) else put("sources", strings(r.sources))
        if (r.destinationResource != null) put("destinationResource", resourceJson(r.destinationResource)) else put("destinations", strings(r.destinations))
        if (r.authorizedGroups.isNotEmpty()) put("authorized_groups", JsonObject(r.authorizedGroups.mapValues { strings(it.value) }))
        r.extra.forEach { (k, v) -> put(k, v) }
    }

    // ------------------------------------------------------------ document → request

    /**
     * The body of a POST ([create]) or PUT: the document without the
     * policy's id; a create sends no rule ids either. Unknown fields go as
     * they are (the server ignores what it does not know).
     */
    fun request(doc: JsonObject, create: Boolean): JsonObject {
        val form = fromJson(doc)
        return toJson(form.copy(id = null, rules = if (create) form.rules.map { it.copy(id = null) } else form.rules))
    }

    // ------------------------------------------------------------ text

    /** The document as text, two-space indented: what the JSON mode shows and the diff compares. */
    fun text(doc: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), doc)

    fun text(p: PolicyForm): String = text(toJson(p))

    /** Several policies as one array. */
    fun textAll(policies: List<PolicyForm>): String = text(JsonArray(policies.map(::toJson)))

    /** Same meaning: equal document forms. */
    fun same(a: PolicyForm, b: PolicyForm): Boolean = toJson(a) == toJson(b)

    // ------------------------------------------------------------ helpers

    private fun resource(e: JsonElement?): ResourceRef? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id").orEmpty()
        val type = o.str("type").orEmpty()
        return if (id.isEmpty() && type.isEmpty()) null else ResourceRef(id, type)
    }

    private fun resourceJson(r: ResourceRef) = buildJsonObject { put("id", r.id); put("type", r.type) }
}

internal fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Ids from an array of strings or of objects that carry an `id`. */
internal fun JsonObject.ids(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { e ->
    when (e) {
        is JsonObject -> e.str("id")
        is JsonNull -> null
        is JsonPrimitive -> e.content
        else -> null
    }
}

internal fun JsonObject.without(keys: Collection<String>): JsonObject = JsonObject(filterKeys { it !in keys })
