package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent

/**
 * The Settings hub's sections, by the ids other screens and links open them
 * with (SettingsActivity.EXTRA_OPEN_SECTION, birdsocks://settings?section=).
 */
object SettingsSections {
    const val APPEARANCE = "appearance"
    const val ACCOUNT = "account"
    const val TUNNEL = "tunnel"
    const val PROXIES = "proxies"
    const val DNS = "dns"
    const val ACCESS = "access"
    const val CONNECTION = "connection"
    const val BACKGROUND = "background"
    const val BACKUP = "backup"
    const val AUTOMATION = "automation"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"

    val ALL = setOf(APPEARANCE, ACCOUNT, TUNNEL, PROXIES, DNS, ACCESS, CONNECTION, BACKGROUND, BACKUP, AUTOMATION, DIAGNOSTICS, ABOUT)

    /** Settings on section [id], or on the hub with [query] typed into its search. */
    fun intent(context: Context, id: String? = null, query: String? = null): Intent =
        Intent(context, SettingsActivity::class.java).apply {
            id?.takeIf { it in ALL }?.let { putExtra(SettingsActivity.EXTRA_OPEN_SECTION, it) }
            query?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() }?.let { putExtra(SettingsActivity.EXTRA_SEARCH, it) }
        }

    /** A query from a link is cut to this many characters. */
    private const val MAX_QUERY = 100

    fun open(context: Context, id: String) = context.startActivity(intent(context, id))
}
