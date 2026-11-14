package io.github.bropines.birdsocks.admin.areas

import android.app.Application
import android.content.Context
import io.github.bropines.birdsocks.admin.api.ApiAccount
import io.github.bropines.birdsocks.admin.api.ApiAccountExtraSettings
import io.github.bropines.birdsocks.admin.api.ApiAccountSettings
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.ApiIntegration
import io.github.bropines.birdsocks.admin.api.ApiPersonalAccessToken
import io.github.bropines.birdsocks.admin.api.ApiRoles
import io.github.bropines.birdsocks.admin.api.ApiService
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.ApiUserInviteCreateRequest
import io.github.bropines.birdsocks.admin.api.HttpRequest
import io.github.bropines.birdsocks.admin.api.HttpResponse
import io.github.bropines.birdsocks.admin.api.HttpTransport
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.areas.proxy.ProxyChanges
import io.github.bropines.birdsocks.admin.areas.settings.AccountSettingsChanges
import io.github.bropines.birdsocks.admin.areas.streaming.EventStreamChanges
import io.github.bropines.birdsocks.admin.areas.users.UserChanges
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.Refusal
import io.github.bropines.birdsocks.admin.safety.SafeChangeRunner
import io.github.bropines.birdsocks.admin.safety.SafetyContext
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The builders of the Users, Settings, Streaming and Proxy areas: classes, guards and what they send. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AreaChangesTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private class Recorder(vararg answers: String) : HttpTransport {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(200, queue.removeFirstOrNull() ?: "")
        }
    }

    private fun api(t: HttpTransport) = NetbirdApi("https://m/api", ApiAuth.Token("nbp_x"), t, io = Dispatchers.Unconfined, sleeper = {})

    private val ann = ApiUser(id = "ann", name = "Ann", role = ApiRoles.USER, status = "active")

    // --- users ---

    @Test
    fun ownerTransferIsHighWithAWarningAndNoUndo() {
        val c = UserChanges.setRole(ctx, ann, ApiRoles.OWNER)
        assertEquals(ChangeClass.HIGH, c.change.changeClass)
        assertTrue(c.change.warnings.isNotEmpty())
        assertNull(c.undo)
    }

    @Test
    fun raisingToAdminIsHighAndLoweringIsMediumWithUndo() {
        assertEquals(ChangeClass.HIGH, UserChanges.setRole(ctx, ann, ApiRoles.ADMIN).change.changeClass)
        val down = UserChanges.setRole(ctx, ann.copy(role = ApiRoles.ADMIN), ApiRoles.USER)
        assertEquals(ChangeClass.MEDIUM, down.change.changeClass)
        assertEquals(ApiRoles.ADMIN, (down.undo!!.change.diff.single().after))
        assertNull(down.undo!!.undo)
    }

    @Test
    fun unblockingTakesAProtectionOff() {
        assertEquals(ChangeClass.HIGH, UserChanges.setBlocked(ctx, ann.copy(isBlocked = true), false).change.changeClass)
        assertEquals(ChangeClass.MEDIUM, UserChanges.setBlocked(ctx, ann, true).change.changeClass)
    }

    @Test
    fun aRoleChangeRereadsTheUserAndKeepsTheRest() = runBlocking {
        val t = Recorder("""[{"id":"ann","role":"user","auto_groups":["g1"],"is_blocked":false}]""", "{}")
        UserChanges.setRole(ctx, ann, ApiRoles.AUDITOR).apply(api(t))
        val put = t.requests[1]
        assertEquals("PUT", put.method)
        val body = AppJson.parseToJsonElement(put.body!!).jsonObject
        assertEquals("auditor", body["role"]!!.jsonPrimitive.content)
        assertEquals("g1", body["auto_groups"].toString().trim('[', ']', '"'))
    }

    @Test
    fun theRunnerRefusesYourOwnUser() {
        val runner = SafeChangeRunner({ null }, null, { SafetyContext("acc", ownUserId = "me") })
        val mine = UserChanges.setRole(ctx, ann.copy(id = "me"), ApiRoles.ADMIN)
        assertEquals(Refusal.OWN_USER, runner.blockedBy(mine.change))
        assertEquals(Refusal.OWN_USER, runner.blockedBy(UserChanges.delete(ctx, ann.copy(isCurrent = true)).change))
        assertNull(runner.blockedBy(UserChanges.setRole(ctx, ann, ApiRoles.AUDITOR).change))
    }

    @Test
    fun thePanelsOwnTokenGoesOnlyThroughTheWarnedKind() {
        val me = ApiUser(id = "me", name = "Me", role = ApiRoles.ADMIN)
        val token = ApiPersonalAccessToken("t1", "ci")
        val runner = SafeChangeRunner({ null }, null, { SafetyContext("acc", ownUserId = "me", tokenAuth = true) })
        val plain = UserChanges.removeToken(ctx, me, token, maybeOwn = false)
        assertEquals(Refusal.OWN_CREDENTIAL, runner.blockedBy(plain.change))
        val warned = UserChanges.removeToken(ctx, me, token, maybeOwn = true)
        assertEquals(UserChanges.TOKEN_DELETE_OWN, warned.change.kind)
        assertEquals(ChangeClass.HIGH, warned.change.changeClass)
        assertTrue(warned.change.warnings.isNotEmpty())
        assertNull(runner.blockedBy(warned.change))
        // A token of somebody else needs no warning and passes.
        assertNull(runner.blockedBy(UserChanges.removeToken(ctx, ann, token, maybeOwn = false).change))
        // Creating one for yourself is fine.
        assertNull(runner.blockedBy(UserChanges.newToken(ctx, me, "new", 30) {}.change))
    }

    @Test
    fun aNewTokenIsHandedOverOnceAndNotKept() = runBlocking {
        var plain: String? = null
        val t = Recorder("""{"plain_token":"nbp_secret","personal_access_token":{"id":"t9","name":"ci"}}""")
        val c = UserChanges.newToken(ctx, ann, "ci", 30) { plain = it.plainToken }
        c.apply(api(t))
        assertEquals("nbp_secret", plain)
        assertEquals("/users/ann/tokens", t.requests.single().url.removePrefix("https://m/api"))
        assertFalse(c.change.toString().contains("nbp_secret"))
    }

    @Test
    fun anInviteForAnAdminIsHigh() {
        fun invite(role: String) = UserChanges.createInvite(ctx, ApiUserInviteCreateRequest("a@b.c", "A", role, emptyList(), 3600), emptyList()) {}
        assertEquals(ChangeClass.HIGH, invite(ApiRoles.ADMIN).change.changeClass)
        assertEquals(ChangeClass.MEDIUM, invite(ApiRoles.USER).change.changeClass)
        assertTrue(invite(ApiRoles.USER).change.warnings.isNotEmpty())
    }

    // --- account settings ---

    private val account = ApiAccount(id = "acc", settings = ApiAccountSettings(networkRange = "100.64.0.0/10", extra = ApiAccountExtraSettings(peerApprovalEnabled = true)))
    private val render: (io.github.bropines.birdsocks.admin.areas.settings.SettingSpec, kotlinx.serialization.json.JsonElement) -> String = { _, v -> v.toString() }

    @Test
    fun theNetworkRangeIsTypedBackAsTheNewRange() {
        val s = AccountSettingsChanges
        val c = s.change(ctx, account, s.NETWORK_RANGE, JsonPrimitive("100.64.0.0/10"), JsonPrimitive("10.20.0.0/16"), render)
        assertEquals(ChangeClass.HIGH, c.change.changeClass)
        assertEquals("10.20.0.0/16", c.change.confirmText)
        assertTrue(c.change.warnings.isNotEmpty())
        assertNotNull(c.undo)
    }

    @Test
    fun turningApprovalOffMergesIntoAFreshReadAndKeepsUnknownFields() = runBlocking {
        val s = AccountSettingsChanges
        val c = s.change(ctx, account, s.PEER_APPROVAL, JsonPrimitive(true), JsonPrimitive(false), render)
        assertEquals(ChangeClass.HIGH, c.change.changeClass)
        val t = Recorder(
            """[{"id":"acc","settings":{"network_range":"100.64.0.0/10","future_field":7,"extra":{"peer_approval_enabled":true,"user_approval_required":true}}}]""",
            """{"id":"acc"}""",
        )
        c.apply(api(t))
        val put = t.requests[1]
        assertEquals("PUT", put.method)
        val settings = AppJson.parseToJsonElement(put.body!!).jsonObject["settings"]!!.jsonObject
        assertEquals("7", settings["future_field"].toString())
        assertFalse(settings["extra"]!!.jsonObject["peer_approval_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(settings["extra"]!!.jsonObject["user_approval_required"]!!.jsonPrimitive.boolean)
    }

    // --- streaming and proxy ---

    @Test
    fun streamOffIsHighAndSendsNoConfig() = runBlocking {
        val i = ApiIntegration(id = 5, enabled = true, platform = "s3", config = mapOf("secret_access_key" to "x"))
        val c = EventStreamChanges.setEnabled(ctx, i, false)
        assertEquals(ChangeClass.HIGH, c.change.changeClass)
        val t = Recorder("{}")
        c.apply(api(t))
        val body = AppJson.parseToJsonElement(t.requests.single().body!!).jsonObject
        assertEquals(JsonObject(emptyMap()), body["config"])
        assertFalse(body["enabled"]!!.jsonPrimitive.boolean)
        // The review never shows a secret value.
        val edit = EventStreamChanges.updateConfig(ctx, i, mapOf("secret_access_key" to "new-secret"))
        assertTrue(edit.change.diff.none { it.after == "new-secret" || it.before == "x" })
    }

    @Test
    fun aServiceToggleRereadsAndKeepsWhatTheModelDoesNotShow() = runBlocking {
        val s = ApiService(id = "s1", name = "web", domain = "web.example.com", enabled = false)
        val on = ProxyChanges.setEnabled(ctx, s, true)
        assertEquals(ChangeClass.HIGH, on.change.changeClass)
        assertEquals(ChangeClass.MEDIUM, ProxyChanges.setEnabled(ctx, s.copy(enabled = true), false).change.changeClass)
        val t = Recorder(
            """{"id":"s1","name":"web","domain":"web.example.com","enabled":false,"meta":{"status":"active"},"auth":{"password_auth":{"enabled":true,"password":"kept"}}}""",
            "{}",
        )
        on.apply(api(t))
        val put = t.requests[1]
        assertEquals("/reverse-proxies/services/s1", put.url.removePrefix("https://m/api"))
        val body = AppJson.parseToJsonElement(put.body!!).jsonObject
        assertTrue(body["enabled"]!!.jsonPrimitive.boolean)
        assertNull(body["id"])
        assertNull(body["meta"])
        assertTrue(put.body!!.contains("kept"))
    }
}
