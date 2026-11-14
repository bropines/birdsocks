package io.github.bropines.birdsocks.admin.areas.policies

import android.content.Context
import io.github.bropines.birdsocks.R

/** The policy screens' words, resolved in the Context given (the screen's: it carries the app's language). */
object PolicyText {

    fun onOff(ctx: Context, v: Boolean): String = ctx.getString(if (v) R.string.admin_value_on else R.string.admin_value_off)

    fun protocol(ctx: Context, p: String): String = when (p) {
        RuleForm.ALL -> ctx.getString(R.string.admin_policy_proto_all)
        RuleForm.TCP -> "TCP"
        RuleForm.UDP -> "UDP"
        RuleForm.ICMP -> "ICMP"
        RuleForm.SSH -> ctx.getString(R.string.admin_policy_proto_ssh)
        else -> p
    }

    fun action(ctx: Context, a: String): String = ctx.getString(if (a == RuleForm.DROP) R.string.admin_policy_action_drop else R.string.admin_policy_action_accept)

    /** "TCP 22, 443", "all traffic", "ICMP", "NetBird SSH". */
    fun traffic(ctx: Context, r: RuleForm): String {
        val name = protocol(ctx, r.protocol)
        if (!r.takesPorts) return name
        if (r.ports.isEmpty()) return ctx.getString(R.string.admin_policy_traffic_all_ports, name)
        return "$name ${short(r.ports)}"
    }

    /** "22, 80, 443 +3" for a row. */
    fun short(ports: List<PortSpan>, keep: Int = 3): String =
        if (ports.size <= keep + 1) PortWords.text(ports) else PortWords.text(ports.take(keep)) + " +" + (ports.size - keep)

    /** One side of a rule: "Admins, Ops", a resource's name, or "nobody". */
    fun side(ctx: Context, groups: List<String>, res: ResourceRef?, net: PolicyNet?): String = when {
        res != null -> net?.resourceName(res) ?: res.id
        groups.isEmpty() -> ctx.getString(R.string.admin_policy_side_none)
        else -> groups.joinToString(", ") { net?.groupName(it) ?: it }
    }

    /** "Admins → Servers, TCP 22"; both ways with ↔; a drop rule starts with "Block". */
    fun rule(ctx: Context, r: RuleForm, net: PolicyNet?): String {
        val arrow = if (r.bidirectional) "↔" else "→"
        val text = "${side(ctx, r.sources, r.sourceResource, net)} $arrow ${side(ctx, r.destinations, r.destinationResource, net)}, ${traffic(ctx, r)}"
        return if (r.isAccept) text else ctx.getString(R.string.admin_policy_rule_block, text)
    }

    /** Every rule of a policy in words, one per line; disabled rules marked. */
    fun summary(ctx: Context, p: PolicyForm, net: PolicyNet?): String =
        if (p.rules.isEmpty()) ctx.getString(R.string.admin_policy_no_rules)
        else p.rules.joinToString("\n") { r -> rule(ctx, r, net) + if (!r.enabled) " " + ctx.getString(R.string.admin_policy_rule_off) else "" }

    fun risk(ctx: Context, f: RiskFinding): String {
        val where = f.rule.ifBlank { f.policy }
        return when (f.kind) {
            RiskKind.ALL_TO_ALL -> ctx.getString(R.string.admin_policy_risk_all_to_all, where)
            RiskKind.ALL_SOURCE -> ctx.getString(R.string.admin_policy_risk_all_source, where)
            RiskKind.ALL_PROTOCOL -> ctx.getString(R.string.admin_policy_risk_all_protocol, where)
            RiskKind.WIDE_PORTS -> ctx.getString(R.string.admin_policy_risk_wide_ports, where, f.detail)
            RiskKind.SSH_ANY_USER -> ctx.getString(R.string.admin_policy_risk_ssh_any, where)
        }
    }

    fun problem(ctx: Context, p: PolicyProblem): String {
        val text = when (p.kind) {
            PolicyProblem.Kind.NO_NAME -> ctx.getString(R.string.admin_policy_err_no_name)
            PolicyProblem.Kind.NO_RULES -> ctx.getString(R.string.admin_policy_err_no_rules)
            PolicyProblem.Kind.BAD_ACTION -> ctx.getString(R.string.admin_policy_err_action, p.value)
            PolicyProblem.Kind.BAD_PROTOCOL -> ctx.getString(R.string.admin_policy_err_protocol, p.value)
            PolicyProblem.Kind.NO_SOURCES -> ctx.getString(R.string.admin_policy_err_no_sources)
            PolicyProblem.Kind.NO_DESTINATIONS -> ctx.getString(R.string.admin_policy_err_no_destinations)
            PolicyProblem.Kind.BAD_PORT -> ctx.getString(R.string.admin_policy_err_port, p.value)
            PolicyProblem.Kind.PORTS_NOT_ALLOWED -> ctx.getString(R.string.admin_policy_err_ports_not_allowed)
            PolicyProblem.Kind.UNKNOWN_GROUP -> ctx.getString(R.string.admin_policy_err_unknown_group, p.value)
            PolicyProblem.Kind.UNKNOWN_POSTURE_CHECK -> ctx.getString(R.string.admin_policy_err_unknown_check, p.value)
            PolicyProblem.Kind.SSH_USERS_MISSING -> ctx.getString(R.string.admin_policy_err_ssh_users, p.value)
            PolicyProblem.Kind.RESOURCE_INCOMPLETE -> ctx.getString(R.string.admin_policy_err_resource)
        }
        return if (p.rule != null) ctx.getString(R.string.admin_policy_err_in_rule, p.rule + 1, text) else text
    }

    fun setProblem(ctx: Context, p: SetProblem): String {
        val at = p.index?.let { it + 1 } ?: 0
        return when (p.kind) {
            SetProblem.Kind.NOT_ARRAY -> ctx.getString(R.string.admin_policy_set_err_array)
            SetProblem.Kind.NOT_OBJECT -> ctx.getString(R.string.admin_policy_set_err_object, at)
            SetProblem.Kind.DUPLICATE_ID -> ctx.getString(R.string.admin_policy_set_err_duplicate, at, p.value)
            SetProblem.Kind.UNKNOWN_ID -> ctx.getString(R.string.admin_policy_set_err_unknown, at, p.value)
            SetProblem.Kind.INVALID -> ctx.getString(R.string.admin_policy_set_err_invalid, at, p.value, p.policy?.let { problem(ctx, it) }.orEmpty())
        }
    }

    fun grant(ctx: Context, g: Grant): String =
        ctx.getString(if (g.outgoing) R.string.admin_policy_grant_out else R.string.admin_policy_grant_in, g.rule.ifBlank { g.policy }, g.policy)
}
