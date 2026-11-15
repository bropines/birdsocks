package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.HttpResponse
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.admin.secure.WriteGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeChangeRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000L
    private val api = NetbirdApi("https://m/api", ApiAuth.Token("t"), { HttpResponse(200, "{}") }, io = Dispatchers.Unconfined)
    private var ctx = SafetyContext(accountId = "acc", ownUserId = "me", selfPeerIds = setOf("p-self"))

    private fun runner(log: AdminAuditLog? = null) = SafeChangeRunner({ api }, log, { ctx }, clock = { now })

    private val deletePeer = ChangeKind("peer.delete", Capabilities.PEERS, Op.DELETE, ChangeClass.HIGH)
    private val renamePeer = ChangeKind("peer.rename", Capabilities.PEERS, Op.UPDATE, ChangeClass.LOW, allowsSelf = true)
    private val deleteToken = ChangeKind("token.delete", Capabilities.PATS, Op.DELETE, ChangeClass.HIGH)
    private val deleteUser = ChangeKind("user.delete", Capabilities.USERS, Op.DELETE, ChangeClass.HIGH)

    private fun planned(
        kind: ChangeKind,
        target: ChangeTarget,
        cls: ChangeClass = kind.defaultClass,
        evidence: ChangeEvidence? = null,
        apply: suspend (NetbirdApi) -> Unit = {},
        verify: (suspend (NetbirdApi) -> Boolean)? = null,
    ) = PlannedChange(AdminChange(kind, target, "t", "e", changeClass = cls, evidence = evidence), apply, verify)

    private fun peer(id: String, name: String = id) = ChangeTarget(TargetType.PEER, id, name)
    private fun grant() = WriteGrant.forTest(now)
    private fun gates(typed: String? = null, grant: WriteGrant? = grant(), confirmed: Boolean = true) = GateEvidence(confirmed, typed, grant)

    @Test
    fun appliesAndVerifies() = runBlocking {
        var applied = false
        val out = runner().run(planned(deletePeer, peer("p1", "laptop"), apply = { applied = true }, verify = { true }), gates("laptop"))
        assertTrue(applied)
        assertEquals(ChangeOutcome.Applied(true), out)
    }

    @Test
    fun mismatchAndUnverifiable() = runBlocking {
        assertEquals(ChangeOutcome.Applied(false), runner().run(planned(renamePeer, peer("p1"), verify = { false }), gates()))
        assertEquals(ChangeOutcome.Applied(null), runner().run(planned(renamePeer, peer("p1"), verify = { throw AdminApiException.Server(503, null) }), gates()))
        assertEquals(ChangeOutcome.Applied(null), runner().run(planned(renamePeer, peer("p1")), gates()))
    }

    @Test
    fun aFailedApplyIsReported() = runBlocking {
        val e = AdminApiException.Conflict("in use")
        val out = runner().run(planned(renamePeer, peer("p1"), apply = { throw e }), gates())
        assertEquals(ChangeOutcome.Failed(e), out)
    }

    @Test
    fun eachGuardRefusesBeforeSending() = runBlocking {
        var sent = 0
        fun p(kind: ChangeKind, target: ChangeTarget) = planned(kind, target, apply = { sent++ })
        suspend fun refused(change: PlannedChange, g: GateEvidence = gates(change.change.target.name)) = (runner().run(change, g) as ChangeOutcome.Refused).reason

        ctx = ctx.copy(readOnly = true)
        assertEquals(Refusal.READ_ONLY, refused(p(renamePeer, peer("p1"))))
        ctx = ctx.copy(readOnly = false, lockState = LockState.NO_SCREEN_LOCK)
        assertEquals(Refusal.NO_SCREEN_LOCK, refused(p(renamePeer, peer("p1"))))
        ctx = ctx.copy(lockState = LockState.CRYPTO_PER_USE, can = { m, op -> !(m == Capabilities.PEERS && op == Op.DELETE) })
        assertEquals(Refusal.NOT_ALLOWED, refused(p(deletePeer, peer("p1"))))
        ctx = ctx.copy(can = { _, _ -> true })
        assertEquals(Refusal.OWN_PEER, refused(p(deletePeer, peer("p-self"))))
        assertEquals(Refusal.OWN_PEER, refused(p(deletePeer, peer("p2").copy(isThisDevice = true))))
        assertEquals(Refusal.OWN_USER, refused(p(deleteUser, ChangeTarget(TargetType.USER, "me", "Me"))))
        assertEquals(Refusal.NOT_CONFIRMED, refused(p(renamePeer, peer("p1")), gates(confirmed = false)))
        assertEquals(Refusal.TYPED_MISMATCH, refused(p(deletePeer, peer("p1", "laptop")), gates("laptp")))
        assertEquals(Refusal.NOT_UNLOCKED, refused(p(renamePeer, peer("p1")), gates(grant = null)))
        assertEquals(Refusal.CHECKS_NOT_PASSED, refused(planned(deletePeer, peer("p1"), cls = ChangeClass.POLICY, apply = { sent++ })))
        assertEquals(Refusal.CHECKS_NOT_PASSED, refused(planned(deletePeer, peer("p1"), cls = ChangeClass.POLICY, evidence = ChangeEvidence(false), apply = { sent++ })))
        assertEquals(0, sent)
    }

    @Test
    fun aChangeThatAllowsSelfMayActOnThisDevice() = runBlocking {
        assertNull(runner().blockedBy(planned(renamePeer, peer("p-self")).change))
        assertEquals(Refusal.OWN_PEER, runner().blockedBy(planned(deletePeer, peer("p-self")).change))
    }

    @Test
    fun theTokenInUseIsGuarded() {
        val mine = ChangeTarget(TargetType.TOKEN, "t-mine", "phone", ownerId = "me")
        val other = ChangeTarget(TargetType.TOKEN, "t-other", "ci", ownerId = "me")
        val someoneElses = ChangeTarget(TargetType.TOKEN, "t-x", "x", ownerId = "you")
        ctx = ctx.copy(tokenAuth = true, ownTokenId = "t-mine")
        assertEquals(Refusal.OWN_CREDENTIAL, runner().blockedBy(planned(deleteToken, mine).change))
        assertNull(runner().blockedBy(planned(deleteToken, other).change))
        // Not known which one is ours: every token of our own user is held back.
        ctx = ctx.copy(ownTokenId = null)
        assertEquals(Refusal.OWN_CREDENTIAL, runner().blockedBy(planned(deleteToken, other).change))
        assertNull(runner().blockedBy(planned(deleteToken, someoneElses).change))
        // Signed in with this device's sign-in: no token to cut off.
        ctx = ctx.copy(tokenAuth = false)
        assertNull(runner().blockedBy(planned(deleteToken, other).change))
    }

    @Test
    fun aGrantIsGoodForOneChangeOnly() = runBlocking {
        val g = grant()
        assertTrue(runner().run(planned(renamePeer, peer("p1")), gates(grant = g)) is ChangeOutcome.Applied)
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_UNLOCKED), runner().run(planned(renamePeer, peer("p1")), gates(grant = g)))
        val stale = WriteGrant.forTest(now - WriteGrant.VALID_MS - 1)
        assertEquals(ChangeOutcome.Refused(Refusal.NOT_UNLOCKED), runner().run(planned(renamePeer, peer("p1")), gates(grant = stale)))
    }

    @Test
    fun noSessionIsARefusal() = runBlocking {
        val r = SafeChangeRunner({ null }, null, { ctx }, clock = { now })
        assertEquals(ChangeOutcome.Refused(Refusal.NO_SESSION), r.run(planned(renamePeer, peer("p1")), gates()))
    }

    @Test
    fun everyAttemptIsInTheLocalLog() = runBlocking {
        val log = AdminAuditLog(tmp.newFile("audit.jsonl"))
        val r = runner(log)
        r.run(planned(renamePeer, peer("p1", "laptop"), verify = { true }), gates())
        r.run(planned(deletePeer, peer("p1", "laptop")), gates("nope"))
        r.run(planned(renamePeer, peer("p1"), apply = { throw AdminApiException.BadRequest(422, "bad name") }), gates())
        val records = log.records("acc")
        assertEquals(listOf(AuditResult.FAILED, AuditResult.REFUSED, AuditResult.VERIFIED), records.map { it.result })
        assertEquals("TYPED_MISMATCH", records[1].detail)
        assertTrue(records[0].detail!!.contains("bad name"))
        assertEquals("peer.rename", records[2].kind)
        assertTrue(log.records("other").isEmpty())
    }
}
