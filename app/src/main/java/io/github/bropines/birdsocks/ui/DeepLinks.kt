package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.bropines.birdsocks.core.Automation

/**
 * `birdsocks://` links. Screen links open a screen and nothing more:
 *
 *     birdsocks://peers | networks | dns | publish | permissions
 *     birdsocks://diagnostics | events            Diagnostics, on a page
 *     birdsocks://access-check?peer=<ip>          the access check
 *     birdsocks://logs?category=NETBIRD           Logs, on one category
 *     birdsocks://settings?section=<id>           Settings, on a section (SettingsSections)
 *
 * Action links change something ([requestFor]):
 *
 *     birdsocks://connect | disconnect | toggle
 *     birdsocks://exit-node?peer=<name|none>
 *     birdsocks://account?name=<profile>
 *     birdsocks://tun?on=true|false
 *     birdsocks://add-account?server=<url>&name=<n>&key=<setup key>
 *
 * Any app or web page can open a link, so an action link runs only once the
 * user allows it in a dialog naming what it does — or at once when it carries
 * the automation token (`&secret=`) while automation is on. add-account only
 * fills in the add-account dialog; adding stays the user's tap.
 *
 * MainActivity receives them: a screen opens on top of it, an action asks there.
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

    /** What an action link asks for. */
    sealed interface Request {
        data class Run(val command: Automation.Command) : Request
        /** An invite: the add-account dialog, filled in. [server] "" is NetBird Cloud. */
        data class AddAccount(val server: String, val name: String, val setupKey: String) : Request
        /** An action link with a missing or unacceptable value. */
        data object Invalid : Request
    }

    /** The action [uri] asks for; null when it is no action link. Values are checked as the broadcasts' are. */
    fun requestFor(uri: Uri): Request? {
        if (uri.scheme != SCHEME) return null
        fun param(name: String) = runCatching { uri.getQueryParameter(name) }.getOrNull()
        fun run(command: Automation.Command?) = command?.let { Request.Run(it) } ?: Request.Invalid
        return when (uri.host) {
            "connect" -> run(Automation.Command.Connect)
            "disconnect" -> run(Automation.Command.Disconnect)
            "toggle" -> run(Automation.Command.Toggle)
            "exit-node" -> run(Automation.exitNode(param("peer")))
            "account" -> run(Automation.account(param("name")))
            "tun" -> run(Automation.tun(param("on")))
            "add-account" -> invite(param("server"), param("name"), param("key"))
            else -> null
        }
    }

    /**
     * An invite's values, or Invalid: an http(s) server or none (NetBird
     * Cloud), a name as an account takes it, and a setup key of the
     * characters setup keys have.
     */
    private fun invite(rawServer: String?, rawName: String?, rawKey: String?): Request {
        val server = if (rawServer.isNullOrBlank()) "" else {
            val s = Automation.clean(rawServer, 253) ?: return Request.Invalid
            val uri = runCatching { java.net.URI(normalizeServer(s)) }.getOrNull() ?: return Request.Invalid
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return Request.Invalid
            serverOrigin(s)
        }
        val name = if (rawName.isNullOrBlank()) "" else Automation.clean(rawName, 64) ?: return Request.Invalid
        val key = if (rawKey.isNullOrBlank()) "" else rawKey.trim().takeIf { k -> k.length <= 64 && k.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } }
            ?: return Request.Invalid
        return Request.AddAccount(server, name, key)
    }
}
