package io.github.bropines.birdsocks.admin.areas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Tune
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.areas.groups.GroupsArea
import io.github.bropines.birdsocks.admin.areas.keys.SetupKeysArea
import io.github.bropines.birdsocks.admin.areas.peers.PeersArea
import io.github.bropines.birdsocks.admin.areas.events.EventsArea
import io.github.bropines.birdsocks.admin.areas.idp.IdentityProvidersArea
import io.github.bropines.birdsocks.admin.areas.proxy.ReverseProxyArea
import io.github.bropines.birdsocks.admin.areas.settings.AccountSettingsArea
import io.github.bropines.birdsocks.admin.areas.streaming.EventStreamingArea
import io.github.bropines.birdsocks.admin.areas.users.ServiceUsersArea
import io.github.bropines.birdsocks.admin.areas.users.UsersArea

/**
 * Every area of the panel, in the order the home screen shows them. One line
 * per area: to build one, replace its `comingArea(…)` line with the `val`
 * from the area's own file — nothing else here changes.
 */
object AdminAreas {
    val all: List<AdminArea> = listOf(
        PeersArea,
        GroupsArea,
        SetupKeysArea,
        comingArea("policies", R.string.admin_area_policies, R.string.admin_area_policies_summary, Icons.Default.Policy, Capabilities.POLICIES),
        comingArea("posture_checks", R.string.admin_area_posture_checks, R.string.admin_area_posture_checks_summary, Icons.Default.HealthAndSafety, Capabilities.POLICIES),
        comingArea("networks", R.string.admin_area_networks, R.string.admin_area_networks_summary, Icons.Default.Hub, Capabilities.NETWORKS),
        comingArea("routes", R.string.admin_area_routes, R.string.admin_area_routes_summary, Icons.AutoMirrored.Filled.AltRoute, Capabilities.ROUTES),
        comingArea("dns", R.string.admin_area_dns, R.string.admin_area_dns_summary, Icons.Default.Dns, null,
            available = { it.canRead(Capabilities.DNS) || it.canRead(Capabilities.NAMESERVERS) }),
        UsersArea,
        ServiceUsersArea,
        EventsArea,
        AccountSettingsArea,
        EventStreamingArea,
        IdentityProvidersArea,
        ReverseProxyArea,
    )

    fun byId(id: String): AdminArea? = all.firstOrNull { it.id == id }

    /**
     * The area a `birdsocks://admin?page=` value names: an id or an alias;
     * null for the home screen ("overview", unknown values).
     */
    fun forPage(page: String?): AdminArea? {
        val p = page?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return all.firstOrNull { it.id == p || p in it.aliases }
    }
}
