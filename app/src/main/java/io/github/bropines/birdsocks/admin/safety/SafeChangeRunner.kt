package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.api.AdminApiException
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.secure.LockState
import io.github.bropines.birdsocks.admin.secure.WriteGrant
import kotlinx.coroutines.CancellationException

/** Why a change did not go out. Every one is decided before anything is sent. */
enum class Refusal {
    /** The account's read-only switch is on, or the credential cannot write anything. */
    READ_ONLY,
    /** No screen lock: nothing can unlock a write. */
    NO_SCREEN_LOCK,
    /** The role's permissions (or a 403 learned earlier) do not allow it. */
    NOT_ALLOWED,
    /** This device's own peer. */
    OWN_PEER,
    /** The user the credential acts as. */
    OWN_USER,
    /** The access token the panel is signed in with (or, when it cannot be told apart, any of its user's). */
    OWN_CREDENTIAL,
    /** The confirm dialog did not happen. */
    NOT_CONFIRMED,
    /** The typed name does not match the target's. */
    TYPED_MISMATCH,
    /** No unlock, or one already used or gone stale. */
    NOT_UNLOCKED,
    /** A POLICY change whose own checks did not pass, or were not run. */
    CHECKS_NOT_PASSED,
    /** No API session (signed out meanwhile). */
    NO_SESSION,
}

/** Who "you" are, for the guards; built by the AdminViewModel for each check. */
data class SafetyContext(
    val accountId: String,
    val readOnly: Boolean = false,
    val lockState: LockState = LockState.CRYPTO_PER_USE,
    /** The user the credential acts as. */
    val ownUserId: String? = null,
    /** The token in use, when it could be told ([NetbirdApi.guessOwnTokenId]). */
    val ownTokenId: String? = null,
    /** Signed in with a personal access token (not this device's sign-in). */
    val tokenAuth: Boolean = false,
    /** This device's peer ids in the API's list. */
    val selfPeerIds: Set<String> = emptySet(),
    val can: (module: String, op: Op) -> Boolean = { _, _ -> true },
)

/** What the person did at the gates: confirmed, typed, unlocked. */
data class GateEvidence(
    val confirmed: Boolean,
    val typedName: String? = null,
    val grant: WriteGrant? = null,
)

sealed class ChangeOutcome {
    /** [verified] is null when there was nothing to re-read, or the re-read itself failed. */
    data class Applied(val verified: Boolean?) : ChangeOutcome()
    data class Refused(val reason: Refusal) : ChangeOutcome()
    data class Failed(val error: Throwable) : ChangeOutcome()
}

/**
 * Applies a [PlannedChange] after checking, itself, that its gates were
 * passed — the dialogs ask, this enforces. A read-only account, no screen
 * lock, a role without the permission, this device's own peer, your own
 * user, the token in use, a missing confirm, a wrong typed name, an absent or
 * reused unlock: each refuses before a request is made. Then it applies,
 * re-reads to verify, and records the attempt in the local [audit] log.
 */
class SafeChangeRunner(
    private val api: () -> NetbirdApi?,
    private val audit: AdminAuditLog?,
    private val context: () -> SafetyContext,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The guard that stops [change] whatever the person does at the gates; null if none. */
    fun blockedBy(change: AdminChange): Refusal? {
        val ctx = context()
        val t = change.target
        val self = !change.kind.allowsSelf
        return when {
            ctx.readOnly -> Refusal.READ_ONLY
            change.needsUnlock && ctx.lockState == LockState.NO_SCREEN_LOCK -> Refusal.NO_SCREEN_LOCK
            !ctx.can(change.kind.module, change.kind.op) -> Refusal.NOT_ALLOWED
            self && t.type == TargetType.PEER && (t.isThisDevice || t.id in ctx.selfPeerIds) -> Refusal.OWN_PEER
            self && t.type == TargetType.USER && (t.isYou || (ctx.ownUserId != null && t.id == ctx.ownUserId)) -> Refusal.OWN_USER
            self && t.type == TargetType.TOKEN && isOwnToken(t, ctx) -> Refusal.OWN_CREDENTIAL
            change.changeClass == ChangeClass.POLICY && change.evidence?.passed != true -> Refusal.CHECKS_NOT_PASSED
            else -> null
        }
    }

    /**
     * The token in use; when it cannot be told apart and the panel signs in
     * with a token, every token of that same user counts — refusing one too
     * many beats cutting the panel off.
     */
    private fun isOwnToken(t: ChangeTarget, ctx: SafetyContext): Boolean {
        if (ctx.ownTokenId != null) return t.id == ctx.ownTokenId
        return ctx.tokenAuth && t.ownerId != null && t.ownerId == ctx.ownUserId
    }

    /** The gate check alone, without applying: what [run] would refuse with, or null. */
    fun check(change: AdminChange, gates: GateEvidence): Refusal? {
        blockedBy(change)?.let { return it }
        if (!gates.confirmed) return Refusal.NOT_CONFIRMED
        if (change.needsTypedConfirmation && gates.typedName?.trim() != change.confirmText.trim()) return Refusal.TYPED_MISMATCH
        if (change.needsUnlock && gates.grant == null) return Refusal.NOT_UNLOCKED
        return null
    }

    suspend fun run(planned: PlannedChange, gates: GateEvidence): ChangeOutcome {
        val change = planned.change
        val client = api()
        val refusal = check(change, gates)
            ?: if (change.needsUnlock && gates.grant?.consume(clock()) != true) Refusal.NOT_UNLOCKED else null
            ?: if (client == null) Refusal.NO_SESSION else null
        if (refusal != null || client == null) {
            val r = refusal ?: Refusal.NO_SESSION
            record(planned, AuditResult.REFUSED, r.name)
            return ChangeOutcome.Refused(r)
        }
        try {
            planned.apply(client)
        } catch (e: CancellationException) {
            record(planned, AuditResult.FAILED, "cancelled")
            throw e
        } catch (e: Exception) {
            record(planned, AuditResult.FAILED, describe(e))
            return ChangeOutcome.Failed(e)
        }
        val verified = planned.verify?.let { verify ->
            try {
                verify(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        record(
            planned,
            when (verified) {
                true -> AuditResult.VERIFIED
                false -> AuditResult.MISMATCH
                null -> AuditResult.APPLIED
            },
            null,
        )
        return ChangeOutcome.Applied(verified)
    }

    /** Records a refusal decided before the gates (the flow's first check), so the log has it too. */
    fun recordRefusal(planned: PlannedChange, reason: Refusal) = record(planned, AuditResult.REFUSED, reason.name)

    private fun record(planned: PlannedChange, result: AuditResult, detail: String?) {
        val change = planned.change
        runCatching {
            audit?.append(
                AuditRecord(
                    time = clock(),
                    accountId = context().accountId,
                    kind = change.kind.id,
                    changeClass = change.changeClass,
                    targetType = change.target.type,
                    targetId = change.target.id,
                    targetName = change.target.name,
                    effect = change.effect,
                    result = result,
                    detail = detail,
                    undo = planned.isUndo,
                )
            )
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is AdminApiException -> listOfNotNull(e.message, e.apiMessage).joinToString(": ")
        else -> e.javaClass.simpleName + (e.message?.let { ": ${it.take(200)}" } ?: "")
    }
}
