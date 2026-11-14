package io.github.bropines.birdsocks.admin.areas.idp

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiIdentityProvider
import io.github.bropines.birdsocks.admin.api.ApiIdentityProviderRequest
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys
import java.net.URI

/**
 * Identity providers (embedded IdP): sign-in through another service. A
 * client secret goes out with a create or an edit and is never shown again;
 * the review says it is replaced, not what it is.
 */
object IdentityProviderChanges {
    val CREATE = ChangeKind("idp.create", Capabilities.IDENTITY_PROVIDERS, Op.CREATE, ChangeClass.MEDIUM)
    val UPDATE = ChangeKind("idp.update", Capabilities.IDENTITY_PROVIDERS, Op.UPDATE, ChangeClass.HIGH)
    val DELETE = ChangeKind("idp.delete", Capabilities.IDENTITY_PROVIDERS, Op.DELETE, ChangeClass.HIGH)

    val TYPES = listOf("oidc", "zitadel", "entra", "google", "okta", "pocketid", "microsoft", "adfs")

    fun target(p: ApiIdentityProvider) = ChangeTarget(TargetType.IDENTITY_PROVIDER, p.id, p.name.ifEmpty { p.id })

    /** An https issuer URL with a host (plain http only to loopback, for a test server). */
    fun issuerOk(issuer: String): Boolean {
        val u = runCatching { URI(issuer.trim()) }.getOrNull() ?: return false
        val host = u.host?.takeIf { it.isNotEmpty() } ?: return false
        return when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> host == "localhost" || host.startsWith("127.")
            else -> false
        }
    }

    fun requestOk(r: ApiIdentityProviderRequest): Boolean =
        r.type in TYPES && r.name.isNotBlank() && issuerOk(r.issuer) && r.clientId.isNotBlank() && r.clientSecret.isNotEmpty()

    fun create(ctx: Context, r: ApiIdentityProviderRequest): PlannedChange {
        require(requestOk(r))
        return PlannedChange(
            change = AdminChange(
                CREATE, ChangeTarget(TargetType.IDENTITY_PROVIDER, "", r.name),
                title = ctx.getString(R.string.admin_idp_create_title, r.name),
                effect = ctx.getString(R.string.admin_idp_create_effect, r.type),
                diff = listOf(
                    DiffLine(ctx.getString(R.string.admin_idp_type), null, r.type),
                    DiffLine(ctx.getString(R.string.admin_idp_name), null, r.name),
                    DiffLine(ctx.getString(R.string.admin_idp_issuer), null, r.issuer),
                    DiffLine(ctx.getString(R.string.admin_idp_client_id), null, r.clientId),
                    DiffLine(ctx.getString(R.string.admin_idp_client_secret), null, SECRET),
                ),
            ),
            apply = { it.createIdentityProvider(r) },
            refreshes = listOf(AdminKeys.IDENTITY_PROVIDERS),
        )
    }

    /** A changed provider can stop everyone who signs in through it: HIGH, the name typed back. */
    fun update(ctx: Context, p: ApiIdentityProvider, r: ApiIdentityProviderRequest): PlannedChange {
        require(requestOk(r))
        return PlannedChange(
            change = AdminChange(
                UPDATE, target(p),
                title = ctx.getString(R.string.admin_idp_update_title, target(p).name),
                effect = ctx.getString(R.string.admin_idp_update_effect),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_idp_type), p.type, r.type).takeIf { p.type != r.type },
                    DiffLine(ctx.getString(R.string.admin_idp_name), p.name, r.name).takeIf { p.name != r.name },
                    DiffLine(ctx.getString(R.string.admin_idp_issuer), p.issuer, r.issuer).takeIf { p.issuer != r.issuer },
                    DiffLine(ctx.getString(R.string.admin_idp_client_id), p.clientId, r.clientId).takeIf { p.clientId != r.clientId },
                    DiffLine(ctx.getString(R.string.admin_idp_client_secret), SECRET, ctx.getString(R.string.admin_idp_secret_replaced)),
                ),
                warnings = listOf(ctx.getString(R.string.admin_idp_signin_warning)),
            ),
            apply = { it.updateIdentityProvider(p.id, r) },
            verify = { api -> api.identityProvider(p.id).let { it.name == r.name && it.issuer == r.issuer && it.clientId == r.clientId } },
            refreshes = listOf(AdminKeys.IDENTITY_PROVIDERS),
        )
    }

    fun delete(ctx: Context, p: ApiIdentityProvider): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(p),
            title = ctx.getString(R.string.admin_idp_delete_title, target(p).name),
            effect = ctx.getString(R.string.admin_idp_delete_effect),
            warnings = listOf(ctx.getString(R.string.admin_idp_signin_warning)),
        ),
        apply = { it.deleteIdentityProvider(p.id) },
        verify = { api -> api.identityProviders().items.none { it.id == p.id } },
        refreshes = listOf(AdminKeys.IDENTITY_PROVIDERS),
        done = ctx.getString(R.string.admin_done_deleted, target(p).name),
    )

    private const val SECRET = "••••••"
}
