package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `birdsocks://` links: they open a screen, and nothing more. Any app on the
 * device can open one, so none of them changes a setting or starts anything;
 * that stays with the automation receiver and its token (docs/AUTOMATION.md).
 * `tailcat/add` only fills in a connection's editor — saving is the user's tap.
 *
 *     birdsocks://serve                     Serve & Funnel
 *     birdsocks://tailcat                   TailCat
 *     birdsocks://tailcat/add?cmd=…         a new TailCat connection from an
 *                                           address or a connect command
 *     birdsocks://logs?category=TAILCAT     Logs, on one category
 *     birdsocks://settings/<section>        Settings, on a section
 *     birdsocks://peers | dns | netcheck | console | files | taildrive | permissions
 *
 * MainActivity receives them (VIEW, scheme tailsocks) and opens the screen on
 * top of itself, so Back lands on the main screen.
 */
object DeepLinks {
    const val SCHEME = "birdsocks"

    /** Settings sections a link may name; the ids SettingsActivity's list uses. */
    private val SETTINGS_SECTIONS = setOf(
        "appearance", "account", "tunnel", "proxies", "dns", "bypass", "sharing",
        "background", "backup", "automation", "diagnostics"
    )

    /** The screen [uri] names, or null for the main screen or a link it does not know. */
    fun intentFor(context: Context, uri: Uri): Intent? {
        if (uri.scheme != SCHEME) return null
        val path = uri.pathSegments
        return when (uri.host) {
            "serve" -> Intent(context, ServeActivity::class.java)
            "tailcat" -> when (path.firstOrNull()) {
                "add" -> ServeActivity.tailcatIntent(context, importText = uri.getQueryParameter("cmd") ?: uri.getQueryParameter("address"))
                else -> ServeActivity.tailcatIntent(context)
            }
            "logs" -> LogsActivity.intent(context, uri.getQueryParameter("category")?.uppercase() ?: "ALL")
            "settings" -> Intent(context, SettingsActivity::class.java).apply {
                path.firstOrNull()?.takeIf { it in SETTINGS_SECTIONS }?.let { putExtra(SettingsActivity.EXTRA_OPEN_SECTION, it) }
            }
            "peers" -> Intent(context, PeersActivity::class.java)
            "dns" -> Intent(context, DnsActivity::class.java)
            "netcheck" -> Intent(context, NetcheckActivity::class.java)
            "console" -> Intent(context, ConsoleActivity::class.java)
            "files" -> Intent(context, FilesActivity::class.java)
            "taildrive" -> Intent(context, TaildriveActivity::class.java)
            "permissions" -> Intent(context, PermissionsActivity::class.java)
            else -> null
        }
    }
}
