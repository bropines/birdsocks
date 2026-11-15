package io.github.bropines.birdsocks.admin.areas.posture

import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiPostureCheck
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A posture check as its editor sees it, without Compose. Each check type
 * the spec has is a typed part; everything else — the certificate check, a
 * type a newer server added, unknown fields of a part — rides along in the
 * `extra` objects, so the visual editor and the JSON mode lose nothing.
 */

private val EMPTY = JsonObject(emptyMap())

data class VersionForm(val minVersion: String = "", val extra: JsonObject = EMPTY)

/** Minimum versions per OS; null leaves that OS unchecked. Linux and Windows are kernel versions. */
data class OsForm(
    val android: String? = null,
    val darwin: String? = null,
    val ios: String? = null,
    val linux: String? = null,
    val windows: String? = null,
    val extra: JsonObject = EMPTY,
) {
    val isEmpty: Boolean get() = listOf(android, darwin, ios, linux, windows).all { it == null }
}

data class LocationForm(val countryCode: String = "", val cityName: String = "")

data class GeoForm(val action: String = ALLOW, val locations: List<LocationForm> = emptyList(), val extra: JsonObject = EMPTY)

data class NetworkForm(val action: String = ALLOW, val ranges: List<String> = emptyList(), val extra: JsonObject = EMPTY)

data class ProcessForm(val linuxPath: String = "", val macPath: String = "", val windowsPath: String = "")

data class ProcessesForm(val processes: List<ProcessForm> = emptyList(), val extra: JsonObject = EMPTY)

const val ALLOW = "allow"
const val DENY = "deny"

/** The check types this editor draws; the order of its sections. */
enum class CheckType(val key: String) {
    NB_VERSION("nb_version_check"),
    OS_VERSION("os_version_check"),
    GEO_LOCATION("geo_location_check"),
    NETWORK_RANGE("peer_network_range_check"),
    PROCESS("process_check"),
}

data class PostureForm(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val nbVersion: VersionForm? = null,
    val os: OsForm? = null,
    val geo: GeoForm? = null,
    val network: NetworkForm? = null,
    val process: ProcessesForm? = null,
    /** Checks this editor does not draw (certificate_check, newer ones), as they came. */
    val otherChecks: JsonObject = EMPTY,
    val extra: JsonObject = EMPTY,
) {
    val isNew: Boolean get() = id.isNullOrEmpty()

    /** The types present, the ones drawn and the others by their JSON key. */
    val types: List<String> get() = listOfNotNull(
        nbVersion?.let { CheckType.NB_VERSION.key }, os?.let { CheckType.OS_VERSION.key }, geo?.let { CheckType.GEO_LOCATION.key },
        network?.let { CheckType.NETWORK_RANGE.key }, process?.let { CheckType.PROCESS.key },
    ) + otherChecks.keys

    fun has(t: CheckType): Boolean = when (t) {
        CheckType.NB_VERSION -> nbVersion != null
        CheckType.OS_VERSION -> os != null
        CheckType.GEO_LOCATION -> geo != null
        CheckType.NETWORK_RANGE -> network != null
        CheckType.PROCESS -> process != null
    }

    /** [t] switched on with an empty part, or off. */
    fun toggle(t: CheckType, on: Boolean): PostureForm = when (t) {
        CheckType.NB_VERSION -> copy(nbVersion = if (on) nbVersion ?: VersionForm() else null)
        CheckType.OS_VERSION -> copy(os = if (on) os ?: OsForm() else null)
        CheckType.GEO_LOCATION -> copy(geo = if (on) geo ?: GeoForm(locations = listOf(LocationForm())) else null)
        CheckType.NETWORK_RANGE -> copy(network = if (on) network ?: NetworkForm() else null)
        CheckType.PROCESS -> copy(process = if (on) process ?: ProcessesForm(listOf(ProcessForm())) else null)
    }
}

object PostureJson {

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val TOP = setOf("id", "name", "description", "checks")
    private val CHECKS = CheckType.entries.map { it.key }.toSet()

    fun fromApi(c: ApiPostureCheck): PostureForm = fromJson(AppJson.encodeToJsonElement(ApiPostureCheck.serializer(), c) as JsonObject)

    fun fromJson(o: JsonObject): PostureForm {
        val checks = o["checks"] as? JsonObject ?: EMPTY
        return PostureForm(
            id = o.str("id")?.takeIf { it.isNotEmpty() },
            name = o.str("name").orEmpty(),
            description = o.str("description").orEmpty(),
            nbVersion = (checks[CheckType.NB_VERSION.key] as? JsonObject)?.let { VersionForm(it.str("min_version").orEmpty(), it.without("min_version")) },
            os = (checks[CheckType.OS_VERSION.key] as? JsonObject)?.let { os ->
                fun min(k: String, field: String) = (os[k] as? JsonObject)?.str(field) ?: if (os[k] is JsonObject) "" else null
                OsForm(
                    android = min("android", "min_version"), darwin = min("darwin", "min_version"), ios = min("ios", "min_version"),
                    linux = min("linux", "min_kernel_version"), windows = min("windows", "min_kernel_version"),
                    extra = os.without("android", "darwin", "ios", "linux", "windows"),
                )
            },
            geo = (checks[CheckType.GEO_LOCATION.key] as? JsonObject)?.let { g ->
                GeoForm(
                    action = g.str("action") ?: ALLOW,
                    locations = (g["locations"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { l -> LocationForm(l.str("country_code").orEmpty(), l.str("city_name").orEmpty()) } },
                    extra = g.without("action", "locations"),
                )
            },
            network = (checks[CheckType.NETWORK_RANGE.key] as? JsonObject)?.let { n ->
                NetworkForm(n.str("action") ?: ALLOW, (n["ranges"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }, n.without("action", "ranges"))
            },
            process = (checks[CheckType.PROCESS.key] as? JsonObject)?.let { p ->
                ProcessesForm(
                    (p["processes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { e -> ProcessForm(e.str("linux_path").orEmpty(), e.str("mac_path").orEmpty(), e.str("windows_path").orEmpty()) } },
                    p.without("processes"),
                )
            },
            otherChecks = JsonObject(checks.filterKeys { it !in CHECKS && checks[it] !is JsonNull }),
            extra = JsonObject(o.filterKeys { it !in TOP }),
        )
    }

    fun toJson(f: PostureForm, withId: Boolean = true): JsonObject = buildJsonObject {
        if (withId) f.id?.let { put("id", it) }
        put("name", f.name)
        put("description", f.description)
        put("checks", buildJsonObject {
            f.nbVersion?.let { v -> put(CheckType.NB_VERSION.key, buildJsonObject { put("min_version", v.minVersion); v.extra.forEach { (k, e) -> put(k, e) } }) }
            f.os?.let { os ->
                put(CheckType.OS_VERSION.key, buildJsonObject {
                    os.android?.let { put("android", buildJsonObject { put("min_version", it) }) }
                    os.darwin?.let { put("darwin", buildJsonObject { put("min_version", it) }) }
                    os.ios?.let { put("ios", buildJsonObject { put("min_version", it) }) }
                    os.linux?.let { put("linux", buildJsonObject { put("min_kernel_version", it) }) }
                    os.windows?.let { put("windows", buildJsonObject { put("min_kernel_version", it) }) }
                    os.extra.forEach { (k, e) -> put(k, e) }
                })
            }
            f.geo?.let { g ->
                put(CheckType.GEO_LOCATION.key, buildJsonObject {
                    put("locations", JsonArray(g.locations.map { l ->
                        buildJsonObject { put("country_code", l.countryCode); if (l.cityName.isNotBlank()) put("city_name", l.cityName) }
                    }))
                    put("action", g.action)
                    g.extra.forEach { (k, e) -> put(k, e) }
                })
            }
            f.network?.let { n ->
                put(CheckType.NETWORK_RANGE.key, buildJsonObject {
                    put("ranges", JsonArray(n.ranges.map(::JsonPrimitive)))
                    put("action", n.action)
                    n.extra.forEach { (k, e) -> put(k, e) }
                })
            }
            f.process?.let { p ->
                put(CheckType.PROCESS.key, buildJsonObject {
                    put("processes", JsonArray(p.processes.map { e ->
                        buildJsonObject {
                            if (e.linuxPath.isNotBlank()) put("linux_path", e.linuxPath)
                            if (e.macPath.isNotBlank()) put("mac_path", e.macPath)
                            if (e.windowsPath.isNotBlank()) put("windows_path", e.windowsPath)
                        }
                    }))
                    p.extra.forEach { (k, e) -> put(k, e) }
                })
            }
            f.otherChecks.forEach { (k, e) -> put(k, e) }
        })
        f.extra.forEach { (k, e) -> put(k, e) }
    }

    /** What POST and PUT take: the document without the id. */
    fun request(f: PostureForm): JsonObject = toJson(f, withId = false)

    fun text(f: PostureForm): String = pretty.encodeToString(JsonElement.serializer(), toJson(f))

    fun same(a: PostureForm, b: PostureForm): Boolean = toJson(a) == toJson(b)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })
}

/** Something the server would refuse; [index] is the location, range or process it is about. */
data class PostureProblem(val kind: Kind, val index: Int? = null, val value: String = "") {
    enum class Kind { NO_NAME, NO_CHECKS, BAD_VERSION, NO_OS, NO_LOCATIONS, BAD_COUNTRY, NO_RANGES, BAD_RANGE, NO_PROCESSES, EMPTY_PROCESS, BAD_ACTION }
}

object PostureValidation {

    /** What go-version accepts, near enough: digits and dots, an optional v, a pre-release or build suffix. */
    private val VERSION = Regex("""^v?\d+(\.\d+)*([-+][0-9A-Za-z.+\-]*)?$""")
    private val COUNTRY = Regex("^[A-Za-z]{2}$")

    fun versionOk(v: String): Boolean = VERSION.matches(v.trim())

    /** A CIDR range, IPv4 or IPv6, with its prefix in range. */
    fun cidrOk(s: String): Boolean {
        val parts = s.trim().split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull() ?: return false
        val ip = parts[0]
        return if (':' in ip) bits in 0..128 && ipv6Ok(ip) else bits in 0..32 && ipv4Ok(ip)
    }

    private fun ipv4Ok(ip: String): Boolean {
        val o = ip.split('.')
        return o.size == 4 && o.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    private fun ipv6Ok(ip: String): Boolean {
        if (ip.count { it == ':' } < 2 || ip.split("::").size > 2) return false
        val groups = ip.split(':').filter { it.isNotEmpty() }
        if (groups.size > 8 || (groups.size < 8 && "::" !in ip)) return false
        return groups.all { g -> g.length in 1..4 && g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
    }

    fun problems(f: PostureForm): List<PostureProblem> = buildList {
        fun add(k: PostureProblem.Kind, i: Int? = null, v: String = "") = add(PostureProblem(k, i, v))
        if (f.name.isBlank()) add(PostureProblem.Kind.NO_NAME)
        if (f.types.isEmpty()) add(PostureProblem.Kind.NO_CHECKS)
        f.nbVersion?.let { if (!versionOk(it.minVersion)) add(PostureProblem.Kind.BAD_VERSION, v = it.minVersion) }
        f.os?.let { os ->
            if (os.isEmpty) add(PostureProblem.Kind.NO_OS)
            listOfNotNull(os.android, os.darwin, os.ios, os.linux, os.windows).filterNot(::versionOk).forEach { add(PostureProblem.Kind.BAD_VERSION, v = it) }
        }
        f.geo?.let { g ->
            if (g.action !in setOf(ALLOW, DENY)) add(PostureProblem.Kind.BAD_ACTION, v = g.action)
            if (g.locations.isEmpty()) add(PostureProblem.Kind.NO_LOCATIONS)
            g.locations.forEachIndexed { i, l -> if (!COUNTRY.matches(l.countryCode)) add(PostureProblem.Kind.BAD_COUNTRY, i, l.countryCode) }
        }
        f.network?.let { n ->
            if (n.action !in setOf(ALLOW, DENY)) add(PostureProblem.Kind.BAD_ACTION, v = n.action)
            if (n.ranges.isEmpty()) add(PostureProblem.Kind.NO_RANGES)
            n.ranges.forEachIndexed { i, r -> if (!cidrOk(r)) add(PostureProblem.Kind.BAD_RANGE, i, r) }
        }
        f.process?.let { p ->
            if (p.processes.isEmpty()) add(PostureProblem.Kind.NO_PROCESSES)
            p.processes.forEachIndexed { i, e -> if (e.linuxPath.isBlank() && e.macPath.isBlank() && e.windowsPath.isBlank()) add(PostureProblem.Kind.EMPTY_PROCESS, i) }
        }
    }

    /** The policies that name posture check [id]: the server refuses to delete it while any does. */
    fun usedBy(id: String, policies: List<ApiPolicy>): List<ApiPolicy> = policies.filter { id in it.sourcePostureChecks }
}
