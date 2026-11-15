package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.api.ApiAuth
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.HttpResponse
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.secure.SealedBlob
import io.github.bropines.birdsocks.admin.secure.SecretBox
import io.github.bropines.birdsocks.admin.secure.UnlockFailure
import io.github.bropines.birdsocks.admin.secure.UnlockResult
import io.github.bropines.birdsocks.admin.secure.WriteGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** The classifier, the gate flow and the sealed audit log. */
class SafetyPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- Classifier ---

    @Test
    fun valuesDecideSomeClasses() {
        assertEquals(ChangeClass.HIGH, ChangeClassifier.userRole("user", "admin"))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.userRole("admin", "user"))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.userRole("user", "auditor"))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.setupKeyCreate(reusable = true, usageLimit = 0))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.setupKeyCreate(reusable = true, usageLimit = 5))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.setupKeyCreate(reusable = false, usageLimit = 0))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.protection(before = true, after = false))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.protection(before = false, after = true))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.expiration(3600, 0))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.expiration(3600, 7200))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.expiration(7200, 3600))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.route(beforeExit = true, afterExit = true, enabledBefore = true, enabledAfter = false))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.route(beforeExit = false, afterExit = true, enabledBefore = true, enabledAfter = true))
        assertEquals(ChangeClass.HIGH, ChangeClassifier.nameservers(primary = true, before = 2, after = 0))
        assertEquals(ChangeClass.MEDIUM, ChangeClassifier.nameservers(primary = false, before = 2, after = 0))
    }

    @Test
    fun classesDecideTheGates() {
        val kind = ChangeKind("x", Capabilities.PEERS, Op.UPDATE, ChangeClass.LOW)
        val t = ChangeTarget(TargetType.PEER, "p", "laptop")
        val low = AdminChange(kind, t, "t", "e")
        assertFalse(low.needsDialog)
        assertTrue(low.needsUnlock)
        assertFalse(low.needsTypedConfirmation)
        val high = low.copy(changeClass = ChangeClass.HIGH)
        assertTrue(high.needsDialog && high.needsTypedConfirmation && high.isHighRisk)
        assertEquals("laptop", high.confirmText)
        assertEquals("delete all", high.copy(confirmPhrase = "delete all").confirmText)
    }

    // --- The flow ---

    private val api = NetbirdApi("https://m/api", ApiAuth.Token("t"), { HttpResponse(200, "{}") }, io = Dispatchers.Unconfined)

    private fun flow(onFinished: (PlannedChange, ChangeOutcome) -> Unit, ctx: SafetyContext = SafetyContext("acc")) =
        ChangeFlow(CoroutineScope(Dispatchers.Unconfined), SafeChangeRunner({ api }, null, { ctx }), onFinished)

    private fun planned(cls: ChangeClass, apply: suspend (NetbirdApi) -> Unit = {}) =
        PlannedChange(AdminChange(ChangeKind("k", Capabilities.PEERS, Op.UPDATE, cls), ChangeTarget(TargetType.PEER, "p", "laptop"), "t", "e"), apply)

    @Test
    fun aMediumChangeGoesThroughReviewThenUnlock() {
        val outcomes = mutableListOf<ChangeOutcome>()
        var applied = false
        val f = flow({ _, o -> outcomes += o })
        assertTrue(f.propose(planned(ChangeClass.MEDIUM) { applied = true }))
        assertTrue(f.step is SafetyStep.Confirm)
        f.confirm(null)
        assertTrue(f.step is SafetyStep.Unlock)
        f.onUnlock(UnlockResult.Granted(WriteGrant.forTest(System.currentTimeMillis())))
        assertTrue(applied)
        assertEquals(SafetyStep.Idle, f.step)
        assertEquals(listOf<ChangeOutcome>(ChangeOutcome.Applied(null)), outcomes)
    }

    @Test
    fun aLowChangeSkipsTheReview() {
        val f = flow({ _, _ -> })
        f.propose(planned(ChangeClass.LOW))
        assertTrue(f.step is SafetyStep.Unlock)
        f.onUnlock(UnlockResult.Cancelled)
        assertEquals(SafetyStep.Idle, f.step)
    }

    @Test
    fun aGuardRefusesAtTheFirstLook() {
        val outcomes = mutableListOf<ChangeOutcome>()
        val f = flow({ _, o -> outcomes += o }, SafetyContext("acc", readOnly = true))
        assertFalse(f.propose(planned(ChangeClass.MEDIUM)))
        assertEquals(SafetyStep.Idle, f.step)
        assertEquals(listOf<ChangeOutcome>(ChangeOutcome.Refused(Refusal.READ_ONLY)), outcomes)
    }

    @Test
    fun oneChangeAtATimeAndAFailedUnlockIsReported() {
        val outcomes = mutableListOf<ChangeOutcome>()
        val f = flow({ _, o -> outcomes += o })
        assertTrue(f.propose(planned(ChangeClass.MEDIUM)))
        assertFalse(f.propose(planned(ChangeClass.MEDIUM)))
        f.confirm(null)
        f.onUnlock(UnlockResult.Failed(UnlockFailure.LOCKOUT))
        assertEquals(SafetyStep.Idle, f.step)
        val failed = outcomes.single() as ChangeOutcome.Failed
        assertEquals(UnlockFailure.LOCKOUT, (failed.error as UnlockFailedException).result.reason)
    }

    // --- The local audit log ---

    /** Reversible, not secure: enough to see that lines are sealed and opened. */
    private class Rot13Box : SecretBox {
        override fun seal(plaintext: String, aad: String): String =
            SealedBlob.encode(aad.toByteArray().copyOf(12), Base64.getEncoder().encode(plaintext.reversed().toByteArray()))
        override fun open(blob: String, aad: String): String = String(Base64.getDecoder().decode(SealedBlob.decode(blob).second)).reversed()
    }

    private fun record(i: Int, account: String = "acc") = AuditRecord(
        time = i.toLong(), accountId = account, kind = "peer.rename", changeClass = ChangeClass.LOW,
        targetType = TargetType.PEER, targetId = "p$i", targetName = "secret-name-$i", effect = "e", result = AuditResult.VERIFIED,
    )

    @Test
    fun sealedLinesKeepNamesOffTheDisk() {
        val file = tmp.newFile("audit.jsonl")
        val log = AdminAuditLog(file, box = Rot13Box())
        log.append(record(1))
        assertFalse(file.readText().contains("secret-name-1"))
        assertEquals("secret-name-1", log.records().single().targetName)
    }

    @Test
    fun theLogKeepsTheNewestAndClearsPerAccount() {
        val log = AdminAuditLog(tmp.newFile("audit.jsonl"), maxEntries = 10)
        repeat(30) { log.append(record(it, if (it % 2 == 0) "a" else "b")) }
        val all = log.records()
        assertTrue(all.size <= 10)
        assertEquals(29L, all.first().time)
        log.clear("a")
        assertTrue(log.records().all { it.accountId == "b" })
    }
}
