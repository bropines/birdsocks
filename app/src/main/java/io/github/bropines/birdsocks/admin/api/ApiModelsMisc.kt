package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* Events, event streaming, the instance, peer jobs and the reverse proxy. See ApiModels.kt. */

// --- Events ---

/** GET /api/events/audit: one entry of the account's audit log. [meta] differs per [activityCode]. */
@Serializable
data class ApiEvent(
    val id: String = "",
    val timestamp: String? = null,
    /** The server's own sentence, in English. */
    val activity: String = "",
    /** peer.rename, setupkey.add, policy.update… */
    @SerialName("activity_code") val activityCode: String = "",
    @SerialName("initiator_id") val initiatorId: String = "",
    @SerialName("initiator_name") val initiatorName: String = "",
    @SerialName("initiator_email") val initiatorEmail: String = "",
    @SerialName("target_id") val targetId: String = "",
    val meta: Map<String, String> = emptyMap(),
)

@Serializable
data class ApiTrafficLocation(
    @SerialName("city_name") val cityName: String = "",
    @SerialName("country_code") val countryCode: String = "",
)

@Serializable
data class ApiTrafficEndpoint(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    @SerialName("geo_location") val geoLocation: ApiTrafficLocation = ApiTrafficLocation(),
    val os: String = "",
    val address: String = "",
    @SerialName("dns_label") val dnsLabel: String = "",
)

@Serializable
data class ApiTrafficUser(val id: String = "", val email: String = "", val name: String = "")

@Serializable
data class ApiTrafficPolicy(val id: String = "", val name: String = "")

@Serializable
data class ApiTrafficIcmp(val type: Int = 0, val code: Int = 0)

@Serializable
data class ApiTrafficSubEvent(val type: String = "", val timestamp: String? = null)

/** One flow of GET /api/events/network-traffic (cloud, experimental). */
@Serializable
data class ApiNetworkTrafficEvent(
    @SerialName("flow_id") val flowId: String = "",
    @SerialName("reporter_id") val reporterId: String = "",
    val source: ApiTrafficEndpoint = ApiTrafficEndpoint(),
    val destination: ApiTrafficEndpoint = ApiTrafficEndpoint(),
    val user: ApiTrafficUser = ApiTrafficUser(),
    val policy: ApiTrafficPolicy = ApiTrafficPolicy(),
    val icmp: ApiTrafficIcmp = ApiTrafficIcmp(),
    /** IANA protocol number. */
    val protocol: Int = 0,
    val direction: String = "",
    @SerialName("rx_bytes") val rxBytes: Long = 0,
    @SerialName("rx_packets") val rxPackets: Long = 0,
    @SerialName("tx_bytes") val txBytes: Long = 0,
    @SerialName("tx_packets") val txPackets: Long = 0,
    @SerialName("num_of_starts") val numOfStarts: Int = 0,
    @SerialName("num_of_ends") val numOfEnds: Int = 0,
    @SerialName("num_of_drops") val numOfDrops: Int = 0,
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
    val events: List<ApiTrafficSubEvent> = emptyList(),
)

/** A page of traffic events; [data] is decoded element by element like any list. */
data class ApiTrafficPage(
    val data: Listing<ApiNetworkTrafficEvent>,
    val page: Int,
    val pageSize: Int,
    val totalRecords: Int,
    val totalPages: Int,
)

/** The filters GET /api/events/network-traffic takes; null leaves one out. Dates RFC 3339. */
data class TrafficQuery(
    val page: Int = 1,
    val pageSize: Int = 50,
    val userId: String? = null,
    val reporterId: String? = null,
    val sourceId: String? = null,
    val destinationId: String? = null,
    val protocol: Int? = null,
    val type: String? = null,
    val connectionType: String? = null,
    val direction: String? = null,
    val search: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
) {
    internal fun params(): List<Pair<String, String>> = listOfNotNull(
        "page" to page.toString(),
        "page_size" to pageSize.toString(),
        userId?.let { "user_id" to it },
        reporterId?.let { "reporter_id" to it },
        sourceId?.let { "source_id" to it },
        destinationId?.let { "destination_id" to it },
        protocol?.let { "protocol" to it.toString() },
        type?.let { "type" to it },
        connectionType?.let { "connection_type" to it },
        direction?.let { "direction" to it },
        search?.let { "search" to it },
        startDate?.let { "start_date" to it },
        endDate?.let { "end_date" to it },
    )
}

// --- Event streaming ---

/**
 * GET /api/event-streaming (cloud). [config] holds the destination's
 * credentials (API keys, secret URLs): never cached, never logged.
 */
@Serializable
data class ApiIntegration(
    val id: Long = 0,
    @SerialName("account_id") val accountId: String = "",
    val enabled: Boolean = false,
    /** datadog, s3, firehose or generic_http. */
    val platform: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val config: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ApiIntegration(id=$id, platform=$platform)"
}

@Serializable
data class ApiIntegrationRequest(
    val platform: String,
    val config: Map<String, String>,
    val enabled: Boolean,
) {
    override fun toString(): String = "ApiIntegrationRequest(platform=$platform)"
}

// --- Instance ---

/** GET /api/instance, unauthenticated: whether a self-hosted server still waits for its first user. */
@Serializable
data class ApiInstanceStatus(@SerialName("setup_required") val setupRequired: Boolean = false)

/** GET /api/instance/version. */
@Serializable
data class ApiInstanceVersion(
    @SerialName("management_current_version") val managementCurrentVersion: String = "",
    @SerialName("dashboard_available_version") val dashboardAvailableVersion: String? = null,
    @SerialName("management_available_version") val managementAvailableVersion: String? = null,
    @SerialName("management_update_available") val managementUpdateAvailable: Boolean = false,
)

// --- Peer jobs (experimental) ---

/** A debug bundle's parameters; the only workload the server knows today. */
@Serializable
data class ApiBundleParameters(
    @SerialName("bundle_for") val bundleFor: Boolean = false,
    @SerialName("bundle_for_time") val bundleForTime: Int = 0,
    @SerialName("log_file_count") val logFileCount: Int = 0,
    val anonymize: Boolean = true,
    @SerialName("anonymize_level") val anonymizeLevel: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
)

@Serializable
data class ApiWorkloadResult(@SerialName("upload_key") val uploadKey: String? = null)

/** A job's workload: [type] "bundle". */
@Serializable
data class ApiWorkload(
    val type: String = TYPE_BUNDLE,
    val parameters: ApiBundleParameters = ApiBundleParameters(),
    val result: ApiWorkloadResult? = null,
) {
    companion object {
        const val TYPE_BUNDLE = "bundle"
    }
}

@Serializable
data class ApiJobRequest(val workload: ApiWorkload)

/** GET /api/peers/{id}/jobs. [status] pending, succeeded or failed. */
@Serializable
data class ApiJob(
    val id: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("triggered_by") val triggeredBy: String = "",
    val status: String = "",
    @SerialName("failed_reason") val failedReason: String? = null,
    val workload: ApiWorkload = ApiWorkload(),
)

// --- Reverse proxy ---

@Serializable
data class ApiServiceTargetOptions(
    @SerialName("skip_tls_verify") val skipTlsVerify: Boolean? = null,
    @SerialName("request_timeout") val requestTimeout: String? = null,
    @SerialName("path_rewrite") val pathRewrite: String? = null,
    @SerialName("custom_headers") val customHeaders: Map<String, String>? = null,
    @SerialName("proxy_protocol") val proxyProtocol: Boolean? = null,
    @SerialName("session_idle_timeout") val sessionIdleTimeout: String? = null,
    @SerialName("direct_upstream") val directUpstream: Boolean? = null,
)

/** Where a service sends its traffic. [targetType] peer, host, domain, subnet or cluster. */
@Serializable
data class ApiServiceTarget(
    @SerialName("target_id") val targetId: String = "",
    @SerialName("target_type") val targetType: String = "",
    val path: String? = null,
    /** inherit, bypass or block. */
    @SerialName("access_action") val accessAction: String? = null,
    /** http, https, tcp or udp. */
    val protocol: String = "http",
    val host: String? = null,
    val port: Int = 0,
    val enabled: Boolean = true,
    val options: ApiServiceTargetOptions? = null,
)

@Serializable
data class ApiPasswordAuth(val enabled: Boolean = false, val password: String = "") {
    override fun toString(): String = "ApiPasswordAuth(enabled=$enabled)"
}

@Serializable
data class ApiPinAuth(val enabled: Boolean = false, val pin: String = "") {
    override fun toString(): String = "ApiPinAuth(enabled=$enabled)"
}

@Serializable
data class ApiBearerAuth(
    val enabled: Boolean = false,
    @SerialName("distribution_groups") val distributionGroups: List<String>? = null,
)

@Serializable
data class ApiLinkAuth(val enabled: Boolean = false)

@Serializable
data class ApiHeaderAuth(val enabled: Boolean = false, val header: String = "", val value: String = "") {
    override fun toString(): String = "ApiHeaderAuth(enabled=$enabled, header=$header)"
}

/** How visitors prove who they are; passwords, PINs and header values are secrets. */
@Serializable
data class ApiServiceAuth(
    @SerialName("password_auth") val passwordAuth: ApiPasswordAuth? = null,
    @SerialName("pin_auth") val pinAuth: ApiPinAuth? = null,
    @SerialName("bearer_auth") val bearerAuth: ApiBearerAuth? = null,
    @SerialName("link_auth") val linkAuth: ApiLinkAuth? = null,
    @SerialName("header_auths") val headerAuths: List<ApiHeaderAuth>? = null,
)

@Serializable
data class ApiAccessRestrictions(
    @SerialName("allowed_cidrs") val allowedCidrs: List<String>? = null,
    @SerialName("blocked_cidrs") val blockedCidrs: List<String>? = null,
    @SerialName("allowed_countries") val allowedCountries: List<String>? = null,
    @SerialName("blocked_countries") val blockedCountries: List<String>? = null,
    /** off, enforce or observe. */
    @SerialName("crowdsec_mode") val crowdsecMode: String? = null,
)

@Serializable
data class ApiServiceMeta(
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("certificate_issued_at") val certificateIssuedAt: String? = null,
    /** pending, active, tunnel_not_created, certificate_pending, certificate_failed, error. */
    val status: String = "",
)

/** GET /api/reverse-proxies/services: a name on the internet in front of [targets]. */
@Serializable
data class ApiService(
    val id: String = "",
    val name: String = "",
    val domain: String = "",
    /** http, tcp, udp or tls. */
    val mode: String? = null,
    @SerialName("listen_port") val listenPort: Int? = null,
    @SerialName("port_auto_assigned") val portAutoAssigned: Boolean? = null,
    @SerialName("proxy_cluster") val proxyCluster: String? = null,
    val targets: List<ApiServiceTarget> = emptyList(),
    val enabled: Boolean = false,
    val terminated: Boolean? = null,
    @SerialName("pass_host_header") val passHostHeader: Boolean? = null,
    @SerialName("rewrite_redirects") val rewriteRedirects: Boolean? = null,
    val auth: ApiServiceAuth = ApiServiceAuth(),
    @SerialName("access_restrictions") val accessRestrictions: ApiAccessRestrictions? = null,
    val meta: ApiServiceMeta = ApiServiceMeta(),
    @SerialName("private") val isPrivate: Boolean? = null,
    @SerialName("access_groups") val accessGroups: List<String>? = null,
)

@Serializable
data class ApiServiceRequest(
    val name: String,
    val domain: String,
    val mode: String? = null,
    @SerialName("listen_port") val listenPort: Int? = null,
    val targets: List<ApiServiceTarget>? = null,
    val enabled: Boolean,
    @SerialName("pass_host_header") val passHostHeader: Boolean? = null,
    @SerialName("rewrite_redirects") val rewriteRedirects: Boolean? = null,
    val auth: ApiServiceAuth? = null,
    @SerialName("access_restrictions") val accessRestrictions: ApiAccessRestrictions? = null,
    @SerialName("private") val isPrivate: Boolean? = null,
    @SerialName("access_groups") val accessGroups: List<String>? = null,
) {
    companion object {
        fun of(s: ApiService) = ApiServiceRequest(
            s.name, s.domain, s.mode, s.listenPort, s.targets, s.enabled, s.passHostHeader, s.rewriteRedirects,
            s.auth, s.accessRestrictions, s.isPrivate, s.accessGroups,
        )
    }
}

/** GET /api/reverse-proxies/domains. [type] free or custom. */
@Serializable
data class ApiReverseProxyDomain(
    val id: String = "",
    val domain: String = "",
    val validated: Boolean = false,
    val type: String = "",
    @SerialName("target_cluster") val targetCluster: String? = null,
    @SerialName("supports_custom_ports") val supportsCustomPorts: Boolean? = null,
    @SerialName("require_subdomain") val requireSubdomain: Boolean? = null,
    @SerialName("supports_crowdsec") val supportsCrowdsec: Boolean? = null,
    @SerialName("supports_private") val supportsPrivate: Boolean? = null,
)

@Serializable
data class ApiReverseProxyDomainRequest(
    val domain: String,
    @SerialName("target_cluster") val targetCluster: String,
)
