package io.github.bropines.birdsocks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.core.TunRoutes
import io.github.bropines.birdsocks.models.NbConfig
import io.github.bropines.birdsocks.models.NbDnsTable
import io.github.bropines.birdsocks.models.NbEvent
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * What a screen shows instead of asking the daemon, when there is no daemon to
 * ask: in the preview renderer, for the README and store screenshots
 * (app/src/screenshotTest), and to put a screen into one exact state while its
 * layout is being worked on.
 *
 * Only previews provide it. In the app [LocalDemo] is always null and every
 * screen reads the daemon as it always has. The values are the daemon's own
 * models, so a demo runs the real rendering, not a stand-in for it. The app's
 * own preferences are not here: a preview hands the screens a context whose
 * SharedPreferences hold them.
 *
 * A screen reads it synchronously, into its initial state, and skips its live
 * loads while it is there: the renderer takes its picture on the first frame,
 * before any coroutine it starts could come back.
 */
data class DemoData(
    val daemon: NetbirdState.Daemon = NetbirdState.Daemon.Running,
    /** What the status stream would have said last. */
    val status: NbStatus? = null,
    /** The active NetBird profile. */
    val profile: String = "default",
    /** The profiles with their servers, as the account lists read them. */
    val accounts: List<Account> = emptyList(),
    /** ListNetworks, exit nodes included. */
    val networks: List<NbNetwork> = emptyList(),
    /** GetConfig for the active profile. */
    val config: NbConfig? = null,
    /** The DNS table birdsocksd keeps beside its socket. */
    val dnsTable: NbDnsTable? = null,
    /** NetBird's events, oldest first, as EventLog keeps them. */
    val events: List<NbEvent> = emptyList(),
    /** Whether the VPN is up, and the routes it was built with. */
    val vpn: Boolean = false,
    val vpnRoutes: TunRoutes.RouteSet? = null,
    /** Where ByeDPI listens, while it runs. */
    val byeDpiAddress: Pair<String, Int>? = null,
    /** The peer whose details sheet is open: its public key, or [SELF_PAGE_KEY]. */
    val openPeer: String? = null,
    /** The item a long list starts scrolled to. */
    val firstItem: Int = 0,
)

val LocalDemo = staticCompositionLocalOf<DemoData?> { null }

/**
 * This flow as state; in a demo, what [pick] takes from it, and nothing that
 * moves. The demo is fixed for the composition's lifetime, so the branch is
 * too.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateOr(pick: (DemoData) -> T): State<T> {
    val demo = LocalDemo.current ?: return collectAsState()
    return remember(demo) { mutableStateOf(pick(demo)) }
}
