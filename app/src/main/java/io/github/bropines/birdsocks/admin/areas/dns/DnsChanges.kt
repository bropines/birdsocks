package io.github.bropines.birdsocks.admin.areas.dns

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiDnsRecord
import io.github.bropines.birdsocks.admin.api.ApiDnsRecordRequest
import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiDnsZoneRequest
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroupRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.areas.routing.RawJson
import io.github.bropines.birdsocks.admin.areas.routing.RawJsonChanges
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeClassifier
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import kotlinx.serialization.json.JsonObject

/**
 * The DNS area's writes as [PlannedChange]s: nameserver groups, the DNS
 * settings, private zones and their records. Taking nameservers away from
 * this device is HIGH; switching its DNS management off warns.
 */
object DnsChanges {
    val NS_CREATE = ChangeKind("nameserver_group.create", Capabilities.NAMESERVERS, Op.CREATE, ChangeClass.MEDIUM)
    val NS_UPDATE = ChangeKind("nameserver_group.update", Capabilities.NAMESERVERS, Op.UPDATE, ChangeClass.MEDIUM)
    val NS_DELETE = ChangeKind("nameserver_group.delete", Capabilities.NAMESERVERS, Op.DELETE, ChangeClass.MEDIUM)
    val NS_JSON = ChangeKind("nameserver_group.json", Capabilities.NAMESERVERS, Op.UPDATE, ChangeClass.MEDIUM)
    val SETTINGS = ChangeKind("dns.settings", Capabilities.DNS, Op.UPDATE, ChangeClass.MEDIUM)
    val ZONE_CREATE = ChangeKind("dns_zone.create", Capabilities.DNS, Op.CREATE, ChangeClass.MEDIUM)
    val ZONE_UPDATE = ChangeKind("dns_zone.update", Capabilities.DNS, Op.UPDATE, ChangeClass.MEDIUM)
    val ZONE_DELETE = ChangeKind("dns_zone.delete", Capabilities.DNS, Op.DELETE, ChangeClass.HIGH)
    val ZONE_JSON = ChangeKind("dns_zone.json", Capabilities.DNS, Op.UPDATE, ChangeClass.MEDIUM)
    val RECORD_CREATE = ChangeKind("dns_record.create", Capabilities.DNS, Op.CREATE, ChangeClass.MEDIUM)
    val RECORD_UPDATE = ChangeKind("dns_record.update", Capabilities.DNS, Op.UPDATE, ChangeClass.MEDIUM)
    val RECORD_DELETE = ChangeKind("dns_record.delete", Capabilities.DNS, Op.DELETE, ChangeClass.MEDIUM)
    val RECORD_JSON = ChangeKind("dns_record.json", Capabilities.DNS, Op.UPDATE, ChangeClass.MEDIUM)

    private val NS_REFRESH = listOf(AdminKeys.NAMESERVERS)
    private val ZONE_REFRESH = listOf(AdminKeys.DNS_ZONES)

    private fun onOff(ctx: Context, v: Boolean) = ctx.getString(if (v) R.string.admin_value_on else R.string.admin_value_off)
    private fun row(ctx: Context, label: Int, before: String?, after: String?, adding: Boolean) =
        if (!adding && before == after) null else DiffLine(ctx.getString(label), before?.ifEmpty { null }, after?.ifEmpty { null })

    fun target(g: ApiNameserverGroup) = ChangeTarget(TargetType.NAMESERVER_GROUP, g.id, g.name)
    fun target(z: ApiDnsZone) = ChangeTarget(TargetType.DNS_ZONE, z.id, z.domain.ifEmpty { z.name })
    fun target(r: ApiDnsRecord) = ChangeTarget(TargetType.DNS_RECORD, r.id, r.name)

    // --- Nameserver groups ---

    fun nsDiff(ctx: Context, a: ApiNameserverGroupRequest?, b: ApiNameserverGroupRequest, names: (String) -> String): List<DiffLine> {
        val adding = a == null
        fun servers(r: ApiNameserverGroupRequest) = r.nameservers.joinToString(", ") { "${it.ip}:${it.port}" }
        return listOfNotNull(
            row(ctx, R.string.admin_dns_ns_name, a?.name, b.name, adding),
            row(ctx, R.string.admin_dns_ns_description, a?.description, b.description, adding && b.description.isNotEmpty()),
            row(ctx, R.string.admin_dns_ns_servers, a?.let(::servers), servers(b), adding),
            row(ctx, R.string.admin_dns_ns_primary, a?.let { onOff(ctx, it.primary) }, onOff(ctx, b.primary), adding),
            row(ctx, R.string.admin_dns_ns_domains, a?.domains?.joinToString(", "), b.domains.joinToString(", "), adding && b.domains.isNotEmpty()),
            row(ctx, R.string.admin_dns_ns_search, a?.let { onOff(ctx, it.searchDomainsEnabled) }, onOff(ctx, b.searchDomainsEnabled), adding && b.searchDomainsEnabled),
            row(ctx, R.string.admin_dns_groups, a?.groups?.joinToString(", ") { names(it) }, b.groups.joinToString(", ") { names(it) }, adding),
            row(ctx, R.string.admin_routing_enabled, a?.let { onOff(ctx, it.enabled) }, onOff(ctx, b.enabled), adding),
        )
    }

    fun nsCreate(ctx: Context, request: ApiNameserverGroupRequest, names: (String) -> String): PlannedChange = PlannedChange(
        change = AdminChange(
            NS_CREATE, ChangeTarget(TargetType.NAMESERVER_GROUP, "", request.name),
            title = ctx.getString(R.string.admin_dns_ns_create_title, request.name),
            effect = ctx.getString(if (request.primary) R.string.admin_dns_ns_create_primary_effect else R.string.admin_dns_ns_create_effect),
            diff = nsDiff(ctx, null, request, names),
        ),
        apply = { it.createNameserverGroup(request) },
        refreshes = NS_REFRESH,
    )

    /**
     * HIGH when this device loses the group (it is switched off or this
     * device's groups leave it) or a primary group loses its last nameserver.
     */
    fun nsUpdate(ctx: Context, g: ApiNameserverGroup, request: ApiNameserverGroupRequest, self: SelfUse, names: (String) -> String, isUndo: Boolean = false): PlannedChange {
        val before = ApiNameserverGroupRequest.of(g)
        val used = DnsRules.uses(g, self)
        val losing = used && !DnsRules.uses(g.copy(enabled = request.enabled, groups = request.groups), self)
        val cls = if (losing) ChangeClass.HIGH else maxOf(ChangeClassifier.nameservers(g.primary, g.nameservers.size, request.nameservers.size), ChangeClass.MEDIUM)
        val enabling = request == before.copy(enabled = true) && !g.enabled
        return PlannedChange(
            change = AdminChange(
                NS_UPDATE, target(g),
                title = ctx.getString(R.string.admin_dns_ns_update_title, g.name),
                effect = ctx.getString(if (g.enabled && !request.enabled) R.string.admin_dns_ns_off_effect else R.string.admin_dns_ns_update_effect),
                diff = nsDiff(ctx, before, request, names),
                warnings = if (losing) listOf(ctx.getString(R.string.admin_dns_ns_self_warn)) else emptyList(),
                changeClass = if (enabling) ChangeClass.LOW else cls,
            ),
            apply = { it.updateNameserverGroup(g.id, request) },
            verify = { api -> api.nameserverGroup(g.id).let { it.enabled == request.enabled && it.nameservers == request.nameservers && it.groups.toSet() == request.groups.toSet() } },
            undo = if (isUndo) null else nsUpdate(ctx, g.copy(name = request.name, description = request.description, nameservers = request.nameservers, enabled = request.enabled,
                groups = request.groups, primary = request.primary, domains = request.domains, searchDomainsEnabled = request.searchDomainsEnabled), before, self, names, isUndo = true),
            refreshes = NS_REFRESH,
            isUndo = isUndo,
        )
    }

    /** HIGH with a warning when this device uses the group; MEDIUM otherwise. */
    fun nsDelete(ctx: Context, g: ApiNameserverGroup, self: SelfUse): PlannedChange {
        val used = DnsRules.uses(g, self)
        return PlannedChange(
            change = AdminChange(
                NS_DELETE, target(g),
                title = ctx.getString(R.string.admin_dns_ns_delete_title, g.name),
                effect = ctx.getString(R.string.admin_dns_ns_delete_effect),
                warnings = if (used) listOf(ctx.getString(R.string.admin_dns_ns_self_warn)) else emptyList(),
                changeClass = if (used) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { it.deleteNameserverGroup(g.id) },
            verify = { api -> try { api.nameserverGroup(g.id); false } catch (_: AdminApiException.NotFound) { true } },
            refreshes = NS_REFRESH,
            done = ctx.getString(R.string.admin_done_deleted, g.name),
        )
    }

    fun nsJson(ctx: Context, g: ApiNameserverGroup, before: JsonObject, after: JsonObject, self: SelfUse): PlannedChange {
        val used = DnsRules.uses(g, self)
        return RawJsonChanges.edit(
            ctx, NS_JSON, target(g), nsPath(g.id), before, after, NS_REFRESH,
            changeClass = if (used) ChangeClass.HIGH else ChangeClass.MEDIUM,
            warnings = if (used) listOf(ctx.getString(R.string.admin_dns_ns_self_json_warn)) else emptyList(),
        )
    }

    // --- Settings ---

    /** The groups whose peers' DNS management is off; turning it off for this device warns. */
    fun settings(ctx: Context, before: ApiDnsSettings, after: List<String>, self: SelfUse, names: (String) -> String, isUndo: Boolean = false): PlannedChange {
        val selfOff = DnsRules.selfGroupsTurnedOff(before.disabledManagementGroups, after, self)
        val added = after.filter { it !in before.disabledManagementGroups }
        return PlannedChange(
            change = AdminChange(
                SETTINGS, ChangeTarget(TargetType.DNS_SETTINGS, "dns", ctx.getString(R.string.admin_dns_settings_target)),
                title = ctx.getString(R.string.admin_dns_settings_title),
                effect = ctx.getString(if (added.isNotEmpty()) R.string.admin_dns_settings_off_effect else R.string.admin_dns_settings_on_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_dns_disabled_groups),
                    before.disabledManagementGroups.joinToString(", ") { names(it) }.ifEmpty { null }, after.joinToString(", ") { names(it) }.ifEmpty { null })),
                warnings = if (selfOff.isNotEmpty()) listOf(ctx.getString(R.string.admin_dns_settings_self_warn, selfOff.joinToString(", ") { names(it) })) else emptyList(),
            ),
            apply = { it.updateDnsSettings(ApiDnsSettings(after)) },
            verify = { it.dnsSettings().disabledManagementGroups.toSet() == after.toSet() },
            undo = if (isUndo) null else settings(ctx, ApiDnsSettings(after), before.disabledManagementGroups, self, names, isUndo = true),
            refreshes = listOf(AdminKeys.DNS_SETTINGS),
            isUndo = isUndo,
        )
    }

    // --- Zones ---

    private fun zoneDiff(ctx: Context, a: ApiDnsZoneRequest?, b: ApiDnsZoneRequest, names: (String) -> String) = listOfNotNull(
        row(ctx, R.string.admin_dns_zone_name, a?.name, b.name, a == null),
        row(ctx, R.string.admin_dns_zone_domain, a?.domain, b.domain, a == null),
        row(ctx, R.string.admin_dns_zone_search, a?.let { onOff(ctx, it.enableSearchDomain) }, onOff(ctx, b.enableSearchDomain), a == null),
        row(ctx, R.string.admin_dns_groups, a?.distributionGroups?.joinToString(", ") { names(it) }, b.distributionGroups.joinToString(", ") { names(it) }, a == null),
        row(ctx, R.string.admin_routing_enabled, a?.let { onOff(ctx, it.enabled != false) }, onOff(ctx, b.enabled != false), a == null),
    )

    fun zoneCreate(ctx: Context, request: ApiDnsZoneRequest, names: (String) -> String): PlannedChange = PlannedChange(
        change = AdminChange(
            ZONE_CREATE, ChangeTarget(TargetType.DNS_ZONE, "", request.domain),
            title = ctx.getString(R.string.admin_dns_zone_create_title, request.domain),
            effect = ctx.getString(R.string.admin_dns_zone_create_effect),
            diff = zoneDiff(ctx, null, request, names),
        ),
        apply = { it.createDnsZone(request) },
        refreshes = ZONE_REFRESH,
    )

    fun zoneUpdate(ctx: Context, z: ApiDnsZone, request: ApiDnsZoneRequest, self: SelfUse, names: (String) -> String, isUndo: Boolean = false): PlannedChange {
        val before = ApiDnsZoneRequest.of(z)
        val losing = DnsRules.uses(z, self) && !DnsRules.uses(z.copy(enabled = request.enabled != false, distributionGroups = request.distributionGroups), self)
        return PlannedChange(
            change = AdminChange(
                ZONE_UPDATE, target(z),
                title = ctx.getString(R.string.admin_dns_zone_update_title, z.domain),
                effect = ctx.getString(R.string.admin_dns_zone_update_effect),
                diff = zoneDiff(ctx, before, request, names),
                warnings = if (losing) listOf(ctx.getString(R.string.admin_dns_zone_self_warn)) else emptyList(),
                changeClass = if (losing) ChangeClass.HIGH else ChangeClass.MEDIUM,
            ),
            apply = { it.updateDnsZone(z.id, request) },
            verify = { api -> api.dnsZone(z.id).let { it.domain == request.domain && it.distributionGroups.toSet() == request.distributionGroups.toSet() } },
            undo = if (isUndo) null else zoneUpdate(ctx, z.copy(name = request.name, domain = request.domain, enabled = request.enabled != false,
                enableSearchDomain = request.enableSearchDomain, distributionGroups = request.distributionGroups), before, self, names, isUndo = true),
            refreshes = ZONE_REFRESH,
            isUndo = isUndo,
        )
    }

    /** Its records go with it. HIGH, no way back. */
    fun zoneDelete(ctx: Context, z: ApiDnsZone, self: SelfUse): PlannedChange = PlannedChange(
        change = AdminChange(
            ZONE_DELETE, target(z),
            title = ctx.getString(R.string.admin_dns_zone_delete_title, z.domain),
            effect = ctx.resources.getQuantityString(R.plurals.admin_dns_zone_delete_effect, z.records.size, z.records.size),
            warnings = if (DnsRules.uses(z, self)) listOf(ctx.getString(R.string.admin_dns_zone_self_warn)) else emptyList(),
        ),
        apply = { it.deleteDnsZone(z.id) },
        verify = { api -> try { api.dnsZone(z.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = ZONE_REFRESH,
        done = ctx.getString(R.string.admin_done_deleted, z.domain),
    )

    /** A zone from the JSON editor; its records are not part of a zone's PUT and are left out. */
    fun zoneJson(ctx: Context, z: ApiDnsZone, before: JsonObject, after: JsonObject): PlannedChange =
        RawJsonChanges.edit(ctx, ZONE_JSON, target(z), zonePath(z.id), JsonObject(before - "records"), JsonObject(after - "records"), ZONE_REFRESH)

    // --- Records ---

    private fun recordDiff(ctx: Context, a: ApiDnsRecordRequest?, b: ApiDnsRecordRequest) = listOfNotNull(
        row(ctx, R.string.admin_dns_record_name, a?.name, b.name, a == null),
        row(ctx, R.string.admin_dns_record_type, a?.type, b.type, a == null),
        row(ctx, R.string.admin_dns_record_content, a?.content, b.content, a == null),
        row(ctx, R.string.admin_dns_record_ttl, a?.ttl?.toString(), b.ttl.toString(), a == null),
    )

    fun recordCreate(ctx: Context, z: ApiDnsZone, request: ApiDnsRecordRequest): PlannedChange = PlannedChange(
        change = AdminChange(
            RECORD_CREATE, ChangeTarget(TargetType.DNS_RECORD, "", request.name),
            title = ctx.getString(R.string.admin_dns_record_create_title, request.name),
            effect = ctx.getString(R.string.admin_dns_record_effect, request.name, request.type, request.content),
            diff = recordDiff(ctx, null, request),
        ),
        apply = { it.createDnsRecord(z.id, request) },
        refreshes = ZONE_REFRESH,
    )

    fun recordUpdate(ctx: Context, z: ApiDnsZone, r: ApiDnsRecord, request: ApiDnsRecordRequest, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            RECORD_UPDATE, target(r),
            title = ctx.getString(R.string.admin_dns_record_update_title, r.name),
            effect = ctx.getString(R.string.admin_dns_record_effect, request.name, request.type, request.content),
            diff = recordDiff(ctx, ApiDnsRecordRequest.of(r), request),
        ),
        apply = { it.updateDnsRecord(z.id, r.id, request) },
        verify = { api -> api.dnsRecord(z.id, r.id).let { it.name == request.name && it.type == request.type && it.content == request.content && it.ttl == request.ttl } },
        undo = if (isUndo) null else recordUpdate(ctx, z, r.copy(name = request.name, type = request.type, content = request.content, ttl = request.ttl), ApiDnsRecordRequest.of(r), isUndo = true),
        refreshes = ZONE_REFRESH,
        isUndo = isUndo,
    )

    fun recordDelete(ctx: Context, z: ApiDnsZone, r: ApiDnsRecord): PlannedChange = PlannedChange(
        change = AdminChange(
            RECORD_DELETE, target(r),
            title = ctx.getString(R.string.admin_dns_record_delete_title, r.name),
            effect = ctx.getString(R.string.admin_dns_record_delete_effect, r.type, r.content),
        ),
        apply = { it.deleteDnsRecord(z.id, r.id) },
        verify = { api -> try { api.dnsRecord(z.id, r.id); false } catch (_: AdminApiException.NotFound) { true } },
        refreshes = ZONE_REFRESH,
        done = ctx.getString(R.string.admin_done_deleted, r.name),
    )

    fun recordJson(ctx: Context, z: ApiDnsZone, r: ApiDnsRecord, before: JsonObject, after: JsonObject): PlannedChange =
        RawJsonChanges.edit(ctx, RECORD_JSON, target(r), recordPath(z.id, r.id), before, after, ZONE_REFRESH)

    fun nsPath(id: String) = RawJson.path("dns", "nameservers", id)
    fun zonePath(id: String) = RawJson.path("dns", "zones", id)
    fun recordPath(zoneId: String, id: String) = RawJson.path("dns", "zones", zoneId, "records", id)
}
