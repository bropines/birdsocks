package io.github.bropines.birdsocks.ui

import io.github.bropines.birdsocks.search.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What people type, against the real catalogue with its real words in English and in Russian
 * (search/SearchEngineTest has the engine on a catalogue of its own). This is the test of
 * the keywords: a query here that stops finding its setting is a list in
 * strings_settings_search_keywords.xml that lost a word.
 */
class SettingsSearchQueriesTest {

    private val languages = listOf(language(englishStrings), language(russianStrings))
    private val engine = SearchEngine(searchEntries(SettingsCatalog.entries, languages), searchStopWords(languages))

    /** What the search shows for [query]. */
    private fun found(query: String): List<String> = engine.search(query).hits.map { it.id }

    /** [id] is among the first [within] results of each query. */
    private fun assertFinds(within: Int, vararg cases: Pair<String, String>) {
        for ((query, id) in cases) {
            val ids = found(query)
            val scores = engine.search(query).hits.take(8).map { "${it.id} ${it.score} ${it.field}" }
            assertTrue("«$query» should find $id within $within, found ${ids.take(8)} — $scores", id in ids.take(within))
        }
    }

    @Test
    fun theEngineHasEveryEntryInBothLanguages() {
        assertEquals(SettingsCatalog.entries.size, searchEntries(SettingsCatalog.entries, languages).size)
        // Each entry is found by its own title, in English and in Russian.
        for (entry in SettingsCatalog.entries) {
            for (text in languages) assertTrue("${entry.id}: ${text(entry.title)} → ${found(text(entry.title)).take(4)}", entry.id in found(text(entry.title)).take(4))
        }
    }

    @Test
    fun aSettingIsFoundByItsNameInEitherLanguage() = assertFinds(
        1,
        "tunnel mode" to "tunnel", "режим туннеля" to "tunnel",
        "server connection" to "control_mode", "подключение к серверу" to "control_mode",
        "logs" to "logs", "логи" to "logs",
        "device name" to "device_name", "имя устройства" to "device_name",
        "app language" to "language", "язык приложения" to "language",
        "amoled" to "amoled", "mtu" to "mtu", "byedpi flags" to "byedpi_flags",
        "permissions" to "permissions", "разрешения" to "permissions",
        "lazy connections" to "lazy_connection", "ленивые соединения" to "lazy_connection",
        "pre-shared key" to "psk", "excluded apps" to "tun_excluded_apps",
    )

    @Test
    fun theNamesPeopleUseInsteadOfOurs() = assertFinds(
        1,
        "kill switch" to "tunnel_mode", "always on vpn" to "tunnel_mode", "magisk" to "tunnel_mode",
        "цензура" to "control_mode", "ркн" to "control_mode", "censorship" to "control_mode",
        "автозапуск" to "autostart", "start on boot" to "autostart",
        "тема" to "theme_mode", "dark mode" to "theme_mode", "тёмная тема" to "theme_mode",
        "self-hosted" to "management_server", "hostname" to "device_name",
        "hotspot" to "socks_lan", "вай фай" to "socks_lan",
        "sign out" to "logout", "разлогиниться" to "logout",
        "material you" to "dynamic_color", "чёрный фон" to "amoled",
        "tasker" to "automation", "таскер" to "automation",
        "zapret" to "byedpi_flags",
        "xiaomi" to "permissions", "оптимизация батареи" to "permissions",
        "port forwarding" to "publish", "проброс порта" to "publish",
        "nslookup" to "dns_screen", "1.1.1.1" to "dns_upstream",
        "boosty" to "donate", "github" to "source",
    )

    @Test
    fun aClientAppFindsTheProxyAndItsLink() = assertFinds(2, "nekobox" to "socks_link", "nekobox" to "proxies", "v2rayng" to "socks_link")

    @Test
    fun aWordSeveralSettingsShareOffersThemAll() {
        assertFinds(3, "vpn" to "tunnel", "vpn" to "tunnel_mode", "впн" to "tunnel_mode")
        assertFinds(3, "dpi" to "byedpi_flags", "дпи" to "byedpi_flags", "бэкап" to "backup", "бэкап" to "backup_full")
        assertFinds(5, "port" to "socks_address", "port" to "control_proxy_port", "порт" to "control_proxy_port")
        assertFinds(4, "battery" to "permissions", "батарея" to "permissions")
        assertFinds(4, "ipv6" to "tun_ipv6", "ipv6" to "ipv6", "ipv6" to "byedpi_ipv4")
        assertFinds(5, "proxy" to "proxies", "прокси" to "proxies")
        assertFinds(3, "dns" to "dns", "днс" to "dns", "root" to "tunnel_mode", "рут" to "tunnel_mode")
        assertFinds(3, "split tunneling" to "tun_excluded_apps", "split tunneling" to "tun_excluded_cidrs", "раздельное туннелирование" to "tun_excluded_apps")
        assertFinds(3, "relay" to "force_relay", "relay" to "relay_quic")
    }

    @Test
    fun aQuestionIsAnsweredByItsSubject() = assertFinds(
        3,
        "как включить впн" to "tunnel_mode",
        "how to turn on the vpn" to "tunnel_mode",
        "how do i change the socks port" to "socks_address",
        "где поменять язык" to "language",
        "как сменить тему" to "theme_mode",
        "как настроить обход блокировок" to "control_mode",
        "where are the logs" to "logs",
        "как сделать бэкап" to "backup_full",
        "can i use my own server" to "management_server",
    )

    @Test
    fun slipsOfTheFinger() = assertFinds(
        2,
        "tunel mode" to "tunnel", "autostrat" to "autostart", "автозапсук" to "autostart",
        "bakcup" to "backup", "langauge" to "language", "permisions" to "permissions", "разрещения" to "permissions",
    )

    @Test
    fun theWrongKeyboardLayout() {
        assertFinds(
            2,
            "мзт" to "tunnel_mode", "ntvf" to "theme_mode", "zpsr" to "language", "fdnjpfgecr" to "autostart", "kjub" to "logs",
        )
        assertEquals("vpn", engine.search("мзт").readAs)
    }

    @Test
    fun transliteration() {
        assertFinds(
            3,
            "avtozapusk" to "autostart", "yazyk" to "language", "tema" to "theme_mode", "logi" to "logs",
            "razresheniya" to "permissions",
        )
        // The word for "settings" itself: whatever is named with it.
        assertNotNull(found("nastroiki").firstOrNull())
    }

    @Test
    fun whatIsInsideADialogIsFoundThroughTheRowThatOpensIt() = assertFinds(
        3,
        "battery optimization" to "permissions", "generate token" to "automation_token",
        "switch account" to "accounts", "add account" to "accounts",
    )

    @Test
    fun nonsenseFindsNothing() {
        for (query in listOf("zzzzqqq", "qwfpgjluy", "ъъъъъ")) assertTrue(query + " " + found(query), found(query).isEmpty())
    }
}
