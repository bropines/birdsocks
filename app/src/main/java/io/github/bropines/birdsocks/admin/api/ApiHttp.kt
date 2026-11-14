package io.github.bropines.birdsocks.admin.api

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One HTTP request as the API client hands it to a transport. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    /** Never the headers: one of them is the credential. */
    override fun toString(): String = "$method ${url.substringBefore('?')}"
}

class HttpResponse(val status: Int, val body: String, headers: Map<String, String> = emptyMap()) {
    private val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]
}

/** The request got no HTTP answer at all. The message is credential-free by construction. */
open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request was not sent: the route to the server cannot be taken ([ProxyRoute.Unusable]). */
class ProxyUnusableException(val reason: ProxyRoute.Reason) : TransportException("proxy unusable: ${reason.name.lowercase()}")

/** Sends one request and waits for its answer. Blocking: call it off the main thread. */
fun interface HttpTransport {
    @Throws(TransportException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * The way to the management server: the control plane's own, as the daemon is
 * handed it in NB_BIRDSOCKS_CONTROL_PROXY — direct, a proxy of the user's
 * (`socks5h://` or `http://`), or ByeDPI's loopback listener.
 */
sealed interface ProxyRoute {
    data object Direct : ProxyRoute
    data class Socks(val host: String, val port: Int) : ProxyRoute
    data class Http(val host: String, val port: Int, val user: String = "", val pass: String = "") : ProxyRoute {
        override fun toString(): String = "Http($host:$port${if (user.isNotEmpty()) ", with a password" else ""})"
    }
    /** A route the app cannot take: never replaced by a direct connection. [reason] for the user. */
    data class Unusable(val reason: Reason) : ProxyRoute

    enum class Reason {
        /** A SOCKS5 proxy with a password: the JDK's SOCKS handshake sends it wrong. */
        SOCKS_AUTH,
        /** Not a proxy URL the app understands. */
        BAD_URL,
        /** ByeDPI is the chosen way and is not running (BirdSocks is stopped). */
        BYEDPI_DOWN,
    }

    companion object {
        /**
         * The route for [url], in the daemon's format; "" is direct. A URL that
         * cannot be followed is [Unusable], never direct: like the daemon, a bad
         * proxy fails the request rather than going around it.
         */
        fun parse(url: String): ProxyRoute {
            if (url.isBlank()) return Direct
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return Unusable(Reason.BAD_URL)
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return Unusable(Reason.BAD_URL)
            val info = uri.rawUserInfo.orEmpty()
            val user = decode(info.substringBefore(':'))
            val pass = decode(info.substringAfter(':', ""))
            return when (uri.scheme?.lowercase()) {
                "socks5", "socks5h" -> if (user.isNotEmpty()) Unusable(Reason.SOCKS_AUTH) else Socks(host, uri.port.takeIf { it > 0 } ?: 1080)
                "http" -> Http(host, uri.port.takeIf { it > 0 } ?: 8080, user, pass)
                else -> Unusable(Reason.BAD_URL)
            }
        }

        private fun decode(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/**
 * OkHttp over [route]. A SOCKS proxy gets the host name unresolved, so the
 * proxy resolves it (socks5h), as the daemon's dials do. No cookies, no cache,
 * no redirects followed: an API answer never needs them, and a redirect would
 * carry the credential somewhere the user did not name.
 */
class OkHttpTransport(route: ProxyRoute, timeoutSec: Long = 20) : HttpTransport {
    private val unusable = route as? ProxyRoute.Unusable
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply {
            when (route) {
                is ProxyRoute.Socks -> proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(route.host, route.port)))
                is ProxyRoute.Http -> {
                    proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(route.host, route.port)))
                    if (route.user.isNotEmpty()) {
                        val auth = Credentials.basic(route.user, route.pass)
                        proxyAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) null
                            else response.request.newBuilder().header("Proxy-Authorization", auth).build()
                        }
                    }
                }
                else -> proxy(Proxy.NO_PROXY)
            }
        }
        .build()

    override fun execute(request: HttpRequest): HttpResponse {
        unusable?.let { throw ProxyUnusableException(it.reason) }
        val body = request.body?.toRequestBody(JSON)
            ?: if (request.method in setOf("POST", "PUT", "PATCH")) "".toRequestBody(JSON) else null
        val okRequest = Request.Builder()
            .url(request.url)
            .method(request.method, body)
            .apply { request.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try {
            client.newCall(okRequest).execute().use { resp ->
                HttpResponse(resp.code, resp.body?.string().orEmpty(), resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() })
            }
        } catch (e: IOException) {
            // The exception's own text names hosts and causes, never headers.
            throw TransportException(e.javaClass.simpleName + (e.message?.let { ": ${it.take(160)}" } ?: ""), e)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

internal object Urls {
    /** One path segment, percent-encoded. */
    fun seg(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
