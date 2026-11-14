package io.github.bropines.birdsocks.admin.api

import io.github.bropines.birdsocks.core.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A list as far as it could be read: one element whose JSON changed shape
 * costs that element, not the list, and [issues] says which were dropped.
 */
data class Listing<T>(val items: List<T>, val issues: List<DecodeIssue> = emptyList()) {
    val isComplete: Boolean get() = issues.isEmpty()
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
 * NetBird's management HTTP API.
 *
 * - [baseUrl] is the account's management URL with its port, plus `/api`
 *   ([AdminUrls.apiBase]).
 * - Reads retry on 429 (honouring Retry-After), 502–504 and a dropped
 *   connection; writes go out once and are retried only on 429.
 * - Lists decode element by element into a [Listing].
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
    // --- Account and identity ---

    suspend fun currentUser(): ApiUser = decodeObject(exchange("GET", "/users/current"), "user")

    suspend fun accounts(): Listing<ApiAccount> = decodeList(exchange("GET", "/accounts"), "account")

    // --- Peers ---

    suspend fun peers(): Listing<ApiPeer> = decodeList(exchange("GET", "/peers"), "peer")

    suspend fun peer(id: String): ApiPeer = decodeObject(exchange("GET", "/peers/${Urls.seg(id)}"), "peer")

    suspend fun updatePeer(id: String, request: ApiPeerRequest): ApiPeer =
        decodeObject(exchange("PUT", "/peers/${Urls.seg(id)}", AppJson.encodeToString(ApiPeerRequest.serializer(), request)), "peer")

    suspend fun deletePeer(id: String) { exchange("DELETE", "/peers/${Urls.seg(id)}") }

    // --- Setup keys ---

    suspend fun setupKeys(): Listing<ApiSetupKey> = decodeList(exchange("GET", "/setup-keys"), "setup key")

    /** The answer carries the whole key, once; the server keeps only a hash. */
    suspend fun createSetupKey(request: ApiCreateSetupKey): ApiSetupKey =
        decodeObject(exchange("POST", "/setup-keys", AppJson.encodeToString(ApiCreateSetupKey.serializer(), request)), "setup key")

    suspend fun revokeSetupKey(key: ApiSetupKey): ApiSetupKey =
        decodeObject(
            exchange("PUT", "/setup-keys/${Urls.seg(key.id)}", AppJson.encodeToString(ApiSetupKeyUpdate.serializer(), ApiSetupKeyUpdate(true, key.autoGroups))),
            "setup key"
        )

    // --- Groups ---

    suspend fun groups(): Listing<ApiGroup> = decodeList(exchange("GET", "/groups"), "group")

    // --- The exchange ---

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

    private inline fun <reified T> decodeList(resp: HttpResponse, what: String): Listing<T> = try {
        ApiDecoding.list(AppJson.parseToJsonElement(resp.body.ifBlank { "[]" }), what) { AppJson.decodeFromJsonElement<T>(it) }
    } catch (e: AdminApiException) {
        throw e
    } catch (e: Exception) {
        throw AdminApiException.Decode("$what list", e)
    }

    companion object {
        private val RETRYABLE_5XX = setOf(502, 503, 504)

        /** The server's own `message` from an error body, trimmed; null when there is none. */
        internal fun apiMessage(body: String): String? =
            runCatching { AppJson.decodeFromString(ApiErrorBody.serializer(), body).message }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
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
