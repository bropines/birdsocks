package io.github.bropines.birdsocks.admin.areas

import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiNameserver
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNetworkResource
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiPolicyRule
import io.github.bropines.birdsocks.admin.api.ApiResource
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.areas.dns.DnsRules
import io.github.bropines.birdsocks.admin.areas.dns.NameserverForm
import io.github.bropines.birdsocks.admin.areas.dns.NsGroupForm
import io.github.bropines.birdsocks.admin.areas.dns.NsProblemKind
import io.github.bropines.birdsocks.admin.areas.dns.RecordForm
import io.github.bropines.birdsocks.admin.areas.dns.RecordProblem
import io.github.bropines.birdsocks.admin.areas.dns.ZoneForm
import io.github.bropines.birdsocks.admin.areas.dns.ZoneProblem
import io.github.bropines.birdsocks.admin.areas.networks.NetworkRules
import io.github.bropines.birdsocks.admin.areas.networks.ResourceForm
import io.github.bropines.birdsocks.admin.areas.networks.ResourceProblem
import io.github.bropines.birdsocks.admin.areas.networks.RouterForm
import io.github.bropines.birdsocks.admin.areas.networks.RouterProblem
import io.github.bropines.birdsocks.admin.areas.routes.RouteForm
import io.github.bropines.birdsocks.admin.areas.routes.RouteProblem
import io.github.bropines.birdsocks.admin.areas.routes.RouteRules
import io.github.bropines.birdsocks.admin.areas.routes.RouteUse
import io.github.bropines.birdsocks.admin.areas.routing.RawJson
import io.github.bropines.birdsocks.admin.areas.routing.RoutingInput
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Networks, Routes and DNS areas' decisions without a screen: input checks, request builders, who uses what. */
class RoutingRulesTest {
    private val self = SelfUse("p-me", setOf("g-all", "g-office"))

    // --- Input ---

    @Test
    fun addressesAndPrefixes() {
        assertTrue(RoutingInput.isIpv4("10.0.0.1"))
        assertFalse(RoutingInput.isIpv4("10.0.0.256"))
        assertFalse(RoutingInput.isIpv4("010.0.0.1"))
        assertTrue(RoutingInput.isIpv6("fd00::1"))
        assertTrue(RoutingInput.isIpv6("::ffff:10.0.0.1"))
        assertFalse(RoutingInput.isIpv6("fd00::1::2"))
        assertFalse(RoutingInput.isIpv6("example.com"))
        assertTrue(RoutingInput.isCidr("10.0.0.0/24"))
        assertTrue(RoutingInput.isCidr("0.0.0.0/0"))
        assertTrue(RoutingInput.isCidr("fd00::/64"))
        assertFalse(RoutingInput.isCidr("10.0.0.0/33"))
        assertFalse(RoutingInput.isCidr("10.0.0.0"))
        assertFalse(RoutingInput.isCidr("10.0.0.0/"))
        assertFalse(RoutingInput.isCidr("fd00::/129"))
        assertTrue(RoutingInput.isExitNode(" 0.0.0.0/0"))
        assertTrue(RoutingInput.isExitNode("::/0"))
        assertFalse(RoutingInput.isExitNode("10.0.0.0/8"))
    }

    @Test
    fun domainsListsMetricsPorts() {
        assertTrue(RoutingInput.isDomain("corp.example.com"))
        assertTrue(RoutingInput.isDomain("local"))
        assertTrue(RoutingInput.isDomain("_sip.example.com"))
        assertFalse(RoutingInput.isDomain("*.example.com"))
        assertTrue(RoutingInput.isDomain("*.example.com", wildcard = true))
        assertFalse(RoutingInput.isDomain("-bad.example.com"))
        assertFalse(RoutingInput.isDomain("a..b"))
        assertEquals("example.com", RoutingInput.normalizeDomain(" Example.COM. "))
        assertEquals(listOf("a.com", "b.com"), RoutingInput.list("a.com, b.com\n a.com;"))
        assertEquals(1, RoutingInput.metric("1"))
        assertEquals(9999, RoutingInput.metric("9999"))
        assertNull(RoutingInput.metric("0"))
        assertNull(RoutingInput.metric("10000"))
        assertNull(RoutingInput.metric("-5"))
        assertEquals(53, RoutingInput.port("53"))
        assertNull(RoutingInput.port("65536"))
        assertNull(RoutingInput.port(""))
    }

    // --- Routes ---

    private val goodRoute = RouteForm(networkId = "office", network = "10.0.0.0/24", peer = "p-gw", groups = listOf("g-office"))

    @Test
    fun routeValidation() {
        assertEquals(emptyList<RouteProblem>(), RouteRules.validate(goodRoute))
        assertEquals(listOf(RouteProblem.NETWORK_ID), RouteRules.validate(goodRoute.copy(networkId = " ")))
        assertEquals(listOf(RouteProblem.NETWORK_ID), RouteRules.validate(goodRoute.copy(networkId = "x".repeat(41))))
        assertEquals(listOf(RouteProblem.NETWORK), RouteRules.validate(goodRoute.copy(network = "10.0.0.0")))
        assertEquals(listOf(RouteProblem.NO_ROUTER), RouteRules.validate(goodRoute.copy(peer = null)))
        assertEquals(listOf(RouteProblem.NO_ROUTER), RouteRules.validate(goodRoute.copy(useGroups = true)))
        assertEquals(listOf(RouteProblem.METRIC), RouteRules.validate(goodRoute.copy(metric = "0")))
        assertEquals(listOf(RouteProblem.NO_GROUPS), RouteRules.validate(goodRoute.copy(groups = emptyList())))
        val domains = goodRoute.copy(domainMode = true, network = "")
        assertEquals(listOf(RouteProblem.NO_DOMAINS), RouteRules.validate(domains))
        assertEquals(listOf(RouteProblem.BAD_DOMAIN), RouteRules.validate(domains.copy(domains = "ok.com bad..com")))
        assertEquals(listOf(RouteProblem.TOO_MANY_DOMAINS), RouteRules.validate(domains.copy(domains = (1..33).joinToString("\n") { "d$it.com" })))
        assertEquals(emptyList<RouteProblem>(), RouteRules.validate(domains.copy(domains = "*.Corp.com\napi.corp.com")))
    }

    @Test
    fun routeRequestSendsOneOfEachPair() {
        val r = RouteRules.request(goodRoute.copy(metric = "100", description = " lan "))
        assertEquals("10.0.0.0/24", r.network)
        assertNull(r.domains)
        assertEquals("p-gw", r.peer)
        assertNull(r.peerGroups)
        assertEquals(100, r.metric)
        assertEquals("lan", r.description)
        assertNull(r.accessControlGroups)
        assertNull("skip_auto_apply only for exit nodes", r.skipAutoApply)

        val d = RouteRules.request(goodRoute.copy(domainMode = true, domains = "*.Corp.com, api.corp.com", useGroups = true, peerGroups = listOf("g-gw"), accessControlGroups = listOf("g-acl")))
        assertNull(d.network)
        assertEquals(listOf("*.corp.com", "api.corp.com"), d.domains)
        assertNull(d.peer)
        assertEquals(listOf("g-gw"), d.peerGroups)
        assertEquals(listOf("g-acl"), d.accessControlGroups)

        val exit = RouteRules.request(RouteRules.exitNodeForm().copy(peer = "p-gw", groups = listOf("g-all"), skipAutoApply = true))
        assertEquals("0.0.0.0/0", exit.network)
        assertEquals(true, exit.skipAutoApply)
    }

    @Test
    fun routeFormRoundTrips() {
        val route = ApiRoute(id = "r1", networkId = "n", description = "d", enabled = true, peerGroups = listOf("g1"), domains = listOf("a.com"), metric = 7,
            masquerade = false, groups = listOf("g2"), keepRoute = true, accessControlGroups = listOf("g3"))
        val form = RouteRules.form(route)
        assertTrue(form.domainMode && form.useGroups)
        val req = RouteRules.request(form)
        assertEquals(io.github.bropines.birdsocks.admin.api.ApiRouteRequest.of(route).copy(network = null, peer = null, skipAutoApply = null), req)
    }

    @Test
    fun whoUsesARoute() {
        assertEquals(setOf(RouteUse.RECEIVES), RouteRules.uses(ApiRoute(groups = listOf("g-office"), peer = "p-gw"), self))
        assertEquals(setOf(RouteUse.ROUTES), RouteRules.uses(ApiRoute(groups = listOf("g-other"), peer = "p-me"), self))
        assertEquals(setOf(RouteUse.ROUTES, RouteUse.RECEIVES), RouteRules.uses(ApiRoute(groups = listOf("g-all"), peerGroups = listOf("g-office")), self))
        assertEquals(emptySet<RouteUse>(), RouteRules.uses(ApiRoute(groups = listOf("g-other"), peer = "p-gw"), self))
        assertEquals(emptySet<RouteUse>(), RouteRules.uses(ApiRoute(groups = listOf("g-all")), SelfUse.NONE))
        assertTrue(ApiRoute(network = "0.0.0.0/0").isExitNode)
    }

    // --- Networks ---

    @Test
    fun resourceTypesAndValidation() {
        assertEquals(NetworkRules.HOST, NetworkRules.resourceType("10.0.0.5"))
        assertEquals(NetworkRules.HOST, NetworkRules.resourceType("10.0.0.5/32"))
        assertEquals(NetworkRules.HOST, NetworkRules.resourceType("fd00::5/128"))
        assertEquals(NetworkRules.SUBNET, NetworkRules.resourceType("10.0.0.0/24"))
        assertEquals(NetworkRules.DOMAIN, NetworkRules.resourceType("*.Example.com"))
        assertNull(NetworkRules.resourceType("not an address"))
        assertNull(NetworkRules.resourceType(""))

        val ok = ResourceForm(name = "LAN", address = "10.0.0.0/24")
        assertEquals(emptyList<ResourceProblem>(), NetworkRules.validate(ok))
        assertEquals(listOf(ResourceProblem.NAME, ResourceProblem.ADDRESS), NetworkRules.validate(ResourceForm(address = "x y")))
        val req = NetworkRules.request(ok.copy(address = " *.Wiki.LAN ", groups = listOf("g1"), name = " Wiki "))
        assertEquals("*.wiki.lan", req.address)
        assertEquals("Wiki", req.name)
        assertEquals(listOf("g1"), req.groups)
    }

    @Test
    fun routerValidationAndRequest() {
        assertEquals(listOf(RouterProblem.NO_ROUTER), NetworkRules.validate(RouterForm()))
        assertEquals(listOf(RouterProblem.METRIC), NetworkRules.validate(RouterForm(peer = "p1", metric = "")))
        val byGroup = NetworkRules.request(RouterForm(useGroups = true, peer = "stale", peerGroups = listOf("g1"), metric = "10"))
        assertNull(byGroup.peer)
        assertEquals(listOf("g1"), byGroup.peerGroups)
        assertEquals(10, byGroup.metric)
        assertTrue(NetworkRules.isSelfRouter(ApiNetworkRouter(peer = "p-me"), "p-me", emptySet()))
        assertTrue(NetworkRules.isSelfRouter(ApiNetworkRouter(peerGroups = listOf("g-office")), "p-me", self.groupIds))
        assertFalse(NetworkRules.isSelfRouter(ApiNetworkRouter(peer = "p-x"), "p-me", self.groupIds))
    }

    @Test
    fun grantsComeFromAcceptRulesToTheResourceOrItsGroups() {
        val res = ApiNetworkResource(id = "res1", name = "LAN", groups = listOf(ApiGroupMinimum("g-lan", "lan")))
        val policies = listOf(
            ApiPolicy(id = "p1", name = "Devs to LAN", enabled = true, rules = listOf(
                ApiPolicyRule(name = "r", enabled = true, sources = listOf(ApiGroupMinimum("g-dev", "devs")), destinations = listOf(ApiGroupMinimum("g-lan", "lan"))),
            )),
            ApiPolicy(id = "p2", name = "Direct", enabled = true, rules = listOf(
                ApiPolicyRule(name = "d", enabled = false, sources = listOf(ApiGroupMinimum("g-ops", "ops")), destinationResource = ApiResource("res1", "subnet")),
            )),
            ApiPolicy(id = "p3", name = "Drop", enabled = true, rules = listOf(
                ApiPolicyRule(name = "x", enabled = true, action = ApiPolicyRule.ACTION_DROP, destinations = listOf(ApiGroupMinimum("g-lan", "lan"))),
            )),
            ApiPolicy(id = "p4", name = "Elsewhere", enabled = true, rules = listOf(
                ApiPolicyRule(name = "y", enabled = true, destinations = listOf(ApiGroupMinimum("g-lan", "lan")), destinationResource = ApiResource("other", "host")),
            )),
        )
        val grants = NetworkRules.grants(res, policies)
        assertEquals(listOf("p1", "p2"), grants.map { it.policyId })
        assertEquals(listOf("devs"), grants[0].sources)
        assertTrue(grants[0].active && !grants[0].direct)
        assertFalse(grants[1].active)
        assertTrue(grants[1].direct)
    }

    // --- DNS ---

    private val goodNs = NsGroupForm(name = "Office", nameservers = listOf(NameserverForm("192.168.1.1")), domains = "office.lan", groups = listOf("g-office"))

    @Test
    fun nameserverValidation() {
        assertTrue(DnsRules.validate(goodNs).isEmpty())
        fun kinds(f: NsGroupForm) = DnsRules.validate(f).map { it.kind }
        assertEquals(listOf(NsProblemKind.NAME), kinds(goodNs.copy(name = "")))
        assertEquals(listOf(NsProblemKind.NO_NAMESERVERS), kinds(goodNs.copy(nameservers = listOf(NameserverForm()))))
        assertEquals(listOf(NsProblemKind.TOO_MANY_NAMESERVERS), kinds(goodNs.copy(nameservers = (1..4).map { NameserverForm("10.0.0.$it") })))
        assertEquals(listOf(NsProblemKind.BAD_IP), kinds(goodNs.copy(nameservers = listOf(NameserverForm("dns.google")))))
        assertEquals(listOf(NsProblemKind.BAD_PORT), kinds(goodNs.copy(nameservers = listOf(NameserverForm("1.1.1.1", "0")))))
        assertEquals(listOf(NsProblemKind.DUPLICATE_NAMESERVER), kinds(goodNs.copy(nameservers = listOf(NameserverForm("1.1.1.1"), NameserverForm("1.1.1.1")))))
        assertEquals(listOf(NsProblemKind.NO_DOMAINS), kinds(goodNs.copy(domains = "")))
        assertEquals(listOf(NsProblemKind.BAD_DOMAIN), kinds(goodNs.copy(domains = "ok.lan bad..lan")))
        assertEquals("bad..lan", DnsRules.validate(goodNs.copy(domains = "ok.lan bad..lan")).single().value)
        assertEquals(listOf(NsProblemKind.NO_GROUPS), kinds(goodNs.copy(groups = emptyList())))
        assertTrue("primary needs no domains", DnsRules.validate(goodNs.copy(primary = true, domains = "")).isEmpty())
        assertTrue("an empty trailing line is ignored", DnsRules.validate(goodNs.copy(nameservers = listOf(NameserverForm("[fd00::53]"), NameserverForm()))).isEmpty())
    }

    @Test
    fun nameserverRequestFollowsTheServersRules() {
        val primary = DnsRules.request(goodNs.copy(primary = true, searchDomainsEnabled = true, nameservers = listOf(NameserverForm("[FD00::53]", "5353"), NameserverForm())))
        assertTrue(primary.primary)
        assertEquals(emptyList<String>(), primary.domains)
        assertFalse("search domains only with domains", primary.searchDomainsEnabled)
        assertEquals(listOf(ApiNameserver("fd00::53", "udp", 5353)), primary.nameservers)
        val match = DnsRules.request(goodNs.copy(domains = "Office.LAN.\nlab.lan", searchDomainsEnabled = true))
        assertEquals(listOf("office.lan", "lab.lan"), match.domains)
        assertTrue(match.searchDomainsEnabled)
        assertEquals(io.github.bropines.birdsocks.admin.api.ApiNameserverGroupRequest.of(ApiNameserverGroup(
            name = "Office", nameservers = listOf(ApiNameserver("192.168.1.1")), enabled = true, groups = listOf("g-office"), domains = listOf("office.lan"),
        )), DnsRules.request(DnsRules.form(ApiNameserverGroup(name = "Office", nameservers = listOf(ApiNameserver("192.168.1.1")), enabled = true, groups = listOf("g-office"), domains = listOf("office.lan")))))
    }

    @Test
    fun whoUsesNameserversAndDnsManagement() {
        assertTrue(DnsRules.uses(ApiNameserverGroup(enabled = true, groups = listOf("g-office")), self))
        assertFalse(DnsRules.uses(ApiNameserverGroup(enabled = false, groups = listOf("g-office")), self))
        assertFalse(DnsRules.uses(ApiNameserverGroup(enabled = true, groups = listOf("g-x")), self))
        assertEquals(listOf("g-office"), DnsRules.selfGroupsTurnedOff(listOf("g-x"), listOf("g-x", "g-office", "g-y"), self))
        assertEquals(emptyList<String>(), DnsRules.selfGroupsTurnedOff(listOf("g-office"), listOf("g-office"), self))
    }

    @Test
    fun zonesAndRecords() {
        assertEquals(emptyList<ZoneProblem>(), DnsRules.validate(ZoneForm("Office", "office.lan", groups = listOf("g1"))))
        assertEquals(listOf(ZoneProblem.NAME, ZoneProblem.DOMAIN, ZoneProblem.NO_GROUPS), DnsRules.validate(ZoneForm("", "bad domain")))
        assertEquals("office.lan", DnsRules.request(ZoneForm("Office", "Office.LAN.", groups = listOf("g1"))).domain)

        assertEquals("www.office.lan", DnsRules.fqdn("www", "office.lan"))
        assertEquals("office.lan", DnsRules.fqdn("@", "office.lan"))
        assertEquals("office.lan", DnsRules.fqdn("", "office.lan"))
        assertEquals("a.office.lan", DnsRules.fqdn("A.Office.lan", "office.lan"))
        assertEquals("www", DnsRules.relativeName("www.office.lan", "office.lan"))
        assertEquals("@", DnsRules.relativeName("office.lan", "office.lan"))

        val zone = "office.lan"
        assertEquals(emptyList<RecordProblem>(), DnsRules.validate(RecordForm("nas", "A", "10.0.0.9"), zone))
        assertEquals(listOf(RecordProblem.CONTENT), DnsRules.validate(RecordForm("nas", "A", "fd00::9"), zone))
        assertEquals(emptyList<RecordProblem>(), DnsRules.validate(RecordForm("nas", "AAAA", "fd00::9"), zone))
        assertEquals(listOf(RecordProblem.CONTENT), DnsRules.validate(RecordForm("files", "CNAME", "10.0.0.9"), zone))
        assertEquals(listOf(RecordProblem.TYPE), DnsRules.validate(RecordForm("x", "MX", "mail"), zone))
        assertEquals(listOf(RecordProblem.TTL), DnsRules.validate(RecordForm("x", "A", "10.0.0.1", ""), zone))
        assertEquals(listOf(RecordProblem.NAME), DnsRules.validate(RecordForm("bad..name", "A", "10.0.0.1"), zone))
        val req = DnsRules.request(RecordForm("files", "CNAME", "NAS.office.lan.", "60"), zone)
        assertEquals("files.office.lan", req.name)
        assertEquals("nas.office.lan", req.content)
        assertEquals(60, req.ttl)
    }

    // --- Raw JSON ---

    @Test
    fun rawJsonKeepsUnknownFieldsAndTurnsGroupsIntoIds() {
        val before = AppJson.parseToJsonElement("""{"id":"r1","name":"LAN","address":"10.0.0.0/24","groups":[{"id":"g1","name":"lan"}],"future":{"x":1}}""").jsonObject
        val after = JsonObject(before + ("name" to kotlinx.serialization.json.JsonPrimitive("LAN 2")))
        val body = RawJson.body(after, idFields = setOf("groups"))
        assertFalse("id" in body)
        assertEquals("""["g1"]""", body["groups"].toString())
        assertEquals("""{"x":1}""", body["future"].toString())
        assertEquals(listOf("name"), RawJson.changedKeys(RawJson.body(before, idFields = setOf("groups")), body))
        assertEquals(listOf("a", "b"), RawJson.changedKeys(JsonObject(mapOf("a" to kotlinx.serialization.json.JsonPrimitive(1))), JsonObject(mapOf("b" to kotlinx.serialization.json.JsonPrimitive(1)))))
        assertEquals("/networks/n%201/resources/r%2F1", RawJson.path("networks", "n 1", "resources", "r/1"))
        assertEquals("r1", RawJson.id(before))
    }
}
