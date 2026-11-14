package io.github.bropines.birdsocks.ui

import io.github.bropines.birdsocks.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings catalogue against the settings themselves. The search finds what the catalogue
 * lists, so the catalogue must list what the sections show — and this is what holds it to that:
 * the sources of the settings sections are read, every title they hand to a row is looked up,
 * and a row without an entry fails the build with the name of the row.
 *
 * To add a setting: add its row to the section as usual, then its entry to ui/SettingsCatalog.kt
 * and its `search_kw_<id>` to both strings_settings_search_keywords.xml. To add a card in a
 * file of its own: nothing more — a file that draws settings rows is read here by itself.
 */
class SettingsCatalogTest {

    // ─── What the sources show ───────────────────────────────────────────────────────────

    /** The composables that draw a row of a settings section, and where each takes its title when it is not named. */
    private val rowFamily = mapOf(
        "SettingsSwitchItem" to 0,
        "SettingsClickableItem" to 0,
        "SettingsEditItem" to 0,
    )

    /**
     * Screens of their own that borrow the settings row. Settings links to each of them, and
     * the link is what the search finds; what is on them is not a row of Settings.
     */
    private val otherScreens = setOf(
        "ui/DnsActivity.kt",
        "ui/DiagnosticTools.kt",
        "ui/PermissionsActivity.kt",
        // The admin panel's own screens and dialogs; Settings → Account links to it.
        "admin/AdminDialogs.kt",
        "admin/AdminSetupScreen.kt",
    )

    /**
     * Titles that are not written where the row is called: the hub's sections (checked by
     * [theSectionsAreTheOnesSettingsHas]) and the account row, titled with the account's name,
     * which carries an anchor of its own.
     */
    private val titlesFromElsewhere = setOf(
        "stringResource(category.titleRes)",
        "label",
    )

    private class Shown(val title: String, val where: String)

    private val anchorCall = Regex("settingAnchor\\(stringResource\\(R\\.string\\.(\\w+)\\)")
    private val stringReference = Regex("R\\.string\\.(\\w+)")

    /** The sources that draw settings: every file with a row of the family or an anchor of its own, bar the other screens. */
    private val settingsSources: Map<String, String> by lazy {
        appSources.filter { (file, source) ->
            file !in otherScreens && file != "ui/SettingsComponents.kt" &&
                (callsOf(file, source, rowFamily.keys.toList()).isNotEmpty() || anchorCall.containsMatchIn(source))
        }
    }

    /** Every title a settings source shows on a row, and every title it anchors by hand. */
    private fun shownTitles(): List<Shown> {
        val shown = ArrayList<Shown>()
        for ((file, source) in settingsSources) {
            for (call in callsOf(file, source, rowFamily.keys.toList())) {
                val title = call.argument("title", rowFamily.getValue(call.name)) ?: error("${call.file}:${call.line}: ${call.name} without a title")
                val resource = stringReference.find(title)?.groupValues?.get(1)
                if (resource != null) shown.add(Shown(resource, "${call.file}:${call.line}"))
                else assertTrue("${call.file}:${call.line}: the title «$title» of ${call.name} is not a string resource this test can read", title in titlesFromElsewhere)
            }
            for (match in anchorCall.findAll(source)) {
                shown.add(Shown(match.groupValues[1], "$file:${source.take(match.range.first).count { it == '\n' } + 1}"))
            }
        }
        return shown
    }

    /** What is wrong between the rows the sources show and the entries of [catalogue]; empty when they agree. */
    private fun disagreements(shown: List<Shown>, catalogue: List<SettingEntry>): List<String> {
        val anchors = catalogue.filter { !it.isPage }.map { stringName(it.anchor) }.toSet()
        val everywhere = settingsSources.values.flatMap { source -> stringReference.findAll(source).map { it.groupValues[1] } }.toSet()
        return shown.filter { it.title !in anchors }.map {
            "${it.where}: the row «${it.title}» has no entry in SettingsCatalog — the search cannot find it"
        }.distinct() + catalogue.filter { !it.isPage && stringName(it.anchor) !in everywhere }.map {
            "SettingsCatalog «${it.id}»: no settings section shows «${stringName(it.anchor)}» — a result would open a section without the row"
        }
    }

    @Test
    fun everyRowOfSettingsIsInTheCatalogueAndEveryEntryIsARow() {
        val shown = shownTitles()
        // Read something at all: a scanner that finds nothing would agree with anything.
        assertTrue("only ${shown.size} rows found in ${settingsSources.keys}", shown.size > 50)
        assertTrue("SettingsActivity.kt is one of the sources", "ui/SettingsActivity.kt" in settingsSources)
        assertTrue("TunnelSection.kt is one of the sources", "ui/TunnelSection.kt" in settingsSources)
        assertEquals("", disagreements(shown, SettingsCatalog.entries).joinToString("\n"))
    }

    /** The guard has teeth: take an entry out, and it names the row that lost it. */
    @Test
    fun aRowTakenOutOfTheCatalogueFailsTheGuard() {
        val shown = shownTitles()
        for (id in listOf("autostart", "tun_ipv6", "theme_mode", "byedpi_flags", "backup_full", "tunnel_mode", "accounts", "mtu")) {
            val without = SettingsCatalog.entries.filter { it.id != id }
            val title = stringName(SettingsCatalog.byId(id)!!.anchor)
            val found = disagreements(shown, without)
            assertTrue("$id: $found", found.any { "«$title»" in it && "has no entry" in it })
        }
    }

    /** And the other way: an entry for a row that is gone is named too. */
    @Test
    fun anEntryWithoutItsRowFailsTheGuard() {
        val ghost = SettingEntry("ghost", SettingsPage.DNS, R.string.search_none, R.string.search_none, R.string.search_kw_dns)
        val found = disagreements(shownTitles(), SettingsCatalog.entries + ghost)
        assertTrue(found.toString(), found.single().startsWith("SettingsCatalog «ghost»"))
    }

    @Test
    fun aRowIsFoundHoweverItIsCalled() {
        val source = """
            fun SettingsSwitchItem(title: String) {}
            SettingsSwitchItem(stringResource(R.string.a_title), stringResource(R.string.a_desc), Icons.Default.Map, on) { on = it }
            SettingsEditItem(
                value = text("a, b"),
                title = stringResource(R.string.b_title),
                icon = Icons.Default.Map
            ) { save(it, 1) }
            SettingsClickableItem(title = stringResource(page.titleRes), subtitle = "")
        """.trimIndent()
        val calls = callsOf("x.kt", source, rowFamily.keys.toList())
        assertEquals(listOf("SettingsSwitchItem", "SettingsEditItem", "SettingsClickableItem"), calls.map { it.name })
        assertEquals(
            listOf("stringResource(R.string.a_title)", "stringResource(R.string.b_title)", "stringResource(page.titleRes)"),
            calls.map { it.argument("title", rowFamily.getValue(it.name)) }
        )
        assertEquals(listOf(2, 3, 8), calls.map { it.line })
    }

    // ─── The sections ────────────────────────────────────────────────────────────────────

    private val settingsActivity: String get() = appSources.getValue("ui/SettingsActivity.kt")

    @Test
    fun theSectionsAreTheOnesSettingsHas() {
        val declared = Regex("SettingsCategory\\(SettingsSections\\.(\\w+), R\\.string\\.(\\w+), R\\.string\\.(\\w+), Icons\\.Default\\.(\\w+)").findAll(settingsActivity).toList()
        assertEquals(SettingsPage.entries.map { it.name }.sorted(), declared.map { it.groupValues[1] }.sorted())
        for (match in declared) {
            val (name, title, summary, icon) = match.destructured
            val page = SettingsPage.valueOf(name)
            assertEquals(name, title, stringName(page.title))
            assertEquals(name, "Filled.$icon", page.icon.name)
            val entry = SettingsCatalog.byId(page.id)!!
            assertTrue("$name is a section", entry.isPage)
            assertEquals(name, summary, stringName(entry.summary))
        }
        // A link may name every section, and nothing else is one.
        assertEquals(SettingsSections.ALL, SettingsPage.entries.map { it.id }.toSet())
    }

    // ─── The entries ─────────────────────────────────────────────────────────────────────

    @Test
    fun idsAreUniqueAndFitALink() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertTrue(id, Regex("[a-z][a-z0-9_]*").matches(id))
        // No two entries of a section open on the same row.
        val rows = SettingsCatalog.entries.filter { !it.isPage }.groupBy { it.page to it.anchor }.filter { it.value.size > 1 }
        assertTrue(rows.values.map { group -> group.map { it.id } }.toString(), rows.isEmpty())
    }

    @Test
    fun everyEntryHasItsWordsInEnglishAndInRussian() {
        for (entry in SettingsCatalog.entries) {
            val keywords = stringName(entry.keywords)
            assertTrue("${entry.id}: $keywords", keywords.startsWith("search_kw_"))
            for ((language, strings) in listOf("values" to englishStrings, "values-ru" to russianStrings)) {
                val list = strings[keywords]
                assertNotNull("${entry.id}: $keywords is missing from $language", list)
                assertTrue("${entry.id}: $keywords in $language is too short a list: $list", keywordsOf(list!!).size >= 3)
            }
            // A title and a line of what it does, both real sentences and not a pattern to fill in.
            for (resource in listOf(entry.title, entry.summary)) {
                val text = englishStrings.getValue(stringName(resource))
                assertTrue("${entry.id}: ${stringName(resource)}", text.isNotBlank() && "%1$" !in text)
            }
        }
        // The Russian list is Russian: not a copy of the English one.
        val same = SettingsCatalog.entries.map { stringName(it.keywords) }.filter { englishStrings[it] == russianStrings[it] }
        assertTrue(same.toString(), same.isEmpty())
    }

    @Test
    fun theStopWordsAreInBothLanguages() {
        val english = keywordsOf(englishStrings.getValue("search_stop_words"))
        val russian = keywordsOf(russianStrings.getValue("search_stop_words"))
        assertTrue("how" in english && "change" in english)
        assertTrue("как" in russian && "включить" in russian)
        // A word a setting is named by is never a little word.
        for (word in listOf("port", "vpn", "proxy", "dns", "порт", "впн", "прокси", "обход")) assertFalse(word, word in english || word in russian)
    }

    @Test
    fun everyNeedLeadsToARowThatIsAlwaysThere() {
        for (need in Need.entries) {
            if (need.reason == 0) {
                assertNull("$need says nothing, so it brings nothing", need.broughtBy)
                continue
            }
            assertTrue("$need has a reason to show", englishStrings.getValue(stringName(need.reason)).isNotBlank())
            // Nothing brings it: the result opens the section and says why.
            if (need.broughtBy == null) continue
            // Follow what brings what; a chain that does not end is a result that opens nothing.
            var entry = SettingsCatalog.byId(need.broughtBy)
            val seen = HashSet<String>()
            while (true) {
                assertNotNull("$need is brought by «${need.broughtBy}», which is no entry", entry)
                assertTrue("$need: ${entry!!.id} needs itself", seen.add(entry.id))
                val bringer = entry.needs?.broughtBy ?: break
                entry = SettingsCatalog.byId(bringer)
            }
        }
    }

    // ─── Where an entry is now ───────────────────────────────────────────────────────────

    private val everything = SettingsFacts { true }
    private val freshInstall = SettingsFacts { it == Need.STATIC_COLORS || it == Need.ANDROID_12 || it == Need.SELF_UPDATE }

    private fun place(id: String, facts: SettingsFacts = everything): SettingPlace? =
        SettingsCatalog.place(SettingsCatalog.byId(id)!!, facts)

    private fun trail(place: SettingPlace) = place.trail.map { stringName(it) }

    @Test
    fun withEverythingOnEveryEntryIsOnItsOwnSection() {
        for (entry in SettingsCatalog.entries) {
            val place = SettingsCatalog.place(entry, everything)
            assertNotNull(entry.id, place)
            assertEquals(entry.id, entry.page.id, place!!.section)
            assertEquals(entry.id, entry.anchor, place.anchor)
            assertEquals(entry.id, 0, place.reason)
        }
        assertEquals(listOf("settings_cat_tunnel", "nb_tun_card"), trail(place("tun_ipv6")!!))
        assertEquals(listOf("menu_settings"), trail(place("tunnel")!!))
        // A section that is not in the hub has nothing to offer.
        assertNull(SettingsCatalog.place(SettingsCatalog.byId("tun_ipv6")!!, everything) { it != SettingsPage.TUNNEL })
    }

    @Test
    fun aRowThatIsNotInItsSectionOpensTheRowThatBringsIt() {
        // A fresh install runs as a proxy: the VPN card is not in the section.
        val ipv6 = place("tun_ipv6", freshInstall)!!
        assertEquals("tunnel", ipv6.section)
        assertEquals(R.string.nb_tun_mode_title, ipv6.anchor)
        assertEquals(R.string.search_need_tun, ipv6.reason)
        // Two steps away — the proxy engine's own row — still lands on the first step that is there.
        val address = place("tun_address", freshInstall)!!
        assertEquals(R.string.nb_tun_mode_title, address.anchor)
        assertEquals(R.string.search_need_tun_socks, address.reason)
        // The server connection's proxy fields are brought by its chips.
        val host = place("control_proxy_host", freshInstall)!!
        assertEquals("connection", host.section)
        assertEquals(R.string.settings_card_server_connection, host.anchor)
        // With the switch on, the row is its own, and the switch stands by should the section disagree.
        val token = place("automation_token")!!
        assertEquals(0, token.reason)
        assertEquals(R.string.automation_token, token.anchor)
        assertEquals(R.string.automation_enable, token.fallback)
    }

    @Test
    fun aProfileSettingWithoutTheDaemonOpensItsSectionAndSaysWhy() {
        val psk = place("psk", freshInstall)!!
        assertEquals("account", psk.section)
        assertEquals(0, psk.anchor)
        assertEquals(R.string.search_need_daemon, psk.reason)
        assertEquals(listOf("settings_cat_account", "settings_card_network"), trail(psk))
    }

    @Test
    fun whatTheDeviceCannotShowIsNotOffered() {
        // Not this build, not this Android: nothing to send the user to.
        assertNull(place("update_now", SettingsFacts { it != Need.SELF_UPDATE }))
        assertNull(place("dynamic_color", SettingsFacts { false }))
        // Without Material You the palette is always there.
        assertEquals(0, place("palette", SettingsFacts { it == Need.STATIC_COLORS })!!.reason)
    }

    // ─── What was found before ───────────────────────────────────────────────────────────

    @Test
    fun theLastOpenedComesFirstAndIsCounted() {
        var stored = ""
        for (id in listOf("tunnel_mode", "language", "tunnel_mode", "autostart")) stored = SettingsSearchHistory.opened(stored, id)
        assertEquals(listOf("autostart" to 1, "tunnel_mode" to 2, "language" to 1), SettingsSearchHistory.parse(stored))
        // Only so many are kept, and a damaged preference is no history.
        repeat(30) { stored = SettingsSearchHistory.opened(stored, "row$it") }
        assertEquals(12, SettingsSearchHistory.parse(stored).size)
        assertEquals(emptyList<Pair<String, Int>>(), SettingsSearchHistory.parse("nonsense;;:3;x:"))
        // A nudge among equals, and no more however often.
        assertEquals(0, SettingsSearchHistory.boost(0))
        assertEquals(SettingsSearchHistory.boost(3), SettingsSearchHistory.boost(30))
        assertTrue(SettingsSearchHistory.boost(30) < 50)
    }
}
