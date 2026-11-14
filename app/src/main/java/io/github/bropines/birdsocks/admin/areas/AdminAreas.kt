package io.github.bropines.birdsocks.admin.areas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Policy
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
        NetworksArea,
        RoutesArea,
        DnsArea,
        comingArea("users", R.string.admin_area_users, R.string.admin_area_users_summary, Icons.Default.People, Capabilities.USERS),
        comingArea("service_users", R.string.admin_area_service_users, R.string.admin_area_service_users_summary, Icons.Default.ManageAccounts, Capabilities.USERS),
        comingArea("events", R.string.admin_area_events, R.string.admin_area_events_summary, Icons.Default.History, Capabilities.EVENTS),
        comingArea("account_settings", R.string.admin_area_account_settings, R.string.admin_area_account_settings_summary, Icons.Default.Tune, Capabilities.ACCOUNTS),
        comingArea("event_streaming", R.string.admin_area_event_streaming, R.string.admin_area_event_streaming_summary, Icons.Default.Stream, Capabilities.SETTINGS,
            available = { it.account?.isCloud == true }),
        comingArea("identity_providers", R.string.admin_area_identity_providers, R.string.admin_area_identity_providers_summary, Icons.Default.Fingerprint, Capabilities.IDENTITY_PROVIDERS),
        comingArea("reverse_proxy", R.string.admin_area_reverse_proxy, R.string.admin_area_reverse_proxy_summary, Icons.Default.Public, Capabilities.SERVICES),
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
