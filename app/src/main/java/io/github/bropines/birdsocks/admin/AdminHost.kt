package io.github.bropines.birdsocks.admin

import android.content.Context
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.api.NetbirdApi
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.state.AdminData
import io.github.bropines.birdsocks.admin.state.DataKey
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything an area screen and its state holder may use, and nothing else:
 * the session, what the role may do, the shared data, the way to propose a
 * change, notes, navigation. The AdminViewModel is the real one;
 * [io.github.bropines.birdsocks.admin.ui.PreviewAdminHost] is a no-op one
 * for previews.
 *
 * Writes go only through [propose]: [api] is there for reads (an area's own
 * per-object lists, a re-read), and AdminWritesTest fails the build when a
 * write method is called anywhere but in a `*Changes.kt` file.
 */
interface AdminHost {
    val account: AdminAccount?
    /** Who the credential acts as. */
    val user: ApiUser?
    val caps: Capabilities?
    /** For reads. Null until signed in. */
    val api: NetbirdApi?
    /** The account's shared lists, as Compose state. */
    val data: AdminData
    /** A coroutine scope that outlives the screen (the ViewModel's). */
    val scope: CoroutineScope
    /** Whether changes may be offered at all: not read-only, a screen lock, a role that can write. */
    val writesAllowed: Boolean
    /** A change is going through its gates: controls that start another should wait. */
    val changeBusy: Boolean

    /** The role may read [module] (or nothing was learned yet). */
    fun canRead(module: String): Boolean = caps?.can(module, Op.READ) != false

    /** The role may do [op] in [module] and, for a write, writes are allowed at all. */
    fun can(module: String, op: Op): Boolean

    /** This device's own peer in the API's list. */
    fun isSelf(peer: ApiPeer): Boolean

    /** Reads [keys] the role may read; with [force] even when a fresh value is held. */
    fun load(vararg keys: DataKey<*>, force: Boolean = false)

    /** Starts [change] through its gates; false when refused at once (the note says why) or another is under way. */
    fun propose(change: PlannedChange): Boolean

    /** A snackbar. */
    fun notify(note: AdminNote)

    /** Goes to an area ([AdminRoute.HOME] for the home screen); [arg] is the area's own (an id to open). */
    fun navigate(route: AdminRoute)

    /** Back from an area to the home screen. */
    fun back()

    /**
     * The area's state holder, made once per account and kept across
     * rotations; [create] runs the first time. An area keeps here what other
     * areas do not need: a selected network's resources, a draft, filters.
     */
    fun <T : AreaState> areaState(id: String, create: (AdminHost) -> T): T
}

/** Where the panel is: the home screen, or one area with an optional argument (an id to open). */
data class AdminRoute(val areaId: String, val arg: String? = null) {
    val isHome: Boolean get() = areaId == HOME.areaId

    companion object {
        val HOME = AdminRoute("home")
    }
}

/**
 * An area's own state, held by the AdminViewModel (see [AdminHost.areaState]).
 * Subclass it per area; keep Compose state in `mutableStateOf` fields and
 * launch in [AdminHost.scope]. [onCleared] runs when the account changes or
 * the credential is forgotten.
 */
abstract class AreaState(val host: AdminHost) {
    open fun onCleared() {}
}

/**
 * Something to tell the user once. [message] is resolved with the
 * activity's Context when shown, so it follows the app's language; [action]
 * is the snackbar's button (Undo, Retry).
 */
class AdminNote(val message: (Context) -> String, val action: Action? = null) {
    class Action(val label: (Context) -> String, val run: () -> Unit)

    /** Each note is shown once, even when two say the same. */
    val id: Long = ids.incrementAndGet()

    companion object {
        private val ids = AtomicLong()

        fun res(res: Int, vararg args: Any): AdminNote = AdminNote({ ctx -> ctx.getString(res, *args) })
        fun text(text: String): AdminNote = AdminNote({ text })
        fun error(e: Throwable): AdminNote = AdminNote({ ctx -> describeError(ctx, e) })
    }
}
