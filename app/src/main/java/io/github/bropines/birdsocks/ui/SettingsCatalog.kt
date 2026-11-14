package io.github.bropines.birdsocks.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.birdsocks.R

/*
 * Everything Settings holds, as the search knows it: every section, and every row, switch,
 * choice and action in a section — what it is called, what it does in one line, the words
 * people look for it by, where it lives, and when it is there at all.
 *
 * A row is anchored by the title it shows: the rows of the settings family mark themselves
 * with it (Modifier.settingAnchor, ui/SettingsSearchUi.kt), so an entry here needs nothing
 * added to the section to be opened on its row. A block that is no such row — a group of
 * chips, a button — carries a settingAnchor with its title where it is laid out.
 *
 * The catalogue is kept whole by a test, not by memory (SettingsCatalogTest): a title that
 * a settings source hands to a row and that has no entry here fails the build, and so does
 * an entry whose row is gone, or one without keywords in English and in Russian. Adding a
 * setting therefore means adding its entry — and its search_kw_ string, twice.
 */

/** A section of Settings, by the id SettingsActivity opens it with ([SettingsSections]). */
enum class SettingsPage(val id: String, @param:StringRes val title: Int, val icon: ImageVector) {
    APPEARANCE(SettingsSections.APPEARANCE, R.string.settings_cat_appearance, Icons.Default.Palette),
    ACCOUNT(SettingsSections.ACCOUNT, R.string.settings_cat_account, Icons.Default.AccountCircle),
    TUNNEL(SettingsSections.TUNNEL, R.string.settings_cat_tunnel, Icons.Default.VpnLock),
    PROXIES(SettingsSections.PROXIES, R.string.settings_cat_proxies, Icons.Default.Lan),
    DNS(SettingsSections.DNS, R.string.settings_cat_dns, Icons.Default.Dns),
    ACCESS(SettingsSections.ACCESS, R.string.settings_cat_access, Icons.Default.Shield),
    CONNECTION(SettingsSections.CONNECTION, R.string.settings_cat_connection, Icons.Default.Cable),
    BACKGROUND(SettingsSections.BACKGROUND, R.string.settings_cat_background, Icons.Default.Bolt),
    BACKUP(SettingsSections.BACKUP, R.string.settings_cat_backup, Icons.Default.SettingsBackupRestore),
    AUTOMATION(SettingsSections.AUTOMATION, R.string.settings_cat_automation, Icons.Default.SmartButton),
    DIAGNOSTICS(SettingsSections.DIAGNOSTICS, R.string.settings_cat_diagnostics, Icons.Default.BugReport),
    ABOUT(SettingsSections.ABOUT, R.string.nb_settings_about, Icons.Default.Info),
}

/**
 * What has to be so for a row to be in its section. While it is not, a result still finds the
 * setting, says [reason], and opens the row that brings it ([broughtBy], an entry's id) — or,
 * with nothing to bring it, the section itself. Without a [reason] there is nothing the user
 * can do about it here: the entry is not offered.
 */
enum class Need(@param:StringRes val reason: Int = 0, val broughtBy: String? = null) {
    /** The profile's own settings, which only a running daemon reads and writes. */
    DAEMON(R.string.search_need_daemon),
    /** VPN or Root mode. */
    TUN_MODE(R.string.search_need_tun, "tunnel_mode"),
    /** VPN mode through Android's slot: the engines Root does not have. */
    TUN_VPN(R.string.search_need_tun_vpn, "tunnel_mode"),
    /** The VPN through the proxy engine (hev-socks5-tunnel). */
    TUN_SOCKS(R.string.search_need_tun_socks, "tun_engine"),
    /** Root mode. */
    TUN_ROOT(R.string.search_need_tun_root, "tunnel_mode"),
    CONTROL_PROXY(R.string.search_need_control_proxy, "control_mode"),
    CONTROL_BYEDPI(R.string.search_need_control_byedpi, "control_mode"),
    AUTOMATION(R.string.search_need_automation, "automation_enable"),
    /** The palette, which Material You's colours replace. */
    STATIC_COLORS(R.string.search_need_static_colors, "dynamic_color"),
    /** Material You: Android 12 and later. */
    ANDROID_12,
    /** A build that updates itself from GitHub. */
    SELF_UPDATE,
}

/** What is so on this device right now: the answers to [Need]. */
fun interface SettingsFacts {
    fun met(need: Need): Boolean
}

/**
 * One thing the search finds. [anchor] is the title of the row to open on — the entry's own
 * [title] unless the row shows another — and 0 for a section. [summary] is the one line a
 * result shows; [help], where there is more to say, is only searched. [card] is the card the
 * row is on, for the result's "section › card".
 */
class SettingEntry internal constructor(
    val id: String,
    val page: SettingsPage,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:StringRes val keywords: Int,
    @param:StringRes val card: Int = 0,
    @param:StringRes val anchor: Int = title,
    @param:StringRes val help: Int = 0,
    val needs: Need? = null,
    /** Offered before anything is typed. */
    val suggested: Boolean = false,
) {
    val isPage: Boolean get() = anchor == 0
}

/**
 * Where an entry is now, and how to get there: the section to open ([section]), the titles
 * that lead to it ([trail]), the row to bring into view and mark ([anchor], 0 for a section).
 * [reason] is non-zero when the entry's own row is not in the section now: [anchor] is then
 * the row that brings it, or 0. [fallback] is a row to mark instead, should [anchor] turn out
 * not to be there after all.
 */
class SettingPlace(
    val entry: SettingEntry,
    val section: String,
    val trail: List<Int>,
    @param:StringRes val anchor: Int,
    @param:StringRes val fallback: Int,
    @param:StringRes val reason: Int,
)

object SettingsCatalog {

    val entries: List<SettingEntry> = buildList {
        // ─── The sections themselves ─────────────────────────────────────────────────────
        fun page(page: SettingsPage, summary: Int, keywords: Int, suggested: Boolean = false) =
            add(SettingEntry(page.id, page, page.title, summary, keywords, anchor = 0, suggested = suggested))
        page(SettingsPage.APPEARANCE, R.string.settings_cat_appearance_desc, R.string.search_kw_appearance)
        page(SettingsPage.ACCOUNT, R.string.settings_cat_account_desc, R.string.search_kw_account)
        page(SettingsPage.TUNNEL, R.string.settings_cat_tunnel_desc, R.string.search_kw_tunnel, suggested = true)
        page(SettingsPage.PROXIES, R.string.settings_cat_proxies_desc, R.string.search_kw_proxies)
        page(SettingsPage.DNS, R.string.settings_cat_dns_desc, R.string.search_kw_dns)
        page(SettingsPage.ACCESS, R.string.settings_cat_access_desc, R.string.search_kw_access)
        page(SettingsPage.CONNECTION, R.string.settings_cat_connection_desc, R.string.search_kw_connection)
        page(SettingsPage.BACKGROUND, R.string.settings_cat_background_desc, R.string.search_kw_background)
        page(SettingsPage.BACKUP, R.string.settings_cat_backup_desc, R.string.search_kw_backup, suggested = true)
        page(SettingsPage.AUTOMATION, R.string.settings_cat_automation_desc, R.string.search_kw_automation)
        page(SettingsPage.DIAGNOSTICS, R.string.settings_cat_diagnostics_desc, R.string.search_kw_diagnostics)
        page(SettingsPage.ABOUT, R.string.settings_cat_about_desc, R.string.search_kw_about)

        fun row(
            id: String, page: SettingsPage, title: Int, summary: Int, keywords: Int, card: Int = 0,
            anchor: Int = title, help: Int = 0, needs: Need? = null, suggested: Boolean = false,
        ) = add(SettingEntry(id, page, title, summary, keywords, card, anchor, help, needs, suggested))

        // ─── Appearance & language ───────────────────────────────────────────────────────
        val look = R.string.settings_sect_personalization
        row("theme_mode", SettingsPage.APPEARANCE, R.string.settings_theme_title, R.string.search_sum_theme, R.string.search_kw_theme_mode, look)
        row("language", SettingsPage.APPEARANCE, R.string.settings_lang_title, R.string.search_sum_language, R.string.search_kw_language, look, suggested = true)
        row("palette", SettingsPage.APPEARANCE, R.string.settings_palette_title, R.string.search_sum_palette, R.string.search_kw_palette, look, needs = Need.STATIC_COLORS)
        row("dynamic_color", SettingsPage.APPEARANCE, R.string.settings_dynamic_color_title, R.string.settings_dynamic_color_desc, R.string.search_kw_dynamic_color, look, needs = Need.ANDROID_12)
        row("amoled", SettingsPage.APPEARANCE, R.string.settings_amoled_black_title, R.string.settings_amoled_black_desc, R.string.search_kw_amoled, look)
        row("main_layout", SettingsPage.APPEARANCE, R.string.settings_main_layout_title, R.string.settings_main_layout_desc, R.string.search_kw_main_layout, R.string.settings_main_screen)
        row("main_hidden", SettingsPage.APPEARANCE, R.string.main_hidden_title, R.string.search_sum_main_hidden, R.string.search_kw_main_hidden, R.string.settings_main_screen)
        row("app_icon", SettingsPage.APPEARANCE, R.string.icon_title, R.string.search_sum_app_icon, R.string.search_kw_app_icon, look, help = R.string.icon_note)

        // ─── Account & device ────────────────────────────────────────────────────────────
        val account = R.string.settings_card_account
        // The row is titled with the account's name; it carries this title as an anchor of its own.
        row("accounts", SettingsPage.ACCOUNT, R.string.nb_accounts_title, R.string.settings_account_hint, R.string.search_kw_accounts, account, suggested = true)
        row("device_name", SettingsPage.ACCOUNT, R.string.nb_settings_device_name, R.string.search_sum_device_name, R.string.search_kw_device_name, account, help = R.string.nb_settings_device_name_desc)
        row("management_server", SettingsPage.ACCOUNT, R.string.nb_settings_server, R.string.search_sum_management_server, R.string.search_kw_management_server, account, needs = Need.DAEMON)
        row("logout", SettingsPage.ACCOUNT, R.string.nb_settings_logout, R.string.nb_settings_logout_desc, R.string.search_kw_logout, account)
        val network = R.string.settings_card_network
        row("client_routes", SettingsPage.ACCOUNT, R.string.nb_settings_client_routes, R.string.nb_settings_client_routes_desc, R.string.search_kw_client_routes, network, needs = Need.DAEMON)
        row("server_routes", SettingsPage.ACCOUNT, R.string.nb_settings_server_routes, R.string.nb_settings_server_routes_desc, R.string.search_kw_server_routes, network, needs = Need.DAEMON)
        row("psk", SettingsPage.ACCOUNT, R.string.nb_settings_psk, R.string.nb_settings_psk_desc, R.string.search_kw_psk, network, needs = Need.DAEMON)

        // ─── Tunnel mode ─────────────────────────────────────────────────────────────────
        // The chips' card is titled only "Mode", which says nothing in a list of results.
        row("tunnel_mode", SettingsPage.TUNNEL, R.string.search_title_tunnel_mode, R.string.search_sum_tunnel_mode, R.string.search_kw_tunnel_mode, anchor = R.string.nb_tun_mode_title, help = R.string.nb_tun_mode_vpn_desc, suggested = true)
        val vpn = R.string.nb_tun_card
        row("tun_engine", SettingsPage.TUNNEL, R.string.nb_tun_engine_title, R.string.search_sum_tun_engine, R.string.search_kw_tun_engine, vpn, help = R.string.nb_tun_engine_socks_desc, needs = Need.TUN_VPN)
        row("tun_root_lan", SettingsPage.TUNNEL, R.string.nb_tun_root_lan_title, R.string.nb_tun_root_lan_desc, R.string.search_kw_tun_root_lan, R.string.nb_tun_engine_root, needs = Need.TUN_ROOT)
        row("tun_root_take", SettingsPage.TUNNEL, R.string.nb_tun_root_take_title, R.string.nb_tun_root_take_desc, R.string.search_kw_tun_root_take, R.string.nb_tun_engine_root, needs = Need.TUN_ROOT)
        row("tun_route_all", SettingsPage.TUNNEL, R.string.nb_tun_route_all_title, R.string.nb_tun_route_all_desc, R.string.search_kw_tun_route_all, vpn, needs = Need.TUN_MODE)
        row("tun_ipv6", SettingsPage.TUNNEL, R.string.nb_tun_ipv6_title, R.string.nb_tun_ipv6_desc, R.string.search_kw_tun_ipv6, vpn, needs = Need.TUN_MODE)
        row("tun_excluded_apps", SettingsPage.TUNNEL, R.string.nb_tun_apps_title, R.string.search_sum_tun_excluded_apps, R.string.search_kw_tun_excluded_apps, vpn, needs = Need.TUN_MODE)
        row("tun_excluded_cidrs", SettingsPage.TUNNEL, R.string.nb_tun_cidrs_title, R.string.nb_tun_cidrs_desc, R.string.search_kw_tun_excluded_cidrs, vpn, needs = Need.TUN_MODE)
        row("tun_address", SettingsPage.TUNNEL, R.string.nb_tun_address_title, R.string.nb_tun_address_desc, R.string.search_kw_tun_address, vpn, needs = Need.TUN_SOCKS)

        // ─── Local proxies ───────────────────────────────────────────────────────────────
        val socks = R.string.nb_socks_title
        row("socks_address", SettingsPage.PROXIES, R.string.nb_settings_socks_address, R.string.search_sum_socks_address, R.string.search_kw_socks_address, socks, help = R.string.nb_settings_loopback_desc)
        row("socks_username", SettingsPage.PROXIES, R.string.nb_settings_socks_user, R.string.search_sum_socks_username, R.string.search_kw_socks_username, socks, help = R.string.nb_settings_socks_auth_desc)
        row("socks_password", SettingsPage.PROXIES, R.string.nb_settings_socks_pass, R.string.search_sum_socks_password, R.string.search_kw_socks_password, socks, help = R.string.nb_settings_socks_auth_desc)
        row("socks_link", SettingsPage.PROXIES, R.string.settings_sagernet_copy, R.string.search_sum_socks_link, R.string.search_kw_socks_link, socks)
        row("socks_lan", SettingsPage.PROXIES, R.string.nb_settings_socks_lan, R.string.nb_settings_socks_lan_desc, R.string.search_kw_socks_lan, R.string.settings_sect_lan)

        // ─── DNS ─────────────────────────────────────────────────────────────────────────
        val netbirdDns = R.string.nb_settings_dns
        row("netbird_dns", SettingsPage.DNS, R.string.nb_settings_dns, R.string.nb_settings_dns_desc, R.string.search_kw_netbird_dns, needs = Need.DAEMON)
        row("dns_labels", SettingsPage.DNS, R.string.nb_settings_dns_labels, R.string.nb_settings_dns_labels_desc, R.string.search_kw_dns_labels, netbirdDns, needs = Need.DAEMON)
        val dnsProxy = R.string.nb_settings_dns_proxy
        row("dns_proxy", SettingsPage.DNS, R.string.nb_settings_dns_proxy, R.string.nb_settings_dns_proxy_desc, R.string.search_kw_dns_proxy)
        row("dns_proxy_address", SettingsPage.DNS, R.string.nb_settings_dns_proxy_address, R.string.search_sum_dns_proxy_address, R.string.search_kw_dns_proxy_address, dnsProxy)
        row("dns_upstream", SettingsPage.DNS, R.string.nb_settings_dns_upstream, R.string.nb_settings_dns_upstream_desc, R.string.search_kw_dns_upstream, dnsProxy)
        val dnsTools = R.string.settings_sect_tools
        row("dns_screen", SettingsPage.DNS, R.string.nb_menu_dns, R.string.settings_link_dns_desc, R.string.search_kw_dns_screen, dnsTools)

        // ─── Access to this phone ────────────────────────────────────────────────────────
        val inbound = R.string.settings_card_inbound
        row("inbound_access", SettingsPage.ACCESS, R.string.nb_settings_inbound, R.string.nb_settings_inbound_desc, R.string.search_kw_inbound_access, inbound)
        row("block_inbound", SettingsPage.ACCESS, R.string.nb_settings_block_inbound, R.string.nb_settings_block_inbound_desc, R.string.search_kw_block_inbound, inbound, needs = Need.DAEMON)
        row("publish", SettingsPage.ACCESS, R.string.nb_expose_title, R.string.settings_link_publish_desc, R.string.search_kw_publish, inbound)

        // ─── Connection ──────────────────────────────────────────────────────────────────
        val peers = R.string.settings_card_peers
        row("lazy_connection", SettingsPage.CONNECTION, R.string.nb_settings_lazy, R.string.nb_settings_lazy_desc, R.string.search_kw_lazy_connection, peers)
        row("relay_quic", SettingsPage.CONNECTION, R.string.nb_settings_quic, R.string.nb_settings_quic_desc, R.string.search_kw_relay_quic, peers)
        row("force_relay", SettingsPage.CONNECTION, R.string.nb_settings_force_relay, R.string.nb_settings_force_relay_desc, R.string.search_kw_force_relay, peers)
        val server = R.string.settings_card_server_connection
        row("control_mode", SettingsPage.CONNECTION, R.string.settings_card_server_connection, R.string.search_sum_control_mode, R.string.search_kw_control_mode, help = R.string.cp_desc, suggested = true)
        row("control_proxy_host", SettingsPage.CONNECTION, R.string.cp_host, R.string.search_sum_control_proxy_host, R.string.search_kw_control_proxy_host, server, needs = Need.CONTROL_PROXY)
        row("control_proxy_port", SettingsPage.CONNECTION, R.string.cp_port, R.string.search_sum_control_proxy_port, R.string.search_kw_control_proxy_port, server, needs = Need.CONTROL_PROXY)
        row("control_proxy_user", SettingsPage.CONNECTION, R.string.cp_user, R.string.cp_auth_desc, R.string.search_kw_control_proxy_user, server, needs = Need.CONTROL_PROXY)
        row("control_proxy_pass", SettingsPage.CONNECTION, R.string.cp_pass, R.string.cp_auth_desc, R.string.search_kw_control_proxy_pass, server, needs = Need.CONTROL_PROXY)
        row("byedpi_flags", SettingsPage.CONNECTION, R.string.cp_byedpi_flags, R.string.cp_byedpi_flags_desc, R.string.search_kw_byedpi_flags, server, needs = Need.CONTROL_BYEDPI)
        row("byedpi_ipv4", SettingsPage.CONNECTION, R.string.cp_byedpi_ipv4, R.string.cp_byedpi_ipv4_desc, R.string.search_kw_byedpi_ipv4, server, needs = Need.CONTROL_BYEDPI)
        val packets = R.string.settings_card_packets
        row("ipv6", SettingsPage.CONNECTION, R.string.nb_settings_ipv6, R.string.nb_settings_ipv6_desc, R.string.search_kw_ipv6, packets, needs = Need.DAEMON)
        row("mtu", SettingsPage.CONNECTION, R.string.nb_settings_mtu, R.string.nb_settings_mtu_desc, R.string.search_kw_mtu, packets, needs = Need.DAEMON)

        // ─── Background & permissions ────────────────────────────────────────────────────
        val background = R.string.nb_settings_background
        row("event_notifications", SettingsPage.BACKGROUND, R.string.nb_settings_event_notifications, R.string.nb_settings_event_notifications_desc, R.string.search_kw_event_notifications, background)
        row("autostart", SettingsPage.BACKGROUND, R.string.nb_settings_boot, R.string.nb_settings_boot_desc, R.string.search_kw_autostart, background)
        row("hide_recents", SettingsPage.BACKGROUND, R.string.settings_hide_from_recents, R.string.settings_hide_from_recents_desc, R.string.search_kw_hide_recents, background)
        row("permissions", SettingsPage.BACKGROUND, R.string.nb_settings_permissions, R.string.nb_settings_permissions_desc, R.string.search_kw_permissions, background, suggested = true)

        // ─── Backup ──────────────────────────────────────────────────────────────────────
        val save = R.string.backup_card_save
        row("backup_settings", SettingsPage.BACKUP, R.string.backup_settings_title, R.string.backup_settings_desc, R.string.search_kw_backup_settings, save)
        row("backup_full", SettingsPage.BACKUP, R.string.backup_full_title, R.string.backup_full_desc, R.string.search_kw_backup_full, save)
        row("restore", SettingsPage.BACKUP, R.string.backup_restore_title, R.string.backup_restore_desc, R.string.search_kw_restore, R.string.backup_card_restore)

        // ─── Automation ──────────────────────────────────────────────────────────────────
        val automation = R.string.automation_title
        row("automation_enable", SettingsPage.AUTOMATION, R.string.automation_enable, R.string.automation_enable_desc, R.string.search_kw_automation_enable, automation)
        row("automation_token", SettingsPage.AUTOMATION, R.string.automation_token, R.string.search_sum_automation_token, R.string.search_kw_automation_token, automation, help = R.string.automation_token_desc, needs = Need.AUTOMATION)
        row("automation_docs", SettingsPage.AUTOMATION, R.string.automation_docs, R.string.automation_docs_desc, R.string.search_kw_automation_docs, automation)

        // ─── Diagnostics & developer ─────────────────────────────────────────────────────
        val developer = R.string.settings_card_developer
        row("log_level", SettingsPage.DIAGNOSTICS, R.string.nb_settings_log_level, R.string.search_sum_log_level, R.string.search_kw_log_level, developer)
        row("extra_env", SettingsPage.DIAGNOSTICS, R.string.nb_settings_env, R.string.nb_settings_env_desc, R.string.search_kw_extra_env, developer)
        row("remote_jobs", SettingsPage.DIAGNOSTICS, R.string.nb_settings_remote_jobs, R.string.nb_settings_remote_jobs_desc, R.string.search_kw_remote_jobs, developer, needs = Need.DAEMON)
        val tools = R.string.settings_sect_tools
        row("diagnostics_screen", SettingsPage.DIAGNOSTICS, R.string.nb_menu_diagnostics, R.string.settings_link_diagnostics_desc, R.string.search_kw_diagnostics_screen, tools)
        row("logs", SettingsPage.DIAGNOSTICS, R.string.logs_title, R.string.settings_link_logs_desc, R.string.search_kw_logs, tools)

        // ─── About ───────────────────────────────────────────────────────────────────────
        val about = R.string.nb_settings_about
        row("about_dialog", SettingsPage.ABOUT, R.string.about_open, R.string.about_open_desc, R.string.search_kw_about_dialog, about)
        row("update_on_launch", SettingsPage.ABOUT, R.string.update_check_on_launch, R.string.update_check_on_launch_desc, R.string.search_kw_update_on_launch, about, needs = Need.SELF_UPDATE)
        row("update_now", SettingsPage.ABOUT, R.string.update_check_now, R.string.update_check_now_desc, R.string.search_kw_update_now, about, needs = Need.SELF_UPDATE)
        row("docs", SettingsPage.ABOUT, R.string.nb_about_docs, R.string.search_sum_docs, R.string.search_kw_docs, about)
        row("source", SettingsPage.ABOUT, R.string.nb_about_source, R.string.search_sum_source, R.string.search_kw_source, about)
        row("donate", SettingsPage.ABOUT, R.string.about_donate, R.string.search_sum_donate, R.string.search_kw_donate, about)
        row("licenses", SettingsPage.ABOUT, R.string.nb_about_licenses, R.string.nb_about_licenses_desc, R.string.search_kw_licenses, about)
    }

    private val byId: Map<String, SettingEntry> = entries.associateBy { it.id }

    fun byId(id: String?): SettingEntry? = byId[id]

    /**
     * Where [entry] is with [facts] as they stand; null when it is not offered. [shown] is
     * whether a section is in the hub at all (Tunnel mode only while it exists).
     */
    fun place(entry: SettingEntry, facts: SettingsFacts, shown: (SettingsPage) -> Boolean = { true }): SettingPlace? {
        val page = entry.page
        if (!shown(page)) return null
        val trail = buildList {
            if (entry.isPage) {
                add(R.string.menu_settings)
            } else {
                add(page.title)
                if (entry.card != 0) add(entry.card)
            }
        }

        val need = entry.needs
        val bringer = need?.broughtBy?.let(::byId)
        if (need != null && !facts.met(need)) {
            if (need.reason == 0) return null
            // Nothing brings it from here: the section is opened, and the result says when the row is there.
            val there = bringer?.let { place(it, facts, shown) }
                ?: return SettingPlace(entry, page.id, trail, 0, 0, need.reason)
            return SettingPlace(entry, there.section, trail, there.anchor, there.fallback, need.reason)
        }
        // The facts are read from what is stored; the section decides for itself. Should the row not
        // be there after all, the row that brings it is marked — when it is in the same section.
        val fallback = bringer?.let { place(it, facts, shown) }?.takeIf { it.section == page.id && it.reason == 0 }?.anchor ?: 0
        return SettingPlace(entry, page.id, trail, entry.anchor, fallback, 0)
    }
}
