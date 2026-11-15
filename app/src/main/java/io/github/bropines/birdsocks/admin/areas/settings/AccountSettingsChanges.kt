package io.github.bropines.birdsocks.admin.areas.settings

import android.content.Context
import androidx.annotation.StringRes
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiAccount
import io.github.bropines.birdsocks.admin.api.ApiAccountSettings
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeClassifier
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** How a setting's value is shaped. */
enum class SettingType { BOOL, SECONDS, TEXT, NAMES, GROUPS }

/** What makes a change of the setting HIGH. */
enum class SettingRisk {
    NONE,
    /** Switching it off removes a protection. */
    OFF,
    /** Switching it on opens something up. */
    ON,
    /** Any change (the network range). */
    ALWAYS,
    /** A list that limits who may join, emptied. */
    EMPTIED,
}

/**
 * One account setting: its JSON key (under `extra` when [extra]), its shape,
 * its words — [onEffect] / [offEffect] for a switch, [onEffect] with the new
 * value as `%1$s` for anything else — and what makes it risky.
 */
data class SettingSpec(
    val key: String,
    val type: SettingType,
    @StringRes val label: Int,
    @StringRes val help: Int,
    @StringRes val onEffect: Int,
    @StringRes val offEffect: Int = 0,
    val risk: SettingRisk = SettingRisk.NONE,
    val extra: Boolean = false,
    @StringRes val warning: Int = 0,
)

/** A titled group of settings on the screen. */
data class SettingSection(@StringRes val title: Int, val specs: List<SettingSpec>)

/**
 * Every account setting the spec has, each saved on its own through
 * [io.github.bropines.birdsocks.admin.api.NetbirdApi.patchAccountSettings]
 * (a merge into a fresh read: fields this app does not know survive), each
 * change described by its effect sentence.
 */
object AccountSettingsChanges {
    val SETTING = ChangeKind("account.setting", Capabilities.ACCOUNTS, Op.UPDATE, ChangeClass.MEDIUM)

    private fun bool(key: String, label: Int, help: Int, on: Int, off: Int, risk: SettingRisk = SettingRisk.NONE, extra: Boolean = false, warning: Int = 0) =
        SettingSpec(key, SettingType.BOOL, label, help, on, off, risk, extra, warning)

    val LOGIN_EXPIRATION = bool("peer_login_expiration_enabled", R.string.admin_acct_login_exp, R.string.admin_acct_login_exp_help, R.string.admin_acct_login_exp_on, R.string.admin_acct_login_exp_off, SettingRisk.OFF)
    val LOGIN_EXPIRATION_TIME = SettingSpec("peer_login_expiration", SettingType.SECONDS, R.string.admin_acct_login_exp_time, R.string.admin_acct_login_exp_time_help, R.string.admin_acct_login_exp_time_set)
    val INACTIVITY_EXPIRATION = bool("peer_inactivity_expiration_enabled", R.string.admin_acct_inact_exp, R.string.admin_acct_inact_exp_help, R.string.admin_acct_inact_exp_on, R.string.admin_acct_inact_exp_off, SettingRisk.OFF)
    val INACTIVITY_EXPIRATION_TIME = SettingSpec("peer_inactivity_expiration", SettingType.SECONDS, R.string.admin_acct_inact_exp_time, R.string.admin_acct_inact_exp_time_help, R.string.admin_acct_inact_exp_time_set)
    val PEER_APPROVAL = bool("peer_approval_enabled", R.string.admin_acct_peer_approval, R.string.admin_acct_peer_approval_help, R.string.admin_acct_peer_approval_on, R.string.admin_acct_peer_approval_off, SettingRisk.OFF, extra = true, warning = R.string.admin_acct_peer_approval_warning)
    val USER_APPROVAL = bool("user_approval_required", R.string.admin_acct_user_approval, R.string.admin_acct_user_approval_help, R.string.admin_acct_user_approval_on, R.string.admin_acct_user_approval_off, SettingRisk.OFF, extra = true)
    val HOSTNAME_COLLISION = bool("peer_hostname_collision_rejected", R.string.admin_acct_hostname, R.string.admin_acct_hostname_help, R.string.admin_acct_hostname_on, R.string.admin_acct_hostname_off)
    val LAZY_CONNECTION = bool("lazy_connection_enabled", R.string.admin_acct_lazy, R.string.admin_acct_lazy_help, R.string.admin_acct_lazy_on, R.string.admin_acct_lazy_off)
    val PEER_EXPOSE = bool("peer_expose_enabled", R.string.admin_acct_expose, R.string.admin_acct_expose_help, R.string.admin_acct_expose_on, R.string.admin_acct_expose_off, SettingRisk.ON)
    val PEER_EXPOSE_GROUPS = SettingSpec("peer_expose_groups", SettingType.GROUPS, R.string.admin_acct_expose_groups, R.string.admin_acct_expose_groups_help, R.string.admin_acct_expose_groups_set)

    val VIEW_BLOCKED = bool("regular_users_view_blocked", R.string.admin_acct_view_blocked, R.string.admin_acct_view_blocked_help, R.string.admin_acct_view_blocked_on, R.string.admin_acct_view_blocked_off, SettingRisk.OFF)
    val LOCAL_AUTH_DISABLED = bool("local_auth_disabled", R.string.admin_acct_local_auth_off, R.string.admin_acct_local_auth_off_help, R.string.admin_acct_local_auth_off_on, R.string.admin_acct_local_auth_off_off, SettingRisk.ON, warning = R.string.admin_acct_local_auth_off_warning)
    val LOCAL_MFA = bool("local_mfa_enabled", R.string.admin_acct_mfa, R.string.admin_acct_mfa_help, R.string.admin_acct_mfa_on, R.string.admin_acct_mfa_off, SettingRisk.OFF)

    val GROUPS_PROPAGATION = bool("groups_propagation_enabled", R.string.admin_acct_propagation, R.string.admin_acct_propagation_help, R.string.admin_acct_propagation_on, R.string.admin_acct_propagation_off)
    val JWT_GROUPS = bool("jwt_groups_enabled", R.string.admin_acct_jwt, R.string.admin_acct_jwt_help, R.string.admin_acct_jwt_on, R.string.admin_acct_jwt_off)
    val JWT_CLAIM = SettingSpec("jwt_groups_claim_name", SettingType.TEXT, R.string.admin_acct_jwt_claim, R.string.admin_acct_jwt_claim_help, R.string.admin_acct_jwt_claim_set)
    val JWT_ALLOW = SettingSpec("jwt_allow_groups", SettingType.NAMES, R.string.admin_acct_jwt_allow, R.string.admin_acct_jwt_allow_help, R.string.admin_acct_jwt_allow_set, risk = SettingRisk.EMPTIED)

    val NETWORK_RANGE = SettingSpec("network_range", SettingType.TEXT, R.string.admin_acct_range, R.string.admin_acct_range_help, R.string.admin_acct_range_set, risk = SettingRisk.ALWAYS, warning = R.string.admin_acct_range_warning)
    val NETWORK_RANGE_V6 = SettingSpec("network_range_v6", SettingType.TEXT, R.string.admin_acct_range6, R.string.admin_acct_range6_help, R.string.admin_acct_range6_set, risk = SettingRisk.ALWAYS, warning = R.string.admin_acct_range_warning)
    val IPV6_GROUPS = SettingSpec("ipv6_enabled_groups", SettingType.GROUPS, R.string.admin_acct_ipv6_groups, R.string.admin_acct_ipv6_groups_help, R.string.admin_acct_ipv6_groups_set)
    val DNS_DOMAIN = SettingSpec("dns_domain", SettingType.TEXT, R.string.admin_acct_dns_domain, R.string.admin_acct_dns_domain_help, R.string.admin_acct_dns_domain_set, warning = R.string.admin_acct_dns_domain_warning)
    val ROUTING_DNS = bool("routing_peer_dns_resolution_enabled", R.string.admin_acct_routing_dns, R.string.admin_acct_routing_dns_help, R.string.admin_acct_routing_dns_on, R.string.admin_acct_routing_dns_off)

    val TRAFFIC_LOGS = bool("network_traffic_logs_enabled", R.string.admin_acct_traffic, R.string.admin_acct_traffic_help, R.string.admin_acct_traffic_on, R.string.admin_acct_traffic_off, extra = true)
    val TRAFFIC_COUNTERS = bool("network_traffic_packet_counter_enabled", R.string.admin_acct_counters, R.string.admin_acct_counters_help, R.string.admin_acct_counters_on, R.string.admin_acct_counters_off, extra = true)
    val TRAFFIC_GROUPS = SettingSpec("network_traffic_logs_groups", SettingType.GROUPS, R.string.admin_acct_traffic_groups, R.string.admin_acct_traffic_groups_help, R.string.admin_acct_traffic_groups_set, extra = true)

    val AUTO_UPDATE_VERSION = SettingSpec("auto_update_version", SettingType.TEXT, R.string.admin_acct_update_version, R.string.admin_acct_update_version_help, R.string.admin_acct_update_version_set)
    val AUTO_UPDATE_ALWAYS = bool("auto_update_always", R.string.admin_acct_update_always, R.string.admin_acct_update_always_help, R.string.admin_acct_update_always_on, R.string.admin_acct_update_always_off)
    val METRICS_PUSH = bool("metrics_push_enabled", R.string.admin_acct_metrics, R.string.admin_acct_metrics_help, R.string.admin_acct_metrics_on, R.string.admin_acct_metrics_off)

    val SECTIONS: List<SettingSection> = listOf(
        SettingSection(R.string.admin_acct_section_peers, listOf(
            LOGIN_EXPIRATION, LOGIN_EXPIRATION_TIME, INACTIVITY_EXPIRATION, INACTIVITY_EXPIRATION_TIME,
            PEER_APPROVAL, HOSTNAME_COLLISION, LAZY_CONNECTION, PEER_EXPOSE, PEER_EXPOSE_GROUPS,
        )),
        SettingSection(R.string.admin_acct_section_users, listOf(USER_APPROVAL, VIEW_BLOCKED, LOCAL_AUTH_DISABLED, LOCAL_MFA)),
        SettingSection(R.string.admin_acct_section_groups, listOf(GROUPS_PROPAGATION, JWT_GROUPS, JWT_CLAIM, JWT_ALLOW)),
        SettingSection(R.string.admin_acct_section_network, listOf(NETWORK_RANGE, NETWORK_RANGE_V6, IPV6_GROUPS, DNS_DOMAIN, ROUTING_DNS)),
        SettingSection(R.string.admin_acct_section_traffic, listOf(TRAFFIC_LOGS, TRAFFIC_COUNTERS, TRAFFIC_GROUPS)),
        SettingSection(R.string.admin_acct_section_updates, listOf(AUTO_UPDATE_VERSION, AUTO_UPDATE_ALWAYS, METRICS_PUSH)),
    )

    val ALL: List<SettingSpec> = SECTIONS.flatMap { it.specs }

    // --- values ---

    /** The settings as JSON, every field the model has. */
    fun json(settings: ApiAccountSettings): JsonObject = AppJson.encodeToJsonElement(ApiAccountSettings.serializer(), settings).jsonObject

    /** [spec]'s value in [settings], or the shape's empty value when the server left it out. */
    fun current(settings: JsonObject, spec: SettingSpec): JsonElement {
        val obj = if (spec.extra) settings["extra"] as? JsonObject else settings
        val v = obj?.get(spec.key)
        return if (v == null || v is JsonNull) empty(spec.type) else v
    }

    fun empty(type: SettingType): JsonElement = when (type) {
        SettingType.BOOL -> JsonPrimitive(false)
        SettingType.SECONDS -> JsonPrimitive(0L)
        SettingType.TEXT -> JsonPrimitive("")
        SettingType.NAMES, SettingType.GROUPS -> JsonArray(emptyList())
    }

    fun asBool(v: JsonElement): Boolean = (v as? JsonPrimitive)?.booleanOrNull == true
    fun asLong(v: JsonElement): Long = (v as? JsonPrimitive)?.longOrNull ?: 0L
    fun asText(v: JsonElement): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    fun asList(v: JsonElement): List<String> = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    fun list(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

    /** What a PATCH carries for [spec] = [value]: the key, under `extra` where it lives. */
    fun patch(spec: SettingSpec, value: JsonElement): JsonObject = buildJsonObject {
        if (spec.extra) put("extra", buildJsonObject { put(spec.key, value) }) else put(spec.key, value)
    }

    /** Whether two values of [spec] mean the same (lists as sets, text trimmed). */
    fun same(spec: SettingSpec, a: JsonElement, b: JsonElement): Boolean = when (spec.type) {
        SettingType.BOOL -> asBool(a) == asBool(b)
        SettingType.SECONDS -> asLong(a) == asLong(b)
        SettingType.TEXT -> asText(a).trim() == asText(b).trim()
        SettingType.NAMES, SettingType.GROUPS -> asList(a).toSet() == asList(b).toSet()
    }

    /** The class of moving [spec] from [before] to [after]. */
    fun classify(spec: SettingSpec, before: JsonElement, after: JsonElement): ChangeClass = when (spec.type) {
        SettingType.BOOL -> when (spec.risk) {
            SettingRisk.OFF -> ChangeClassifier.protection(asBool(before), asBool(after))
            SettingRisk.ON -> if (!asBool(before) && asBool(after)) ChangeClass.HIGH else ChangeClass.MEDIUM
            SettingRisk.ALWAYS -> ChangeClass.HIGH
            else -> ChangeClass.MEDIUM
        }
        SettingType.SECONDS -> ChangeClassifier.expiration(asLong(before), asLong(after))
        else -> when (spec.risk) {
            SettingRisk.ALWAYS -> ChangeClass.HIGH
            SettingRisk.EMPTIED -> if (asList(before).isNotEmpty() && asList(after).isEmpty()) ChangeClass.HIGH else ChangeClass.MEDIUM
            else -> ChangeClass.MEDIUM
        }
    }

    /** Whether a typed value is acceptable for [spec]; null when it is, else a string resource saying why not. */
    @StringRes
    fun problem(spec: SettingSpec, value: JsonElement): Int? = when (spec) {
        NETWORK_RANGE -> if (SettingsInput.isCidr4(asText(value))) null else R.string.admin_acct_bad_cidr4
        NETWORK_RANGE_V6 -> if (asText(value).isEmpty() || SettingsInput.isCidr6(asText(value))) null else R.string.admin_acct_bad_cidr6
        DNS_DOMAIN -> if (asText(value).isEmpty() || SettingsInput.isDomain(asText(value))) null else R.string.admin_acct_bad_domain
        LOGIN_EXPIRATION_TIME, INACTIVITY_EXPIRATION_TIME ->
            if (asLong(value) in SettingsInput.MIN_EXPIRATION_SEC..SettingsInput.MAX_EXPIRATION_SEC) null else R.string.admin_acct_bad_duration
        else -> null
    }

    // --- the change ---

    /**
     * [spec] from [before] to [after] on [account]. [render] turns a value
     * into words for the review (group ids into names, seconds into a
     * duration). The network range is typed back as the new range.
     */
    fun change(
        ctx: Context,
        account: ApiAccount,
        spec: SettingSpec,
        before: JsonElement,
        after: JsonElement,
        render: (SettingSpec, JsonElement) -> String,
        isUndo: Boolean = false,
    ): PlannedChange {
        val label = ctx.getString(spec.label)
        val effect = when {
            spec.type == SettingType.BOOL -> ctx.getString(if (asBool(after)) spec.onEffect else spec.offEffect)
            else -> ctx.getString(spec.onEffect, render(spec, after).ifEmpty { ctx.getString(R.string.admin_acct_none) })
        }
        val cls = classify(spec, before, after)
        return PlannedChange(
            change = AdminChange(
                SETTING, ChangeTarget(TargetType.ACCOUNT, account.id, label),
                title = ctx.getString(R.string.admin_acct_change_title, label),
                effect = effect,
                diff = listOf(DiffLine(label, render(spec, before), render(spec, after))),
                warnings = if (spec.warning != 0 && (cls == ChangeClass.HIGH || spec == DNS_DOMAIN)) listOf(ctx.getString(spec.warning)) else emptyList(),
                changeClass = cls,
                confirmPhrase = if (spec.risk == SettingRisk.ALWAYS && spec.type == SettingType.TEXT) asText(after).ifEmpty { null } else null,
            ),
            apply = { it.patchAccountSettings(account.id, patch(spec, after)) },
            verify = { api ->
                api.accounts().items.firstOrNull { it.id == account.id }?.let { same(spec, current(json(it.settings), spec), after) } ?: false
            },
            undo = if (isUndo) null else change(ctx, account, spec, after, before, render, isUndo = true),
            refreshes = listOf(AdminKeys.ACCOUNTS),
            isUndo = isUndo,
        )
    }
}

/** Input checks for the settings that take typed values. */
object SettingsInput {
    const val MIN_EXPIRATION_SEC = 3_600L
    const val MAX_EXPIRATION_SEC = 180L * 86_400L

    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** An IPv4 network: a.b.c.d/n with n from 8 to 30. */
    fun isCidr4(s: String): Boolean {
        val parts = s.trim().split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull() ?: return false
        return IPV4.matches(parts[0]) && bits in 8..30
    }

    /** An IPv6 network: hex groups with at most one "::", /n from 16 to 120. */
    fun isCidr6(s: String): Boolean {
        val parts = s.trim().split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull() ?: return false
        if (bits !in 16..120) return false
        val addr = parts[0].lowercase()
        if (addr.isEmpty() || addr.split("::").size > 2) return false
        val groups = addr.split(':').filter { it.isNotEmpty() }
        val compressed = "::" in addr
        return groups.all { it.length in 1..4 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } &&
            (if (compressed) groups.size < 8 else groups.size == 8)
    }

    /** A DNS name of labels: "netbird.cloud", "corp.internal". */
    fun isDomain(s: String): Boolean {
        val d = s.trim().lowercase().removeSuffix(".")
        return d.length in 1..253 && d.split('.').let { labels -> labels.size >= 1 && labels.all { LABEL.matches(it) } }
    }
}
