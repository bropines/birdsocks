package io.github.bropines.birdsocks.admin.safety

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bropines.birdsocks.admin.secure.UnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where a proposed change is in its gates. [SafetyHost] draws it. */
sealed class SafetyStep {
    data object Idle : SafetyStep()
    /** The review dialog: target, effect, diff, warnings and — for HIGH — the typed name. */
    data class Confirm(val planned: PlannedChange) : SafetyStep()
    /** Confirmed; the unlock prompt is up. */
    data class Unlock(val planned: PlannedChange, val typedName: String?) : SafetyStep()
    data class Applying(val change: AdminChange) : SafetyStep()
}

/**
 * One change at a time through its gates: propose → (review) → unlock →
 * apply → verify. Owned by the AdminViewModel, so a rotation keeps the step;
 * [SafetyHost] draws [step] and reports back here. [onFinished] gets every
 * outcome — applied, failed, refused, refused at the first look — to tell
 * the user, refresh and offer the undo.
 */
class ChangeFlow(
    private val scope: CoroutineScope,
    private val runner: SafeChangeRunner,
    private val onFinished: (PlannedChange, ChangeOutcome) -> Unit,
) {
    var step: SafetyStep by mutableStateOf(SafetyStep.Idle)
        private set

    val busy: Boolean get() = step != SafetyStep.Idle

    /**
     * Starts [planned] through its gates. A guard that would stop it whatever
     * the person does refuses at once (and is logged); LOW goes straight to the
     * unlock. False when it was refused or another change is under way.
     */
    fun propose(planned: PlannedChange): Boolean {
        if (busy) return false
        runner.blockedBy(planned.change)?.let { reason ->
            runner.recordRefusal(planned, reason)
            onFinished(planned, ChangeOutcome.Refused(reason))
            return false
        }
        step = if (planned.change.needsDialog) SafetyStep.Confirm(planned) else SafetyStep.Unlock(planned, null)
        return true
    }

    /** The review dialog's confirm, with what was typed for a HIGH change. */
    fun confirm(typedName: String?) {
        val s = step as? SafetyStep.Confirm ?: return
        step = SafetyStep.Unlock(s.planned, typedName)
    }

    fun cancel() {
        if (step !is SafetyStep.Applying) step = SafetyStep.Idle
    }

    /** What the unlock prompt said; a grant applies the change. */
    fun onUnlock(result: UnlockResult) {
        val s = step as? SafetyStep.Unlock ?: return
        when (result) {
            is UnlockResult.Granted -> {
                step = SafetyStep.Applying(s.planned.change)
                scope.launch {
                    val outcome = try {
                        runner.run(s.planned, GateEvidence(confirmed = true, typedName = s.typedName, grant = result.grant))
                    } finally {
                        step = SafetyStep.Idle
                    }
                    onFinished(s.planned, outcome)
                }
            }
            UnlockResult.Cancelled -> step = SafetyStep.Idle
            is UnlockResult.Failed -> {
                step = SafetyStep.Idle
                onFinished(s.planned, ChangeOutcome.Failed(UnlockFailedException(result)))
            }
        }
    }
}

/** The unlock did not happen for a reason other than the user closing the prompt. */
class UnlockFailedException(val result: UnlockResult.Failed) : Exception("unlock failed: ${result.reason.name.lowercase()}")
