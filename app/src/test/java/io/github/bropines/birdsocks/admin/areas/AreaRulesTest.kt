package io.github.bropines.birdsocks.admin.areas

import io.github.bropines.birdsocks.admin.api.ApiEvent
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessToken
import io.github.bropines.birdsocks.admin.api.ApiRoles
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.areas.events.EventRules
import io.github.bropines.birdsocks.admin.areas.events.MetaRow
import io.github.bropines.birdsocks.admin.areas.idp.IdentityProviderChanges
import io.github.bropines.birdsocks.admin.areas.proxy.ProxyChanges
import io.github.bropines.birdsocks.admin.areas.settings.AccountSettingsChanges
import io.github.bropines.birdsocks.admin.areas.settings.SettingsInput
import io.github.bropines.birdsocks.admin.areas.streaming.EventStreamChanges
import io.github.bropines.birdsocks.admin.areas.users.TokenRules
import io.github.bropines.birdsocks.admin.areas.users.UserLock
import io.github.bropines.birdsocks.admin.areas.users.UserRules
import io.github.bropines.birdsocks.admin.areas.users.UserStatus
import io.github.bropines.birdsocks.admin.api.ApiAccountExtraSettings
import io.github.bropines.birdsocks.admin.api.ApiAccountSettings
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** What the Users, Events, Settings, Streaming, IdP and Proxy areas decide without a screen. */
class AreaRulesTest {

    private val owner = ApiUser(id = "o", name = "Olga", role = ApiRoles.OWNER, status = "active")
    private val admin = ApiUser(id = "a", name = "Anna", role = ApiRoles.ADMIN, status = "active")
    private val me = ApiUser(id = "me", name = "Me", role = ApiRoles.ADMIN, isCurrent = true)

    // --- users ---

    @Test
    fun statusReadsPendingFirstThenBlocked() {
        assertEquals(UserStatus.PENDING, UserRules.status(admin.copy(pendingApproval = true, isBlocked = true)))
        assertEquals(UserStatus.BLOCKED, UserRules.status(admin.copy(isBlocked = true)))
        assertEquals(UserStatus.BLOCKED, UserRules.status(admin.copy(status = ApiUser.STATUS_BLOCKED)))
        assertEquals(UserStatus.INVITED, UserRules.status(admin.copy(status = ApiUser.STATUS_INVITED)))
        assertEquals(UserStatus.ACTIVE, UserRules.status(admin))
    }

    @Test
    fun theLastOwnerIsNeverDemotedBlockedOrDeleted() {
        val all = listOf(owner, admin, me)
        assertTrue(UserRules.isLastOwner(owner, all))
        assertEquals(UserLock.LAST_OWNER, UserRules.lock(owner, all, "me"))
        assertFalse(UserRules.canSetRole(owner, ApiRoles.ADMIN, all, "me"))
        assertFalse(UserRules.canBlockOrDelete(owner, all, "me"))
        // A second owner (a service user does not count) frees the first.
        val two = all + owner.copy(id = "o2")
        assertTrue(UserRules.isLastOwner(owner, all + owner.copy(id = "svc", isServiceUser = true)))
        assertTrue(UserRules.canSetRole(owner, ApiRoles.ADMIN, two, "me"))
        assertTrue(UserRules.canBlockOrDelete(owner, two, "me"))
    }

    @Test
    fun yourOwnUserIsNeverOffered() {
        val all = listOf(owner, admin, me)
        assertEquals(UserLock.OWN, UserRules.lock(me, all, "me"))
        assertEquals(UserLock.OWN, UserRules.lock(admin, all, "a"))
        assertFalse(UserRules.canSetRole(me, ApiRoles.USER, all, "me"))
        assertFalse(UserRules.canBlockOrDelete(me, all, null))
        assertTrue(UserRules.canSetRole(admin, ApiRoles.USER, all, "me"))
        assertFalse(UserRules.canSetRole(admin, ApiRoles.ADMIN, all, "me"))
    }

    @Test
    fun ownerTransferAndRoleChoices() {
        assertTrue(UserRules.isOwnerTransfer(ApiRoles.ADMIN, ApiRoles.OWNER))
        assertFalse(UserRules.isOwnerTransfer(ApiRoles.OWNER, ApiRoles.OWNER))
        assertFalse(UserRules.isOwnerTransfer(ApiRoles.USER, ApiRoles.ADMIN))
        assertEquals(ApiRoles.KNOWN + "custom", UserRules.roleChoices("custom"))
        assertFalse(ApiRoles.OWNER in UserRules.grantableRoles)
    }

    // --- tokens ---

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun stamp(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    @Test
    fun expiryInDays() {
        val soon = ApiPersonalAccessToken("t1", expirationDate = stamp(now + 3 * day + 1000))
        val later = ApiPersonalAccessToken("t2", expirationDate = stamp(now + 40 * day))
        val gone = ApiPersonalAccessToken("t3", expirationDate = stamp(now - day / 2))
        val never = ApiPersonalAccessToken("t4")
        assertEquals(3, TokenRules.daysLeft(soon, now))
        assertTrue(TokenRules.expiringSoon(soon, now))
        assertFalse(TokenRules.expiringSoon(later, now))
        assertTrue(TokenRules.isExpired(gone, now))
        assertFalse(TokenRules.expiringSoon(gone, now))
        assertEquals(-1, TokenRules.daysLeft(gone, now))
        assertNull(TokenRules.daysLeft(never, now))
        assertFalse(TokenRules.expiringSoon(never, now))
    }

    @Test
    fun theTokenInUseIsTheOneJustUsed() {
        val a = ApiPersonalAccessToken("a", lastUsed = stamp(now - 5_000))
        val b = ApiPersonalAccessToken("b", lastUsed = stamp(now - 10 * day))
        val c = ApiPersonalAccessToken("c", lastUsed = stamp(now - 30_000))
        assertEquals("a", TokenRules.guessOwn(listOf(a, b), now))
        assertNull(TokenRules.guessOwn(listOf(a, b, c), now))
        assertNull(TokenRules.guessOwn(listOf(b), now))
    }

    @Test
    fun whichTokensMayBeThePanelsOwn() {
        val t = ApiPersonalAccessToken("t")
        assertTrue(TokenRules.maybeOwn(t, "me", "me", null, tokenAuth = true))
        assertTrue(TokenRules.maybeOwn(t, "me", "me", "t", tokenAuth = true))
        assertFalse(TokenRules.maybeOwn(t, "me", "me", "other", tokenAuth = true))
        assertFalse(TokenRules.maybeOwn(t, "svc", "me", null, tokenAuth = true))
        assertFalse(TokenRules.maybeOwn(t, "me", "me", null, tokenAuth = false))
    }

    // --- events ---

    private fun ev(id: String, ts: String?, code: String = "peer.rename", who: String = "u1", meta: Map<String, String> = emptyMap()) =
        ApiEvent(id, ts, "did $code", code, who, "User $who", "", "t", meta)

    @Test
    fun eventsGroupByLocalDayNewestFirst() {
        val utc = TimeZone.getTimeZone("UTC")
        val days = EventRules.byDay(listOf(
            ev("1", "2026-11-13T23:30:00Z"), ev("2", "2026-11-14T08:00:00Z"), ev("3", null), ev("4", "2026-11-14T09:00:00Z"),
        ), utc)
        assertEquals(listOf(20261114, 20261113, 0), days.map { it.day })
        assertEquals(listOf("4", "2"), days[0].events.map { it.id })
        // The same instant is another day two hours east.
        val east = TimeZone.getTimeZone("GMT+02:00")
        assertEquals(20261114, EventRules.dayKey(java.time.Instant.parse("2026-11-13T23:30:00Z").toEpochMilli(), east))
    }

    @Test
    fun eventFiltersAllApply() {
        val list = listOf(
            ev("1", "2026-11-14T08:00:00Z", "peer.rename", "u1", mapOf("name" to "laptop")),
            ev("2", "2026-11-14T08:00:00Z", "setupkey.add", "u2"),
            ev("3", "2026-11-14T08:00:00Z", "peer.rename", "u2", mapOf("name" to "nas")),
        )
        assertEquals(listOf("1", "3"), EventRules.filter(list, setOf("peer.rename"), null, "").map { it.id })
        assertEquals(listOf("3"), EventRules.filter(list, setOf("peer.rename"), "u2", "").map { it.id })
        assertEquals(listOf("1"), EventRules.filter(list, emptySet(), null, "laptop").map { it.id })
        assertEquals(listOf("peer.rename" to 2, "setupkey.add" to 1), EventRules.codes(list))
        assertEquals("u2", EventRules.initiators(list).first().first)
    }

    @Test
    fun metaPairsBeforeAndAfter() {
        val rows = EventRules.metaRows(mapOf("old_name" to "a", "new_name" to "b", "ip" to "1.2.3.4", "role_old" to "user", "role_new" to "admin"))
        assertEquals(listOf(MetaRow.Changed("name", "a", "b"), MetaRow.Changed("role", "user", "admin"), MetaRow.Plain("ip", "1.2.3.4")), rows)
        assertEquals(listOf(MetaRow.Changed("name", "laptop", "work")), EventRules.metaRows(mapOf("old_name" to "laptop", "name" to "work")))
        assertEquals(listOf(MetaRow.Changed("", "x", "y")), EventRules.metaRows(mapOf("old" to "x", "new" to "y")))
        assertEquals(listOf(MetaRow.Plain("a", "1"), MetaRow.Plain("old_b", "2")), EventRules.metaRows(mapOf("old_b" to "2", "a" to "1")))
    }

    // --- account settings ---

    @Test
    fun settingsClassesFollowTheirRisk() {
        val s = AccountSettingsChanges
        assertEquals(ChangeClass.HIGH, s.classify(s.PEER_APPROVAL, JsonPrimitive(true), JsonPrimitive(false)))
        assertEquals(ChangeClass.MEDIUM, s.classify(s.PEER_APPROVAL, JsonPrimitive(false), JsonPrimitive(true)))
        assertEquals(ChangeClass.HIGH, s.classify(s.NETWORK_RANGE, JsonPrimitive("100.64.0.0/10"), JsonPrimitive("10.10.0.0/16")))
        assertEquals(ChangeClass.HIGH, s.classify(s.PEER_EXPOSE, JsonPrimitive(false), JsonPrimitive(true)))
        assertEquals(ChangeClass.MEDIUM, s.classify(s.PEER_EXPOSE, JsonPrimitive(true), JsonPrimitive(false)))
        assertEquals(ChangeClass.MEDIUM, s.classify(s.GROUPS_PROPAGATION, JsonPrimitive(true), JsonPrimitive(false)))
        assertEquals(ChangeClass.HIGH, s.classify(s.LOGIN_EXPIRATION_TIME, JsonPrimitive(86_400L), JsonPrimitive(7 * 86_400L)))
        assertEquals(ChangeClass.MEDIUM, s.classify(s.LOGIN_EXPIRATION_TIME, JsonPrimitive(7 * 86_400L), JsonPrimitive(86_400L)))
        assertEquals(ChangeClass.HIGH, s.classify(s.JWT_ALLOW, JsonArray(listOf(JsonPrimitive("staff"))), JsonArray(emptyList())))
        assertEquals(ChangeClass.HIGH, s.classify(s.LOCAL_AUTH_DISABLED, JsonPrimitive(false), JsonPrimitive(true)))
    }

    @Test
    fun everySpecIsReadAndPatchedWhereItLives() {
        val s = AccountSettingsChanges
        val json = s.json(ApiAccountSettings(networkRange = "100.64.0.0/10", extra = ApiAccountExtraSettings(peerApprovalEnabled = true)))
        assertEquals(JsonPrimitive("100.64.0.0/10"), s.current(json, s.NETWORK_RANGE))
        assertEquals(JsonPrimitive(true), s.current(json, s.PEER_APPROVAL))
        // Absent from the model's JSON (null): the shape's empty value.
        assertEquals(JsonArray(emptyList()), s.current(json, s.JWT_ALLOW))
        assertEquals(JsonPrimitive(false), s.current(JsonObject(emptyMap()), s.PEER_APPROVAL))
        val patch = s.patch(s.PEER_APPROVAL, JsonPrimitive(false))
        assertEquals(JsonPrimitive(false), patch["extra"]!!.jsonObject["peer_approval_enabled"])
        assertEquals(setOf("dns_domain"), s.patch(s.DNS_DOMAIN, JsonPrimitive("x.y")).keys)
        assertTrue(s.same(s.TRAFFIC_GROUPS, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("a")))))
        val keys = s.ALL.map { (if (it.extra) "extra." else "") + it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun typedValuesAreChecked() {
        assertTrue(SettingsInput.isCidr4("100.64.0.0/10"))
        assertFalse(SettingsInput.isCidr4("100.64.0.0"))
        assertFalse(SettingsInput.isCidr4("300.1.1.1/16"))
        assertFalse(SettingsInput.isCidr4("10.0.0.0/31"))
        assertTrue(SettingsInput.isCidr6("fd00:1234::/64"))
        assertTrue(SettingsInput.isCidr6("2001:db8:0:0:0:0:0:0/48"))
        assertFalse(SettingsInput.isCidr6("fd00::1::/64"))
        assertFalse(SettingsInput.isCidr6("fd00::/8"))
        assertTrue(SettingsInput.isDomain("netbird.cloud"))
        assertFalse(SettingsInput.isDomain("-bad.example"))
        assertFalse(SettingsInput.isDomain("a..b"))
        val s = AccountSettingsChanges
        assertNull(s.problem(s.LOGIN_EXPIRATION_TIME, JsonPrimitive(86_400L)))
        assertNotNull(s.problem(s.LOGIN_EXPIRATION_TIME, JsonPrimitive(60L)))
        assertNotNull(s.problem(s.NETWORK_RANGE, JsonPrimitive("")))
        assertNull(s.problem(s.NETWORK_RANGE_V6, JsonPrimitive("")))
    }

    // --- streaming, identity providers, proxy ---

    @Test
    fun streamSecretsAreHidden() {
        assertTrue(EventStreamChanges.isSecret("secret_access_key"))
        assertTrue(EventStreamChanges.isSecret("api_key"))
        assertTrue(EventStreamChanges.isSecret("auth_header"))
        assertFalse(EventStreamChanges.isSecret("bucket_name"))
        assertEquals("us-east-1", EventStreamChanges.shown("region", "us-east-1"))
        assertFalse(EventStreamChanges.shown("api_key", "abcd").contains("abcd"))
    }

    @Test
    fun issuerMustBeHttps() {
        assertTrue(IdentityProviderChanges.issuerOk("https://accounts.example.com"))
        assertTrue(IdentityProviderChanges.issuerOk("http://127.0.0.1:8080"))
        assertFalse(IdentityProviderChanges.issuerOk("http://idp.example.com"))
        assertFalse(IdentityProviderChanges.issuerOk("idp.example.com"))
    }

    @Test
    fun serviceJsonIsCheckedAndStrippedOfServerFields() {
        val ok = AppJson.parseToJsonElement("""{"id":"s1","name":"web","domain":"web.example.com","meta":{"status":"active"},"enabled":true}""").jsonObject
        assertNull(ProxyChanges.serviceProblem(ok))
        assertNotNull(ProxyChanges.serviceProblem(JsonObject(mapOf("name" to JsonPrimitive("x")))))
        assertNotNull(ProxyChanges.serviceProblem(null))
        assertEquals(setOf("name", "domain", "enabled"), ProxyChanges.writable(ok).keys)
        assertNull(ProxyChanges.serviceProblem(AppJson.parseToJsonElement(ProxyChanges.TEMPLATE).jsonObject.let {
            JsonObject(it + ("name" to JsonPrimitive("a")) + ("domain" to JsonPrimitive("a.b")))
        }))
        assertEquals(listOf("eu.proxy.netbird.io"), ProxyChanges.clusterAddresses(AppJson.parseToJsonElement("""[{"address":"eu.proxy.netbird.io"},{"address":""},{}]""")))
    }
}
