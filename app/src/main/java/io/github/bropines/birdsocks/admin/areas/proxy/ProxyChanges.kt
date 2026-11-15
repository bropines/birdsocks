package io.github.bropines.birdsocks.admin.areas.proxy

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiReverseProxyDomain
import io.github.bropines.birdsocks.admin.api.ApiReverseProxyDomainRequest
import io.github.bropines.birdsocks.admin.api.ApiService
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.api.Urls
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The reverse proxy: services (a name on the internet in front of peers or
 * hosts) and their domains. A service is written as the server's own JSON,
 * re-read before each PUT, so fields this app does not model survive and a
 * password the list does not show is not blanked.
 */
object ProxyChanges {
    val SERVICE_CREATE = ChangeKind("proxy_service.create", Capabilities.SERVICES, Op.CREATE, ChangeClass.HIGH)
    val SERVICE_TOGGLE = ChangeKind("proxy_service.toggle", Capabilities.SERVICES, Op.UPDATE, ChangeClass.MEDIUM)
    val SERVICE_EDIT = ChangeKind("proxy_service.edit", Capabilities.SERVICES, Op.UPDATE, ChangeClass.HIGH)
    val SERVICE_DELETE = ChangeKind("proxy_service.delete", Capabilities.SERVICES, Op.DELETE, ChangeClass.HIGH)
    val DOMAIN_ADD = ChangeKind("proxy_domain.add", Capabilities.SERVICES, Op.CREATE, ChangeClass.MEDIUM)
    val DOMAIN_DELETE = ChangeKind("proxy_domain.delete", Capabilities.SERVICES, Op.DELETE, ChangeClass.HIGH)

    /** Fields the server sets itself; left out of what is sent. */
    private val READ_ONLY = setOf("id", "meta", "port_auto_assigned", "proxy_cluster", "terminated")

    fun target(s: ApiService) = ChangeTarget(TargetType.PROXY_SERVICE, s.id, s.name.ifEmpty { s.domain.ifEmpty { s.id } })

    fun domainTarget(d: ApiReverseProxyDomain) = ChangeTarget(TargetType.PROXY_DOMAIN, d.id, d.domain.ifEmpty { d.id })

    /** What a service's JSON must have to be sent: an object with a name and a domain; null when it does, else why not. */
    fun serviceProblem(obj: JsonObject?): String? = when {
        obj == null -> "not an object"
        (obj["name"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() -> "\"name\" is required"
        (obj["domain"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() -> "\"domain\" is required"
        else -> null
    }

    /** [obj] without the fields the server sets. */
    fun writable(obj: JsonObject): JsonObject = JsonObject(obj.filterKeys { it !in READ_ONLY })

    /** A starting point for a new service: one HTTP target on a peer. */
    val TEMPLATE: String = JsonText.format(
        """{"name":"","domain":"","enabled":true,"targets":[{"target_type":"peer","target_id":"","protocol":"http","port":80,"enabled":true}]}"""
    ) ?: "{}"

    private suspend fun freshService(api: NetbirdApi, id: String): JsonObject =
        api.rawGet(path(id)) as? JsonObject
            ?: throw io.github.bropines.birdsocks.admin.api.AdminApiException.Decode("service")

    private fun path(id: String) = "/reverse-proxies/services/${Urls.seg(id)}"

    fun createService(ctx: Context, body: JsonObject): PlannedChange {
        val name = (body["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val domain = (body["domain"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return PlannedChange(
            change = AdminChange(
                SERVICE_CREATE, ChangeTarget(TargetType.PROXY_SERVICE, "", name),
                title = ctx.getString(R.string.admin_rp_service_create_title, name),
                effect = ctx.getString(R.string.admin_rp_service_create_effect, domain),
                textDiff = "" to JsonText.format(body),
                warnings = listOf(ctx.getString(R.string.admin_rp_public_warning)),
            ),
            apply = { it.rawSend("POST", "/reverse-proxies/services", writable(body)) },
            refreshes = listOf(AdminKeys.PROXY_SERVICES),
        )
    }

    /** Switching a service on puts it on the internet: HIGH; off MEDIUM, with an undo. */
    fun setEnabled(ctx: Context, s: ApiService, on: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            SERVICE_TOGGLE, target(s),
            title = ctx.getString(if (on) R.string.admin_rp_service_on_title else R.string.admin_rp_service_off_title, target(s).name),
            effect = ctx.getString(if (on) R.string.admin_rp_service_on_effect else R.string.admin_rp_service_off_effect, s.domain),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_rp_enabled), s.enabled.toString(), on.toString())),
            warnings = listOfNotNull(if (on) ctx.getString(R.string.admin_rp_public_warning) else null),
            changeClass = if (on && !s.enabled) ChangeClass.HIGH else ChangeClass.MEDIUM,
        ),
        apply = { api ->
            val fresh = freshService(api, s.id)
            api.rawSend("PUT", path(s.id), writable(JsonObject(fresh + ("enabled" to JsonPrimitive(on)))))
        },
        verify = { it.proxyService(s.id).enabled == on },
        undo = if (isUndo) null else setEnabled(ctx, s.copy(enabled = on), !on, isUndo = true),
        refreshes = listOf(AdminKeys.PROXY_SERVICES),
        isUndo = isUndo,
    )

    /** The service as edited JSON, replacing what the server has; the review shows the line diff. */
    fun editService(ctx: Context, s: ApiService, before: JsonObject, after: JsonObject): PlannedChange = PlannedChange(
        change = AdminChange(
            SERVICE_EDIT, target(s),
            title = ctx.getString(R.string.admin_rp_service_edit_title, target(s).name),
            effect = ctx.getString(R.string.admin_rp_service_edit_effect, s.domain),
            textDiff = JsonText.format(before) to JsonText.format(after),
        ),
        apply = { it.rawSend("PUT", path(s.id), writable(after)) },
        refreshes = listOf(AdminKeys.PROXY_SERVICES),
    )

    fun deleteService(ctx: Context, s: ApiService): PlannedChange = PlannedChange(
        change = AdminChange(
            SERVICE_DELETE, target(s),
            title = ctx.getString(R.string.admin_rp_service_delete_title, target(s).name),
            effect = ctx.getString(R.string.admin_rp_service_delete_effect, s.domain),
        ),
        apply = { it.deleteProxyService(s.id) },
        verify = { api -> api.proxyServices().items.none { it.id == s.id } },
        refreshes = listOf(AdminKeys.PROXY_SERVICES),
        done = ctx.getString(R.string.admin_done_deleted, target(s).name),
    )

    fun addDomain(ctx: Context, domain: String, cluster: String): PlannedChange = PlannedChange(
        change = AdminChange(
            DOMAIN_ADD, ChangeTarget(TargetType.PROXY_DOMAIN, "", domain),
            title = ctx.getString(R.string.admin_rp_domain_add_title, domain),
            effect = ctx.getString(R.string.admin_rp_domain_add_effect, domain, cluster),
            diff = listOf(
                DiffLine(ctx.getString(R.string.admin_rp_domain), null, domain),
                DiffLine(ctx.getString(R.string.admin_rp_cluster), null, cluster),
            ),
        ),
        apply = { it.createProxyDomain(ApiReverseProxyDomainRequest(domain.trim().lowercase(), cluster.trim())) },
        verify = { api -> api.proxyDomains().items.any { it.domain.equals(domain.trim(), ignoreCase = true) } },
        refreshes = listOf(AdminKeys.PROXY_DOMAINS),
    )

    /** Services on the domain stop answering. [services] names them in the warning. */
    fun deleteDomain(ctx: Context, d: ApiReverseProxyDomain, services: List<ApiService>): PlannedChange {
        val on = services.filter { it.domain == d.domain || it.domain.endsWith("." + d.domain) }
        return PlannedChange(
            change = AdminChange(
                DOMAIN_DELETE, domainTarget(d),
                title = ctx.getString(R.string.admin_rp_domain_delete_title, domainTarget(d).name),
                effect = ctx.getString(R.string.admin_rp_domain_delete_effect),
                warnings = listOfNotNull(on.takeIf { it.isNotEmpty() }?.let { list -> ctx.getString(R.string.admin_rp_domain_in_use, list.joinToString(", ") { it.name.ifEmpty { it.domain } }) }),
            ),
            apply = { it.deleteProxyDomain(d.id) },
            verify = { api -> api.proxyDomains().items.none { it.id == d.id } },
            refreshes = listOf(AdminKeys.PROXY_DOMAINS),
            done = ctx.getString(R.string.admin_done_deleted, domainTarget(d).name),
        )
    }

    /** A cluster's address from GET /reverse-proxies/clusters, read loosely. */
    fun clusterAddresses(raw: kotlinx.serialization.json.JsonElement?): List<String> =
        (raw as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("address")?.let { a -> runCatching { a.jsonPrimitive.content }.getOrNull() } }
            .filter { it.isNotBlank() }.distinct()
}
