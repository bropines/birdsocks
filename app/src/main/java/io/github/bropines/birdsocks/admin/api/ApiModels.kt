package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * NetBird's management API objects, written by hand from the interface
 * description (shared/management/http/api/openapi.yml, v0.83) — the generated
 * Go types are not imported anywhere. AppJson ignores fields we do not
 * declare. Everything has a default: servers of other versions leave fields
 * out, and a missing field must not cost the whole object.
 *
 * Requests: a field the spec marks optional is nullable and null by default,
 * so it is left out of the body (AppJson has explicitNulls = false) and the
 * server keeps what it has. Required fields are sent as given.
 *
 * Files by area: this one (identity, account, peers, setup keys, groups),
 * ApiModelsAccess (policies, posture checks), ApiModelsRouting (networks,
 * routes, DNS), ApiModelsPeople (users, tokens, invites, identity providers),
 * ApiModelsMisc (events, event streaming, instance, jobs, reverse proxy).
 */

// --- Identity ---

/** GET /api/users/current; also one row of /api/users. */
@Serializable
data class ApiUser(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    /** owner, admin, user, billing_admin, auditor, network_admin… */
    val role: String = "",
    /** active, invited, blocked. */
    val status: String = "",
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("is_service_user") val isServiceUser: Boolean = false,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    /** Waiting for an admin's approval (account setting user_approval_required). */
    @SerialName("pending_approval") val pendingApproval: Boolean = false,
    @SerialName("auto_groups") val autoGroups: List<String> = emptyList(),
    @SerialName("last_login") val lastLogin: String? = null,
    /** api, integration or jwt. */
    val issued: String = "",
    @SerialName("idp_id") val idpId: String = "",
    /** Absent on servers older than the permissions model. */
    val permissions: ApiPermissions? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_INVITED = "invited"
        const val STATUS_BLOCKED = "blocked"
    }
}

/** Per module (peers, setup_keys, groups…) the operations allowed: read, create, update, delete. */
@Serializable
data class ApiPermissions(
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    val modules: Map<String, Map<String, Boolean>> = emptyMap(),
)

// --- Account ---

/** GET /api/accounts: one element for everyone but an MSP. */
@Serializable
data class ApiAccount(
    val id: String = "",
    val domain: String = "",
    @SerialName("domain_category") val domainCategory: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: String = "",
    val settings: ApiAccountSettings = ApiAccountSettings(),
    val onboarding: ApiAccountOnboarding? = null,
)

/**
 * The account's settings, every field the spec has, so a PUT sends back what
 * it read. A server newer than this model may have more: prefer
 * [NetbirdApi.patchAccountSettings], which merges into a fresh read.
 */
@Serializable
data class ApiAccountSettings(
    @SerialName("peer_login_expiration_enabled") val peerLoginExpirationEnabled: Boolean = false,
    /** Seconds. */
    @SerialName("peer_login_expiration") val peerLoginExpiration: Long = 0,
    @SerialName("peer_inactivity_expiration_enabled") val peerInactivityExpirationEnabled: Boolean = false,
    /** Seconds. */
    @SerialName("peer_inactivity_expiration") val peerInactivityExpiration: Long = 0,
    @SerialName("regular_users_view_blocked") val regularUsersViewBlocked: Boolean = false,
    @SerialName("groups_propagation_enabled") val groupsPropagationEnabled: Boolean? = null,
    @SerialName("jwt_groups_enabled") val jwtGroupsEnabled: Boolean? = null,
    @SerialName("jwt_groups_claim_name") val jwtGroupsClaimName: String? = null,
    @SerialName("jwt_allow_groups") val jwtAllowGroups: List<String>? = null,
    @SerialName("routing_peer_dns_resolution_enabled") val routingPeerDnsResolutionEnabled: Boolean? = null,
    @SerialName("dns_domain") val dnsDomain: String = "",
    @SerialName("network_range") val networkRange: String = "",
    @SerialName("network_range_v6") val networkRangeV6: String? = null,
    @SerialName("peer_expose_enabled") val peerExposeEnabled: Boolean = false,
    @SerialName("peer_expose_groups") val peerExposeGroups: List<String> = emptyList(),
    val extra: ApiAccountExtraSettings? = null,
    @SerialName("lazy_connection_enabled") val lazyConnectionEnabled: Boolean? = null,
    @SerialName("auto_update_version") val autoUpdateVersion: String? = null,
    @SerialName("auto_update_always") val autoUpdateAlways: Boolean? = null,
    @SerialName("metrics_push_enabled") val metricsPushEnabled: Boolean? = null,
    @SerialName("peer_hostname_collision_rejected") val peerHostnameCollisionRejected: Boolean? = null,
    @SerialName("embedded_idp_enabled") val embeddedIdpEnabled: Boolean? = null,
    @SerialName("local_auth_disabled") val localAuthDisabled: Boolean? = null,
    @SerialName("local_mfa_enabled") val localMfaEnabled: Boolean? = null,
    @SerialName("ipv6_enabled_groups") val ipv6EnabledGroups: List<String>? = null,
)

/** Cloud: peer and user approval, traffic logs. */
@Serializable
data class ApiAccountExtraSettings(
    @SerialName("peer_approval_enabled") val peerApprovalEnabled: Boolean = false,
    @SerialName("user_approval_required") val userApprovalRequired: Boolean = false,
    @SerialName("network_traffic_logs_enabled") val networkTrafficLogsEnabled: Boolean = false,
    @SerialName("network_traffic_logs_groups") val networkTrafficLogsGroups: List<String> = emptyList(),
    @SerialName("network_traffic_packet_counter_enabled") val networkTrafficPacketCounterEnabled: Boolean = false,
)

@Serializable
data class ApiAccountOnboarding(
    @SerialName("signup_form_pending") val signupFormPending: Boolean = false,
    @SerialName("onboarding_flow_pending") val onboardingFlowPending: Boolean = false,
)

/** PUT /api/accounts/{id}. */
@Serializable
data class ApiAccountRequest(
    val settings: ApiAccountSettings,
    val onboarding: ApiAccountOnboarding? = null,
)

// --- Peers ---

/** The group a peer belongs to, as a peer lists it. */
@Serializable
data class ApiGroupMinimum(
    val id: String = "",
    val name: String = "",
    @SerialName("peers_count") val peersCount: Int = 0,
    @SerialName("resources_count") val resourcesCount: Int = 0,
    /** api, integration or jwt. */
    val issued: String = "",
)

@Serializable
data class ApiNetworkAddress(
    @SerialName("net_ip") val netIp: String = "",
    val mac: String = "",
)

/** What the peer's own client was started with; reported, not settable here. */
@Serializable
data class ApiPeerLocalFlags(
    @SerialName("rosenpass_enabled") val rosenpassEnabled: Boolean = false,
    @SerialName("rosenpass_permissive") val rosenpassPermissive: Boolean = false,
    @SerialName("server_ssh_allowed") val serverSshAllowed: Boolean = false,
    @SerialName("remote_jobs_allowed") val remoteJobsAllowed: Boolean = false,
    @SerialName("disable_client_routes") val disableClientRoutes: Boolean = false,
    @SerialName("disable_server_routes") val disableServerRoutes: Boolean = false,
    @SerialName("disable_dns") val disableDns: Boolean = false,
    @SerialName("disable_firewall") val disableFirewall: Boolean = false,
    @SerialName("block_lan_access") val blockLanAccess: Boolean = false,
    @SerialName("block_inbound") val blockInbound: Boolean = false,
    @SerialName("lazy_connection_enabled") val lazyConnectionEnabled: Boolean = false,
)

/** GET /api/peers (PeerBatch) and /api/peers/{id} (Peer). */
@Serializable
data class ApiPeer(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val ipv6: String = "",
    @SerialName("network_addresses") val networkAddresses: List<ApiNetworkAddress> = emptyList(),
    @SerialName("connection_ip") val connectionIp: String = "",
    val connected: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    val os: String = "",
    @SerialName("kernel_version") val kernelVersion: String = "",
    @SerialName("geoname_id") val geonameId: Int = 0,
    val version: String = "",
    @SerialName("ui_version") val uiVersion: String = "",
    val groups: List<ApiGroupMinimum> = emptyList(),
    @SerialName("ssh_enabled") val sshEnabled: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    val hostname: String = "",
    @SerialName("dns_label") val dnsLabel: String = "",
    @SerialName("extra_dns_labels") val extraDnsLabels: List<String> = emptyList(),
    @SerialName("login_expiration_enabled") val loginExpirationEnabled: Boolean = false,
    @SerialName("login_expired") val loginExpired: Boolean = false,
    @SerialName("last_login") val lastLogin: String? = null,
    @SerialName("inactivity_expiration_enabled") val inactivityExpirationEnabled: Boolean = false,
    /** Cloud only: the peer waits for an admin. */
    @SerialName("approval_required") val approvalRequired: Boolean = false,
    @SerialName("disapproval_reason") val disapprovalReason: String = "",
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("city_name") val cityName: String = "",
    @SerialName("serial_number") val serialNumber: String = "",
    val ephemeral: Boolean = false,
    @SerialName("local_flags") val localFlags: ApiPeerLocalFlags? = null,
    /** The list only. */
    @SerialName("accessible_peers_count") val accessiblePeersCount: Int? = null,
)

/**
 * PUT /api/peers/{id}: the first four fields are required, so they are sent
 * as the peer has them ([of]); the rest are left out unless set.
 */
@Serializable
data class ApiPeerRequest(
    val name: String,
    @SerialName("ssh_enabled") val sshEnabled: Boolean,
    @SerialName("login_expiration_enabled") val loginExpirationEnabled: Boolean,
    @SerialName("inactivity_expiration_enabled") val inactivityExpirationEnabled: Boolean,
    /** Cloud only: false approves a waiting peer. */
    @SerialName("approval_required") val approvalRequired: Boolean? = null,
    val ip: String? = null,
    val ipv6: String? = null,
) {
    companion object {
        fun of(peer: ApiPeer) = ApiPeerRequest(peer.name, peer.sshEnabled, peer.loginExpirationEnabled, peer.inactivityExpirationEnabled)
    }
}

/** GET /api/peers/{id}/accessible-peers. */
@Serializable
data class ApiAccessiblePeer(
    val id: String = "",
    val name: String = "",
    val ip: String = "",
    val ipv6: String = "",
    @SerialName("dns_label") val dnsLabel: String = "",
    @SerialName("user_id") val userId: String = "",
    val os: String = "",
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("city_name") val cityName: String = "",
    @SerialName("geoname_id") val geonameId: Int = 0,
    val connected: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
)

/** POST /api/peers/{id}/temporary-access: [rules] like "tcp/22". */
@Serializable
data class ApiPeerTemporaryAccessRequest(
    val name: String,
    @SerialName("wg_pub_key") val wgPubKey: String,
    val rules: List<String>,
)

@Serializable
data class ApiPeerTemporaryAccessResponse(
    val name: String = "",
    val id: String = "",
    val rules: List<String> = emptyList(),
)

// --- Setup keys ---

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
    @SerialName("allow_extra_dns_labels") val allowExtraDnsLabels: Boolean? = null,
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

// --- Groups ---

@Serializable
data class ApiPeerMinimum(val id: String = "", val name: String = "")

/** A group member that is not a peer: a network resource ([type] host, subnet, domain) or a peer by id. */
@Serializable
data class ApiResource(
    val id: String = "",
    /** host, subnet, domain or peer. */
    val type: String = "",
)

/** GET /api/groups. */
@Serializable
data class ApiGroup(
    val id: String = "",
    val name: String = "",
    @SerialName("peers_count") val peersCount: Int = 0,
    @SerialName("resources_count") val resourcesCount: Int = 0,
    /** api, integration or jwt: only "api" groups are edited by hand. */
    val issued: String = "",
    val peers: List<ApiPeerMinimum> = emptyList(),
    val resources: List<ApiResource> = emptyList(),
) {
    /** The group every peer is in; it cannot be edited or deleted. */
    val isAll: Boolean get() = name == ALL

    companion object {
        const val ALL = "All"
    }
}

/** POST /api/groups, PUT /api/groups/{id}: [peers] are peer ids; a PUT replaces the members. */
@Serializable
data class ApiGroupRequest(
    val name: String,
    val peers: List<String>? = null,
    val resources: List<ApiResource>? = null,
) {
    companion object {
        /** The group as it is, ready for a PUT that changes one thing. */
        fun of(group: ApiGroup) = ApiGroupRequest(group.name, group.peers.map { it.id }, group.resources)
    }
}

/** What every error answer carries. */
@Serializable
internal data class ApiErrorBody(val message: String = "", val code: Int = 0)
