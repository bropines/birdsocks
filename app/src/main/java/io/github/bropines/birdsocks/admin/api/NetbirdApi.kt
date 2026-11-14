package io.github.bropines.birdsocks.admin.api

import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.ui.parseRfc3339Millis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A list as far as it could be read: one element whose JSON changed shape
 * costs that element, not the list, and [issues] says which were dropped.
 */
data class Listing<T>(val items: List<T>, val issues: List<DecodeIssue> = emptyList()) {
    val isComplete: Boolean get() = issues.isEmpty()

    fun <R> map(transform: (T) -> R): Listing<R> = Listing(items.map(transform), issues)
}

/** One list element that could not be read: where it was, its id if it had one, and why. */
data class DecodeIssue(val what: String, val index: Int, val id: String?, val reason: String)

/** How reads back off. Writes are retried only on a 429, which the server answers before applying anything. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    /** A Retry-After longer than this is not waited out: the call fails as rate-limited. */
    val maxRetryAfterMs: Long = 30_000,
) {
    fun backoff(attempt: Int): Long = (baseDelayMs shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(maxDelayMs)
}

/** How a request proves who sends it. [toString] never prints the secret. */
sealed interface ApiAuth {
    val header: String

    /** A personal access token: `Authorization: Token nbp_…`. */
    class Token(private val token: String) : ApiAuth {
        override val header: String get() = "Token $token"
        override fun toString() = "Token(…)"
    }

    /** A JWT from the identity provider, as the dashboard sends it. */
    class Bearer(private val jwt: String) : ApiAuth {
        override val header: String get() = "Bearer $jwt"
        override fun toString() = "Bearer(…)"
    }
}

/**
 * NetBird's management HTTP API, one method per endpoint the panel uses.
 *
 * - [baseUrl] is the account's management URL with its port, plus `/api`
 *   ([AdminUrls.apiBase]).
 * - Reads retry on 429 (honouring Retry-After), 502–504 and a dropped
 *   connection; writes go out once and are retried only on 429.
 * - Lists decode element by element into a [Listing].
 *
 * **Screens never call the write methods** (POST, PUT, DELETE): every write is
 * a [io.github.bropines.birdsocks.admin.safety.PlannedChange] built in an
 * area's `*Changes.kt` and run by the SafeChangeRunner. AdminPanelStructureTest holds
 * the sources to that.
 */
class NetbirdApi(
    private val baseUrl: String,
    private val auth: ApiAuth,
    private val transport: HttpTransport,
    private val retry: RetryPolicy = RetryPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Whether this client signs in with a personal access token (the guard against revoking it needs to know). */
    val usesToken: Boolean get() = auth is ApiAuth.Token

    // ================================================================ account and identity

    suspend fun currentUser(): ApiUser = get("/users/current", ApiUser.serializer(), "user")

    suspend fun accounts(): Listing<ApiAccount> = list("/accounts", ApiAccount.serializer(), "account")

    suspend fun updateAccount(id: String, request: ApiAccountRequest): ApiAccount =
        send("PUT", "/accounts/${seg(id)}", ApiAccountRequest.serializer(), request, ApiAccount.serializer(), "account")

    /**
     * Changes some of the account's settings and keeps the rest exactly as the
     * server has them, fields this model does not know included: reads the
     * account afresh, lays [patch] over its `settings` (an `extra` object in
     * the patch is merged key by key too) and PUTs the result.
     */
    suspend fun patchAccountSettings(id: String, patch: JsonObject): ApiAccount {
        val all = rawGet("/accounts") as? JsonArray ?: throw AdminApiException.Decode("account list")
        val current = all.firstOrNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content == id }?.jsonObject
            ?: throw AdminApiException.NotFound(null)
        val settings = current["settings"] as? JsonObject ?: JsonObject(emptyMap())
        val merged = JsonMerge.merge(settings, patch)
        val body = buildJsonObject {
            put("settings", merged)
            current["onboarding"]?.let { put("onboarding", it) }
        }
        return decodeObject(rawSend("PUT", "/accounts/${seg(id)}", body), "account")
    }

    // ================================================================ peers

    /** [name] and [ip] narrow the list on the server. */
    suspend fun peers(name: String? = null, ip: String? = null): Listing<ApiPeer> =
        list("/peers" + query("name" to name, "ip" to ip), ApiPeer.serializer(), "peer")

    suspend fun peer(id: String): ApiPeer = get("/peers/${seg(id)}", ApiPeer.serializer(), "peer")

    suspend fun updatePeer(id: String, request: ApiPeerRequest): ApiPeer =
        send("PUT", "/peers/${seg(id)}", ApiPeerRequest.serializer(), request, ApiPeer.serializer(), "peer")

    suspend fun deletePeer(id: String) { exchange("DELETE", "/peers/${seg(id)}") }

    suspend fun accessiblePeers(id: String): Listing<ApiAccessiblePeer> =
        list("/peers/${seg(id)}/accessible-peers", ApiAccessiblePeer.serializer(), "accessible peer")

    suspend fun createTemporaryAccess(peerId: String, request: ApiPeerTemporaryAccessRequest): ApiPeerTemporaryAccessResponse =
        send("POST", "/peers/${seg(peerId)}/temporary-access", ApiPeerTemporaryAccessRequest.serializer(), request, ApiPeerTemporaryAccessResponse.serializer(), "temporary access")

    /** Experimental on the server. */
    suspend fun peerJobs(peerId: String): Listing<ApiJob> = list("/peers/${seg(peerId)}/jobs", ApiJob.serializer(), "job")

    suspend fun peerJob(peerId: String, jobId: String): ApiJob = get("/peers/${seg(peerId)}/jobs/${seg(jobId)}", ApiJob.serializer(), "job")

    suspend fun createPeerJob(peerId: String, request: ApiJobRequest): ApiJob =
        send("POST", "/peers/${seg(peerId)}/jobs", ApiJobRequest.serializer(), request, ApiJob.serializer(), "job")

    // ================================================================ setup keys

    suspend fun setupKeys(): Listing<ApiSetupKey> = list("/setup-keys", ApiSetupKey.serializer(), "setup key")

    suspend fun setupKey(id: String): ApiSetupKey = get("/setup-keys/${seg(id)}", ApiSetupKey.serializer(), "setup key")

    /** The answer carries the whole key, once; the server keeps only a hash. */
    suspend fun createSetupKey(request: ApiCreateSetupKey): ApiSetupKey =
        send("POST", "/setup-keys", ApiCreateSetupKey.serializer(), request, ApiSetupKey.serializer(), "setup key")

    suspend fun updateSetupKey(id: String, request: ApiSetupKeyUpdate): ApiSetupKey =
        send("PUT", "/setup-keys/${seg(id)}", ApiSetupKeyUpdate.serializer(), request, ApiSetupKey.serializer(), "setup key")

    suspend fun revokeSetupKey(key: ApiSetupKey): ApiSetupKey = updateSetupKey(key.id, ApiSetupKeyUpdate(true, key.autoGroups))

    suspend fun deleteSetupKey(id: String) { exchange("DELETE", "/setup-keys/${seg(id)}") }

    // ================================================================ groups

    suspend fun groups(name: String? = null): Listing<ApiGroup> = list("/groups" + query("name" to name), ApiGroup.serializer(), "group")

    suspend fun group(id: String): ApiGroup = get("/groups/${seg(id)}", ApiGroup.serializer(), "group")

    suspend fun createGroup(request: ApiGroupRequest): ApiGroup =
        send("POST", "/groups", ApiGroupRequest.serializer(), request, ApiGroup.serializer(), "group")

    suspend fun updateGroup(id: String, request: ApiGroupRequest): ApiGroup =
        send("PUT", "/groups/${seg(id)}", ApiGroupRequest.serializer(), request, ApiGroup.serializer(), "group")

    suspend fun deleteGroup(id: String) { exchange("DELETE", "/groups/${seg(id)}") }

    // ================================================================ policies

    suspend fun policies(): Listing<ApiPolicy> = list("/policies", ApiPolicy.serializer(), "policy")

    suspend fun policy(id: String): ApiPolicy = get("/policies/${seg(id)}", ApiPolicy.serializer(), "policy")

    suspend fun createPolicy(request: ApiPolicyRequest): ApiPolicy =
        send("POST", "/policies", ApiPolicyRequest.serializer(), request, ApiPolicy.serializer(), "policy")

    suspend fun updatePolicy(id: String, request: ApiPolicyRequest): ApiPolicy =
        send("PUT", "/policies/${seg(id)}", ApiPolicyRequest.serializer(), request, ApiPolicy.serializer(), "policy")

    suspend fun deletePolicy(id: String) { exchange("DELETE", "/policies/${seg(id)}") }

    // ================================================================ posture checks

    suspend fun postureChecks(): Listing<ApiPostureCheck> = list("/posture-checks", ApiPostureCheck.serializer(), "posture check")

    suspend fun postureCheck(id: String): ApiPostureCheck = get("/posture-checks/${seg(id)}", ApiPostureCheck.serializer(), "posture check")

    suspend fun createPostureCheck(request: ApiPostureCheckRequest): ApiPostureCheck =
        send("POST", "/posture-checks", ApiPostureCheckRequest.serializer(), request, ApiPostureCheck.serializer(), "posture check")

    suspend fun updatePostureCheck(id: String, request: ApiPostureCheckRequest): ApiPostureCheck =
        send("PUT", "/posture-checks/${seg(id)}", ApiPostureCheckRequest.serializer(), request, ApiPostureCheck.serializer(), "posture check")

    suspend fun deletePostureCheck(id: String) { exchange("DELETE", "/posture-checks/${seg(id)}") }

    /** ISO 3166-1 alpha-2 codes the geolocation database knows. */
    suspend fun countries(): List<String> = get("/locations/countries", ListSerializer(String.serializer()), "country list")

    /** The spec says one City; servers answer a list. Both are taken. */
    suspend fun cities(country: String): Listing<ApiCity> {
        val root = AppJson.parseToJsonElement(exchange("GET", "/locations/countries/${seg(country)}/cities").body.ifBlank { "[]" })
        val array = if (root is JsonObject) JsonArray(listOf(root)) else root
        return ApiDecoding.list(array, "city") { AppJson.decodeFromJsonElement(ApiCity.serializer(), it) }
    }

    // ================================================================ networks

    suspend fun networks(): Listing<ApiNetwork> = list("/networks", ApiNetwork.serializer(), "network")

    suspend fun network(id: String): ApiNetwork = get("/networks/${seg(id)}", ApiNetwork.serializer(), "network")

    suspend fun createNetwork(request: ApiNetworkRequest): ApiNetwork =
        send("POST", "/networks", ApiNetworkRequest.serializer(), request, ApiNetwork.serializer(), "network")

    suspend fun updateNetwork(id: String, request: ApiNetworkRequest): ApiNetwork =
        send("PUT", "/networks/${seg(id)}", ApiNetworkRequest.serializer(), request, ApiNetwork.serializer(), "network")

    suspend fun deleteNetwork(id: String) { exchange("DELETE", "/networks/${seg(id)}") }

    suspend fun networkResources(networkId: String): Listing<ApiNetworkResource> =
        list("/networks/${seg(networkId)}/resources", ApiNetworkResource.serializer(), "network resource")

    suspend fun networkResource(networkId: String, id: String): ApiNetworkResource =
        get("/networks/${seg(networkId)}/resources/${seg(id)}", ApiNetworkResource.serializer(), "network resource")

    suspend fun createNetworkResource(networkId: String, request: ApiNetworkResourceRequest): ApiNetworkResource =
        send("POST", "/networks/${seg(networkId)}/resources", ApiNetworkResourceRequest.serializer(), request, ApiNetworkResource.serializer(), "network resource")

    suspend fun updateNetworkResource(networkId: String, id: String, request: ApiNetworkResourceRequest): ApiNetworkResource =
        send("PUT", "/networks/${seg(networkId)}/resources/${seg(id)}", ApiNetworkResourceRequest.serializer(), request, ApiNetworkResource.serializer(), "network resource")

    suspend fun deleteNetworkResource(networkId: String, id: String) { exchange("DELETE", "/networks/${seg(networkId)}/resources/${seg(id)}") }

    suspend fun networkRouters(networkId: String): Listing<ApiNetworkRouter> =
        list("/networks/${seg(networkId)}/routers", ApiNetworkRouter.serializer(), "network router")

    /** Every router of every network. */
    suspend fun allNetworkRouters(): Listing<ApiNetworkRouter> = list("/networks/routers", ApiNetworkRouter.serializer(), "network router")

    suspend fun networkRouter(networkId: String, id: String): ApiNetworkRouter =
        get("/networks/${seg(networkId)}/routers/${seg(id)}", ApiNetworkRouter.serializer(), "network router")

    suspend fun createNetworkRouter(networkId: String, request: ApiNetworkRouterRequest): ApiNetworkRouter =
        send("POST", "/networks/${seg(networkId)}/routers", ApiNetworkRouterRequest.serializer(), request, ApiNetworkRouter.serializer(), "network router")

    suspend fun updateNetworkRouter(networkId: String, id: String, request: ApiNetworkRouterRequest): ApiNetworkRouter =
        send("PUT", "/networks/${seg(networkId)}/routers/${seg(id)}", ApiNetworkRouterRequest.serializer(), request, ApiNetworkRouter.serializer(), "network router")

    suspend fun deleteNetworkRouter(networkId: String, id: String) { exchange("DELETE", "/networks/${seg(networkId)}/routers/${seg(id)}") }

    // ================================================================ routes (legacy)

    suspend fun routes(): Listing<ApiRoute> = list("/routes", ApiRoute.serializer(), "route")

    suspend fun route(id: String): ApiRoute = get("/routes/${seg(id)}", ApiRoute.serializer(), "route")

    suspend fun createRoute(request: ApiRouteRequest): ApiRoute =
        send("POST", "/routes", ApiRouteRequest.serializer(), request, ApiRoute.serializer(), "route")

    suspend fun updateRoute(id: String, request: ApiRouteRequest): ApiRoute =
        send("PUT", "/routes/${seg(id)}", ApiRouteRequest.serializer(), request, ApiRoute.serializer(), "route")

    suspend fun deleteRoute(id: String) { exchange("DELETE", "/routes/${seg(id)}") }

    // ================================================================ DNS

    suspend fun nameserverGroups(): Listing<ApiNameserverGroup> = list("/dns/nameservers", ApiNameserverGroup.serializer(), "nameserver group")

    suspend fun nameserverGroup(id: String): ApiNameserverGroup = get("/dns/nameservers/${seg(id)}", ApiNameserverGroup.serializer(), "nameserver group")

    suspend fun createNameserverGroup(request: ApiNameserverGroupRequest): ApiNameserverGroup =
        send("POST", "/dns/nameservers", ApiNameserverGroupRequest.serializer(), request, ApiNameserverGroup.serializer(), "nameserver group")

    suspend fun updateNameserverGroup(id: String, request: ApiNameserverGroupRequest): ApiNameserverGroup =
        send("PUT", "/dns/nameservers/${seg(id)}", ApiNameserverGroupRequest.serializer(), request, ApiNameserverGroup.serializer(), "nameserver group")

    suspend fun deleteNameserverGroup(id: String) { exchange("DELETE", "/dns/nameservers/${seg(id)}") }

    /** The spec writes the answer as a list of one; servers send the object. Both are taken. */
    suspend fun dnsSettings(): ApiDnsSettings {
        val root = AppJson.parseToJsonElement(exchange("GET", "/dns/settings").body.ifBlank { "{}" })
        val obj = (root as? JsonArray)?.firstOrNull() ?: root
        return try {
            AppJson.decodeFromJsonElement(ApiDnsSettings.serializer(), obj)
        } catch (e: Exception) {
            throw AdminApiException.Decode("DNS settings", e)
        }
    }

    suspend fun updateDnsSettings(request: ApiDnsSettings): ApiDnsSettings =
        send("PUT", "/dns/settings", ApiDnsSettings.serializer(), request, ApiDnsSettings.serializer(), "DNS settings")

    suspend fun dnsZones(): Listing<ApiDnsZone> = list("/dns/zones", ApiDnsZone.serializer(), "DNS zone")

    suspend fun dnsZone(id: String): ApiDnsZone = get("/dns/zones/${seg(id)}", ApiDnsZone.serializer(), "DNS zone")

    suspend fun createDnsZone(request: ApiDnsZoneRequest): ApiDnsZone =
        send("POST", "/dns/zones", ApiDnsZoneRequest.serializer(), request, ApiDnsZone.serializer(), "DNS zone")

    suspend fun updateDnsZone(id: String, request: ApiDnsZoneRequest): ApiDnsZone =
        send("PUT", "/dns/zones/${seg(id)}", ApiDnsZoneRequest.serializer(), request, ApiDnsZone.serializer(), "DNS zone")

    suspend fun deleteDnsZone(id: String) { exchange("DELETE", "/dns/zones/${seg(id)}") }

    suspend fun dnsRecords(zoneId: String): Listing<ApiDnsRecord> = list("/dns/zones/${seg(zoneId)}/records", ApiDnsRecord.serializer(), "DNS record")

    suspend fun dnsRecord(zoneId: String, id: String): ApiDnsRecord =
        get("/dns/zones/${seg(zoneId)}/records/${seg(id)}", ApiDnsRecord.serializer(), "DNS record")

    suspend fun createDnsRecord(zoneId: String, request: ApiDnsRecordRequest): ApiDnsRecord =
        send("POST", "/dns/zones/${seg(zoneId)}/records", ApiDnsRecordRequest.serializer(), request, ApiDnsRecord.serializer(), "DNS record")

    suspend fun updateDnsRecord(zoneId: String, id: String, request: ApiDnsRecordRequest): ApiDnsRecord =
        send("PUT", "/dns/zones/${seg(zoneId)}/records/${seg(id)}", ApiDnsRecordRequest.serializer(), request, ApiDnsRecord.serializer(), "DNS record")

    suspend fun deleteDnsRecord(zoneId: String, id: String) { exchange("DELETE", "/dns/zones/${seg(zoneId)}/records/${seg(id)}") }

    // ================================================================ users

    /** [serviceUsers] true lists service users, false regular ones, null whatever the server gives. */
    suspend fun users(serviceUsers: Boolean? = null): Listing<ApiUser> =
        list("/users" + query("service_user" to serviceUsers?.toString()), ApiUser.serializer(), "user")

    suspend fun createUser(request: ApiUserCreateRequest): ApiUser =
        send("POST", "/users", ApiUserCreateRequest.serializer(), request, ApiUser.serializer(), "user")

    suspend fun updateUser(id: String, request: ApiUserRequest): ApiUser =
        send("PUT", "/users/${seg(id)}", ApiUserRequest.serializer(), request, ApiUser.serializer(), "user")

    suspend fun deleteUser(id: String) { exchange("DELETE", "/users/${seg(id)}") }

    /** Sends the invitation e-mail again. */
    suspend fun resendUserInvite(id: String) { exchange("POST", "/users/${seg(id)}/invite", "") }

    suspend fun approveUser(id: String): ApiUser = decodeObject(exchange("POST", "/users/${seg(id)}/approve", ""), "user")

    suspend fun rejectUser(id: String) { exchange("DELETE", "/users/${seg(id)}/reject") }

    // --- personal access tokens ---

    suspend fun tokens(userId: String): Listing<ApiPersonalAccessToken> =
        list("/users/${seg(userId)}/tokens", ApiPersonalAccessToken.serializer(), "token")

    suspend fun token(userId: String, tokenId: String): ApiPersonalAccessToken =
        get("/users/${seg(userId)}/tokens/${seg(tokenId)}", ApiPersonalAccessToken.serializer(), "token")

    /** The plain token is in the answer once. */
    suspend fun createToken(userId: String, request: ApiPersonalAccessTokenRequest): ApiPersonalAccessTokenGenerated =
        send("POST", "/users/${seg(userId)}/tokens", ApiPersonalAccessTokenRequest.serializer(), request, ApiPersonalAccessTokenGenerated.serializer(), "token")

    suspend fun deleteToken(userId: String, tokenId: String) { exchange("DELETE", "/users/${seg(userId)}/tokens/${seg(tokenId)}") }

    /**
     * Which of [userId]'s tokens this client is signed in with, as far as can
     * be told: the server keeps no part of a token that names it, but marks
     * `last_used` on every request — and this one has just been made. The
     * token used within [windowMs] of now, when exactly one was; else null.
     */
    suspend fun guessOwnTokenId(userId: String, windowMs: Long = 120_000): String? {
        if (!usesToken) return null
        val now = clock()
        val recent = tokens(userId).items.filter { t ->
            val used = parseRfc3339Millis(t.lastUsed) ?: return@filter false
            now - used in -windowMs..windowMs
        }
        return recent.singleOrNull()?.id
    }

    // --- invites (links) ---

    suspend fun userInvites(): Listing<ApiUserInvite> = list("/users/invites", ApiUserInvite.serializer(), "invite")

    /** The answer carries the invite token: a link that admits whoever holds it. */
    suspend fun createUserInvite(request: ApiUserInviteCreateRequest): ApiUserInvite =
        send("POST", "/users/invites", ApiUserInviteCreateRequest.serializer(), request, ApiUserInvite.serializer(), "invite")

    suspend fun deleteUserInvite(id: String) { exchange("DELETE", "/users/invites/${seg(id)}") }

    suspend fun regenerateUserInvite(id: String, request: ApiUserInviteRegenerateRequest = ApiUserInviteRegenerateRequest()): ApiUserInviteRegenerateResponse =
        send("POST", "/users/invites/${seg(id)}/regenerate", ApiUserInviteRegenerateRequest.serializer(), request, ApiUserInviteRegenerateResponse.serializer(), "invite")

    // ================================================================ events

    suspend fun auditEvents(): Listing<ApiEvent> = list("/events/audit", ApiEvent.serializer(), "event")

    /** Cloud only, experimental. */
    suspend fun trafficEvents(q: TrafficQuery = TrafficQuery()): ApiTrafficPage {
        val root = AppJson.parseToJsonElement(exchange("GET", "/events/network-traffic" + query(*q.params().toTypedArray())).body.ifBlank { "{}" })
        val obj = root as? JsonObject ?: throw AdminApiException.Decode("traffic events")
        fun int(name: String) = runCatching { obj[name]?.jsonPrimitive?.int }.getOrNull() ?: 0
        return ApiTrafficPage(
            data = ApiDecoding.list(obj["data"] ?: JsonNull, "traffic event") { AppJson.decodeFromJsonElement(ApiNetworkTrafficEvent.serializer(), it) },
            page = int("page"),
            pageSize = int("page_size"),
            totalRecords = int("total_records"),
            totalPages = int("total_pages"),
        )
    }

    // ================================================================ event streaming (cloud)

    suspend fun integrations(): Listing<ApiIntegration> = list("/event-streaming", ApiIntegration.serializer(), "event stream")

    suspend fun integration(id: Long): ApiIntegration = get("/event-streaming/$id", ApiIntegration.serializer(), "event stream")

    suspend fun createIntegration(request: ApiIntegrationRequest): ApiIntegration =
        send("POST", "/event-streaming", ApiIntegrationRequest.serializer(), request, ApiIntegration.serializer(), "event stream")

    suspend fun updateIntegration(id: Long, request: ApiIntegrationRequest): ApiIntegration =
        send("PUT", "/event-streaming/$id", ApiIntegrationRequest.serializer(), request, ApiIntegration.serializer(), "event stream")

    suspend fun deleteIntegration(id: Long) { exchange("DELETE", "/event-streaming/$id") }

    // ================================================================ identity providers

    suspend fun identityProviders(): Listing<ApiIdentityProvider> = list("/identity-providers", ApiIdentityProvider.serializer(), "identity provider")

    suspend fun identityProvider(id: String): ApiIdentityProvider = get("/identity-providers/${seg(id)}", ApiIdentityProvider.serializer(), "identity provider")

    suspend fun createIdentityProvider(request: ApiIdentityProviderRequest): ApiIdentityProvider =
        send("POST", "/identity-providers", ApiIdentityProviderRequest.serializer(), request, ApiIdentityProvider.serializer(), "identity provider")

    suspend fun updateIdentityProvider(id: String, request: ApiIdentityProviderRequest): ApiIdentityProvider =
        send("PUT", "/identity-providers/${seg(id)}", ApiIdentityProviderRequest.serializer(), request, ApiIdentityProvider.serializer(), "identity provider")

    suspend fun deleteIdentityProvider(id: String) { exchange("DELETE", "/identity-providers/${seg(id)}") }

    // ================================================================ instance

    /** Unauthenticated on the server; sent with the credential all the same. */
    suspend fun instanceStatus(): ApiInstanceStatus = get("/instance", ApiInstanceStatus.serializer(), "instance status")

    suspend fun instanceVersion(): ApiInstanceVersion = get("/instance/version", ApiInstanceVersion.serializer(), "instance version")

    // ================================================================ reverse proxy

    suspend fun proxyServices(): Listing<ApiService> = list("/reverse-proxies/services", ApiService.serializer(), "service")

    suspend fun proxyService(id: String): ApiService = get("/reverse-proxies/services/${seg(id)}", ApiService.serializer(), "service")

    suspend fun createProxyService(request: ApiServiceRequest): ApiService =
        send("POST", "/reverse-proxies/services", ApiServiceRequest.serializer(), request, ApiService.serializer(), "service")

    suspend fun updateProxyService(id: String, request: ApiServiceRequest): ApiService =
        send("PUT", "/reverse-proxies/services/${seg(id)}", ApiServiceRequest.serializer(), request, ApiService.serializer(), "service")

    suspend fun deleteProxyService(id: String) { exchange("DELETE", "/reverse-proxies/services/${seg(id)}") }

    suspend fun proxyDomains(): Listing<ApiReverseProxyDomain> = list("/reverse-proxies/domains", ApiReverseProxyDomain.serializer(), "domain")

    /** The spec names a Service as the answer; what comes back is not read. */
    suspend fun createProxyDomain(request: ApiReverseProxyDomainRequest) {
        exchange("POST", "/reverse-proxies/domains", AppJson.encodeToString(ApiReverseProxyDomainRequest.serializer(), request))
    }

    suspend fun deleteProxyDomain(id: String) { exchange("DELETE", "/reverse-proxies/domains/${seg(id)}") }

    /** Asks the server to check the domain's DNS now; true when it answers 2xx. */
    suspend fun validateProxyDomain(id: String): Boolean = runCatching { exchange("GET", "/reverse-proxies/domains/${seg(id)}/validate") }.isSuccess

    // ================================================================ raw JSON

    /**
     * Any GET under the API as plain JSON, for the JSON editor and for fields
     * a model does not know. [path] starts with "/" and is relative to /api.
     */
    suspend fun rawGet(path: String): JsonElement =
        AppJson.parseToJsonElement(exchange("GET", checkPath(path)).body.ifBlank { "null" })

    /** A write with a JSON body, as given; the answer as JSON (null for none). A write: only from a PlannedChange. */
    suspend fun rawSend(method: String, path: String, body: JsonElement?): JsonElement {
        require(method in setOf("POST", "PUT", "DELETE")) { "not a write method" }
        val resp = exchange(method, checkPath(path), body?.toString() ?: if (method == "DELETE") null else "")
        return if (resp.body.isBlank()) JsonNull else AppJson.parseToJsonElement(resp.body)
    }

    private fun checkPath(path: String): String {
        require(path.startsWith("/") && "://" !in path && ".." !in path) { "a path under /api" }
        return path
    }

    // ================================================================ the exchange

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, what: String): T =
        decodeObject(exchange("GET", path), serializer, what)

    private suspend fun <T> list(path: String, serializer: KSerializer<T>, what: String): Listing<T> =
        decodeList(exchange("GET", path), serializer, what)

    private suspend fun <Q, T> send(method: String, path: String, reqSerializer: KSerializer<Q>, request: Q, respSerializer: KSerializer<T>, what: String): T =
        decodeObject(exchange(method, path, AppJson.encodeToString(reqSerializer, request)), respSerializer, what)

    private suspend fun exchange(method: String, path: String, body: String? = null): HttpResponse {
        val headers = buildMap {
            put("Authorization", auth.header)
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        val resp = withContext(io) { sendWithRetries(HttpRequest(method, baseUrl + path, headers, body), write = method != "GET") }
        if (resp.status in 200..299) return resp
        throw errorFor(resp)
    }

    /** Returns the first answer not worth retrying, or the last one. */
    private suspend fun sendWithRetries(request: HttpRequest, write: Boolean): HttpResponse {
        var attempt = 0
        while (true) {
            attempt++
            val resp = try {
                transport.execute(request)
            } catch (e: TransportException) {
                // A write that got no answer may have been applied: reported, never re-sent.
                if (!write && e !is ProxyUnusableException && attempt < retry.maxAttempts) {
                    sleeper(retry.backoff(attempt))
                    continue
                }
                throw AdminApiException.Network(e.message ?: "no answer", e)
            }
            val retryable = resp.status == 429 || (!write && resp.status in RETRYABLE_5XX)
            if (retryable && attempt < retry.maxAttempts) {
                val wait = retryAfterMs(resp) ?: retry.backoff(attempt)
                if (wait <= retry.maxRetryAfterMs) {
                    sleeper(wait)
                    continue
                }
            }
            return resp
        }
    }

    private fun retryAfterMs(resp: HttpResponse): Long? {
        val raw = resp.header("Retry-After")?.trim() ?: return null
        raw.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        return runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            (fmt.parse(raw)!!.time - clock()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun errorFor(resp: HttpResponse): AdminApiException {
        val msg = apiMessage(resp.body)
        return when (resp.status) {
            400, 422 -> AdminApiException.BadRequest(resp.status, msg)
            401 -> AdminApiException.Unauthorized(msg)
            403 -> AdminApiException.Forbidden(msg)
            404 -> AdminApiException.NotFound(msg)
            409 -> AdminApiException.Conflict(msg)
            429 -> AdminApiException.RateLimited(msg, retryAfterMs(resp)?.div(1000))
            in 500..599 -> AdminApiException.Server(resp.status, msg)
            else -> AdminApiException.Unexpected(resp.status, msg)
        }
    }

    private inline fun <reified T> decodeObject(resp: HttpResponse, what: String): T = try {
        AppJson.decodeFromString<T>(resp.body.ifBlank { "{}" })
    } catch (e: Exception) {
        throw AdminApiException.Decode(what, e)
    }

    private inline fun <reified T> decodeObject(element: JsonElement, what: String): T = try {
        AppJson.decodeFromJsonElement<T>(if (element is JsonNull) JsonObject(emptyMap()) else element)
    } catch (e: Exception) {
        throw AdminApiException.Decode(what, e)
    }

    private fun <T> decodeObject(resp: HttpResponse, serializer: KSerializer<T>, what: String): T = try {
        AppJson.decodeFromString(serializer, resp.body.ifBlank { "{}" })
    } catch (e: Exception) {
        throw AdminApiException.Decode(what, e)
    }

    private fun <T> decodeList(resp: HttpResponse, serializer: KSerializer<T>, what: String): Listing<T> = try {
        ApiDecoding.list(AppJson.parseToJsonElement(resp.body.ifBlank { "[]" }), what) { AppJson.decodeFromJsonElement(serializer, it) }
    } catch (e: AdminApiException) {
        throw e
    } catch (e: Exception) {
        throw AdminApiException.Decode("$what list", e)
    }

    companion object {
        private val RETRYABLE_5XX = setOf(502, 503, 504)

        private fun seg(value: String) = Urls.seg(value)

        /** "?a=1&b=2" for the pairs whose value is not null; "" when none is. */
        internal fun query(vararg params: Pair<String, String?>): String {
            val kept = params.filter { it.second != null }
            if (kept.isEmpty()) return ""
            return kept.joinToString("&", prefix = "?") { (k, v) -> Urls.seg(k) + "=" + Urls.seg(v!!) }
        }

        /** The server's own `message` from an error body, trimmed; null when there is none. */
        internal fun apiMessage(body: String): String? =
            runCatching { AppJson.decodeFromString(ApiErrorBody.serializer(), body).message }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
    }
}

/** Deep merge of JSON objects, for patches over what the server holds. */
internal object JsonMerge {
    /** [patch] over [base]: objects merge key by key, anything else in [patch] replaces. */
    fun merge(base: JsonObject, patch: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in patch) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) merge(old, v) else v
        }
        return JsonObject(out)
    }
}

/** The partial decoding, apart from HTTP so it is tested on its own. */
internal object ApiDecoding {
    /**
     * Every element of [root] — a JSON array, as NetBird answers a list — read
     * with [decode]; what fails is a [DecodeIssue], not an exception. null is
     * an empty list; anything that is not an array is a [AdminApiException.Decode].
     */
    fun <T> list(root: JsonElement, what: String, decode: (JsonElement) -> T): Listing<T> {
        val array = when (root) {
            JsonNull -> return Listing(emptyList())
            is JsonArray -> root
            else -> throw AdminApiException.Decode("$what list")
        }
        val items = ArrayList<T>(array.size)
        val issues = mutableListOf<DecodeIssue>()
        array.forEachIndexed { index, el ->
            try {
                items += decode(el)
            } catch (e: Exception) {
                val id = (el as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                issues += DecodeIssue(what, index, id, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(200))
            }
        }
        return Listing(items, issues)
    }
}

/** Where the API is, from a management URL. */
object AdminUrls {
    /**
     * The API's base: the management URL's scheme, host and port (kept: an
     * old self-hosted server serves management, and its API, on 33073) plus
     * `/api`. Null for a URL the panel will not send a credential to: https
     * only, plain http only to loopback, no user info.
     */
    fun apiBase(managementUrl: String): String? {
        val uri = runCatching { URI(managementUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        when (scheme) {
            "https" -> Unit
            "http" -> if (!isLoopback(host)) return null
            else -> return null
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "$scheme://${h.lowercase()}$port/api"
    }

    /**
     * The account's identity for the vault: scheme, host and port with the
     * scheme's default port written out, so `https://x` and `https://x:443`
     * are one server and a different server is another credential.
     */
    fun normalize(managementUrl: String): String {
        val uri = runCatching { URI(managementUrl.trim()) }.getOrNull() ?: return managementUrl.trim().lowercase()
        val scheme = uri.scheme?.lowercase() ?: "https"
        val host = uri.host?.lowercase() ?: return managementUrl.trim().lowercase()
        val port = if (uri.port > 0) uri.port else if (scheme == "http") 80 else 443
        return "$scheme://$host:$port"
    }

    /** NetBird Cloud, where the cloud-only API parts exist. */
    fun isCloud(managementUrl: String): Boolean =
        runCatching { URI(managementUrl.trim()).host.orEmpty().lowercase() }.getOrDefault("").let { it == "netbird.io" || it.endsWith(".netbird.io") }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"
}
