package io.github.bropines.birdsocks.admin

import android.content.Context
import io.github.bropines.birdsocks.admin.api.AdminUrls
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.api.OkHttpTransport
import io.github.bropines.birdsocks.admin.api.ProxyRoute
import io.github.bropines.birdsocks.admin.secure.CredentialVault
import io.github.bropines.birdsocks.admin.secure.KeystoreSecretBox
import io.github.bropines.birdsocks.admin.secure.PrefsKeyValueStore
import io.github.bropines.birdsocks.core.AppJson
import io.github.bropines.birdsocks.core.ByeDpiProxy
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** The active NetBird account, as the panel addresses it. */
data class AdminAccount(
    val profile: String,
    val managementUrl: String,
    /** The vault's key for this profile on this server. */
    val id: String,
    /** Null when the panel will not send a credential there (plain http to a remote host). */
    val apiBase: String?,
    /** NetBird's web dashboard for this server, for what the API does not offer; null when unknown. */
    val dashboardUrl: String? = null,
) {
    val host: String get() = runCatching { java.net.URI(managementUrl).host }.getOrNull() ?: managementUrl
    val isCloud: Boolean get() = AdminUrls.isCloud(managementUrl)
}

/**
 * What the panel needs from the rest of the app: the vault, the active
 * account, the way to the server and the in-memory sign-in token.
 */
object AdminSession {
    @Volatile private var vaultInstance: CredentialVault? = null

    fun vault(context: Context): CredentialVault = vaultInstance ?: synchronized(this) {
        vaultInstance ?: CredentialVault(
            PrefsKeyValueStore(context.applicationContext.getSharedPreferences(CredentialVault.PREFS_NAME, Context.MODE_PRIVATE)),
            KeystoreSecretBox()
        ).also { vaultInstance = it }
    }

    /** The active profile and its server; needs the daemon running. */
    suspend fun activeAccount(context: Context): AdminAccount {
        val profile = Netbird.activeProfile()
        val config = Netbird.config(profile)
        val url = config.managementUrl.ifEmpty { GlobalSettings.CLOUD_MANAGEMENT_URL }
        val dashboard = runCatching { config.dashboardUrl }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return AdminAccount(profile, url, vault(context).accountId(profile, url), AdminUrls.apiBase(url), dashboard)
    }

    /**
     * The control plane's way to the server, the one the daemon is handed as
     * NB_BIRDSOCKS_CONTROL_PROXY (NetbirdService.controlProxyUrl): the user's
     * proxy, ByeDPI's listener while it runs, or direct.
     */
    fun controlRoute(context: Context): ProxyRoute = when (GlobalSettings.getControlMode(context)) {
        GlobalSettings.CONTROL_PROXY -> ProxyRoute.parse(GlobalSettings.getControlProxyUrl(context))
        GlobalSettings.CONTROL_BYEDPI -> ByeDpiProxy.activeAddress?.let { ProxyRoute.Socks(it.first, it.second) }
            ?: ProxyRoute.Unusable(ProxyRoute.Reason.BYEDPI_DOWN)
        else -> ProxyRoute.Direct
    }

    fun api(context: Context, account: AdminAccount, auth: ApiAuth): NetbirdApi? =
        account.apiBase?.let { NetbirdApi(it, auth, OkHttpTransport(controlRoute(context))) }

    // --- This device's sign-in (experimental) ---

    /**
     * Tokens from the device's own sign-in, per account, in memory only: never
     * written anywhere, gone with the process, dropped on the first 401.
     */
    private val jwts = ConcurrentHashMap<String, String>()

    fun jwt(accountId: String): String? = jwts[accountId]
    fun setJwt(accountId: String, token: String?) {
        if (token.isNullOrBlank()) jwts.remove(accountId) else jwts[accountId] = token
    }

    @Serializable
    internal data class JwtRequest(
        val verificationURI: String = "",
        val verificationURIComplete: String = "",
        val userCode: String = "",
        val deviceCode: String = "",
        val cachedToken: String = "",
    )

    @Serializable
    internal data class JwtToken(val token: String = "")

    /**
     * The daemon's RequestJWTAuth — the flow NetBird made for SSH, against the
     * account's identity provider. A cached token comes back at once; otherwise
     * [openBrowser] gets the page and this waits (WaitJWTToken) until the user
     * has finished there. Whether the management API accepts the token depends
     * on the server's identity provider setup: unverified, hence experimental.
     */
    suspend fun deviceSignIn(openBrowser: (String) -> Unit): String {
        val request = AppJson.decodeFromString<JwtRequest>(
            Netbird.call("RequestJWTAuth", buildJsonObject { put("hasGraphicalSession", true) }.toString(), 60_000)
        )
        if (request.cachedToken.isNotBlank()) return request.cachedToken
        openBrowser(request.verificationURIComplete.ifEmpty { request.verificationURI })
        val answer = AppJson.decodeFromString<JwtToken>(
            Netbird.call("WaitJWTToken", buildJsonObject {
                put("deviceCode", request.deviceCode)
                put("userCode", request.userCode)
            }.toString(), 20 * 60_000)
        )
        return answer.token.ifBlank { throw IllegalStateException("no token in the answer") }
    }
}
