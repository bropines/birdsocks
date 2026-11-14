package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiPeer

/*
 * What the policy screens work out on the phone, from lists the panel
 * already holds: the local validation, the risk lint, the access check for
 * this device, coverage and "who can reach". Pure functions on PolicyForm.
 */

/**
 * The network the checks read: groups with their peers, peers with their
 * groups, the posture checks that exist, this device's peer. Built once per
 * screen from the cached lists ([of]); a null list means it was not read, and
 * the checks that need it say nothing rather than guess.
 */
class PolicyNet(
    val groups: List<ApiGroup>? = null,
    val peers: List<ApiPeer>? = null,
    val postureCheckIds: Set<String>? = null,
    val selfPeerId: String? = null,
    /** Network resources by id → name, when they were read. */
    val resourceNames: Map<String, String> = emptyMap(),
) {
    val groupsById: Map<String, ApiGroup> = groups.orEmpty().associateBy { it.id }
    val allGroupId: String? = groups?.firstOrNull { it.isAll }?.id
    private val peersByGroup: Map<String, Set<String>> by lazy {
        val m = mutableMapOf<String, MutableSet<String>>()
        groups.orEmpty().forEach { g -> m.getOrPut(g.id) { mutableSetOf() } += g.peers.map { it.id } }
        peers.orEmpty().forEach { p -> p.groups.forEach { g -> m.getOrPut(g.id) { mutableSetOf() } += p.id } }
        allGroupId?.let { all -> m.getOrPut(all) { mutableSetOf() } += peers.orEmpty().map { it.id } }
        m
    }

    fun groupName(id: String): String = groupsById[id]?.name ?: id

    fun peerName(id: String): String = peers?.firstOrNull { it.id == id }?.name ?: id

    fun resourceName(r: ResourceRef): String = if (r.isPeer) peerName(r.id) else resourceNames[r.id] ?: r.id

    fun isAll(id: String): Boolean = id == allGroupId || groupsById[id]?.isAll == true

    fun peersOf(groupId: String): Set<String> = peersByGroup[groupId].orEmpty()

    /** The groups [peerId] is in (with All). */
    fun groupsOf(peerId: String): Set<String> = peersByGroup.filterValues { peerId in it }.keys

    companion object {
        fun of(groups: List<ApiGroup>?, peers: List<ApiPeer>?, postureCheckIds: Set<String>?, self: ApiPeer?, resourceNames: Map<String, String> = emptyMap()) =
            PolicyNet(groups, peers, postureCheckIds, self?.id, resourceNames)
    }
}

// ==================================================================== LOCAL

/** Something the server would refuse, or that cannot work; [rule] is the rule's index, null for the policy. */
data class PolicyProblem(val kind: Kind, val rule: Int? = null, val value: String = "") {
    enum class Kind {
        NO_NAME, NO_RULES, BAD_ACTION, BAD_PROTOCOL, NO_SOURCES, NO_DESTINATIONS, BAD_PORT,
        PORTS_NOT_ALLOWED, UNKNOWN_GROUP, UNKNOWN_POSTURE_CHECK, SSH_USERS_MISSING, RESOURCE_INCOMPLETE,
    }
}

object PolicyValidation {

    /**
     * Everything wrong with [p] that the server would refuse (its own checks,
     * read from the handler) or that would make a rule match nothing: groups
     * and posture checks that do not exist are found only when [net] has them.
     */
    fun problems(p: PolicyForm, net: PolicyNet = PolicyNet()): List<PolicyProblem> = buildList {
        if (p.name.isBlank()) add(PolicyProblem(PolicyProblem.Kind.NO_NAME))
        if (p.rules.isEmpty()) add(PolicyProblem(PolicyProblem.Kind.NO_RULES))
        net.postureCheckIds?.let { known -> p.postureChecks.filter { it !in known }.forEach { add(PolicyProblem(PolicyProblem.Kind.UNKNOWN_POSTURE_CHECK, value = it)) } }
        p.rules.forEachIndexed { i, r -> addAll(rule(r, i, net)) }
    }

    fun rule(r: RuleForm, i: Int, net: PolicyNet = PolicyNet()): List<PolicyProblem> = buildList {
        fun add(kind: PolicyProblem.Kind, value: String = "") = add(PolicyProblem(kind, i, value))
        if (r.action !in RuleForm.ACTIONS) add(PolicyProblem.Kind.BAD_ACTION, r.action)
        if (r.protocol !in RuleForm.PROTOCOLS) add(PolicyProblem.Kind.BAD_PROTOCOL, r.protocol)
        if (r.sourceResource == null && r.sources.isEmpty()) add(PolicyProblem.Kind.NO_SOURCES)
        if (r.destinationResource == null && r.destinations.isEmpty()) add(PolicyProblem.Kind.NO_DESTINATIONS)
        listOfNotNull(r.sourceResource, r.destinationResource).filter { it.id.isBlank() || it.type.isBlank() }.forEach { add(PolicyProblem.Kind.RESOURCE_INCOMPLETE, it.id) }
        r.badPorts.forEach { add(PolicyProblem.Kind.BAD_PORT, it) }
        r.ports.filterNot(PortWords::valid).forEach { add(PolicyProblem.Kind.BAD_PORT, it.toString()) }
        if ((r.protocol == RuleForm.ALL || r.protocol == RuleForm.ICMP) && (r.ports.isNotEmpty() || r.badPorts.isNotEmpty())) {
            add(PolicyProblem.Kind.PORTS_NOT_ALLOWED, r.protocol)
        }
        if (r.protocol == RuleForm.SSH && r.authorizedGroups.isNotEmpty()) {
            r.sources.filter { it !in r.authorizedGroups }.forEach { add(PolicyProblem.Kind.SSH_USERS_MISSING, net.groupName(it)) }
        }
        if (net.groups != null) {
            (r.sources + r.destinations + r.authorizedGroups.keys).distinct().filter { it !in net.groupsById }.forEach { add(PolicyProblem.Kind.UNKNOWN_GROUP, it) }
        }
    }
}

// ==================================================================== RISKS

/** What a rule opens up; each has its own words on screen. */
enum class RiskKind {
    /** Every peer to every peer: the All group on both sides. */
    ALL_TO_ALL,
    /** The All group as a source: every peer, including ones added tomorrow. */
    ALL_SOURCE,
    /** All protocols and ports, not one service. */
    ALL_PROTOCOL,
    /** A port range of [WIDE_RANGE] ports or more. */
    WIDE_PORTS,
    /** NetBird SSH without a list of local users: any user, root included. */
    SSH_ANY_USER,
}

/** One risky rule: [policy] and [rule] name it in the user's own words. */
data class RiskFinding(val kind: RiskKind, val policy: String, val rule: String, val ruleIndex: Int, val detail: String = "") {
    /** What identifies it across two versions: the same kind in the same rule. */
    val key: String get() = "$kind|$policy|$ruleIndex|$detail"
}

object PolicyLint {

    const val WIDE_RANGE = 1000

    /** Every finding in enabled accept rules of [p] (a disabled policy opens nothing). */
    fun findings(p: PolicyForm, net: PolicyNet = PolicyNet()): List<RiskFinding> {
        if (!p.enabled) return emptyList()
        return p.rules.withIndex().filter { it.value.enabled && it.value.isAccept }.flatMap { (i, r) ->
            buildList {
                fun add(k: RiskKind, d: String = "") = add(RiskFinding(k, p.name, r.name, i, d))
                val allSrc = r.sourceResource == null && r.sources.any(net::isAll)
                val allDst = r.destinationResource == null && r.destinations.any(net::isAll)
                when {
                    allSrc && allDst -> add(RiskKind.ALL_TO_ALL)
                    allSrc || (allDst && r.bidirectional) -> add(RiskKind.ALL_SOURCE)
                }
                if (r.protocol == RuleForm.ALL) add(RiskKind.ALL_PROTOCOL)
                r.ports.filter { it.width >= WIDE_RANGE }.forEach { add(RiskKind.WIDE_PORTS, it.toString()) }
                if (r.protocol == RuleForm.SSH && r.authorizedGroups.isEmpty()) add(RiskKind.SSH_ANY_USER)
            }
        }
    }

    /**
     * What [after] opens that [before] did not: a finding that was already
     * there is not news (the Default policy is All → All on purpose).
     */
    fun introduced(before: PolicyForm?, after: PolicyForm, net: PolicyNet = PolicyNet()): List<RiskFinding> {
        val old = before?.let { b -> findings(b, net).map { it.copy(policy = after.name).key }.toSet() }.orEmpty()
        return findings(after, net).filter { it.key !in old }
    }
}

// ==================================================================== ACCESS

/** A rule that lets this device reach, or be reached: [outgoing] when it is among the sources. */
data class Grant(val policyId: String?, val policy: String, val ruleIndex: Int, val rule: String, val outgoing: Boolean) {
    val key: String get() = "${policyId ?: policy}|$ruleIndex|$outgoing"
}

/**
 * What a candidate does to this device's access: the rules that grant it
 * something before and after. [cutOff] when it had some and has none left;
 * [skipped] when the device is not a peer the lists know.
 */
data class AccessReport(val before: List<Grant>, val after: List<Grant>, val skipped: Boolean = false) {
    val lost: List<Grant> get() = before.filter { b -> after.none { it.key == b.key } }
    val cutOff: Boolean get() = !skipped && before.isNotEmpty() && after.isEmpty()
}

object PolicyAccess {

    /** Rules of [policies] that grant [peerId] (in [groups]) anything: enabled accept rules with it on a side. */
    fun grants(policies: List<PolicyForm>, peerId: String, groups: Set<String>): List<Grant> = policies.filter { it.enabled }.flatMap { p ->
        p.rules.withIndex().filter { it.value.enabled && it.value.isAccept }.flatMap { (i, r) ->
            val inSrc = matches(r.sources, r.sourceResource, peerId, groups)
            val inDst = matches(r.destinations, r.destinationResource, peerId, groups)
            listOfNotNull(
                if (inSrc || (inDst && r.bidirectional)) Grant(p.id, p.name, i, r.name, outgoing = true) else null,
                if (inDst || (inSrc && r.bidirectional)) Grant(p.id, p.name, i, r.name, outgoing = false) else null,
            )
        }
    }

    /**
     * Does replacing [before] with [after] (the whole saved set, before and
     * after this change) leave this device without any rule that grants it
     * access? Drop rules are not weighed: losing the last accept is what cuts
     * a phone off.
     */
    fun check(before: List<PolicyForm>, after: List<PolicyForm>, net: PolicyNet): AccessReport {
        val self = net.selfPeerId ?: return AccessReport(emptyList(), emptyList(), skipped = true)
        if (net.peers?.none { it.id == self } != false) return AccessReport(emptyList(), emptyList(), skipped = true)
        val groups = net.groupsOf(self)
        return AccessReport(grants(before, self, groups), grants(after, self, groups))
    }

    /** [all] with [changed] put in its place (by id), or added when it is new; [removed] ids left out. */
    fun replace(all: List<PolicyForm>, changed: List<PolicyForm>, removed: Set<String> = emptySet()): List<PolicyForm> {
        val byId = changed.filter { !it.isNew }.associateBy { it.id }
        return all.filter { it.id !in removed }.map { byId[it.id] ?: it } + changed.filter { it.isNew || all.none { a -> a.id == it.id } }
    }

    private fun matches(groupIds: List<String>, res: ResourceRef?, peerId: String, groups: Set<String>): Boolean =
        if (res != null) res.isPeer && res.id == peerId else groupIds.any { it in groups }
}

// ==================================================================== coverage and reach

object PolicyCoverage {

    /** The peers a rule touches: its sources' and destinations' peers, a peer resource itself. */
    fun peers(r: RuleForm, net: PolicyNet): Set<String> = side(r.sources, r.sourceResource, net) + side(r.destinations, r.destinationResource, net)

    /** The peers a policy touches, every rule together. */
    fun peers(p: PolicyForm, net: PolicyNet): Set<String> = p.rules.flatMap { peers(it, net) }.toSet()

    fun side(groups: List<String>, res: ResourceRef?, net: PolicyNet): Set<String> =
        if (res != null) (if (res.isPeer) setOf(res.id) else emptySet()) else groups.flatMap(net::peersOf).toSet()
}

/** "Who can reach X": a rule that lets [fromGroups] (or [fromResource]) reach the target. */
data class Reach(
    val policyId: String?,
    val policy: String,
    val ruleIndex: Int,
    val rule: RuleForm,
    val fromGroups: List<String>,
    val fromResource: ResourceRef?,
    /** It matched only because the rule works both ways. */
    val reverse: Boolean,
) {
    val blocks: Boolean get() = !rule.isAccept
}

object WhoCanReach {

    /** The target: a peer (its groups decide) or one group. */
    sealed interface Target {
        data class Peer(val id: String) : Target
        data class Group(val id: String) : Target
    }

    /**
     * Every enabled rule of the enabled [policies] that reaches [target]: the
     * target among the destinations, or among the sources of a rule that
     * works both ways. Drop rules come along, marked: they are part of the answer.
     */
    fun of(target: Target, policies: List<PolicyForm>, net: PolicyNet): List<Reach> {
        val groups: Set<String> = when (target) {
            is Target.Peer -> net.groupsOf(target.id)
            is Target.Group -> setOf(target.id)
        }
        val peerId = (target as? Target.Peer)?.id
        fun hit(ids: List<String>, res: ResourceRef?) = if (res != null) res.isPeer && res.id == peerId else ids.any { it in groups }
        return policies.filter { it.enabled }.flatMap { p ->
            p.rules.withIndex().filter { it.value.enabled }.flatMap { (i, r) ->
                listOfNotNull(
                    if (hit(r.destinations, r.destinationResource)) Reach(p.id, p.name, i, r, r.sources, r.sourceResource, reverse = false) else null,
                    if (r.bidirectional && hit(r.sources, r.sourceResource)) Reach(p.id, p.name, i, r, r.destinations, r.destinationResource, reverse = true) else null,
                )
            }
        }.sortedWith(compareBy<Reach> { !it.blocks }.thenBy { it.policy.lowercase() })
    }

    /** The peers that can reach through [reaches] (accept rules only). */
    fun peers(reaches: List<Reach>, net: PolicyNet): Set<String> =
        reaches.filter { !it.blocks }.flatMap { PolicyCoverage.side(it.fromGroups, it.fromResource, net) }.toSet()
}
