package io.github.bropines.birdsocks.admin

import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.areas.AdminAreas
import io.github.bropines.birdsocks.admin.home.Attention
import io.github.bropines.birdsocks.admin.home.AttentionItem
import io.github.bropines.birdsocks.admin.ui.ListFilter
import io.github.bropines.birdsocks.admin.ui.ListQuery
import io.github.bropines.birdsocks.admin.ui.ListSort
import io.github.bropines.birdsocks.admin.ui.PreviewAdminHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The panel's seams: the area registry, the deep link's pages, the list
 * query, the attention list — and the rule that screens never write.
 */
class AdminPanelStructureTest {

    // --- Registry and deep links ---

    @Test
    fun areaIdsAreUniqueAndNotPages() {
        val ids = AdminAreas.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val aliases = AdminAreas.all.flatMap { it.aliases }
        assertTrue((ids + aliases).none { it == AdminPanel.PAGE_OVERVIEW || it == AdminPanel.PAGE_SETUP || it == AdminRoute.HOME.areaId })
        assertEquals(ids.size + aliases.size, (ids + aliases).toSet().size)
    }

    @Test
    fun theOldPagesStillOpenTheirAreas() {
        assertEquals("peers", AdminAreas.forPage("peers")?.id)
        assertEquals("setup_keys", AdminAreas.forPage("keys")?.id)
        assertEquals("setup_keys", AdminAreas.forPage("SETUP_KEYS")?.id)
        assertEquals("groups", AdminAreas.forPage(" groups ")?.id)
        assertNull(AdminAreas.forPage("overview"))
        assertNull(AdminAreas.forPage(null))
        assertNull(AdminAreas.forPage("nope"))
        assertTrue(AdminPanel.isPage("setup") && AdminPanel.isPage("overview") && AdminPanel.isPage("policies"))
        assertFalse(AdminPanel.isPage("nope"))
    }

    @Test
    fun everyRequiredAreaIsRegistered() {
        val ids = AdminAreas.all.map { it.id }.toSet()
        val required = setOf(
            "peers", "groups", "setup_keys", "policies", "posture_checks", "networks", "routes", "dns", "users",
            "service_users", "events", "account_settings", "event_streaming", "identity_providers", "reverse_proxy",
        )
        assertEquals(required, ids)
        assertTrue(AdminAreas.byId("peers")!!.ready)
    }

    @Test
    fun cloudOnlyAreasHideOnSelfHosted() {
        val selfHosted = PreviewAdminHost(account = AdminAccount("p", "https://nb.example.com", "id", "https://nb.example.com/api"))
        val cloud = PreviewAdminHost()
        assertFalse(AdminAreas.byId("event_streaming")!!.visibleFor(selfHosted))
        assertTrue(AdminAreas.byId("event_streaming")!!.visibleFor(cloud))
    }

    // --- Lists ---

    private data class Row(val name: String, val online: Boolean, val tags: List<String> = emptyList())

    @Test
    fun searchWordsAndFiltersAllMustMatch() {
        val rows = listOf(Row("Office laptop", true, listOf("dev")), Row("office nas", false), Row("home phone", true))
        val online = ListFilter<Row>("online", "Online") { it.online }
        val dev = ListFilter<Row>("dev", "Dev") { "dev" in it.tags }
        val byName = ListSort<Row>("name", "Name", compareBy { it.name.lowercase() })
        fun q(query: String, active: Set<String> = emptySet()) =
            ListQuery.apply(rows, query, { listOf(it.name) + it.tags }, listOf(online, dev), active, byName).map { it.name }
        assertEquals(listOf("home phone", "Office laptop", "office nas"), q(""))
        assertEquals(listOf("Office laptop", "office nas"), q("OFFICE"))
        assertEquals(listOf("Office laptop"), q("office lap"))
        assertEquals(listOf("home phone", "Office laptop"), q("", setOf("online")))
        assertEquals(listOf("Office laptop"), q("", setOf("online", "dev")))
        assertEquals(listOf("Office laptop"), q("dev"))
    }

    // --- Attention ---

    @Test
    fun attentionListsWhatWaitsInOrder() {
        val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        val items = Attention.items(
            peers = listOf(ApiPeer(id = "p1", name = "b", approvalRequired = true), ApiPeer(id = "p2", name = "a"), ApiPeer(id = "p3", name = "a", approvalRequired = true)),
            users = listOf(ApiUser(id = "u1", email = "x@y", pendingApproval = true), ApiUser(id = "u2")),
            keys = listOf(
                ApiSetupKey(id = "soon", valid = true, expires = "2023-11-17T00:00:00Z"),
                ApiSetupKey(id = "today", valid = true, expires = "2023-11-15T01:00:00Z"),
                ApiSetupKey(id = "later", valid = true, expires = "2024-01-01T00:00:00Z"),
                ApiSetupKey(id = "revoked", valid = true, revoked = true, expires = "2023-11-16T00:00:00Z"),
                ApiSetupKey(id = "past", valid = true, expires = "2023-11-01T00:00:00Z"),
            ),
            now = now,
        )
        assertEquals(listOf("peer:p3", "peer:p1", "user:u1", "key:today", "key:soon"), items.map { it.id })
        assertEquals(0, (items[3] as AttentionItem.KeyExpiring).daysLeft)
        assertEquals(2, (items[4] as AttentionItem.KeyExpiring).daysLeft)
        assertTrue(Attention.items(null, null, null, now).isEmpty())
    }

    // --- Screens never write ---

    private val adminRoot: File by lazy {
        val candidates = listOf("src/main/java/io/github/bropines/birdsocks/admin", "app/src/main/java/io/github/bropines/birdsocks/admin")
        candidates.map(::File).first { it.isDirectory }
    }

    /**
     * Every NetbirdApi method that sends POST, PUT or DELETE may be called only
     * in a `*Changes.kt` file (inside a PlannedChange), in api/ itself or in
     * safety/. A screen that calls one would write without the gates.
     */
    @Test
    fun writeMethodsAreCalledOnlyFromChangeBuilders() {
        val api = File(adminRoot, "api/NetbirdApi.kt").readText()
        val fn = Regex("""suspend fun (\w+)\(""")
        val writes = mutableSetOf<String>()
        val starts = fn.findAll(api).toList()
        starts.forEachIndexed { i, m ->
            val body = api.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: api.length)
            if (Regex(""""(POST|PUT|DELETE)"""").containsMatchIn(body) || body.contains("rawSend(") && m.groupValues[1] != "rawSend" ||
                Regex("""=\s*(update\w+|revokeSetupKey)\(""").containsMatchIn(body)) writes += m.groupValues[1]
        }
        writes += "rawSend"
        assertTrue("found the write methods", writes.containsAll(setOf("deletePeer", "updatePeer", "createSetupKey", "revokeSetupKey", "patchAccountSettings", "approveUser")))
        assertFalse("reads are not writes", writes.any { it in setOf("peers", "peer", "groups", "rawGet", "dnsSettings", "guessOwnTokenId") })

        val call = Regex("""\.(${writes.joinToString("|")})\(""")
        val offenders = adminRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val rel = f.relativeTo(adminRoot).path
            !(rel.startsWith("api/") || rel.startsWith("safety/") || f.name.endsWith("Changes.kt"))
        }.flatMap { f ->
            f.readLines().mapIndexedNotNull { n, line -> if (call.containsMatchIn(line)) "${f.relativeTo(adminRoot)}:${n + 1}: ${line.trim()}" else null }
        }.toList()
        assertEquals("write calls outside *Changes.kt", emptyList<String>(), offenders)
    }
}
