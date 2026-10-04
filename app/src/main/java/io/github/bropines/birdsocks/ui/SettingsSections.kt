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
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"

    val ALL = setOf(APPEARANCE, ACCOUNT, TUNNEL, PROXIES, DNS, ACCESS, CONNECTION, BACKGROUND, BACKUP, DIAGNOSTICS, ABOUT)

    fun intent(context: Context, id: String? = null): Intent =
        Intent(context, SettingsActivity::class.java).apply {
            id?.takeIf { it in ALL }?.let { putExtra(SettingsActivity.EXTRA_OPEN_SECTION, it) }
        }

    fun open(context: Context, id: String) = context.startActivity(intent(context, id))
}
