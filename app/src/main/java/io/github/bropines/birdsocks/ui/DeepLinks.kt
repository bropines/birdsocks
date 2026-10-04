package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `birdsocks://` links: they open a screen, and nothing more. Any app on the
 * device can open one, so none of them changes a setting or starts anything.
 *
 *     birdsocks://peers | networks | dns | publish | permissions
 *     birdsocks://diagnostics | events            Diagnostics, on a page
 *     birdsocks://access-check?peer=<ip>          the access check
 *     birdsocks://logs?category=NETBIRD           Logs, on one category
 *     birdsocks://settings?section=<id>           Settings, on a section (SettingsSections)
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
            "dns" -> Intent(context, DnsActivity::class.java)
            "publish" -> Intent(context, ExposeActivity::class.java)
            "diagnostics" -> DiagnosticsActivity.intent(context)
            "events" -> DiagnosticsActivity.intent(context, DiagnosticsActivity.PAGE_EVENTS)
            "access-check" -> TraceActivity.intent(context, uri.getQueryParameter("peer"))
            "logs" -> LogsActivity.intent(context, uri.getQueryParameter("category")?.uppercase() ?: "ALL")
            "settings" -> SettingsSections.intent(context, uri.getQueryParameter("section") ?: uri.pathSegments.firstOrNull())
            "permissions" -> Intent(context, PermissionsActivity::class.java)
            else -> null
        }
    }
}
