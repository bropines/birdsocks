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
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

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
                            NetbirdService.restart(context)
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
                    // The dashboard of that server: where its peers, groups and keys are managed.
                    SettingsClickableItem(
                        title = stringResource(R.string.nb_settings_server),
                        subtitle = cfg.managementUrl,
                        icon = Icons.Default.Dns
                    ) { openUrl(context, cfg.dashboardUrl) }
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
                var socksAddress by remember { mutableStateOf(GlobalSettings.getSocksAddress(context)) }
                var user by remember { mutableStateOf(GlobalSettings.getSocksUser(context)) }
                var pass by remember { mutableStateOf(GlobalSettings.getSocksPass(context)) }
                var lan by remember { mutableStateOf(GlobalSettings.isSocksLanShared(context)) }
                SettingsEditItem(stringResource(R.string.nb_settings_socks_address), socksAddress, Icons.Default.SettingsEthernet,
                    placeholder = GlobalSettings.DEFAULT_SOCKS_ADDRESS, description = stringResource(R.string.nb_settings_loopback_desc),
                    onAction = { GlobalSettings.randomLoopback(GlobalSettings.getSocksPort(context)) }, actionIcon = Icons.Default.Casino
                ) { v ->
                    if (!GlobalSettings.isLoopbackAddress(v.trim())) {
                        Toast.makeText(context, context.getString(R.string.nb_settings_bad_loopback), Toast.LENGTH_SHORT).show()
                    } else startSetting { GlobalSettings.setSocksAddress(context, v); socksAddress = GlobalSettings.getSocksAddress(context) }
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
                var upstream by remember { mutableStateOf(GlobalSettings.getDnsUpstream(context)) }
                SettingsEditItem(stringResource(R.string.nb_settings_dns_upstream), upstream, Icons.Default.CallSplit,
                    placeholder = stringResource(R.string.nb_settings_dns_upstream_hint), description = stringResource(R.string.nb_settings_dns_upstream_desc), enabled = enabled
                ) { v -> startSetting { GlobalSettings.setDnsUpstream(context, v); upstream = GlobalSettings.getDnsUpstream(context) } }
                SettingsEditItem(stringResource(R.string.nb_settings_dns_proxy_address), address, Icons.Default.SettingsEthernet,
                    placeholder = GlobalSettings.DEFAULT_DNS_PROXY, description = stringResource(R.string.nb_settings_loopback_desc), enabled = enabled,
                    onAction = { GlobalSettings.randomLoopback(GlobalSettings.getDnsProxyAddress(context).substringAfterLast(':').toIntOrNull() ?: 48153) },
                    actionIcon = Icons.Default.Casino
                ) { v ->
                    if (!GlobalSettings.isLoopbackAddress(v.trim())) {
                        Toast.makeText(context, context.getString(R.string.nb_settings_bad_loopback), Toast.LENGTH_SHORT).show()
                    } else startSetting { GlobalSettings.setDnsProxyAddress(context, v); address = GlobalSettings.getDnsProxyAddress(context) }
                }
            }

            // --- NetBird's own settings, in the profile ---
            SettingsCard(stringResource(R.string.nb_settings_netbird)) {
                val cfg = config
                if (cfg == null) {
                    HelpText(stringResource(R.string.nb_settings_netbird_off))
                } else {
                    // Rosenpass is not offered: its key exchange runs over the overlay
                    // from a host socket, which userspace mode cannot route, so the
                    // switch would read "on" without post-quantum keys.
                    SettingsEditItem(stringResource(R.string.nb_settings_psk), if (cfg.preSharedKey.isEmpty()) "" else "••••••••", Icons.Default.Key,
                        description = stringResource(R.string.nb_settings_psk_desc)
                    ) { v -> setConfig { put("optionalPreSharedKey", v.trim()) } }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_server_routes), stringResource(R.string.nb_settings_server_routes_desc), Icons.Default.AltRoute, !cfg.disableServerRoutes) {
                        setConfig { put("disableServerRoutes", !it) }
                    }
                    SettingsSwitchItem(stringResource(R.string.nb_settings_block_inbound), stringResource(R.string.nb_settings_block_inbound_desc), Icons.Default.Block, cfg.blockInbound) {
                        setConfig { put("blockInbound", it) }
                    }
                    var labels by remember { mutableStateOf(GlobalSettings.getDnsLabels(context)) }
                    SettingsEditItem(stringResource(R.string.nb_settings_dns_labels), labels, Icons.Default.Label,
                        placeholder = "phone, poco", description = stringResource(R.string.nb_settings_dns_labels_desc)
                    ) { v ->
                        val list = v.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        GlobalSettings.setDnsLabels(context, list.joinToString(", "))
                        labels = GlobalSettings.getDnsLabels(context)
                        setConfig {
                            putJsonArray("dnsLabels") { list.forEach { add(it) } }
                            put("cleanDNSLabels", list.isEmpty())
                        }
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
                    SettingsSwitchItem(stringResource(R.string.nb_settings_remote_jobs), stringResource(R.string.nb_settings_remote_jobs_desc), Icons.Default.BugReport, cfg.remoteJobsAllowed) {
                        setConfig { put("remoteJobsAllowed", it) }
                    }
                    SettingsEditItem("MTU", if (cfg.mtu > 0) cfg.mtu.toString() else "", Icons.Default.Straighten,
                        placeholder = "1280", description = stringResource(R.string.nb_settings_mtu_desc)
                    ) { v ->
                        val mtu = v.trim().toIntOrNull()
                        if (mtu == null || mtu !in 576..8192) Toast.makeText(context, context.getString(R.string.nb_settings_bad_mtu), Toast.LENGTH_SHORT).show()
                        else setConfig { put("mtu", mtu) }
                    }
                }
            }

            // --- How the daemon runs ---
            SettingsCard(stringResource(R.string.nb_settings_daemon)) {
                var lazy by remember { mutableStateOf(GlobalSettings.getLazyConn(context)) }
                val lazyValues = listOf("", "on", "off")
                Text(stringResource(R.string.nb_settings_lazy), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                HelpText(stringResource(R.string.nb_settings_lazy_desc), Modifier.padding(bottom = 8.dp))
                SlidingSegmentedChips(
                    listOf(stringResource(R.string.nb_settings_lazy_server), stringResource(R.string.nb_settings_lazy_on), stringResource(R.string.nb_settings_lazy_off)),
                    lazyValues.indexOf(lazy).coerceAtLeast(0), { i ->
                        startSetting { GlobalSettings.setLazyConn(context, lazyValues[i]); lazy = lazyValues[i] }
                    }, Modifier.fillMaxWidth()
                )
                var quic by remember { mutableStateOf(GlobalSettings.isRelayQuic(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_quic), stringResource(R.string.nb_settings_quic_desc), Icons.Default.Speed, quic) {
                    startSetting { GlobalSettings.setRelayQuic(context, it); quic = it }
                }
                var inbound by remember { mutableStateOf(GlobalSettings.isInboundAccess(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_inbound), stringResource(R.string.nb_settings_inbound_desc), Icons.Default.CallReceived, inbound) {
                    startSetting { GlobalSettings.setInboundAccess(context, it); inbound = it }
                }
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
                var eventNotes by remember { mutableStateOf(GlobalSettings.isEventNotifications(context)) }
                SettingsSwitchItem(stringResource(R.string.nb_settings_event_notifications), stringResource(R.string.nb_settings_event_notifications_desc), Icons.Default.NotificationsActive, eventNotes) {
                    GlobalSettings.setEventNotifications(context, it); eventNotes = it
                }
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
                SettingsClickableItem(stringResource(R.string.nb_about_docs), "docs.netbird.io", Icons.AutoMirrored.Filled.MenuBook) {
                    openUrl(context, "https://docs.netbird.io/")
                }
                SettingsClickableItem(stringResource(R.string.nb_about_source), "github.com/bropines/birdsocks", Icons.Default.Code) {
                    openUrl(context, "https://github.com/bropines/birdsocks")
                }
                var showLicenses by remember { mutableStateOf(false) }
                SettingsClickableItem(stringResource(R.string.nb_about_licenses), stringResource(R.string.nb_about_licenses_desc), Icons.Default.Gavel) { showLicenses = true }
                if (showLicenses) {
                    AlertDialog(
                        onDismissRequest = { showLicenses = false },
                        title = { Text(stringResource(R.string.nb_about_licenses)) },
                        text = { Text(stringResource(R.string.nb_about_licenses_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
                        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.action_close)) } }
                    )
                }
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
