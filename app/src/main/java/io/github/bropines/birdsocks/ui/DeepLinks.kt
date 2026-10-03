package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `birdsocks://` links: they open a screen, and nothing more. Any app on the
 * device can open one, so none of them changes a setting or starts anything.
 *
 *     birdsocks://peers | networks | settings | permissions
 *     birdsocks://logs?category=NETBIRD     Logs, on one category
 *
 * MainActivity receives them and opens the screen on top of itself.
 */
object DeepLinks {
    const val SCHEME = "birdsocks"

    /** The screen [uri] names, or null for the main screen or a link it does not know. */
    fun intentFor(context: Context, uri: Uri): Intent? {
        if (uri.scheme != SCHEME) return null
        return when (uri.host) {
            "peers" -> Intent(context, PeersActivity::class.java)
            "networks" -> Intent(context, NetworksActivity::class.java)
            "logs" -> LogsActivity.intent(context, uri.getQueryParameter("category")?.uppercase() ?: "ALL")
            "settings" -> Intent(context, SettingsActivity::class.java)
            "permissions" -> Intent(context, PermissionsActivity::class.java)
            else -> null
        }
    }
}
