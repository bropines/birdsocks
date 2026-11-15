package io.github.bropines.birdsocks.admin.api

/**
 * Everything a management API call can end in besides success, typed so the
 * panel can say what happened in its own words instead of printing a body.
 * No subclass ever carries a credential: messages are built from the status,
 * the server's own `message` and our text.
 */
sealed class AdminApiException(
    message: String,
    val status: Int,
    /** The server's `message`, when it sent one; trimmed to 300 characters. */
    val apiMessage: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 401: the credential is expired, revoked or wrong. */
    class Unauthorized(apiMessage: String?) : AdminApiException("401 unauthorized", 401, apiMessage)

    /** 403: the credential's role may not do this. */
    class Forbidden(apiMessage: String?) : AdminApiException("403 forbidden", 403, apiMessage)

    /** 404: gone, or a feature this server does not have. */
    class NotFound(apiMessage: String?) : AdminApiException("404 not found", 404, apiMessage)

    /** 409: in the way of something else. */
    class Conflict(apiMessage: String?) : AdminApiException("409 conflict", 409, apiMessage)

    /** 400 or 422: the server refused the request's content. */
    class BadRequest(status: Int, apiMessage: String?) : AdminApiException("$status bad request", status, apiMessage)

    /** 429 after the retries ran out; [retryAfterSec] as the server last asked. */
    class RateLimited(apiMessage: String?, val retryAfterSec: Long?) : AdminApiException("429 rate limited", 429, apiMessage)

    /** 5xx after the retries a read gets (a write gets none). */
    class Server(status: Int, apiMessage: String?) : AdminApiException("$status server error", status, apiMessage)

    /** Any other status, a 3xx redirect among them. */
    class Unexpected(status: Int, apiMessage: String?) : AdminApiException("HTTP $status", status, apiMessage)

    /** No answer at all: proxy, DNS, TLS, timeout. [detail] is already credential-free. */
    class Network(val detail: String, cause: Throwable? = null) : AdminApiException("network: $detail", 0, null, cause)

    /** The answer came but could not be read as [what]. */
    class Decode(val what: String, cause: Throwable? = null) : AdminApiException("cannot read $what", 200, null, cause)

    /** Whether the same request may succeed later without anything changing here. */
    val isTransient: Boolean get() = this is Network || this is RateLimited || this is Server
}
