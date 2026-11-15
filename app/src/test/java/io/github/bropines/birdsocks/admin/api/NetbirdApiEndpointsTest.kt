package io.github.bropines.birdsocks.admin.api

import io.github.bropines.birdsocks.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The new endpoints: method, path, body and how the answer is read. */
class NetbirdApiEndpointsTest {
    private class Recorder(vararg answers: String) : HttpTransport {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(200, queue.removeFirstOrNull() ?: "")
        }
    }

    private fun api(t: HttpTransport, now: Long = 0, auth: ApiAuth = ApiAuth.Token("nbp_x")) =
        NetbirdApi("https://m.example:443/api", auth, t, io = Dispatchers.Unconfined, sleeper = {}, clock = { now })

    private fun HttpRequest.path() = url.removePrefix("https://m.example:443/api")

    @Test
    fun crudPathsAndMethods() = runBlocking {
        val t = Recorder()
        val a = api(t)
        a.deleteGroup("g/1")
        a.deletePolicy("p")
        a.deletePostureCheck("pc")
        a.deleteNetworkResource("n", "r")
        a.deleteNetworkRouter("n", "rt")
        a.deleteRoute("rt")
        a.deleteNameserverGroup("ns")
        a.deleteDnsRecord("z", "rec")
        a.deleteToken("u", "t")
        a.deleteUserInvite("i")
        a.rejectUser("u")
        a.deleteIntegration(7)
        a.deleteProxyDomain("d")
        assertEquals(
            listOf(
                "DELETE /groups/g%2F1", "DELETE /policies/p", "DELETE /posture-checks/pc", "DELETE /networks/n/resources/r",
                "DELETE /networks/n/routers/rt", "DELETE /routes/rt", "DELETE /dns/nameservers/ns", "DELETE /dns/zones/z/records/rec",
                "DELETE /users/u/tokens/t", "DELETE /users/invites/i", "DELETE /users/u/reject", "DELETE /event-streaming/7",
                "DELETE /reverse-proxies/domains/d",
            ),
            t.requests.map { "${it.method} ${it.path()}" },
        )
    }

    @Test
    fun createSendsTheRequestBody() = runBlocking {
        val t = Recorder("""{"id":"g9","name":"devs"}""")
        val g = api(t).createGroup(ApiGroupRequest("devs", peers = listOf("p1")))
        assertEquals("g9", g.id)
        val r = t.requests.single()
        assertEquals("POST", r.method)
        assertEquals("/groups", r.path())
        assertEquals("""{"name":"devs","peers":["p1"]}""", r.body)
        assertEquals("application/json", r.headers["Content-Type"])
    }

    @Test
    fun approveAndInviteSendAnEmptyBody() = runBlocking {
        val t = Recorder("""{"id":"u","pending_approval":false}""", "")
        val a = api(t)
        a.approveUser("u")
        a.resendUserInvite("u")
        assertEquals(listOf("POST /users/u/approve", "POST /users/u/invite"), t.requests.map { "${it.method} ${it.path()}" })
        assertEquals("", t.requests[0].body)
    }

    @Test
    fun queriesAreEncodedAndNullsLeftOut() = runBlocking {
        val t = Recorder("[]", "[]", "[]")
        val a = api(t)
        a.peers(name = "my host")
        a.users(serviceUsers = true)
        a.groups()
        assertEquals(listOf("/peers?name=my%20host", "/users?service_user=true", "/groups"), t.requests.map { it.path() })
    }

    @Test
    fun dnsSettingsAcceptBothShapes() = runBlocking {
        val asList = api(Recorder("""[{"disabled_management_groups":["g1"]}]""")).dnsSettings()
        val asObject = api(Recorder("""{"disabled_management_groups":["g2"]}""")).dnsSettings()
        assertEquals(listOf("g1"), asList.disabledManagementGroups)
        assertEquals(listOf("g2"), asObject.disabledManagementGroups)
    }

    @Test
    fun citiesAcceptAnObjectOrAList() = runBlocking {
        assertEquals(1, api(Recorder("""{"geoname_id":1,"city_name":"Berlin"}""")).cities("DE").items.size)
        assertEquals(2, api(Recorder("""[{"geoname_id":1,"city_name":"A"},{"geoname_id":2,"city_name":"B"}]""")).cities("DE").items.size)
    }

    @Test
    fun trafficPageDecodesItsDataElementByElement() = runBlocking {
        val t = Recorder("""{"data":[{"flow_id":"a"},{"flow_id":{"bad":1}}],"page":2,"page_size":50,"total_records":51,"total_pages":2}""")
        val page = api(t).trafficEvents(TrafficQuery(page = 2, search = "ssh"))
        assertEquals(listOf("a"), page.data.items.map { it.flowId })
        assertEquals(1, page.data.issues.size)
        assertEquals(2, page.totalPages)
        assertEquals("/events/network-traffic?page=2&page_size=50&search=ssh", t.requests.single().path())
    }

    @Test
    fun patchAccountSettingsKeepsWhatTheModelDoesNotKnow() = runBlocking {
        val t = Recorder(
            """[{"id":"acc","settings":{"peer_login_expiration":3600,"future_field":{"x":1},"extra":{"peer_approval_enabled":true,"user_approval_required":false}},"onboarding":{"signup_form_pending":false}}]""",
            """{"id":"acc","settings":{"peer_login_expiration":7200}}""",
        )
        val patch = buildJsonObject {
            put("peer_login_expiration", 7200)
            put("extra", buildJsonObject { put("user_approval_required", true) })
        }
        val account = api(t).patchAccountSettings("acc", patch)
        assertEquals(7200, account.settings.peerLoginExpiration)
        val put = t.requests[1]
        assertEquals("PUT", put.method)
        assertEquals("/accounts/acc", put.path())
        val settings = AppJson.parseToJsonElement(put.body!!).jsonObject["settings"]!!.jsonObject
        assertEquals(JsonPrimitive(7200), settings["peer_login_expiration"])
        assertTrue("future_field" in settings)
        val extra = settings["extra"]!!.jsonObject
        assertEquals(JsonPrimitive(true), extra["peer_approval_enabled"])
        assertEquals(JsonPrimitive(true), extra["user_approval_required"])
        assertTrue("onboarding" in AppJson.parseToJsonElement(put.body!!).jsonObject)
    }

    @Test
    fun patchOfAnUnknownAccountIsNotFound() = runBlocking {
        try {
            api(Recorder("""[{"id":"other"}]""")).patchAccountSettings("acc", buildJsonObject {})
            fail()
        } catch (_: AdminApiException.NotFound) {
        }
    }

    @Test
    fun rawSendTakesOnlyWritesUnderTheApi() = runBlocking {
        val a = api(Recorder())
        for ((method, path) in listOf("GET" to "/groups", "PUT" to "https://evil/x", "PUT" to "/a/../b", "PUT" to "groups")) {
            try {
                a.rawSend(method, path, null)
                fail("$method $path")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun ownTokenIsTheOneJustUsed() = runBlocking {
        val now = 1_700_000_000_000L
        val tokens = """[
            {"id":"old","name":"a","last_used":"2023-11-01T00:00:00Z"},
            {"id":"mine","name":"b","last_used":"2023-11-14T22:13:10Z"},
            {"id":"never","name":"c"}
        ]"""
        assertEquals("mine", api(Recorder(tokens), now).guessOwnTokenId("u"))
        // Two used just now: cannot tell.
        val two = """[{"id":"a","last_used":"2023-11-14T22:13:10Z"},{"id":"b","last_used":"2023-11-14T22:13:15Z"}]"""
        assertNull(api(Recorder(two), now).guessOwnTokenId("u"))
        // This device's sign-in is not a token at all.
        assertNull(api(Recorder(tokens), now, ApiAuth.Bearer("jwt")).guessOwnTokenId("u"))
    }

    @Test
    fun capabilitiesKnowTheNewModules() {
        val caps = Capabilities(mapOf("policies" to mapOf("read" to true, "update" to true)), fromServer = true, role = "network_admin")
        assertTrue(caps.can(Capabilities.POLICIES, Capabilities.Op.UPDATE))
        assertTrue(Capabilities.NAMESERVERS in Capabilities.MODULES)
        assertTrue(!caps.readOnly)
    }
}
