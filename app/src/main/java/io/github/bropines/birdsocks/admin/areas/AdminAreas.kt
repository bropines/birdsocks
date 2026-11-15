package io.github.bropines.birdsocks.admin.areas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Tune
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.areas.dns.DnsArea
import io.github.bropines.birdsocks.admin.areas.groups.GroupsArea
import io.github.bropines.birdsocks.admin.areas.keys.SetupKeysArea
import io.github.bropines.birdsocks.admin.areas.networks.NetworksArea
import io.github.bropines.birdsocks.admin.areas.peers.PeersArea
import io.github.bropines.birdsocks.admin.areas.routes.RoutesArea
import io.github.bropines.birdsocks.admin.areas.policies.PoliciesArea
import io.github.bropines.birdsocks.admin.areas.posture.PostureChecksArea
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
        PoliciesArea,
        PostureChecksArea,
        NetworksArea,
        RoutesArea,
        DnsArea,
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
