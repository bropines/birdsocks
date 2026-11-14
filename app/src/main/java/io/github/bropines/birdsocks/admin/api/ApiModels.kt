package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * NetBird's management API objects, written by hand from the interface
 * description (shared/management/http/api/openapi.yml, v0.80.0) — the
 * generated Go types are not imported anywhere. Only what the panel shows or
 * sends back is declared; ApiJson ignores the rest. Everything has a default:
 * servers of other versions leave fields out, and a missing field must not
 * cost the whole object.
 */

/** GET /api/users/current; also one row of /api/users. */
@Serializable
data class ApiUser(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "",
    val status: String = "",
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("is_service_user") val isServiceUser: Boolean = false,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("auto_groups") val autoGroups: List<String> = emptyList(),
    @SerialName("last_login") val lastLogin: String? = null,
    /** Absent on servers older than the permissions model. */
    val permissions: ApiPermissions? = null,
)

/** Per module (peers, setup_keys, groups…) the operations allowed: read, create, update, delete. */
@Serializable
data class ApiPermissions(
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    val modules: Map<String, Map<String, Boolean>> = emptyMap(),
)

/** GET /api/accounts: one element for everyone but an MSP. */
@Serializable
data class ApiAccount(
    val id: String = "",
    val domain: String = "",
    @SerialName("domain_category") val domainCategory: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String = "",
    val settings: ApiAccountSettings = ApiAccountSettings(),
)

@Serializable
data class ApiAccountSettings(
    @SerialName("peer_login_expiration_enabled") val peerLoginExpirationEnabled: Boolean = false,
    /** Seconds. */
    @SerialName("peer_login_expiration") val peerLoginExpiration: Long = 0,
    @SerialName("dns_domain") val dnsDomain: String = "",
    @SerialName("network_range") val networkRange: String = "",
)

/** The group a peer belongs to, as a peer lists it. */
@Serializable
data class ApiGroupMinimum(
    val id: String = "",
    val name: String = "",
    @SerialName("peers_count") val peersCount: Int = 0,
    @SerialName("resources_count") val resourcesCount: Int = 0,
    val issued: String = "",
)

/** GET /api/peers (PeerBatch) and /api/peers/{id} (Peer). */
@Serializable
data class ApiPeer(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val ipv6: String = "",
    @SerialName("connection_ip") val connectionIp: String = "",
    val connected: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    val os: String = "",
    val version: String = "",
    val groups: List<ApiGroupMinimum> = emptyList(),
    @SerialName("ssh_enabled") val sshEnabled: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    val hostname: String = "",
    @SerialName("dns_label") val dnsLabel: String = "",
    @SerialName("login_expiration_enabled") val loginExpirationEnabled: Boolean = false,
    @SerialName("login_expired") val loginExpired: Boolean = false,
    @SerialName("last_login") val lastLogin: String? = null,
    @SerialName("inactivity_expiration_enabled") val inactivityExpirationEnabled: Boolean = false,
    /** Cloud only. */
    @SerialName("approval_required") val approvalRequired: Boolean = false,
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("city_name") val cityName: String = "",
    val ephemeral: Boolean = false,
    @SerialName("accessible_peers_count") val accessiblePeersCount: Int? = null,
)

/** PUT /api/peers/{id}: all four of the first fields are required, so they are sent as the peer has them. */
@Serializable
data class ApiPeerRequest(
    val name: String,
    @SerialName("ssh_enabled") val sshEnabled: Boolean,
    @SerialName("login_expiration_enabled") val loginExpirationEnabled: Boolean,
    @SerialName("inactivity_expiration_enabled") val inactivityExpirationEnabled: Boolean,
) {
    companion object {
        fun of(peer: ApiPeer) = ApiPeerRequest(peer.name, peer.sshEnabled, peer.loginExpirationEnabled, peer.inactivityExpirationEnabled)
    }
}

/** GET /api/setup-keys. The list masks [key]; only the create answer has it whole. */
@Serializable
data class ApiSetupKey(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val expires: String? = null,
    /** "one-off" or "reusable". */
    val type: String = "",
    val valid: Boolean = false,
    val revoked: Boolean = false,
    @SerialName("used_times") val usedTimes: Int = 0,
    @SerialName("last_used") val lastUsed: String? = null,
    /** "valid", "overused", "expired" or "revoked". */
    val state: String = "",
    @SerialName("auto_groups") val autoGroups: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
    /** 0 is unlimited. */
    @SerialName("usage_limit") val usageLimit: Int = 0,
    val ephemeral: Boolean = false,
    @SerialName("allow_extra_dns_labels") val allowExtraDnsLabels: Boolean = false,
) {
    val isReusable: Boolean get() = type == TYPE_REUSABLE

    /** Never the key, masked or not. */
    override fun toString(): String = "ApiSetupKey(id=$id, name=$name, state=$state)"

    companion object {
        const val TYPE_ONE_OFF = "one-off"
        const val TYPE_REUSABLE = "reusable"
    }
}

/** POST /api/setup-keys. [expiresIn] in seconds, 1 day to 1 year. */
@Serializable
data class ApiCreateSetupKey(
    val name: String,
    val type: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("auto_groups") val autoGroups: List<String> = emptyList(),
    @SerialName("usage_limit") val usageLimit: Int = 0,
    val ephemeral: Boolean = false,
) {
    companion object {
        const val MIN_EXPIRES_SEC = 86_400L
        const val MAX_EXPIRES_SEC = 31_536_000L
    }
}

/** PUT /api/setup-keys/{id}: both fields required. */
@Serializable
data class ApiSetupKeyUpdate(
    val revoked: Boolean,
    @SerialName("auto_groups") val autoGroups: List<String>,
)

@Serializable
data class ApiPeerMinimum(val id: String = "", val name: String = "")

/** GET /api/groups. */
@Serializable
data class ApiGroup(
    val id: String = "",
    val name: String = "",
    @SerialName("peers_count") val peersCount: Int = 0,
    @SerialName("resources_count") val resourcesCount: Int = 0,
    val issued: String = "",
    val peers: List<ApiPeerMinimum> = emptyList(),
)

/** What every error answer carries. */
@Serializable
internal data class ApiErrorBody(val message: String = "", val code: Int = 0)
