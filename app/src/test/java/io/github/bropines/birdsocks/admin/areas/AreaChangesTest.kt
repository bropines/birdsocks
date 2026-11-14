package io.github.bropines.birdsocks.admin.areas

import android.app.Application
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.ApiCreateSetupKey
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiGroupRequest
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerMinimum
import io.github.bropines.birdsocks.admin.api.ApiPeerRequest
import io.github.bropines.birdsocks.admin.api.ApiResource
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiSetupKeyUpdate
import io.github.bropines.birdsocks.admin.api.HttpRequest
import io.github.bropines.birdsocks.admin.api.HttpResponse
import io.github.bropines.birdsocks.admin.api.HttpTransport
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.areas.groups.GroupChanges
import io.github.bropines.birdsocks.admin.areas.groups.GroupUsage
import io.github.bropines.birdsocks.admin.areas.keys.KeyChanges
import io.github.bropines.birdsocks.admin.areas.peers.PeerChanges
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The change builders of Peers, Groups and Setup keys: their classes and
 * warnings, and that `apply` sends what it says against a small in-memory
 * server — a re-read first, other members kept, verify and undo agreeing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AreaChangesTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    /** Groups, peers and keys by id; answers GET and PUT on them, records every request. */
    private class FakeServer : HttpTransport {
        val groups = linkedMapOf<String, ApiGroup>()
        val peers = linkedMapOf<String, ApiPeer>()
        val keys = linkedMapOf<String, ApiSetupKey>()
        val log = mutableListOf<String>()

        override fun execute(request: HttpRequest): HttpResponse {
            val path = request.url.substringAfter("/api")
            log += "${request.method} $path"
            val parts = path.trim('/').split('/')
            return when {
                parts[0] == "groups" && request.method == "GET" -> json(ApiGroup.serializer(), groups[parts[1]])
                parts[0] == "groups" && request.method == "PUT" -> {
                    val r = AppJson.decodeFromString(ApiGroupRequest.serializer(), request.body!!)
                    val g = groups.getValue(parts[1]).copy(
                        name = r.name,
                        peers = r.peers.orEmpty().map { ApiPeerMinimum(it, peers[it]?.name ?: it) },
                        resources = r.resources.orEmpty(),
                    )
                    groups[g.id] = g
                    json(ApiGroup.serializer(), g)
                }
                parts[0] == "peers" && request.method == "GET" -> json(ApiPeer.serializer(), peers[parts[1]])
                parts[0] == "peers" && request.method == "PUT" -> {
                    val r = AppJson.decodeFromString(ApiPeerRequest.serializer(), request.body!!)
                    val p = peers.getValue(parts[1]).copy(name = r.name, sshEnabled = r.sshEnabled, inactivityExpirationEnabled = r.inactivityExpirationEnabled)
                    peers[p.id] = p
                    json(ApiPeer.serializer(), p)
                }
                parts[0] == "setup-keys" && request.method == "GET" -> json(ApiSetupKey.serializer(), keys[parts[1]])
                parts[0] == "setup-keys" && request.method == "PUT" -> {
                    val r = AppJson.decodeFromString(ApiSetupKeyUpdate.serializer(), request.body!!)
                    val k = keys.getValue(parts[1]).copy(revoked = r.revoked, autoGroups = r.autoGroups)
                    keys[k.id] = k
                    json(ApiSetupKey.serializer(), k)
                }
                else -> HttpResponse(404, """{"message":"not found","code":404}""")
            }
        }

        private fun <T> json(s: kotlinx.serialization.KSerializer<T>, v: T?) =
            if (v == null) HttpResponse(404, """{"message":"not found","code":404}""") else HttpResponse(200, AppJson.encodeToString(s, v))
    }

    private val server = FakeServer()
    private val api = NetbirdApi("https://m/api", ApiAuth.Token("t"), server, io = Dispatchers.Unconfined, sleeper = {})

    private val all = ApiGroup("g-all", ApiGroup.ALL)
    private val office = ApiGroup("g-office", "office", peers = listOf(ApiPeerMinimum("p-phone", "phone"), ApiPeerMinimum("p-laptop", "laptop")))
    private val servers = ApiGroup("g-servers", "servers", peers = listOf(ApiPeerMinimum("p-laptop", "laptop")))
    private val groups = listOf(all, office, servers)
    private val phone = ApiPeer(id = "p-phone", name = "phone", groups = listOf(ApiGroupMinimum("g-all", "All"), ApiGroupMinimum("g-office", "office")))
    private val laptop = ApiPeer(id = "p-laptop", name = "laptop", groups = listOf(ApiGroupMinimum("g-all", "All"), ApiGroupMinimum("g-office", "office"), ApiGroupMinimum("g-servers", "servers")))

    private fun seed() {
        groups.forEach { server.groups[it.id] = it }
        listOf(phone, laptop).forEach { server.peers[it.id] = it }
    }

    @Test
    fun peerGroupsEditEachGroupOnAFreshRead() = runBlocking {
        seed()
        // Someone else added a peer to servers since the screen read it.
        server.groups["g-servers"] = servers.copy(peers = servers.peers + ApiPeerMinimum("p-other", "other"))
        val change = PeerChanges.setGroups(ctx, phone, listOf("g-all", "g-servers"), groups, self = false)
        assertEquals(ChangeClass.MEDIUM, change.change.changeClass)
        assertTrue(change.change.warnings.isEmpty())
        change.apply(api)
        assertEquals(listOf("p-laptop"), server.groups.getValue("g-office").peers.map { it.id })
        assertEquals(listOf("p-laptop", "p-other", "p-phone"), server.groups.getValue("g-servers").peers.map { it.id })
        assertTrue(change.verify!!(api))
        assertFalse("All is never sent", server.log.any { it.contains("g-all") })
        // Undo puts both back.
        change.undo!!.apply(api)
        assertEquals(listOf("p-laptop", "p-phone"), server.groups.getValue("g-office").peers.map { it.id })
        assertEquals(listOf("p-laptop", "p-other"), server.groups.getValue("g-servers").peers.map { it.id })
    }

    @Test
    fun thisDeviceLeavingAGroupWarnsAndACutOffIsHigh() {
        val warned = PeerChanges.setGroups(ctx, phone, listOf("g-all"), groups, self = true)
        assertEquals(ChangeClass.MEDIUM, warned.change.changeClass)
        assertEquals(1, warned.change.warnings.size)
        assertTrue(warned.change.target.isThisDevice)
        val cut = PeerChanges.setGroups(ctx, phone, listOf("g-all"), groups, self = true, lostPolicies = listOf("office-to-servers"))
        assertEquals(ChangeClass.HIGH, cut.change.changeClass)
        assertTrue(cut.change.warnings.last().contains("office-to-servers"))
        assertTrue(PeerChanges.GROUPS.allowsSelf)
    }

    @Test
    fun bulkGroupsOnePutPerGroupAndUndo() = runBlocking {
        seed()
        val change = PeerChanges.bulkGroups(ctx, listOf(phone, laptop), add = listOf("g-servers"), remove = listOf("g-office"), groups = groups, selfPeerId = null)
        assertEquals(ChangeClass.MEDIUM, change.change.changeClass)
        assertEquals(TargetType.PEER, change.change.target.type)
        assertEquals(2, change.change.diff.size)
        change.apply(api)
        assertEquals(2, server.log.count { it.startsWith("PUT") })
        assertTrue(server.groups.getValue("g-office").peers.isEmpty())
        assertEquals(setOf("p-laptop", "p-phone"), server.groups.getValue("g-servers").peers.map { it.id }.toSet())
        assertTrue(change.verify!!(api))
        change.undo!!.apply(api)
        assertEquals(setOf("p-phone", "p-laptop"), server.groups.getValue("g-office").peers.map { it.id }.toSet())
        assertEquals(listOf("p-laptop"), server.groups.getValue("g-servers").peers.map { it.id })
    }

    @Test
    fun bulkWithThisDeviceAndACutOffTypesTheCountBack() {
        val change = PeerChanges.bulkGroups(ctx, listOf(phone, laptop), emptyList(), listOf("g-office"), groups, selfPeerId = "p-phone", lostPolicies = listOf("pol"))
        assertEquals(ChangeClass.HIGH, change.change.changeClass)
        assertEquals(change.change.target.name, change.change.confirmText)
        assertTrue(change.change.target.isThisDevice)
    }

    @Test
    fun sshAndInactivityClasses() = runBlocking {
        seed()
        assertEquals(ChangeClass.MEDIUM, PeerChanges.setSsh(ctx, phone, true, false).change.changeClass)
        assertEquals(ChangeClass.LOW, PeerChanges.setSsh(ctx, phone, false, false).change.changeClass)
        assertEquals(ChangeClass.MEDIUM, PeerChanges.setInactivityExpiration(ctx, phone, false, false).change.changeClass)
        val on = PeerChanges.setSsh(ctx, phone, true, false)
        on.apply(api)
        assertTrue(server.peers.getValue("p-phone").sshEnabled)
        assertTrue(on.verify!!(api))
        assertEquals(listOf("GET /peers/p-phone", "PUT /peers/p-phone", "GET /peers/p-phone"), server.log)
    }

    @Test
    fun groupMembersAndResources() = runBlocking {
        seed()
        val members = GroupChanges.setPeers(ctx, office, listOf("p-laptop"), { it }, selfPeerId = "p-phone")
        assertEquals(1, members.change.warnings.size)
        assertEquals(ChangeClass.MEDIUM, members.change.changeClass)
        assertEquals(ChangeClass.HIGH, GroupChanges.setPeers(ctx, office, listOf("p-laptop"), { it }, "p-phone", listOf("pol")).change.changeClass)
        members.apply(api)
        assertEquals(listOf("p-laptop"), server.groups.getValue("g-office").peers.map { it.id })
        assertTrue(members.verify!!(api))

        val res = GroupChanges.setResources(ctx, servers, listOf(ApiResource("r1", "host")), { it.id })
        res.apply(api)
        assertEquals(listOf(ApiResource("r1", "host")), server.groups.getValue("g-servers").resources)
        assertEquals(listOf("p-laptop"), server.groups.getValue("g-servers").peers.map { it.id })
        assertTrue(res.verify!!(api))
    }

    @Test
    fun theAllGroupIsNeverEdited() = runBlocking {
        seed()
        val r = runCatching { GroupChanges.rename(ctx, all, "Everyone").apply(api) }
        assertTrue(r.isFailure)
        assertFalse(server.log.any { it.startsWith("PUT") })
    }

    @Test
    fun groupDeleteIsHighAndNamesItsUsage() {
        val usage = GroupUsage(policies = listOf("office-to-servers"), setupKeys = listOf("laptops"), unchecked = listOf("routes"))
        val d = GroupChanges.delete(ctx, office, usage, selfInGroup = true)
        assertEquals(ChangeClass.HIGH, d.change.changeClass)
        assertEquals("office", d.change.confirmText)
        assertEquals(4, d.change.warnings.size)
        assertTrue(d.change.warnings[0].contains("office-to-servers"))
        assertEquals(ChangeClass.LOW, GroupChanges.rename(ctx, office, "hq").change.changeClass)
        assertEquals(ChangeClass.MEDIUM, GroupChanges.create(ctx, "new", emptyList(), emptyList()).change.changeClass)
    }

    @Test
    fun setupKeyGroupsKeepTheRevokedFlagFromAFreshRead() = runBlocking {
        val key = ApiSetupKey(id = "k", name = "laptops", valid = true, autoGroups = listOf("g-office"))
        server.keys["k"] = key.copy(revoked = true)
        val change = KeyChanges.setAutoGroups(ctx, key, listOf("g-servers"), { it })
        assertEquals(ChangeClass.MEDIUM, change.change.changeClass)
        change.apply(api)
        assertEquals(ApiSetupKeyUpdate(true, listOf("g-servers")).autoGroups, server.keys.getValue("k").autoGroups)
        assertTrue(server.keys.getValue("k").revoked)
        assertTrue(change.verify!!(api))
        assertEquals(listOf("g-office"), change.undo!!.let { it.apply(api); server.keys.getValue("k").autoGroups })
        assertNull(change.undo!!.undo)
    }

    @Test
    fun setupKeyDeleteAndCreateClasses() {
        val key = ApiSetupKey(id = "k", name = "laptops", valid = true)
        assertEquals(ChangeClass.HIGH, KeyChanges.delete(ctx, key).change.changeClass)
        assertEquals("laptops", KeyChanges.delete(ctx, key).change.confirmText)
        val unlimited = ApiCreateSetupKey("k", ApiSetupKey.TYPE_REUSABLE, 86_400, usageLimit = 0, allowExtraDnsLabels = true)
        val c = KeyChanges.create(ctx, unlimited, emptyList()) {}
        assertEquals(ChangeClass.HIGH, c.change.changeClass)
        assertTrue(c.change.diff.any { it.after == ctx.getString(io.github.bropines.birdsocks.R.string.admin_value_on) })
    }
}
