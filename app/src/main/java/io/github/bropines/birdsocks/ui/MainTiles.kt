package io.github.bropines.birdsocks.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.core.MainItem

/** How an item of the main screen is named and drawn: on its tile, and in the list of what can be added. */
internal data class MainItemSpec(
    @param:StringRes val title: Int,
    /** One line for the list of what can be added. */
    @param:StringRes val desc: Int,
    val icon: ImageVector,
)

internal fun MainItem.spec(): MainItemSpec = when (this) {
    MainItem.STATUS -> MainItemSpec(R.string.main_block_status, R.string.main_block_status_desc, Icons.Default.PowerSettingsNew)
    MainItem.EXIT_NODE -> MainItemSpec(R.string.nb_exit_node, R.string.main_block_exit_node_desc, Icons.Default.Public)
    MainItem.PROXY -> MainItemSpec(R.string.nb_socks_title, R.string.main_block_proxy_desc, Icons.Default.Lan)

    MainItem.PEERS -> MainItemSpec(R.string.nb_menu_peers, R.string.tile_peers_desc, Icons.Default.Devices)
    MainItem.NETWORKS -> MainItemSpec(R.string.nb_menu_networks, R.string.tile_networks_desc, Icons.Default.Hub)
    MainItem.DNS -> MainItemSpec(R.string.nb_menu_dns, R.string.tile_dns_desc, Icons.Default.Dns)
    MainItem.PUBLISH -> MainItemSpec(R.string.nb_expose_title, R.string.tile_publish_desc, Icons.Default.Public)
    MainItem.DIAGNOSTICS -> MainItemSpec(R.string.nb_menu_diagnostics, R.string.tile_diagnostics_desc, Icons.Default.MonitorHeart)
    MainItem.SETTINGS -> MainItemSpec(R.string.menu_settings, R.string.tile_settings_desc, Icons.Default.Settings)
    MainItem.LOGS -> MainItemSpec(R.string.logs_title, R.string.tile_logs_desc, Icons.AutoMirrored.Filled.List)
    MainItem.ADMIN -> MainItemSpec(R.string.tile_admin, R.string.tile_admin_desc, Icons.Default.AdminPanelSettings)

    MainItem.ACCOUNTS -> MainItemSpec(R.string.nb_accounts_title, R.string.tile_accounts_desc, Icons.Default.SwitchAccount)
    MainItem.VPN -> MainItemSpec(R.string.tile_vpn, R.string.tile_vpn_desc, Icons.Default.VpnKey)

    MainItem.TUNNEL -> MainItemSpec(R.string.settings_cat_tunnel, R.string.tile_tunnel_desc, Icons.Default.VpnLock)
    MainItem.APP_SPLIT -> MainItemSpec(R.string.title_activity_tun_excluded_apps, R.string.tile_app_split_desc, Icons.Default.Apps)
    MainItem.PROXIES -> MainItemSpec(R.string.settings_cat_proxies, R.string.tile_proxies_desc, Icons.Default.SettingsEthernet)
    MainItem.DNS_SETTINGS -> MainItemSpec(R.string.tile_dns_settings, R.string.tile_dns_settings_desc, Icons.Default.Dns)
    MainItem.PERMISSIONS -> MainItemSpec(R.string.perm_title, R.string.tile_permissions_desc, Icons.Default.VerifiedUser)
}

@StringRes
internal fun MainItem.Group.title(): Int = when (this) {
    MainItem.Group.BLOCK -> R.string.main_layout_group_blocks
    MainItem.Group.SCREEN -> R.string.main_layout_group_screens
    MainItem.Group.SHORTCUT -> R.string.main_layout_group_shortcuts
    MainItem.Group.SETTING -> R.string.main_layout_group_settings
}

/**
 * The screen a tile opens, for the tiles that open one. The others act on the main screen
 * itself — the account sheet, VPN mode — and MainScreen answers for those.
 */
internal fun MainItem.screen(context: Context): Intent? = when (this) {
    MainItem.PEERS -> Intent(context, PeersActivity::class.java)
    MainItem.NETWORKS -> Intent(context, NetworksActivity::class.java)
    MainItem.DNS -> Intent(context, DnsActivity::class.java)
    MainItem.PUBLISH -> Intent(context, ExposeActivity::class.java)
    MainItem.DIAGNOSTICS -> DiagnosticsActivity.intent(context)
    MainItem.SETTINGS -> Intent(context, SettingsActivity::class.java)
    MainItem.LOGS -> Intent(context, LogsActivity::class.java)
    MainItem.ADMIN -> io.github.bropines.birdsocks.admin.AdminPanel.intent(context)
    MainItem.TUNNEL -> SettingsSections.intent(context, SettingsSections.TUNNEL)
    MainItem.APP_SPLIT -> Intent(context, TunExcludedAppsActivity::class.java)
    MainItem.PROXIES -> SettingsSections.intent(context, SettingsSections.PROXIES)
    MainItem.DNS_SETTINGS -> SettingsSections.intent(context, SettingsSections.DNS)
    MainItem.PERMISSIONS -> Intent(context, PermissionsActivity::class.java)
    else -> null
}

/** What this build offers of the main screen's items: Tunnel mode's own only where the build has it. */
internal val mainItemsOffered: (MainItem) -> Boolean = { !it.needsTunnel || TunnelSection.AVAILABLE }
