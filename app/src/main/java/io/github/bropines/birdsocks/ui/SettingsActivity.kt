package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import appctr.Appctr
import io.github.bropines.birdsocks.BuildConfig
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.GlobalSettings
import io.github.bropines.birdsocks.core.Netbird
import io.github.bropines.birdsocks.core.NetbirdService
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.SegmentedChipItem
import io.github.bropines.birdsocks.core.SlidingSegmentedChips
import io.github.bropines.birdsocks.core.wrapContextWithLocale
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme
import io.github.bropines.birdsocks.ui.theme.findActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

class SettingsActivity : ComponentActivity() {
    companion object {
        /** Kept for links that name a section; the screen is one list now. */
        const val EXTRA_OPEN_SECTION = "open_section"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var appTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
            var themePreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
            var dynamicColor by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }
            var amoled by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }
            BirdSocksTheme(appTheme = appTheme, themePreset = themePreset, dynamicColorEnabled = dynamicColor, amoledModeEnabled = amoled) {
                SettingsScreen(
                    onBack = { finish() },
                    appearance = Appearance(
                        theme = appTheme, onTheme = { appTheme = it; GlobalSettings.setAppTheme(context, it) },
                        preset = themePreset, onPreset = { themePreset = it; GlobalSettings.setThemePreset(context, it) },
                        dynamicColor = dynamicColor, onDynamicColor = { dynamicColor = it; GlobalSettings.setDynamicColorEnabled(context, it) },
                        amoled = amoled, onAmoled = { amoled = it; GlobalSettings.setBoolean(context, "amoled_mode", it) }
                    )
                )
            }
        }
    }
}

class Appearance(
    val theme: String, val onTheme: (String) -> Unit,
    val preset: String, val onPreset: (String) -> Unit,
    val dynamicColor: Boolean, val onDynamicColor: (Boolean) -> Unit,
    val amoled: Boolean, val onAmoled: (Boolean) -> Unit
)

data class PresetItem(val id: String, val color: Color, val name: String)

fun generateRandomString(length: Int = 12): String {
    val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length).map { allowed.random() }.joinToString("")
}

@Composable
fun SettingsScreen(onBack: () -> Unit, appearance: Appearance) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daemon by NetbirdState.daemon.collectAsState()
    val status by NetbirdState.status.collectAsState()
    val running = daemon == NetbirdState.Daemon.Running

    // Settings the daemon reads when it starts: changing one while it runs
    // takes a restart, which the banner offers.
    var restartNeeded by remember { mutableStateOf(false) }
    fun startSetting(block: () -> Unit) {
        block()
        if (daemon != NetbirdState.Daemon.Stopped) restartNeeded = true
    }

    // The profile's settings, from the daemon; null until it answers.
    var config by remember { mutableStateOf<NbConfig?>(null) }
    LaunchedEffect(running) { config = if (running) runCatching { Netbird.config() }.getOrNull() else null }
    fun setConfig(fields: JsonObjectBuilder.() -> Unit) {
        scope.launch {
            runCatching {
                Netbird.setConfig(fields)
                config = Netbird.config()
                // Saved to the profile; the engine reads it when it connects.
                Netbird.reconnect()
            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.menu_settings), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (restartNeeded) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nb_settings_restart_needed), Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            restartNeeded = false
                            scope.launch {
                                NetbirdService.stop(context)
                                NetbirdState.daemon.collectFirst { it == NetbirdState.Daemon.Stopped }
                                NetbirdService.start(context)
                            }
                        }) { Text(stringResource(R.string.nb_settings_restart)) }
                    }
                }
            }

            // --- Device and account ---
            SettingsCard(stringResource(R.string.nb_settings_account)) {
                var deviceName by remember { mutableStateOf(GlobalSettings.getDeviceName(context)) }
                SettingsEditItem(
                    title = stringResource(R.string.nb_settings_device_name),
                    value = deviceName,
                    icon = Icons.Default.Smartphone,
                    placeholder = GlobalSettings.systemDeviceName(context),
                    description = stringResource(R.string.nb_settings_device_name_desc)
                ) { startSetting { GlobalSettings.setDeviceName(context, it); deviceName = GlobalSettings.getDeviceName(context) } }
                config?.let { cfg ->
                    SettingsClickableItem(
                        title = stringResource(R.string.nb_settings_server),
                        subtitle = cfg.managementUrl,
                        icon = Icons.Default.Dns
                    ) { }
                }
                SettingsClickableItem(
                    title = stringResource(R.string.nb_settings_logout),
                    subtitle = stringResource(R.string.nb_settings_logout_desc),
                    icon = Icons.AutoMirrored.Filled.Logout,
                    enabled = running && status?.state?.needsLogin == false
                ) { confirmLogout = true }
            }

            if (running) AccountsCard()

            // --- The SOCKS5 proxy ---
            SettingsCard(stringResource(R.string.nb_socks_title)) {
                var port by remember { mutableStateOf(GlobalSettings.getSocksPort(context).toString()) }
                var user by remember { mutableStateOf(GlobalSettings.getSocksUser(context)) }
                var pass by remember { mutableStateOf(GlobalSettings.getSocksPass(context)) }
                var lan by remember { mutableStateOf(GlobalSettings.isSocksLanShared(context)) }
                SettingsEditItem(stringResource(R.string.nb_settings_socks_port), port, Icons.Default.SettingsEthernet, placeholder = GlobalSettings.DEFAULT_SOCKS_PORT.toString()) { v ->
                    val p = v.trim().toIntOrNull()
                    if (p == null || p !in 1..65535) {
                        Toast.makeText(context, context.getString(R.string.nb_settings_bad_port), Toast.LENGTH_SHORT).show()
                    } else startSetting { GlobalSettings.setSocksPort(context, p); port = p.toString() }
                }
                SettingsEditItem(stringResource(R.string.nb_settings_socks_user), user, Icons.Default.Person,
                    description = stringResource(R.string.nb_settings_socks_auth_desc),
                    onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino
                ) { startSetting { GlobalSettings.setSocksUser(context, it.trim()); user = it.trim() } }
                SettingsEditItem(stringResource(R.string.nb_settings_socks_pass), if (pass.isEmpty()) "" else "••••••••", Icons.Default.Password,
                    description = stringResource(R.string.nb_settings_socks_auth_desc),
                    onAction = { generateRandomString(16) }, actionIcon = Icons.Default.Casino
                ) { startSetting { GlobalSettings.setSocksPass(context, it.trim()); pass = it.trim() } }
                SettingsSwitchItem(stringResource(R.string.nb_settings_socks_lan), stringResource(R.string.nb_settings_socks_lan_desc), Icons.Default.Wifi, lan) {
                    startSetting { GlobalSettings.setSocksLanShared(context, it); lan = it }
                }
            }

            // --- The DNS proxy ---
            SettingsCard(stringResource(R.string.nb_settings_dns_proxy)) {
                var enabled by remember { mutableStateOf(GlobalSettings.isDnsProxyEnabled(context)) }
                var address by remember { mutableStateOf(GlobalSettings.getDnsProxyAddress(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_dns_proxy), stringResource(R.string.nb_settings_dns_proxy_desc), Icons.Default.Dns, enabled) {
                    startSetting { GlobalSettings.setDnsProxyEnabled(context, it); enabled = it }
                }
                SettingsEditItem(stringResource(R.string.nb_settings_dns_proxy_address), address, Icons.Default.SettingsEthernet,
                    placeholder = GlobalSettings.DEFAULT_DNS_PROXY, enabled = enabled
                ) { v ->
                    val port = v.substringAfterLast(':', "").toIntOrNull()
                    if (':' !in v || port == null || port !in 1..65535 || v.substringBeforeLast(':').isBlank()) {
                        Toast.makeText(context, context.getString(R.string.nb_settings_bad_address), Toast.LENGTH_SHORT).show()
                    } else startSetting { GlobalSettings.setDnsProxyAddress(context, v); address = GlobalSettings.getDnsProxyAddress(context) }
                }
            }

            // --- NetBird's own settings, in the profile ---
            SettingsCard(stringResource(R.string.nb_settings_netbird)) {
                val cfg = config
                if (cfg == null) {
                    HelpText(stringResource(R.string.nb_settings_netbird_off))
                } else {
                    SettingsSwitchItem("Rosenpass", stringResource(R.string.nb_settings_rosenpass_desc), Icons.Default.Shield, cfg.rosenpassEnabled) {
                        setConfig { put("rosenpassEnabled", it) }
                    }
                    if (cfg.rosenpassEnabled) {
                        SettingsSwitchItem(stringResource(R.string.nb_settings_rosenpass_permissive), stringResource(R.string.nb_settings_rosenpass_permissive_desc), Icons.Default.ShieldMoon, cfg.rosenpassPermissive) {
                            setConfig { put("rosenpassPermissive", it) }
                        }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_lazy), stringResource(R.string.nb_settings_lazy_desc), Icons.Default.Bedtime, cfg.lazyConnectionEnabled) {
                        setConfig { put("lazyConnectionEnabled", it) }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_block_inbound), stringResource(R.string.nb_settings_block_inbound_desc), Icons.Default.Block, cfg.blockInbound) {
                        setConfig { put("blockInbound", it) }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_dns), stringResource(R.string.nb_settings_dns_desc), Icons.Default.Dns, !cfg.disableDns) {
                        setConfig { put("disableDns", !it) }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_client_routes), stringResource(R.string.nb_settings_client_routes_desc), Icons.Default.Hub, !cfg.disableClientRoutes) {
                        setConfig { put("disableClientRoutes", !it) }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_ipv6), stringResource(R.string.nb_settings_ipv6_desc), Icons.Default.Language, !cfg.disableIpv6) {
                        setConfig { put("disableIpv6", !it) }
                    }
                }
            }

            // --- How the daemon runs ---
            SettingsCard(stringResource(R.string.nb_settings_daemon)) {
                var forceRelay by remember { mutableStateOf(GlobalSettings.isForceRelay(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_force_relay), stringResource(R.string.nb_settings_force_relay_desc), Icons.Default.CallSplit, forceRelay) {
                    startSetting { GlobalSettings.setForceRelay(context, it); forceRelay = it }
                }
                var level by remember { mutableStateOf(GlobalSettings.getLogLevel(context)) }
                val levels = listOf("info", "debug", "trace")
                Text(stringResource(R.string.nb_settings_log_level), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                SlidingSegmentedChips(levels, levels.indexOf(level).coerceAtLeast(0), { i ->
                    startSetting { GlobalSettings.setLogLevel(context, levels[i]); level = levels[i] }
                }, Modifier.fillMaxWidth())
                var env by remember { mutableStateOf(GlobalSettings.getExtraEnv(context)) }
                SettingsEditItem(stringResource(R.string.nb_settings_env), env.lines().filter { it.isNotBlank() }.joinToString(", "), Icons.Default.Code,
                    placeholder = "NB_RELAY_TRANSPORT=ws", description = stringResource(R.string.nb_settings_env_desc)
                ) { startSetting { GlobalSettings.setExtraEnv(context, it.split(',', '\n').joinToString("\n") { l -> l.trim() }); env = GlobalSettings.getExtraEnv(context) } }
            }

            // --- Background ---
            SettingsCard(stringResource(R.string.nb_settings_background)) {
                var autoStart by remember { mutableStateOf(GlobalSettings.isAutoStartEnabled(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_boot), stringResource(R.string.nb_settings_boot_desc), Icons.Default.RestartAlt, autoStart) {
                    GlobalSettings.setAutoStartEnabled(context, it); autoStart = it
                }
                SettingsClickableItem(stringResource(R.string.nb_settings_permissions), stringResource(R.string.nb_settings_permissions_desc), Icons.Default.BatteryChargingFull) {
                    context.startActivity(Intent(context, PermissionsActivity::class.java))
                }
            }

            AppearanceCard(appearance)

            // --- About ---
            SettingsCard(stringResource(R.string.nb_settings_about)) {
                Text("BirdSocks ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("NetBird ${runCatching { Appctr.coreVersion() }.getOrDefault("?")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.daemonVersion?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.nb_settings_daemon_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                HelpText(stringResource(R.string.nb_settings_about_desc))
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.nb_settings_logout)) },
            text = { Text(stringResource(R.string.nb_settings_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { runCatching { Netbird.logout() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } }
                }) { Text(stringResource(R.string.nb_settings_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectFirst(predicate: (T) -> Boolean) {
    first(predicate)
}

@Composable
private fun AppearanceCard(a: Appearance) {
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.settings_sect_personalization)) {
        val themes = listOf(
            Triple("system", Icons.Default.Settings, stringResource(R.string.settings_theme_system)),
            Triple("light", Icons.Default.LightMode, stringResource(R.string.settings_theme_light)),
            Triple("dark", Icons.Default.DarkMode, stringResource(R.string.settings_theme_dark))
        )
        Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            items = themes.map { SegmentedChipItem(it.third, it.second) },
            selectedIndex = themes.indexOfFirst { it.first == a.theme }.coerceAtLeast(0),
            onOptionSelected = { a.onTheme(themes[it].first) },
            modifier = Modifier.fillMaxWidth(),
            height = 38.dp
        )

        var lang by remember { mutableStateOf(GlobalSettings.getString(context, "app_locale", "sys")) }
        val langs = listOf(
            "sys" to stringResource(R.string.settings_lang_sys),
            "en" to stringResource(R.string.settings_lang_en),
            "ru" to stringResource(R.string.settings_lang_ru)
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.settings_lang_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SlidingSegmentedChips(
            items = langs.map { SegmentedChipItem(it.second, Icons.Default.Language) },
            selectedIndex = langs.indexOfFirst { it.first == lang }.coerceAtLeast(0),
            onOptionSelected = { i ->
                val id = langs[i].first
                lang = id
                GlobalSettings.setString(context, "app_locale", id)
                AppCompatDelegate.setApplicationLocales(if (id == "sys") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(id))
                context.findActivity()?.recreate()
            },
            modifier = Modifier.fillMaxWidth(),
            height = 38.dp
        )

        if (!a.dynamicColor || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            val presets = listOf(
                PresetItem("default", Color(0xFF6750A4), stringResource(R.string.settings_preset_default)),
                PresetItem("lavender", Color(0xFF704E9B), stringResource(R.string.settings_preset_lavender)),
                PresetItem("emerald", Color(0xFF006B54), stringResource(R.string.settings_preset_emerald)),
                PresetItem("sapphire", Color(0xFF005FAF), stringResource(R.string.settings_preset_sapphire)),
                PresetItem("amber", Color(0xFF825500), stringResource(R.string.settings_preset_amber)),
                PresetItem("monochrome", Color(0xFF1D2023), stringResource(R.string.settings_preset_monochrome)),
                PresetItem("tokionight", Color(0xFF7AA2F7), stringResource(R.string.settings_preset_tokionight))
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_palette_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                presets.forEach { item ->
                    val selected = a.preset == item.id
                    Box(
                        Modifier.size(36.dp).background(item.color, CircleShape).clickable { a.onPreset(item.id) }
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(12.dp))
            SettingsSwitchItem(stringResource(R.string.settings_dynamic_color_title), stringResource(R.string.settings_dynamic_color_desc), Icons.Default.Palette, a.dynamicColor, onCheckedChange = a.onDynamicColor)
        }
        SettingsSwitchItem(stringResource(R.string.settings_amoled_black_title), stringResource(R.string.settings_amoled_black_desc), Icons.Default.Contrast, a.amoled, onCheckedChange = a.onAmoled)
    }
}
