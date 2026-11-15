package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.state.DataKey

/**
 * How much a change can hurt, which decides the gates it passes:
 *  - LOW: the control that asked for it was the confirm (a switch, a rename field); then the unlock.
 *  - MEDIUM: a dialog naming the target and the effect, then the unlock.
 *  - HIGH: the dialog with the before → after diff and the target's name typed back, the
 *    unlock, then a re-read that verifies; undo where the API allows one.
 *  - POLICY: HIGH, plus the checks the change carries ([AdminChange.evidence]) must have passed.
 *
 * Every class asks for the unlock: in this app every write goes through AdminWriteGate.
 */
enum class ChangeClass { LOW, MEDIUM, HIGH, POLICY }

/**
 * A kind of change. Areas declare their own next to their builders
 * (`PeerChanges.RENAME`), so adding one touches no shared file.
 *
 * @param id stable, for the local audit log: "peer.rename", "group.delete".
 * @param module and [op]: the permission it needs ([io.github.bropines.birdsocks.admin.api.Capabilities]).
 * @param defaultClass its class unless a builder decides from the values.
 * @param allowsSelf the change may act on this device's own peer, your own user or the token in
 *   use (a rename, a switch), with a warning; otherwise the runner refuses those targets.
 */
data class ChangeKind(
    val id: String,
    val module: String,
    val op: Op,
    val defaultClass: ChangeClass,
    val allowsSelf: Boolean = false,
)

/** What a change acts on; the runner's guards and the audit log read it. */
enum class TargetType {
    PEER, GROUP, SETUP_KEY, USER, TOKEN, INVITE, POLICY, POSTURE_CHECK, NETWORK, NETWORK_RESOURCE,
    NETWORK_ROUTER, ROUTE, NAMESERVER_GROUP, DNS_SETTINGS, DNS_ZONE, DNS_RECORD, ACCOUNT, EVENT_STREAM,
    IDENTITY_PROVIDER, PROXY_SERVICE, PROXY_DOMAIN, JOB,
}

/**
 * The thing a change acts on. [name] is what a person types back for a HIGH
 * change, so it is the name they see on screen. [ownerId] is the user a
 * TOKEN belongs to. [isThisDevice] and [isYou] are what the builder knows;
 * the runner also checks the ids against its own [SafetyContext].
 */
data class ChangeTarget(
    val type: TargetType,
    val id: String,
    val name: String,
    val ownerId: String? = null,
    val isThisDevice: Boolean = false,
    val isYou: Boolean = false,
)

/** One row of a before → after preview; null on a side for something added or removed. */
data class DiffLine(val label: String, val before: String?, val after: String?)

/** What a POLICY change's own checks found; without [passed] the runner refuses it. */
data class ChangeEvidence(val passed: Boolean, val notes: List<String> = emptyList())

/**
 * A described change, ready for the gates: what it is, what it acts on, what
 * it does in one sentence already in the user's language, and for HIGH the
 * before → after lines. Builders pass a Context from the screen (it carries
 * the app's language), never the Application.
 */
data class AdminChange(
    val kind: ChangeKind,
    val target: ChangeTarget,
    val title: String,
    val effect: String,
    val diff: List<DiffLine> = emptyList(),
    /** Shown above the confirm: "this is the phone you are using", "3 peers lose access". */
    val warnings: List<String> = emptyList(),
    val changeClass: ChangeClass = kind.defaultClass,
    /** Typed back instead of the target's name ("delete all"); null for the name. */
    val confirmPhrase: String? = null,
    /** POLICY: what the area's checks found. */
    val evidence: ChangeEvidence? = null,
    /**
     * A text diff for the review (a JSON document before → after); shown with the folded
     * line diff. Null when [diff]'s field rows say it all.
     */
    val textDiff: Pair<String, String>? = null,
) {
    val needsUnlock: Boolean get() = true
    /** LOW skips the dialog: the control that asked for it was the confirm. */
    val needsDialog: Boolean get() = changeClass != ChangeClass.LOW
    val needsTypedConfirmation: Boolean get() = changeClass == ChangeClass.HIGH || changeClass == ChangeClass.POLICY
    val confirmText: String get() = confirmPhrase ?: target.name
    val isHighRisk: Boolean get() = changeClass == ChangeClass.HIGH || changeClass == ChangeClass.POLICY
}

/**
 * A change with the calls that make it. Screens build these (through an
 * area's `*Changes` object) and hand them to `host.propose(…)`; they never
 * call the API's write methods themselves.
 *
 * @param apply does it. Runs on the main thread; the API switches to IO itself.
 * @param verify re-reads and says whether the server now holds what was asked.
 * @param undo the change that puts the old state back, gated like any other.
 * @param refreshes the shared data to read again once it applied.
 * @param done what to say when it applied; null for the generic "Done".
 */
class PlannedChange(
    val change: AdminChange,
    val apply: suspend (NetbirdApi) -> Unit,
    val verify: (suspend (NetbirdApi) -> Boolean)? = null,
    val undo: PlannedChange? = null,
    val refreshes: List<DataKey<*>> = emptyList(),
    val done: String? = null,
    /** This change puts back what another one changed; the audit log marks it. */
    val isUndo: Boolean = false,
)
