package io.github.bropines.birdsocks.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/**
 * The app's own settings, in one SharedPreferences file. NetBird's settings —
 * the management server, Rosenpass, routes, DNS — are not here: they live in
 * the daemon's profile and go through GetConfig/SetConfig.
 *
 * A backup carries a key only once BackupFormat lists it.
 */
object GlobalSettings {
    private const val PREFS_NAME = "global_settings"

    const val DEFAULT_SOCKS_PORT = 48125
    const val CLOUD_MANAGEMENT_URL = "https://api.netbird.io:443"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, default: String): String = prefs(context).getString(key, default) ?: default
    fun setString(context: Context, key: String, value: String) = prefs(context).edit().putString(key, value).apply()
    fun getBoolean(context: Context, key: String, default: Boolean): Boolean = prefs(context).getBoolean(key, default)
    fun setBoolean(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun getLong(context: Context, key: String, default: Long): Long = prefs(context).getLong(key, default)
    fun setLong(context: Context, key: String, value: Long) = prefs(context).edit().putLong(key, value).apply()

    /** Every stored entry as it is, for a backup to pick from (core/Backup.kt). */
    fun snapshot(context: Context): Map<String, *> = prefs(context).all
    /** Several changes in one write that is on disk when this returns: a restore. */
    fun commit(context: Context, changes: SharedPreferences.Editor.() -> Unit): Boolean = prefs(context).edit().apply(changes).commit()

    // --- Appearance ---
    fun getAppTheme(context: Context): String = getString(context, "app_theme", "system")
    fun setAppTheme(context: Context, theme: String) = setString(context, "app_theme", theme)
    fun getThemePreset(context: Context): String = getString(context, "theme_preset", "default")
    fun setThemePreset(context: Context, preset: String) = setString(context, "theme_preset", preset)
    fun isDynamicColorEnabled(context: Context): Boolean = getBoolean(context, "dynamic_color", true)
    fun setDynamicColorEnabled(context: Context, enabled: Boolean) = setBoolean(context, "dynamic_color", enabled)

    // --- The SOCKS5 proxy the daemon serves ---
    const val DEFAULT_SOCKS_ADDRESS = "127.0.0.1:$DEFAULT_SOCKS_PORT"

    /**
     * host:port the proxy listens on: any address in 127.0.0.0/8 (a random one
     * keeps it off the well-known 127.0.0.1), or 0.0.0.0 with LAN sharing on.
     */
    fun getSocksAddress(context: Context): String {
        getString(context, "socks_address", "").takeIf { it.isNotBlank() }?.let { return it }
        // Before addresses there was only a port.
        val oldPort = prefs(context).getInt("socks_port", DEFAULT_SOCKS_PORT)
        return "127.0.0.1:$oldPort"
    }
    fun setSocksAddress(context: Context, address: String) = setString(context, "socks_address", address.trim())
    fun getSocksPort(context: Context): Int = getSocksAddress(context).substringAfterLast(':').toIntOrNull() ?: DEFAULT_SOCKS_PORT
    /** Where apps on this device reach the proxy: the loopback host, also with LAN sharing on. */
    fun getSocksHost(context: Context): String = getSocksAddress(context).substringBeforeLast(':')

    /** host:port in 127.0.0.0/8, port 1–65535: the only kind of address a proxy here may take. */
    fun isLoopbackAddress(address: String): Boolean {
        val host = address.substringBeforeLast(':', "")
        val port = address.substringAfterLast(':', "").toIntOrNull() ?: return false
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        return port in 1..65535 && octets.size == 4 && octets[0] == 127 && octets.all { it in 0..255 }
    }

    /** A random 127.x.y.z on [port]: a proxy other apps do not find on 127.0.0.1. */
    fun randomLoopback(port: Int): String = "127.${(1..254).random()}.${(1..254).random()}.${(1..254).random()}:$port"
    fun getSocksUser(context: Context): String = getString(context, "socks_user", "")
    fun setSocksUser(context: Context, user: String) = setString(context, "socks_user", user)
    fun getSocksPass(context: Context): String = getString(context, "socks_pass", "")
    fun setSocksPass(context: Context, pass: String) = setString(context, "socks_pass", pass)
    /** Listen on every interface instead of loopback, so other devices on the LAN can use it. */
    fun isSocksLanShared(context: Context): Boolean = getBoolean(context, "socks_lan", false)
    fun setSocksLanShared(context: Context, shared: Boolean) = setBoolean(context, "socks_lan", shared)

    // --- The DNS proxy: NetBird's resolver first, the network's after ---
    const val DEFAULT_DNS_PROXY = "127.0.0.1:48153"
    fun isDnsProxyEnabled(context: Context): Boolean = getBoolean(context, "dns_proxy_enabled", true)
    fun setDnsProxyEnabled(context: Context, enabled: Boolean) = setBoolean(context, "dns_proxy_enabled", enabled)
    fun getDnsProxyAddress(context: Context): String = getString(context, "dns_proxy", DEFAULT_DNS_PROXY).ifBlank { DEFAULT_DNS_PROXY }
    fun setDnsProxyAddress(context: Context, address: String) = setString(context, "dns_proxy", address.trim())
    /** host:port, comma-separated, for names NetBird does not answer; empty for the network's resolvers. */
    fun getDnsUpstream(context: Context): String = getString(context, "dns_upstream", "")
    fun setDnsUpstream(context: Context, upstream: String) = setString(context, "dns_upstream", upstream.trim())

    // --- The daemon ---
    fun getLogLevel(context: Context): String = getString(context, "log_level", "info")
    fun setLogLevel(context: Context, level: String) = setString(context, "log_level", level)
    /** NB_FORCE_RELAY: every peer through a relay, never direct. An environment knob, read at start. */
    fun isForceRelay(context: Context): Boolean = getBoolean(context, "force_relay", false)
    fun setForceRelay(context: Context, enabled: Boolean) = setBoolean(context, "force_relay", enabled)
    /** The relay may race QUIC against WebSocket; off by default, QUIC is throttled on many networks. */
    fun isRelayQuic(context: Context): Boolean = getBoolean(context, "relay_quic", false)
    fun setRelayQuic(context: Context, enabled: Boolean) = setBoolean(context, "relay_quic", enabled)

    // The control plane's way to the server: direct, a proxy of the user's, or ByeDPI.
    const val CONTROL_DIRECT = "direct"
    const val CONTROL_PROXY = "proxy"
    const val CONTROL_BYEDPI = "byedpi"
    fun getControlMode(context: Context): String = getString(context, "cp_mode", CONTROL_DIRECT)
    fun setControlMode(context: Context, mode: String) = setString(context, "cp_mode", mode)
    /** "socks5" or "http". */
    fun getControlProxyType(context: Context): String = getString(context, "cp_type", "socks5")
    fun setControlProxyType(context: Context, type: String) = setString(context, "cp_type", type)
    fun getControlProxyHost(context: Context): String = getString(context, "cp_host", "")
    fun setControlProxyHost(context: Context, host: String) = setString(context, "cp_host", host.trim())
    fun getControlProxyPort(context: Context): String = getString(context, "cp_port", "")
    fun setControlProxyPort(context: Context, port: String) = setString(context, "cp_port", port.trim())
    fun getControlProxyUser(context: Context): String = getString(context, "cp_user", "")
    fun setControlProxyUser(context: Context, user: String) = setString(context, "cp_user", user)
    fun getControlProxyPass(context: Context): String = getString(context, "cp_pass", "")
    fun setControlProxyPass(context: Context, pass: String) = setString(context, "cp_pass", pass)
    fun getByeDpiFlags(context: Context): String = getString(context, "byedpi_flags", ByeDpiProxy.DEFAULT_FLAGS)
    fun setByeDpiFlags(context: Context, flags: String) = setString(context, "byedpi_flags", flags.trim())
    fun isByeDpiIpv4Only(context: Context): Boolean = getBoolean(context, "byedpi_ipv4", false)
    fun setByeDpiIpv4Only(context: Context, on: Boolean) = setBoolean(context, "byedpi_ipv4", on)

    /**
     * The user's own control proxy as a URL (socks5h:// or http://), or ""
     * when it is not the chosen way or has no host. ByeDPI's URL comes from
     * NetbirdService, which runs it.
     */
    fun getControlProxyUrl(context: Context): String {
        if (getControlMode(context) != CONTROL_PROXY) return ""
        val host = getControlProxyHost(context).ifEmpty { return "" }
        val http = getControlProxyType(context) == "http"
        val port = getControlProxyPort(context).ifEmpty { if (http) "8080" else "1080" }
        val user = getControlProxyUser(context)
        val auth = if (user.isNotEmpty()) "${pctEncodeUserInfo(user)}:${pctEncodeUserInfo(getControlProxyPass(context))}@" else ""
        // An IPv6 literal needs its brackets in a URL.
        val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "${if (http) "http" else "socks5h"}://$auth$h:$port"
    }

    /**
     * Percent-encodes a URL userinfo component: RFC 3986 unreserved characters
     * stay, everything else becomes %XX of its UTF-8 bytes. A `/`, `?`, `#`,
     * `@` or `%` in a password would otherwise break the URL the daemon parses.
     */
    fun pctEncodeUserInfo(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(Character.forDigit(c shr 4, 16).uppercaseChar()).append(Character.forDigit(c and 0xF, 16).uppercaseChar())
            }
        }
        return sb.toString()
    }
    /** Lazy connections: "" follows the server, "on" or "off" overrides it. */
    fun getLazyConn(context: Context): String = getString(context, "lazy_conn", "")
    fun setLazyConn(context: Context, value: String) = setString(context, "lazy_conn", value)

    /** Peers may reach this device's own services through its NetBird address (adb, sshd, a web server). */
    fun isInboundAccess(context: Context): Boolean = getBoolean(context, "inbound_access", false)
    fun setInboundAccess(context: Context, on: Boolean) = setBoolean(context, "inbound_access", on)

    /** Warnings and errors NetBird reports become notifications. */
    fun isEventNotifications(context: Context): Boolean = getBoolean(context, "event_notifications", true)
    fun setEventNotifications(context: Context, on: Boolean) = setBoolean(context, "event_notifications", on)

    /**
     * Extra DNS names asked for this device, comma-separated. The daemon
     * takes them but cannot say them back (GetConfig has no field), so the
     * app keeps the copy it shows.
     */
    /** Who signed in to [profile] through the browser, as the server said. */
    fun getAccountEmail(context: Context, profile: String): String = getString(context, "account_email_$profile", "")
    fun setAccountEmail(context: Context, profile: String, email: String) = setString(context, "account_email_$profile", email)

    fun getDnsLabels(context: Context, profile: String): String = getString(context, "dns_labels_$profile", "")
    fun setDnsLabels(context: Context, profile: String, value: String) = setString(context, "dns_labels_$profile", value)

    /** What the app keeps per profile follows a rename. */
    fun renameProfileKeys(context: Context, from: String, to: String) {
        for (key in listOf("account_email_", "dns_labels_", "tun_overlay_")) {
            val v = getString(context, key + from, "")
            prefs(context).edit().remove(key + from).apply()
            if (v.isNotEmpty()) setString(context, key + to, v)
        }
    }

    /** ...and goes with the profile. */
    fun removeProfileKeys(context: Context, profile: String) {
        prefs(context).edit().remove("account_email_$profile").remove("dns_labels_$profile").remove("tun_overlay_$profile").apply()
    }
    /** NAME=value lines handed to the daemon as they are. */
    fun getExtraEnv(context: Context): String = getString(context, "extra_env", "")
    fun setExtraEnv(context: Context, env: String) = setString(context, "extra_env", env)

    /** The name this device registers under: the user's, or the one Android shows. */
    fun getDeviceName(context: Context): String =
        getString(context, "device_name", "").ifBlank { systemDeviceName(context) }
    fun setDeviceName(context: Context, name: String) = setString(context, "device_name", name.trim())

    fun systemDeviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // --- Lifecycle ---
    fun isAutoStartEnabled(context: Context): Boolean = getBoolean(context, "auto_start", false)
    fun setAutoStartEnabled(context: Context, enabled: Boolean) = setBoolean(context, "auto_start", enabled)
    /** The user wanted it on: the boot receiver and a restarted service bring it back. */
    fun wasRunning(context: Context): Boolean = getBoolean(context, "was_running", false)
    fun setWasRunning(context: Context, running: Boolean) = setBoolean(context, "was_running", running)

    // --- VPN (TUN) mode: Android's VPN fed into the SOCKS5 proxy (TunVpnService) ---
    /** The VPN comes up with the daemon and goes down with it. */
    fun isTunModeEnabled(context: Context): Boolean = getBoolean(context, "tun_mode_enabled", false)
    fun setTunModeEnabled(context: Context, enabled: Boolean) = setBoolean(context, "tun_mode_enabled", enabled)
    /** The default route into the VPN even with no exit node or domain route selected. */
    fun isTunRouteAll(context: Context): Boolean = getBoolean(context, "tun_route_all", false)
    fun setTunRouteAll(context: Context, on: Boolean) = setBoolean(context, "tun_route_all", on)
    /** ::/0 into the VPN beside 0.0.0.0/0; NetBird's own IPv6 goes in regardless. */
    fun isTunIpv6Enabled(context: Context): Boolean = getBoolean(context, "tun_ipv6_enabled", false)
    fun setTunIpv6Enabled(context: Context, on: Boolean) = setBoolean(context, "tun_ipv6_enabled", on)

    /**
     * Apps that bypass the VPN unless the user changed the list: banks and
     * stores that refuse a VPN, and TailSocks, a mesh client of its own whose
     * traffic must not loop through another tunnel.
     */
    val DEFAULT_TUN_EXCLUDED_APPS = setOf(
        "io.github.bropines.tailscaled",
        "io.github.bropines.tailscaled.dev",
        "ru.oneme.app",
        "com.vkontakte.android",
        "ru.vk.store.tv",
        "ru.nspk.mirpay",
        "ru.rostel",
        "com.avito.android",
    )
    fun getTunExcludedApps(context: Context): Set<String> {
        if (!prefs(context).contains("tun_excluded_apps")) return DEFAULT_TUN_EXCLUDED_APPS
        return getString(context, "tun_excluded_apps", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    fun setTunExcludedApps(context: Context, apps: Set<String>) = setString(context, "tun_excluded_apps", apps.sorted().joinToString(","))
    /** Comma-separated CIDRs carved out of the default route (Android 13+); empty, the proxy already sends the LAN direct. */
    fun getTunExcludedCIDRs(context: Context): String = getString(context, "tun_excluded_cidrs", "")
    fun setTunExcludedCIDRs(context: Context, cidrs: String) = setString(context, "tun_excluded_cidrs", cidrs.trim())
    const val DEFAULT_TUN_ADDRESS = "198.18.0.1/32"
    /** The VPN interface's IPv4 address with its prefix; a /32 drags no connected route over NetBird's. */
    fun getTunAddress(context: Context): String = getString(context, "tun_address", DEFAULT_TUN_ADDRESS).ifBlank { DEFAULT_TUN_ADDRESS }
    fun setTunAddress(context: Context, address: String) = setString(context, "tun_address", address.trim())
    /** The NetBird network's prefixes last seen under [profile], so the VPN can come up before the first status. */
    fun getTunOverlay(context: Context, profile: String): String = getString(context, "tun_overlay_$profile", "")
    fun setTunOverlay(context: Context, profile: String, prefixes: String) = setString(context, "tun_overlay_$profile", prefixes)
    /** A unique-local IPv6 address for the VPN, made once: it keeps IPv6 working for apps inside it. */
    fun getTunUla(context: Context): String {
        getString(context, "tun_ula", "").takeIf { it.isNotEmpty() }?.let { return it }
        val r = java.security.SecureRandom()
        val ula = "fd%02x:%04x:%04x::1".format(r.nextInt(256), r.nextInt(65536), r.nextInt(65536))
        setString(context, "tun_ula", ula)
        return ula
    }
}
