package io.github.bropines.birdsocks.admin.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetbirdApiTest {
    /** Answers from a script, one per request, and remembers what was asked. */
    private class FakeTransport(vararg answers: () -> HttpResponse) : HttpTransport {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return queue.removeFirst()()
        }
    }

    private val sleeps = mutableListOf<Long>()

    private fun api(t: HttpTransport, auth: ApiAuth = ApiAuth.Token("nbp_secret")) =
        NetbirdApi("https://api.netbird.io:443/api", auth, t, io = Dispatchers.Unconfined, sleeper = { sleeps += it })

    private fun ok(body: String) = { HttpResponse(200, body) }
    private fun status(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) = { HttpResponse(code, body, headers) }

    // --- Partial decoding ---

    @Test
    fun oneBadElementCostsOnlyThatElement() {
        val root = Json.parseToJsonElement(
            """[{"id":"a","name":"one"}, {"id":"b","name":{"nested":"wrong"}}, "not an object", {"id":"c","name":"three"}]"""
        )
        val listing = ApiDecoding.list(root, "peer") { io.github.bropines.birdsocks.core.AppJson.decodeFromJsonElement(ApiPeer.serializer(), it) }
        assertEquals(listOf("a", "c"), listing.items.map { it.id })
        assertFalse(listing.isComplete)
        assertEquals(listOf(1, 2), listing.issues.map { it.index })
        assertEquals("b", listing.issues[0].id)
        assertNull(listing.issues[1].id)
        assertEquals("peer", listing.issues[0].what)
    }

    @Test
    fun nullListIsEmptyAndAnObjectIsADecodeError() {
        assertTrue(ApiDecoding.list(Json.parseToJsonElement("null"), "peer") { it }.items.isEmpty())
        try {
            ApiDecoding.list(Json.parseToJsonElement("""{"peers":[]}"""), "peer") { it }
            fail("an object is not a list")
        } catch (e: AdminApiException.Decode) {
            assertEquals("peer list", e.what)
        }
    }

    @Test
    fun listThroughTheClient() = runBlocking {
        val t = FakeTransport(ok("""[{"id":"g1","name":"All","peers_count":3},{"id":"g2","name":5,"peers_count":"x"}]"""))
        val groups = api(t).groups()
        assertEquals(listOf("g1"), groups.items.map { it.id })
        assertEquals(1, groups.issues.size)
    }

    // --- Requests ---

    @Test
    fun tokenHeaderAndUrl() = runBlocking {
        val t = FakeTransport(ok("""{"id":"u","role":"owner"}"""))
        api(t).currentUser()
        val r = t.requests.single()
        assertEquals("GET", r.method)
        assertEquals("https://api.netbird.io:443/api/users/current", r.url)
        assertEquals("Token nbp_secret", r.headers["Authorization"])
        // Nothing that prints a request shows the credential.
        assertFalse(r.toString().contains("nbp_secret"))
        assertFalse(ApiAuth.Token("nbp_secret").toString().contains("nbp_secret"))
    }

    @Test
    fun bearerForThisDevicesSignIn() = runBlocking {
        val t = FakeTransport(ok("[]"))
        api(t, ApiAuth.Bearer("eyJ.jwt")).peers()
        assertEquals("Bearer eyJ.jwt", t.requests.single().headers["Authorization"])
    }

    @Test
    fun pathSegmentsAreEncoded() = runBlocking {
        val t = FakeTransport(ok(""))
        api(t).deletePeer("a/b c")
        assertEquals("https://api.netbird.io:443/api/peers/a%2Fb%20c", t.requests.single().url)
        assertEquals("DELETE", t.requests.single().method)
    }

    @Test
    fun revokeSendsBothRequiredFields() = runBlocking {
        val t = FakeTransport(ok("""{"id":"k","revoked":true}"""))
        api(t).revokeSetupKey(ApiSetupKey(id = "k", autoGroups = listOf("g1")))
        val r = t.requests.single()
        assertEquals("PUT", r.method)
        assertEquals("""{"revoked":true,"auto_groups":["g1"]}""", r.body)
        assertEquals("application/json", r.headers["Content-Type"])
    }

    // --- Retries ---

    @Test
    fun readsRetryOn5xxAndDroppedConnections() = runBlocking {
        val t = FakeTransport(status(503), { throw TransportException("reset") }, ok("[]"))
        assertTrue(api(t).peers().items.isEmpty())
        assertEquals(3, t.requests.size)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test
    fun readsGiveUpAfterThreeAttempts() = runBlocking {
        val t = FakeTransport(status(502), status(502), status(502, """{"message":"bad gateway","code":502}"""))
        try {
            api(t).peers()
            fail()
        } catch (e: AdminApiException.Server) {
            assertEquals(502, e.status)
            assertEquals("bad gateway", e.apiMessage)
            assertTrue(e.isTransient)
        }
        assertEquals(3, t.requests.size)
    }

    @Test
    fun writesAreNotRetriedOn5xxOrNoAnswer() = runBlocking {
        val t = FakeTransport(status(503))
        try { api(t).deletePeer("p"); fail() } catch (_: AdminApiException.Server) {}
        assertEquals(1, t.requests.size)

        val t2 = FakeTransport({ throw TransportException("timeout") })
        try { api(t2).deletePeer("p"); fail() } catch (e: AdminApiException.Network) { assertEquals("timeout", e.detail) }
        assertEquals(1, t2.requests.size)
    }

    @Test
    fun writesRetryOn429HonouringRetryAfter() = runBlocking {
        val t = FakeTransport(status(429, headers = mapOf("Retry-After" to "2")), ok(""))
        api(t).deletePeer("p")
        assertEquals(2, t.requests.size)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun aLongRetryAfterIsNotWaitedOut() = runBlocking {
        val t = FakeTransport(status(429, """{"message":"too many requests","code":429}""", mapOf("Retry-After" to "120")))
        try {
            api(t).peers()
            fail()
        } catch (e: AdminApiException.RateLimited) {
            assertEquals(120L, e.retryAfterSec)
        }
        assertEquals(1, t.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun anUnusableProxyIsNotRetried() = runBlocking {
        val t = FakeTransport({ throw ProxyUnusableException(ProxyRoute.Reason.SOCKS_AUTH) })
        try {
            api(t).peers()
            fail()
        } catch (e: AdminApiException.Network) {
            assertEquals(ProxyRoute.Reason.SOCKS_AUTH, (e.cause as ProxyUnusableException).reason)
        }
        assertEquals(1, t.requests.size)
    }

    // --- Errors ---

    @Test
    fun statusesMapToTypedErrors() = runBlocking {
        suspend fun errorFor(code: Int): AdminApiException = try {
            api(FakeTransport(status(code, """{"message":"m$code","code":$code}"""))).currentUser()
            throw AssertionError("no error for $code")
        } catch (e: AdminApiException) { e }
        assertTrue(errorFor(401) is AdminApiException.Unauthorized)
        assertTrue(errorFor(403) is AdminApiException.Forbidden)
        assertTrue(errorFor(404) is AdminApiException.NotFound)
        assertTrue(errorFor(409) is AdminApiException.Conflict)
        assertTrue(errorFor(422) is AdminApiException.BadRequest)
        assertTrue(errorFor(500) is AdminApiException.Server)
        assertTrue(errorFor(302) is AdminApiException.Unexpected)
        assertEquals("m403", errorFor(403).apiMessage)
        assertFalse(errorFor(401).isTransient)
    }

    @Test
    fun unreadableObjectIsADecodeError() = runBlocking {
        try {
            api(FakeTransport(ok("<html>"))).currentUser()
            fail()
        } catch (e: AdminApiException.Decode) {
            assertEquals("user", e.what)
        }
    }
}
