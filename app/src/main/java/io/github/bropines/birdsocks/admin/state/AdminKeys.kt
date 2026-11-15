package io.github.bropines.birdsocks.admin.state

import io.github.bropines.birdsocks.admin.api.ApiAccount
import io.github.bropines.birdsocks.admin.api.ApiDnsSettings
import io.github.bropines.birdsocks.admin.api.ApiDnsZone
import io.github.bropines.birdsocks.admin.api.ApiEvent
import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiIdentityProvider
import io.github.bropines.birdsocks.admin.api.ApiInstanceVersion
import io.github.bropines.birdsocks.admin.api.ApiIntegration
import io.github.bropines.birdsocks.admin.api.ApiNameserverGroup
import io.github.bropines.birdsocks.admin.api.ApiNetwork
import io.github.bropines.birdsocks.admin.api.ApiNetworkRouter
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.api.ApiPostureCheck
import io.github.bropines.birdsocks.admin.api.ApiReverseProxyDomain
import io.github.bropines.birdsocks.admin.api.ApiRoute
import io.github.bropines.birdsocks.admin.api.ApiService
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUser
import io.github.bropines.birdsocks.admin.api.ApiUserInvite
import io.github.bropines.birdsocks.admin.api.Capabilities

/**
 * The account-wide data the panel shares between areas: a peer picker in
 * Policies reads the same PEERS the Peers area shows. Each area asks for the
 * keys it needs when it opens (`host.load(...)`); nothing is read before
 * someone needs it, which matters with self-hosted rate limits of a few
 * requests a minute.
 *
 * Caching is decided here, once: everything not marked `cache = false` is
 * kept sealed on disk ([AdminCache]). Never cache invites (their links admit
 * people), personal access tokens, event-streaming configs (destination
 * credentials) or audit events. Per-object data (a network's resources, a
 * user's tokens, a zone's records) belongs to the area's own state holder.
 */
object AdminKeys {
    val ACCOUNTS = DataKey.list("accounts", Capabilities.ACCOUNTS, ApiAccount.serializer()) { it.accounts() }

    val PEERS = DataKey.list("peers", Capabilities.PEERS, ApiPeer.serializer()) { it.peers() }

    /** The list masks keys already; [ApiSetupKey.key] is blanked before caching all the same. */
    val SETUP_KEYS = DataKey.list("setup_keys", Capabilities.SETUP_KEYS, ApiSetupKey.serializer(), strip = { it.copy(key = "") }) { it.setupKeys() }

    val GROUPS = DataKey.list("groups", Capabilities.GROUPS, ApiGroup.serializer()) { it.groups() }

    /** Every user the server lists without a filter. */
    val USERS = DataKey.list("users", Capabilities.USERS, ApiUser.serializer()) { it.users() }

    val SERVICE_USERS = DataKey.list("service_users", Capabilities.USERS, ApiUser.serializer()) { it.users(serviceUsers = true) }

    val POLICIES = DataKey.list("policies", Capabilities.POLICIES, ApiPolicy.serializer()) { it.policies() }

    val POSTURE_CHECKS = DataKey.list("posture_checks", Capabilities.POLICIES, ApiPostureCheck.serializer()) { it.postureChecks() }

    val NETWORKS = DataKey.list("networks", Capabilities.NETWORKS, ApiNetwork.serializer()) { it.networks() }

    /** Every network's routers at once (GET /networks/routers). */
    val NETWORK_ROUTERS = DataKey.list("network_routers", Capabilities.NETWORKS, ApiNetworkRouter.serializer()) { it.allNetworkRouters() }

    val ROUTES = DataKey.list("routes", Capabilities.ROUTES, ApiRoute.serializer()) { it.routes() }

    val NAMESERVERS = DataKey.list("nameservers", Capabilities.NAMESERVERS, ApiNameserverGroup.serializer()) { it.nameserverGroups() }

    val DNS_SETTINGS = DataKey.single("dns_settings", Capabilities.DNS, ApiDnsSettings.serializer()) { it.dnsSettings() }

    val DNS_ZONES = DataKey.list("dns_zones", Capabilities.DNS, ApiDnsZone.serializer()) { it.dnsZones() }

    val IDENTITY_PROVIDERS = DataKey.list("identity_providers", Capabilities.IDENTITY_PROVIDERS, ApiIdentityProvider.serializer()) { it.identityProviders() }

    val PROXY_SERVICES = DataKey.list("proxy_services", Capabilities.SERVICES, ApiService.serializer(), strip = ::stripServiceSecrets) { it.proxyServices() }

    val PROXY_DOMAINS = DataKey.list("proxy_domains", Capabilities.SERVICES, ApiReverseProxyDomain.serializer()) { it.proxyDomains() }

    val INSTANCE_VERSION = DataKey.single("instance_version", Capabilities.ACCOUNTS, ApiInstanceVersion.serializer()) { it.instanceVersion() }

    // --- Never cached ---

    /** Links that admit people. */
    val USER_INVITES = DataKey.list("user_invites", Capabilities.USERS, ApiUserInvite.serializer(), cache = false) { it.userInvites() }

    /** The destinations' credentials are in the configs. */
    val EVENT_STREAMS = DataKey.list("event_streams", Capabilities.SETTINGS, ApiIntegration.serializer(), cache = false) { it.integrations() }

    /** Read fresh each time; the server keeps it. */
    val AUDIT_EVENTS = DataKey.list("audit_events", Capabilities.EVENTS, ApiEvent.serializer(), cache = false) { it.auditEvents() }

    /** Every key above; the cache seeds and saves these. An area's own key joins by being listed here. */
    val ALL: List<DataKey<*>> = listOf(
        ACCOUNTS, PEERS, SETUP_KEYS, GROUPS, USERS, SERVICE_USERS, POLICIES, POSTURE_CHECKS, NETWORKS, NETWORK_ROUTERS,
        ROUTES, NAMESERVERS, DNS_SETTINGS, DNS_ZONES, IDENTITY_PROVIDERS, PROXY_SERVICES, PROXY_DOMAINS, INSTANCE_VERSION,
        USER_INVITES, EVENT_STREAMS, AUDIT_EVENTS,
    )

    /** What the home screen needs: the overview and the attention list. */
    val HOME: List<DataKey<*>> = listOf(ACCOUNTS, PEERS, SETUP_KEYS, GROUPS, USERS)

    /** A service's passwords, PINs and header values stay off the disk. */
    private fun stripServiceSecrets(s: ApiService): ApiService = s.copy(
        auth = s.auth.copy(
            passwordAuth = s.auth.passwordAuth?.copy(password = ""),
            pinAuth = s.auth.pinAuth?.copy(pin = ""),
            headerAuths = s.auth.headerAuths?.map { it.copy(value = "") },
        )
    )
}
