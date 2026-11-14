package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* Access control: policies and posture checks. See ApiModels.kt for the conventions. */

// --- Policies ---

@Serializable
data class ApiPortRange(val start: Int = 0, val end: Int = 0)

/**
 * One rule of a policy as GET answers it: sources and destinations as groups,
 * or a single resource instead ([sourceResource], [destinationResource]).
 */
@Serializable
data class ApiPolicyRule(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    /** accept or drop. */
    val action: String = ACTION_ACCEPT,
    val bidirectional: Boolean = false,
    /** all, tcp, udp, icmp or netbird-ssh. */
    val protocol: String = PROTOCOL_ALL,
    val ports: List<String> = emptyList(),
    @SerialName("port_ranges") val portRanges: List<ApiPortRange> = emptyList(),
    /** netbird-ssh: per group, the local users it may log in as. */
    @SerialName("authorized_groups") val authorizedGroups: Map<String, List<String>> = emptyMap(),
    val sources: List<ApiGroupMinimum> = emptyList(),
    val sourceResource: ApiResource? = null,
    val destinations: List<ApiGroupMinimum> = emptyList(),
    val destinationResource: ApiResource? = null,
) {
    companion object {
        const val ACTION_ACCEPT = "accept"
        const val ACTION_DROP = "drop"
        const val PROTOCOL_ALL = "all"
        const val PROTOCOL_TCP = "tcp"
        const val PROTOCOL_UDP = "udp"
        const val PROTOCOL_ICMP = "icmp"
        const val PROTOCOL_SSH = "netbird-ssh"
    }
}

/** GET /api/policies. */
@Serializable
data class ApiPolicy(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    @SerialName("source_posture_checks") val sourcePostureChecks: List<String> = emptyList(),
    val rules: List<ApiPolicyRule> = emptyList(),
)

/** A rule as POST and PUT take it: groups by id. [id] keeps an existing rule. */
@Serializable
data class ApiPolicyRuleRequest(
    val id: String? = null,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    val action: String,
    val bidirectional: Boolean,
    val protocol: String,
    val ports: List<String>? = null,
    @SerialName("port_ranges") val portRanges: List<ApiPortRange>? = null,
    @SerialName("authorized_groups") val authorizedGroups: Map<String, List<String>>? = null,
    val sources: List<String>? = null,
    val sourceResource: ApiResource? = null,
    val destinations: List<String>? = null,
    val destinationResource: ApiResource? = null,
) {
    companion object {
        fun of(rule: ApiPolicyRule) = ApiPolicyRuleRequest(
            id = rule.id.ifEmpty { null },
            name = rule.name,
            description = rule.description,
            enabled = rule.enabled,
            action = rule.action,
            bidirectional = rule.bidirectional,
            protocol = rule.protocol,
            ports = rule.ports.takeIf { it.isNotEmpty() },
            portRanges = rule.portRanges.takeIf { it.isNotEmpty() },
            authorizedGroups = rule.authorizedGroups.takeIf { it.isNotEmpty() },
            sources = rule.sources.map { it.id }.takeIf { rule.sourceResource == null },
            sourceResource = rule.sourceResource,
            destinations = rule.destinations.map { it.id }.takeIf { rule.destinationResource == null },
            destinationResource = rule.destinationResource,
        )
    }
}

/** POST /api/policies, PUT /api/policies/{id}. */
@Serializable
data class ApiPolicyRequest(
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    @SerialName("source_posture_checks") val sourcePostureChecks: List<String>? = null,
    val rules: List<ApiPolicyRuleRequest>,
) {
    companion object {
        /** The policy as it is, ready for a PUT that changes one thing. */
        fun of(policy: ApiPolicy) = ApiPolicyRequest(
            name = policy.name,
            description = policy.description,
            enabled = policy.enabled,
            sourcePostureChecks = policy.sourcePostureChecks,
            rules = policy.rules.map(ApiPolicyRuleRequest::of),
        )
    }
}

// --- Posture checks ---

@Serializable
data class ApiMinVersionCheck(@SerialName("min_version") val minVersion: String = "")

@Serializable
data class ApiMinKernelVersionCheck(@SerialName("min_kernel_version") val minKernelVersion: String = "")

@Serializable
data class ApiOsVersionCheck(
    val android: ApiMinVersionCheck? = null,
    val darwin: ApiMinVersionCheck? = null,
    val ios: ApiMinVersionCheck? = null,
    val linux: ApiMinKernelVersionCheck? = null,
    val windows: ApiMinKernelVersionCheck? = null,
)

@Serializable
data class ApiLocation(
    /** ISO 3166-1 alpha-2. */
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("city_name") val cityName: String? = null,
)

@Serializable
data class ApiGeoLocationCheck(
    val locations: List<ApiLocation> = emptyList(),
    /** allow or deny. */
    val action: String = "allow",
)

@Serializable
data class ApiPeerNetworkRangeCheck(
    val ranges: List<String> = emptyList(),
    /** allow or deny. */
    val action: String = "allow",
)

@Serializable
data class ApiProcess(
    @SerialName("linux_path") val linuxPath: String? = null,
    @SerialName("mac_path") val macPath: String? = null,
    @SerialName("windows_path") val windowsPath: String? = null,
)

@Serializable
data class ApiProcessCheck(val processes: List<ApiProcess> = emptyList())

@Serializable
data class ApiCertificateCheck(@SerialName("ca_certificates") val caCertificates: List<String> = emptyList())

/** The checks a posture check holds; each one is optional. */
@Serializable
data class ApiChecks(
    @SerialName("nb_version_check") val nbVersionCheck: ApiMinVersionCheck? = null,
    @SerialName("os_version_check") val osVersionCheck: ApiOsVersionCheck? = null,
    @SerialName("geo_location_check") val geoLocationCheck: ApiGeoLocationCheck? = null,
    @SerialName("peer_network_range_check") val peerNetworkRangeCheck: ApiPeerNetworkRangeCheck? = null,
    @SerialName("process_check") val processCheck: ApiProcessCheck? = null,
    @SerialName("certificate_check") val certificateCheck: ApiCertificateCheck? = null,
)

/** GET /api/posture-checks. */
@Serializable
data class ApiPostureCheck(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val checks: ApiChecks = ApiChecks(),
)

/** POST /api/posture-checks, PUT /api/posture-checks/{id}. */
@Serializable
data class ApiPostureCheckRequest(
    val name: String,
    val description: String = "",
    val checks: ApiChecks? = null,
) {
    companion object {
        fun of(check: ApiPostureCheck) = ApiPostureCheckRequest(check.name, check.description, check.checks)
    }
}

/** GET /api/locations/countries/{country}/cities: one element. */
@Serializable
data class ApiCity(
    @SerialName("geoname_id") val geonameId: Int = 0,
    @SerialName("city_name") val cityName: String = "",
)
