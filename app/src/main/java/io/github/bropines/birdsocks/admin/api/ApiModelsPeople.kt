package io.github.bropines.birdsocks.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* People and credentials: users, personal access tokens, invites, identity providers. See ApiModels.kt. */

// --- Users ---

/** POST /api/users: a service user ([isServiceUser], no e-mail) or an invited user. */
@Serializable
data class ApiUserCreateRequest(
    val email: String? = null,
    val name: String? = null,
    val role: String,
    @SerialName("auto_groups") val autoGroups: List<String>,
    @SerialName("is_service_user") val isServiceUser: Boolean,
)

/** PUT /api/users/{id}: all three required. */
@Serializable
data class ApiUserRequest(
    val role: String,
    @SerialName("auto_groups") val autoGroups: List<String>,
    @SerialName("is_blocked") val isBlocked: Boolean,
) {
    companion object {
        fun of(user: ApiUser) = ApiUserRequest(user.role, user.autoGroups, user.isBlocked)
    }
}

/** The roles management knows; a server may add more, so a role is kept as a string. */
object ApiRoles {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val USER = "user"
    const val BILLING_ADMIN = "billing_admin"
    const val AUDITOR = "auditor"
    const val NETWORK_ADMIN = "network_admin"
    val KNOWN = listOf(OWNER, ADMIN, NETWORK_ADMIN, USER, AUDITOR, BILLING_ADMIN)
}

// --- Personal access tokens ---

/**
 * GET /api/users/{id}/tokens. The secret is never in it: the server keeps a
 * hash and hands the plain token out once, in [ApiPersonalAccessTokenGenerated].
 */
@Serializable
data class ApiPersonalAccessToken(
    val id: String = "",
    val name: String = "",
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used") val lastUsed: String? = null,
)

/** POST /api/users/{id}/tokens: [expiresIn] in days, 1 to 365. */
@Serializable
data class ApiPersonalAccessTokenRequest(
    val name: String,
    @SerialName("expires_in") val expiresIn: Int,
) {
    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 365
    }
}

/** The create answer: [plainToken] once. Never logged: [toString] leaves it out. */
@Serializable
data class ApiPersonalAccessTokenGenerated(
    @SerialName("plain_token") val plainToken: String = "",
    @SerialName("personal_access_token") val personalAccessToken: ApiPersonalAccessToken = ApiPersonalAccessToken(),
) {
    override fun toString(): String = "ApiPersonalAccessTokenGenerated(id=${personalAccessToken.id})"
}

// --- Invites (link invites) ---

/** GET /api/users/invites. [inviteToken] is a bearer credential: it admits whoever holds it. */
@Serializable
data class ApiUserInvite(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "",
    @SerialName("auto_groups") val autoGroups: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val expired: Boolean = false,
    /** Only in the create answer. */
    @SerialName("invite_token") val inviteToken: String? = null,
) {
    override fun toString(): String = "ApiUserInvite(id=$id, role=$role)"
}

/** POST /api/users/invites: [expiresIn] in seconds. */
@Serializable
data class ApiUserInviteCreateRequest(
    val email: String,
    val name: String,
    val role: String,
    @SerialName("auto_groups") val autoGroups: List<String>,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
data class ApiUserInviteRegenerateRequest(@SerialName("expires_in") val expiresIn: Long? = null)

@Serializable
data class ApiUserInviteRegenerateResponse(
    @SerialName("invite_token") val inviteToken: String = "",
    @SerialName("invite_expires_at") val inviteExpiresAt: String? = null,
) {
    override fun toString(): String = "ApiUserInviteRegenerateResponse(…)"
}

// --- Identity providers ---

/** GET /api/identity-providers. [type] oidc, zitadel, entra, google, okta, pocketid, microsoft, adfs. */
@Serializable
data class ApiIdentityProvider(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val issuer: String = "",
    @SerialName("client_id") val clientId: String = "",
)

/** POST and PUT: [clientSecret] is a secret; [toString] leaves it out. */
@Serializable
data class ApiIdentityProviderRequest(
    val type: String,
    val name: String,
    val issuer: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
) {
    override fun toString(): String = "ApiIdentityProviderRequest(type=$type, name=$name)"
}
