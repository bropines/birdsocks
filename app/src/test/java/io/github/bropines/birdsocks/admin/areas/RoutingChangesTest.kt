package io.github.bropines.birdsocks.admin.areas

import android.app.Application
import android.content.Context
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiNameserver
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroupRequest
import io.github.bropines.birdsocks.admin.api.ApiNetwork
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouterRequest
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiRouteRequest
import io.github.bropines.birdsocks.admin.api.HttpRequest
import io.github.bropines.birdsocks.admin.api.HttpResponse
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.areas.dns.DnsChanges
import io.github.bropines.birdsocks.admin.areas.networks.NetworkChanges
import io.github.bropines.birdsocks.admin.areas.routes.RouteChanges
import io.github.bropines.birdsocks.admin.areas.routing.SelfUse
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The classes, warnings and calls of the Networks, Routes and DNS changes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RoutingChangesTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val self = SelfUse("p-me", setOf("g-all", "g-office"))
    private val names: (String) -> String = { it }

    private val sent = mutableListOf<HttpRequest>()
    private fun api(answer: (HttpRequest) -> String = { "{}" }) =
        NetbirdApi("https://m/api", ApiAuth.Token("t"), { req -> sent += req; HttpResponse(200, answer(req)) }, io = Dispatchers.Unconfined)

    // --- Routes ---

    private val officeRoute = ApiRoute(id = "r1", networkId = "office", network = "10.0.0.0/24", peer = "p-gw", groups = listOf("g-office"), enabled = true, metric = 10)
    private val otherRoute = officeRoute.copy(id = "r2", groups = listOf("g-other"))
    private val exitRoute = officeRoute.copy(id = "r3", networkId = "exit", network = "0.0.0.0/0", groups = listOf("g-other"))

    @Test
    fun deletingARouteThisDeviceUsesIsHigh() {
        val used = RouteChanges.delete(ctx, officeRoute, self).change
        assertEquals(ChangeClass.HIGH, used.changeClass)
        assertTrue(used.warnings.isNotEmpty())
        assertEquals(TargetType.ROUTE, used.target.type)
        assertEquals("office", used.confirmText)

        val unused = RouteChanges.delete(ctx, otherRoute, self).change
        assertEquals(ChangeClass.MEDIUM, unused.changeClass)
        assertTrue(unused.warnings.isEmpty())

        assertEquals(ChangeClass.HIGH, RouteChanges.delete(ctx, exitRoute, self).change.changeClass)
        assertEquals("a switched-off route cuts nobody off", ChangeClass.MEDIUM, RouteChanges.delete(ctx, officeRoute.copy(enabled = false), self).change.changeClass)
    }

    @Test
    fun routeSwitchAndUpdateClasses() {
        assertEquals(ChangeClass.LOW, RouteChanges.setEnabled(ctx, officeRoute.copy(enabled = false), true, self).change.changeClass)
        assertEquals(ChangeClass.HIGH, RouteChanges.setEnabled(ctx, officeRoute, false, self).change.changeClass)
        assertEquals(ChangeClass.MEDIUM, RouteChanges.setEnabled(ctx, otherRoute, false, self).change.changeClass)
        assertEquals(ChangeClass.HIGH, RouteChanges.setEnabled(ctx, exitRoute, false, self).change.changeClass)

        val req = ApiRouteRequest.of(officeRoute)
        assertEquals(ChangeClass.MEDIUM, RouteChanges.update(ctx, officeRoute, req.copy(metric = 20), self, names).change.changeClass)
        val leaving = RouteChanges.update(ctx, officeRoute, req.copy(groups = listOf("g-other")), self, names).change
        assertEquals(ChangeClass.HIGH, leaving.changeClass)
        assertTrue(leaving.warnings.isNotEmpty())
        assertEquals(ChangeClass.HIGH, RouteChanges.update(ctx, exitRoute, ApiRouteRequest.of(exitRoute).copy(network = "10.0.0.0/8"), self, names).change.changeClass)
        assertTrue(RouteChanges.update(ctx, officeRoute, req.copy(metric = 20), self, names).change.diff.any { it.before == "10" && it.after == "20" })
        assertNotNull(RouteChanges.update(ctx, officeRoute, req.copy(metric = 20), self, names).undo)
        assertEquals("a new exit node warns", 1, RouteChanges.create(ctx, ApiRouteRequest.of(exitRoute), names).change.warnings.size)
    }

    @Test
    fun routeSwitchRereadsBeforeThePut() = runBlocking {
        sent.clear()
        val fresh = AppJson.encodeToString(ApiRoute.serializer(), officeRoute.copy(description = "changed elsewhere"))
        RouteChanges.setEnabled(ctx, officeRoute, false, self).apply(api { if (it.method == "GET") fresh else "{}" })
        assertEquals(listOf("GET", "PUT"), sent.map { it.method })
        val body = AppJson.parseToJsonElement(sent[1].body!!).jsonObject
        assertEquals("false", body["enabled"].toString())
        assertEquals("\"changed elsewhere\"", body["description"].toString())
    }

    // --- Networks ---

    @Test
    fun networkChanges() {
        val n = ApiNetwork(id = "n1", name = "Office", resources = listOf("a", "b"), routers = listOf("r"), policies = listOf("p"))
        val del = NetworkChanges.delete(ctx, n, selfRoutes = true).change
        assertEquals(ChangeClass.HIGH, del.changeClass)
        assertEquals(2, del.warnings.size)
        assertTrue(NetworkChanges.delete(ctx, n, selfRoutes = true).refreshes.containsAll(listOf(AdminKeys.NETWORKS, AdminKeys.NETWORK_ROUTERS)))

        val router = ApiNetworkRouter(id = "rt", peer = "p-me", metric = 100, masquerade = true, enabled = true)
        val off = NetworkChanges.updateRouter(ctx, "n1", router, ApiNetworkRouterRequest.of(router).copy(enabled = false), self = true, names = names, also = emptyList()).change
        assertEquals(ChangeClass.HIGH, off.changeClass)
        val on = NetworkChanges.updateRouter(ctx, "n1", router.copy(enabled = false), ApiNetworkRouterRequest.of(router), self = false, names = names, also = emptyList()).change
        assertEquals(ChangeClass.LOW, on.changeClass)
        assertTrue(NetworkChanges.deleteRouter(ctx, "n1", router, self = true, names = names, also = emptyList()).change.warnings.isNotEmpty())
    }

    @Test
    fun resourceJsonSendsGroupIdsAndKeepsUnknownFields() = runBlocking {
        sent.clear()
        val before = AppJson.parseToJsonElement("""{"id":"x","name":"LAN","address":"10.0.0.0/24","enabled":true,"groups":[{"id":"g1","name":"lan"}],"future":1}""").jsonObject
        val after = AppJson.parseToJsonElement("""{"id":"x","name":"LAN","address":"10.0.1.0/24","enabled":true,"groups":[{"id":"g1","name":"lan"}],"future":1}""").jsonObject
        val target = io.github.bropines.birdsocks.admin.safety.ChangeTarget(TargetType.NETWORK_RESOURCE, "x", "LAN")
        val change = NetworkChanges.json(ctx, target, NetworkChanges.resourcePath("n1", "x"), before, after, emptyList())
        assertEquals(listOf("address"), change.change.diff.map { it.label })
        assertNotNull(change.change.textDiff)
        change.apply(api())
        assertEquals("PUT", sent.single().method)
        assertEquals("https://m/api/networks/n1/resources/x", sent.single().url)
        val body = AppJson.parseToJsonElement(sent.single().body!!).jsonObject
        assertEquals("""["g1"]""", body["groups"].toString())
        assertEquals("1", body["future"].toString())
        assertTrue("id" !in body)
        assertTrue(change.verify!!(api { """{"id":"x","address":"10.0.1.0/24"}""" }))
    }

    // --- DNS ---

    private val nsUsed = ApiNameserverGroup(id = "ns1", name = "Office DNS", nameservers = listOf(ApiNameserver("10.0.0.53")), enabled = true, groups = listOf("g-office"), domains = listOf("office.lan"))
    private val nsOther = nsUsed.copy(id = "ns2", groups = listOf("g-other"))

    @Test
    fun deletingNameserversThisDeviceUsesIsHigh() {
        val used = DnsChanges.nsDelete(ctx, nsUsed, self).change
        assertEquals(ChangeClass.HIGH, used.changeClass)
        assertTrue(used.warnings.isNotEmpty())
        assertEquals(ChangeClass.MEDIUM, DnsChanges.nsDelete(ctx, nsOther, self).change.changeClass)
        assertEquals(ChangeClass.HIGH, DnsChanges.nsUpdate(ctx, nsUsed, ApiNameserverGroupRequest.of(nsUsed).copy(enabled = false), self, names).change.changeClass)
        assertEquals(ChangeClass.MEDIUM, DnsChanges.nsUpdate(ctx, nsOther, ApiNameserverGroupRequest.of(nsOther).copy(enabled = false), self, names).change.changeClass)
        assertEquals(ChangeClass.LOW, DnsChanges.nsUpdate(ctx, nsOther.copy(enabled = false), ApiNameserverGroupRequest.of(nsOther), self, names).change.changeClass)
        val primary = nsOther.copy(primary = true, domains = emptyList())
        assertEquals("a primary group losing its last nameserver", ChangeClass.HIGH,
            DnsChanges.nsUpdate(ctx, primary, ApiNameserverGroupRequest.of(primary).copy(nameservers = emptyList()), self, names).change.changeClass)
    }

    @Test
    fun turningDnsManagementOffForThisDeviceWarns() = runBlocking {
        val warned = DnsChanges.settings(ctx, ApiDnsSettings(listOf("g-x")), listOf("g-x", "g-office"), self, names).change
        assertEquals(1, warned.warnings.size)
        assertTrue(warned.warnings.single().contains("g-office"))
        assertTrue(DnsChanges.settings(ctx, ApiDnsSettings(listOf("g-x")), listOf("g-x", "g-y"), self, names).change.warnings.isEmpty())
        sent.clear()
        DnsChanges.settings(ctx, ApiDnsSettings(), listOf("g-y"), self, names).apply(api())
        assertEquals("""{"disabled_management_groups":["g-y"]}""", sent.single().body)
    }

    @Test
    fun zoneChanges() {
        val z = ApiDnsZone(id = "z1", name = "Office", domain = "office.lan", distributionGroups = listOf("g-office"), records = listOf(io.github.bropines.birdsocks.admin.api.ApiDnsRecord("r", "a.office.lan", "A", "10.0.0.1", 60)))
        val del = DnsChanges.zoneDelete(ctx, z, self).change
        assertEquals(ChangeClass.HIGH, del.changeClass)
        assertEquals("office.lan", del.confirmText)
        assertTrue(del.warnings.isNotEmpty())
        val before = AppJson.encodeToJsonElement(ApiDnsZone.serializer(), z).jsonObject
        val after = kotlinx.serialization.json.JsonObject(before + ("enable_search_domain" to kotlinx.serialization.json.JsonPrimitive(true)))
        assertEquals(listOf("enable_search_domain"), DnsChanges.zoneJson(ctx, z, before, after).change.diff.map { it.label })
    }
}
