package io.github.bropines.birdsocks.admin

import android.content.Context
import android.content.Intent
import io.github.bropines.birdsocks.admin.areas.AdminAreas

/**
 * The way into the admin panel from anywhere in the app: Settings → Account,
 * the `birdsocks://admin` link, a button on the main screen. The panel decides
 * for itself what to show — the token setup when the active account has no
 * credential yet, the panel otherwise.
 */
object AdminPanel {
    /** The home screen. */
    const val PAGE_OVERVIEW = "overview"
    const val PAGE_PEERS = "peers"
    /** Setup keys; "setup_keys" opens them too. */
    const val PAGE_KEYS = "keys"
    const val PAGE_GROUPS = "groups"
    /** The credential screen, even with a token stored. */
    const val PAGE_SETUP = "setup"

    internal const val EXTRA_PAGE = "admin_page"

    /** Whether [page] opens something: the home, the credential screen, or an area's id or alias ([AdminAreas]). */
    internal fun isPage(page: String): Boolean = page == PAGE_OVERVIEW || page == PAGE_SETUP || AdminAreas.forPage(page) != null

    fun intent(context: Context, page: String? = null): Intent =
        Intent(context, AdminActivity::class.java).apply {
            page?.trim()?.lowercase()?.takeIf { isPage(it) }?.let { putExtra(EXTRA_PAGE, it) }
        }

    /** Opens the panel, on [page] when given: an area id ("policies"), "overview" or "setup". */
    fun open(context: Context, page: String? = null) {
        val i = intent(context, page)
        if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /** Whether [profile] on [managementUrl] has a stored token, for a row's subtitle. Reads no secret. */
    fun hasCredential(context: Context, profile: String, managementUrl: String): Boolean {
        val vault = AdminSession.vault(context)
        val id = vault.accountId(profile, managementUrl)
        return vault.hasToken(id) || AdminSession.jwt(id) != null
    }
}
