package io.github.bropines.birdsocks.admin.areas.peers

import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPolicy

/**
 * What the Peers list filters and orders by, apart from Compose so it is
 * tested on the JVM. The screen turns these into [io.github.bropines.birdsocks.admin.ui.ListFilter]s
 * with resolved labels; chips AND together.
 */
object PeerQuery {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val PENDING = "pending"
    const val EXPIRATION_ON = "expiration_on"
    const val EXPIRED = "expired"
    const val SSH = "ssh"
    private const val GROUP_PREFIX = "group:"
    private const val OS_PREFIX = "os:"

    /** The fixed chips' tests, by id. */
    val fixed: Map<String, (ApiPeer) -> Boolean> = linkedMapOf(
        ONLINE to { p -> p.connected },
        OFFLINE to { p -> !p.connected },
        PENDING to { p -> p.approvalRequired },
        EXPIRATION_ON to { p -> p.loginExpirationEnabled },
        EXPIRED to { p -> p.loginExpired },
        SSH to { p -> p.sshEnabled },
    )

    fun groupFilterId(groupId: String) = GROUP_PREFIX + groupId
    fun osFilterId(family: String) = OS_PREFIX + family

    fun inGroup(groupId: String): (ApiPeer) -> Boolean = { p -> p.groups.any { it.id == groupId } }
    fun hasOs(family: String): (ApiPeer) -> Boolean = { p -> osFamily(p.os) == family }

    /**
     * "Linux 6.8", "linux" and "Ubuntu 24.04" are not one family by string, so
     * the family is the first word, lowercased, with the kernel names a
     * client reports mapped to what people call them.
     */
    fun osFamily(os: String): String {
        val first = os.trim().substringBefore(' ').lowercase()
        return when (first) {
            "" -> ""
            "darwin", "macos", "mac" -> "macos"
            "windows", "microsoft" -> "windows"
            "ios", "ipados" -> "ios"
            else -> first
        }
    }

    /** The families present, most peers first; empty strings left out. */
    fun osFamilies(peers: List<ApiPeer>): List<String> =
        peers.map { osFamily(it.os) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** Groups worth a chip: not "All" (every peer is in it), and with at least one listed peer. */
    fun groupChips(peers: List<ApiPeer>, groups: List<ApiGroup>): List<ApiGroup> {
        val used = peers.flatMap { p -> p.groups.map { it.id } }.toSet()
        return groups.filter { !it.isAll && it.id in used }.sortedBy { it.name.lowercase() }
    }

    /** "macos" as "macOS", "linux" as "Linux". */
    fun osLabel(family: String): String = when (family) {
        "macos" -> "macOS"
        "ios" -> "iOS"
        else -> family.replaceFirstChar { it.uppercase() }
    }

    /** Waiting for approval, then this device, then online, then by name. */
    fun attention(isSelf: (ApiPeer) -> Boolean): Comparator<ApiPeer> =
        compareByDescending<ApiPeer> { it.approvalRequired }.thenByDescending { it.loginExpired }
            .thenByDescending { isSelf(it) }.thenByDescending { it.connected }.thenBy { it.name.lowercase() }
}

/** One group's membership edit: [add] and [remove] are peer ids. */
data class GroupEdit(val groupId: String, val groupName: String, val add: Set<String>, val remove: Set<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()

    /** [current] with this edit made, the order kept, new ones at the end. */
    fun applyTo(current: List<String>): List<String> = (current.filter { it !in remove } + add.filter { it !in current }).distinct()
}

/**
 * Peer-to-group changes as per-group edits: NetBird keeps membership on the
 * group (a PUT of the whole peer list), not on the peer.
 */
object PeerGroups {
    /** One peer moving from [before] to [after] (group ids); "All" is never edited. */
    fun forPeer(peerId: String, before: Collection<String>, after: Collection<String>, groups: List<ApiGroup>): List<GroupEdit> {
        val byId = groups.associateBy { it.id }
        val added = after.toSet() - before.toSet()
        val removed = before.toSet() - after.toSet()
        return (added.map { it to true } + removed.map { it to false })
            .filter { (id, _) -> byId[id]?.isAll != true }
            .map { (id, add) -> GroupEdit(id, byId[id]?.name ?: id, if (add) setOf(peerId) else emptySet(), if (add) emptySet() else setOf(peerId)) }
            .sortedBy { it.groupName.lowercase() }
    }

    /**
     * Several peers at once: each group in [add] gets the peers not in it yet,
     * each in [remove] loses the ones that are. A group in both is left alone;
     * a group no peer would change in is not sent.
     */
    fun bulk(peers: List<ApiPeer>, add: Collection<String>, remove: Collection<String>, groups: List<ApiGroup>): List<GroupEdit> {
        val byId = groups.associateBy { it.id }
        val both = add.toSet() intersect remove.toSet()
        fun members(gid: String) = peers.filter { p -> p.groups.any { it.id == gid } }.map { it.id }.toSet()
        val adds = (add.toSet() - both).map { gid -> GroupEdit(gid, byId[gid]?.name ?: gid, peers.map { it.id }.toSet() - members(gid), emptySet()) }
        val removes = (remove.toSet() - both).map { gid -> GroupEdit(gid, byId[gid]?.name ?: gid, emptySet(), members(gid)) }
        return (adds + removes).filter { !it.isEmpty && byId[it.groupId]?.isAll != true }.sortedBy { it.groupName.lowercase() }
    }

    /** Group ids [peerId] is in after [edits]. */
    fun groupsAfter(peerId: String, before: Collection<String>, edits: List<GroupEdit>): Set<String> {
        val out = before.toMutableSet()
        edits.forEach { e -> if (peerId in e.remove) out -= e.groupId; if (peerId in e.add) out += e.groupId }
        return out
    }
}

/**
 * "This change may cut this device off", from the cached policies: an
 * enabled rule that reaches this device through a group today, and through
 * none of its groups after the change. Cheap and conservative — it does not
 * resolve resources or posture checks, so it warns rather than decides.
 */
object SelfReach {
    /** The names of enabled policies whose some rule side matches [before] and not [after]. */
    fun policiesLost(before: Set<String>, after: Set<String>, policies: List<ApiPolicy>): List<String> {
        if (before == after) return emptyList()
        return policies.filter { it.enabled }.filter { p ->
            p.rules.filter { it.enabled }.any { r ->
                listOf(r.sources.map { it.id }, r.destinations.map { it.id }).any { side ->
                    side.any { it in before } && side.none { it in after }
                }
            }
        }.map { it.name.ifEmpty { it.id } }.distinct()
    }
}
