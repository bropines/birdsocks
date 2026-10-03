package io.github.bropines.birdsocks.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The app's own settings, in one SharedPreferences file. NetBird's settings —
 * the management server, Rosenpass, routes, DNS — are not here: they live in
 * the daemon's profile and go through GetConfig/SetConfig.
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

    // --- Appearance ---
    fun getAppTheme(context: Context): String = getString(context, "app_theme", "system")
    fun setAppTheme(context: Context, theme: String) = setString(context, "app_theme", theme)
    fun getThemePreset(context: Context): String = getString(context, "theme_preset", "default")
    fun setThemePreset(context: Context, preset: String) = setString(context, "theme_preset", preset)
    fun isDynamicColorEnabled(context: Context): Boolean = getBoolean(context, "dynamic_color", true)
    fun setDynamicColorEnabled(context: Context, enabled: Boolean) = setBoolean(context, "dynamic_color", enabled)

    // --- The SOCKS5 proxy the daemon serves ---
    fun getSocksPort(context: Context): Int = prefs(context).getInt("socks_port", DEFAULT_SOCKS_PORT)
    fun setSocksPort(context: Context, port: Int) = prefs(context).edit().putInt("socks_port", port).apply()
    fun getSocksUser(context: Context): String = getString(context, "socks_user", "")
    fun setSocksUser(context: Context, user: String) = setString(context, "socks_user", user)
    fun getSocksPass(context: Context): String = getString(context, "socks_pass", "")
    fun setSocksPass(context: Context, pass: String) = setString(context, "socks_pass", pass)
    /** Listen on every interface instead of loopback, so other devices on the LAN can use it. */
    fun isSocksLanShared(context: Context): Boolean = getBoolean(context, "socks_lan", false)
    fun setSocksLanShared(context: Context, shared: Boolean) = setBoolean(context, "socks_lan", shared)

    // --- The daemon ---
    fun getLogLevel(context: Context): String = getString(context, "log_level", "info")
    fun setLogLevel(context: Context, level: String) = setString(context, "log_level", level)
    /** NB_FORCE_RELAY: every peer through a relay, never direct. An environment knob, read at start. */
    fun isForceRelay(context: Context): Boolean = getBoolean(context, "force_relay", false)
    fun setForceRelay(context: Context, enabled: Boolean) = setBoolean(context, "force_relay", enabled)
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
}
