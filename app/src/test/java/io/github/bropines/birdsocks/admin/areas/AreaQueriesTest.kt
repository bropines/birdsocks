package io.github.bropines.birdsocks.admin.areas

import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiPolicyRule
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.areas.groups.GroupUsage
import io.github.bropines.birdsocks.admin.areas.keys.KeyQuery
import io.github.bropines.birdsocks.admin.areas.peers.GroupEdit
import io.github.bropines.birdsocks.admin.areas.peers.PeerGroups
import io.github.bropines.birdsocks.admin.areas.peers.PeerQuery
import io.github.bropines.birdsocks.admin.areas.peers.SelfReach
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Peers, Groups and Setup keys areas' plain logic: filters, membership edits, usage, the cut-off check. */
class AreaQueriesTest {
    private val all = ApiGroup("g-all", ApiGroup.ALL)
    private val office = ApiGroup("g-office", "office")
    private val servers = ApiGroup("g-servers", "servers")
    private val groups = listOf(all, office, servers)
    private fun min(g: ApiGroup) = ApiGroupMinimum(g.id, g.name)

    private val phone = ApiPeer(id = "p-phone", name = "phone", os = "android 14", connected = true, groups = listOf(min(all), min(office)))
    private val laptop = ApiPeer(id = "p-laptop", name = "laptop", os = "Darwin 23.1", connected = true, sshEnabled = true, loginExpirationEnabled = true, groups = listOf(min(all), min(office), min(servers)))
    private val nas = ApiPeer(id = "p-nas", name = "nas", os = "linux", loginExpired = true, loginExpirationEnabled = true, groups = listOf(min(all), min(servers)))
    private val tablet = ApiPeer(id = "p-new", name = "tablet", os = "Android", approvalRequired = true, groups = listOf(min(all)))
    private val peers = listOf(phone, laptop, nas, tablet)

    private fun filters(): List<ListFilter<ApiPeer>> =
        PeerQuery.fixed.map { (id, t) -> ListFilter(id, id, t) } +
            PeerQuery.groupChips(peers, groups).map { ListFilter(PeerQuery.groupFilterId(it.id), it.name, PeerQuery.inGroup(it.id)) } +
            PeerQuery.osFamilies(peers).map { ListFilter(PeerQuery.osFilterId(it), it, PeerQuery.hasOs(it)) }

    private fun shown(vararg active: String) = ListQuery.apply(peers, "", { listOf(it.name) }, filters(), active.toSet(), null).map { it.name }

    @Test
    fun peerChipsAndTogether() {
        assertEquals(listOf("phone", "laptop"), shown(PeerQuery.ONLINE))
        assertEquals(listOf("nas", "tablet"), shown(PeerQuery.OFFLINE))
        assertEquals(listOf("tablet"), shown(PeerQuery.PENDING))
        assertEquals(listOf("laptop", "nas"), shown(PeerQuery.EXPIRATION_ON))
        assertEquals(listOf("nas"), shown(PeerQuery.EXPIRED))
        assertEquals(listOf("laptop"), shown(PeerQuery.SSH))
        assertEquals(listOf("laptop"), shown(PeerQuery.ONLINE, PeerQuery.groupFilterId("g-servers")))
        assertEquals(listOf("phone", "tablet"), shown(PeerQuery.osFilterId("android")))
        assertEquals(emptyList<String>(), shown(PeerQuery.ONLINE, PeerQuery.OFFLINE))
    }

    @Test
    fun osFamiliesAndGroupChips() {
        assertEquals("macos", PeerQuery.osFamily("Darwin 23.1"))
        assertEquals("android", PeerQuery.osFamily("Android"))
        assertEquals("windows", PeerQuery.osFamily("Windows 11 Pro"))
        assertEquals("", PeerQuery.osFamily("  "))
        assertEquals(listOf("android", "linux", "macos"), PeerQuery.osFamilies(peers))
        assertEquals("macOS", PeerQuery.osLabel("macos"))
        // "All" never gets a chip; a group nobody listed is in gets none either.
        assertEquals(listOf("office", "servers"), PeerQuery.groupChips(peers, groups + ApiGroup("g-empty", "empty")).map { it.name })
    }

    @Test
    fun attentionPutsWaitingPeersFirst() {
        val sorted = peers.sortedWith(PeerQuery.attention { it.id == "p-phone" }).map { it.name }
        assertEquals(listOf("tablet", "nas", "phone", "laptop"), sorted)
    }

    @Test
    fun onePeerGroupEditsSkipAll() {
        val edits = PeerGroups.forPeer("p-phone", listOf("g-all", "g-office"), listOf("g-servers"), groups)
        assertEquals(
            listOf(GroupEdit("g-office", "office", emptySet(), setOf("p-phone")), GroupEdit("g-servers", "servers", setOf("p-phone"), emptySet())),
            edits,
        )
        assertEquals(setOf("g-all", "g-servers"), PeerGroups.groupsAfter("p-phone", listOf("g-all", "g-office"), edits))
        assertTrue(PeerGroups.forPeer("p", listOf("g-office"), listOf("g-office"), groups).isEmpty())
    }

    @Test
    fun bulkEditsSendOnlyWhatChanges() {
        val edits = PeerGroups.bulk(listOf(phone, nas), add = listOf("g-office", "g-all"), remove = listOf("g-servers"), groups = groups)
        assertEquals(
            listOf(GroupEdit("g-office", "office", setOf("p-nas"), emptySet()), GroupEdit("g-servers", "servers", emptySet(), setOf("p-nas"))),
            edits,
        )
        // A group on both sides is left alone; a group nobody changes in is not sent.
        assertTrue(PeerGroups.bulk(listOf(phone), listOf("g-office"), listOf("g-office"), groups).isEmpty())
        assertTrue(PeerGroups.bulk(listOf(phone), listOf("g-office"), emptyList(), groups).isEmpty())
    }

    @Test
    fun anEditKeepsOtherMembersAndOrder() {
        val e = GroupEdit("g", "g", add = setOf("c", "a"), remove = setOf("b"))
        assertEquals(listOf("a", "x", "c"), e.applyTo(listOf("a", "b", "x")))
    }

    @Test
    fun selfReachWarnsOnlyWhenNoGroupLeftMatches() {
        val rule = ApiPolicyRule(enabled = true, sources = listOf(min(office)), destinations = listOf(min(servers)))
        val p = ApiPolicy(id = "pol", name = "office-to-servers", enabled = true, rules = listOf(rule))
        val off = p.copy(id = "off", name = "off", enabled = false)
        val before = setOf("g-all", "g-office")
        assertEquals(listOf("office-to-servers"), SelfReach.policiesLost(before, setOf("g-all"), listOf(p, off)))
        // Still matched through another group on the same side.
        val wide = p.copy(rules = listOf(rule.copy(sources = listOf(min(office), min(all)))))
        assertTrue(SelfReach.policiesLost(before, setOf("g-all"), listOf(wide)).isEmpty())
        assertTrue(SelfReach.policiesLost(before, before, listOf(p)).isEmpty())
    }

    @Test
    fun groupUsageFromCachedLists() {
        val u = GroupUsage.of(
            "g-office",
            policies = listOf(ApiPolicy(id = "p", name = "pol", rules = listOf(ApiPolicyRule(destinations = listOf(min(office))))), ApiPolicy(id = "q", name = "other")),
            routes = listOf(ApiRoute(id = "r", networkId = "lan", groups = listOf("g-x"), accessControlGroups = listOf("g-office"))),
            nameservers = listOf(ApiNameserverGroup(id = "n", name = "dns", groups = listOf("g-office"))),
            dnsZones = null,
            dnsSettings = ApiDnsSettings(disabledManagementGroups = listOf("g-office")),
            setupKeys = listOf(ApiSetupKey(id = "k", name = "live", autoGroups = listOf("g-office")), ApiSetupKey(id = "k2", name = "dead", revoked = true, autoGroups = listOf("g-office"))),
            users = listOf(ApiUser(id = "u", name = "Ann", autoGroups = listOf("g-office"))),
            routers = listOf(ApiNetworkRouter(id = "rt", peerGroups = listOf("g-office"))),
        )
        assertEquals(listOf("pol"), u.policies)
        assertEquals(listOf("lan"), u.routes)
        assertEquals(listOf("dns"), u.nameservers)
        assertEquals(listOf("live"), u.setupKeys)
        assertEquals(listOf("Ann"), u.users)
        assertEquals(1, u.networkRouters)
        assertTrue(u.dnsManagementDisabled && u.isUsed)
        assertEquals(listOf("dns_zones"), u.unchecked)
        assertFalse(GroupUsage.of("g-free", emptyList(), emptyList(), emptyList(), emptyList(), ApiDnsSettings(), emptyList(), emptyList(), emptyList()).isUsed)
    }

    @Test
    fun keyStatesAndFilters() {
        val now = 1_800_000_000_000L
        val valid = ApiSetupKey(id = "v", valid = true, state = "valid")
        val revoked = ApiSetupKey(id = "r", revoked = true, state = "valid")
        val expired = ApiSetupKey(id = "e", state = "expired")
        val oldNoState = ApiSetupKey(id = "o", expires = "2020-01-01T00:00:00Z")
        val used = ApiSetupKey(id = "u", usageLimit = 2, usedTimes = 2, expires = "2099-01-01T00:00:00Z")
        assertEquals(KeyQuery.State.VALID, KeyQuery.state(valid, now))
        assertEquals(KeyQuery.State.REVOKED, KeyQuery.state(revoked, now))
        assertEquals(KeyQuery.State.EXPIRED, KeyQuery.state(expired, now))
        assertEquals(KeyQuery.State.EXPIRED, KeyQuery.state(oldNoState, now))
        assertEquals(KeyQuery.State.OVERUSED, KeyQuery.state(used, now))
        val keys = listOf(valid, revoked, expired, oldNoState, used)
        val f = KeyQuery.filters(now)
        assertEquals(listOf("v"), keys.filter(f.getValue(KeyQuery.VALID)).map { it.id })
        assertEquals(listOf("e", "o"), keys.filter(f.getValue(KeyQuery.EXPIRED)).map { it.id })
        assertEquals(listOf("r"), keys.filter(f.getValue(KeyQuery.REVOKED)).map { it.id })
    }
}
