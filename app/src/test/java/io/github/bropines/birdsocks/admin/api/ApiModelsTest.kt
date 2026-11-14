package io.github.bropines.birdsocks.admin.api

import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main API objects, decoded from sample answers shaped as NetBird's
 * interface description (shared/management/http/api/openapi.yml, v0.80.0)
 * gives them, with its own example values.
 */
class ApiModelsTest {
    @Test
    fun currentUserWithPermissions() {
        val user = AppJson.decodeFromString<ApiUser>(
            """
            {
              "id": "google-oauth2|277474792786460067937",
              "email": "demo@netbird.io",
              "name": "Tom Schulz",
              "role": "admin",
              "status": "active",
              "last_login": "2023-05-05T09:00:35.477782Z",
              "auto_groups": ["ch8i4ug6lnn4g9hqv7m0"],
              "is_current": true,
              "is_service_user": false,
              "is_blocked": false,
              "pending_approval": false,
              "issued": "api",
              "permissions": {
                "is_restricted": false,
                "modules": {
                  "networks": {"read": true, "create": false, "update": false, "delete": false},
                  "peers": {"read": true, "create": false, "update": true, "delete": true}
                }
              }
            }
            """
        )
        assertEquals("demo@netbird.io", user.email)
        assertEquals("admin", user.role)
        assertTrue(user.isCurrent)
        assertEquals(listOf("ch8i4ug6lnn4g9hqv7m0"), user.autoGroups)
        val caps = Capabilities.of(user)
        assertTrue(caps.fromServer)
        assertTrue(caps.can(Capabilities.PEERS, Capabilities.Op.DELETE))
        assertFalse(caps.can(Capabilities.PEERS, Capabilities.Op.CREATE))
        // A module the server does not list is not allowed.
        assertFalse(caps.can(Capabilities.SETUP_KEYS, Capabilities.Op.READ))
    }

    @Test
    fun userFromAnOlderServerHasNoPermissions() {
        val user = AppJson.decodeFromString<ApiUser>("""{"id":"u1","email":"a@b","name":"A","role":"user","auto_groups":[],"status":"active","is_blocked":false,"pending_approval":false}""")
        assertNull(user.permissions)
        val caps = Capabilities.of(user)
        assertFalse(caps.fromServer)
        assertTrue(caps.can(Capabilities.PEERS, Capabilities.Op.READ))
        assertFalse(caps.can(Capabilities.PEERS, Capabilities.Op.DELETE))
        assertTrue(caps.readOnly)
    }

    @Test
    fun peerBatch() {
        val peer = AppJson.decodeFromString<ApiPeer>(
            """
            {
              "id": "chacbco6lnnbn6cg5s90",
              "name": "stage-host-1",
              "created_at": "2023-05-05T09:00:35.477782Z",
              "ip": "10.64.0.1",
              "ipv6": "fd00:4e42:ab12::1",
              "network_addresses": [{"net_ip": "192.168.1.10/24", "mac": "00:11:22:33:44:55"}],
              "connection_ip": "35.64.0.1",
              "connected": true,
              "last_seen": "2023-05-05T10:05:26.420578Z",
              "os": "Darwin 13.2.1",
              "kernel_version": "23.2.0",
              "geoname_id": 2643743,
              "version": "0.14.0",
              "groups": [{"id": "ch8i4ug6lnn4g9hqv7m0", "name": "devs", "peers_count": 2, "resources_count": 5, "issued": "api"}],
              "ssh_enabled": true,
              "user_id": "google-oauth2|277474792786460067937",
              "hostname": "stage-host-1",
              "ui_version": "0.14.0",
              "dns_label": "stage-host-1.netbird.cloud",
              "login_expiration_enabled": false,
              "login_expired": false,
              "last_login": "2023-05-05T09:00:35.477782Z",
              "inactivity_expiration_enabled": false,
              "approval_required": true,
              "country_code": "DE",
              "city_name": "Berlin",
              "serial_number": "C02XJ0J0JGH7",
              "extra_dns_labels": ["stage-host-1"],
              "ephemeral": false,
              "local_flags": {"rosenpass_enabled": true},
              "accessible_peers_count": 5
            }
            """
        )
        assertEquals("stage-host-1", peer.name)
        assertEquals("10.64.0.1", peer.ip)
        assertTrue(peer.connected)
        assertTrue(peer.sshEnabled)
        assertTrue(peer.approvalRequired)
        assertEquals("devs", peer.groups.single().name)
        assertEquals(5, peer.accessiblePeersCount)
        assertEquals("Berlin", peer.cityName)
    }

    @Test
    fun peerRequestCarriesTheRequiredFields() {
        val peer = ApiPeer(id = "p", name = "old", sshEnabled = true, loginExpirationEnabled = true, inactivityExpirationEnabled = false)
        val body = Json.parseToJsonElement(AppJson.encodeToString(ApiPeerRequest.serializer(), ApiPeerRequest.of(peer).copy(name = "new"))).jsonObject
        assertEquals(setOf("name", "ssh_enabled", "login_expiration_enabled", "inactivity_expiration_enabled"), body.keys)
        assertEquals("new", body["name"]!!.jsonPrimitive.content)
        assertEquals("true", body["ssh_enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun setupKeyMaskedAndClear() {
        val masked = AppJson.decodeFromString<ApiSetupKey>(
            """
            {
              "id": "2531583362", "key": "A6160****", "name": "Default key",
              "expires": "2023-06-01T14:47:22.291057Z", "type": "reusable", "valid": true, "revoked": false,
              "used_times": 2, "last_used": "2023-05-05T09:00:35.477782Z", "state": "valid",
              "auto_groups": ["ch8i4ug6lnn4g9hqv7m0"], "updated_at": "2023-05-05T09:00:35.477782Z",
              "usage_limit": 0, "ephemeral": true, "allow_extra_dns_labels": true
            }
            """
        )
        assertTrue(masked.isReusable)
        assertEquals(2, masked.usedTimes)
        assertEquals("valid", masked.state)
        // toString never prints a key.
        assertFalse(masked.toString().contains("A6160"))

        val clear = AppJson.decodeFromString<ApiSetupKey>("""{"id":"1","key":"A616097E-FCF0-48FA-9354-CA4A61142761","name":"k","type":"one-off","valid":true,"state":"valid","auto_groups":[]}""")
        assertEquals("A616097E-FCF0-48FA-9354-CA4A61142761", clear.key)
        assertFalse(clear.isReusable)
    }

    @Test
    fun createSetupKeyRequest() {
        val body = Json.parseToJsonElement(
            AppJson.encodeToString(ApiCreateSetupKey.serializer(), ApiCreateSetupKey("Phone", ApiSetupKey.TYPE_ONE_OFF, 86_400, listOf("g1"), 0, false))
        ).jsonObject
        assertEquals("one-off", body["type"]!!.jsonPrimitive.content)
        assertEquals("86400", body["expires_in"]!!.jsonPrimitive.content)
        assertEquals("0", body["usage_limit"]!!.jsonPrimitive.content)
        assertTrue("auto_groups" in body)
    }

    @Test
    fun groupWithPeers() {
        val group = AppJson.decodeFromString<ApiGroup>(
            """
            {
              "id": "ch8i4ug6lnn4g9hqv7m0", "name": "devs", "peers_count": 2, "resources_count": 5, "issued": "api",
              "peers": [{"id": "chacbco6lnnbn6cg5s90", "name": "stage-host-1"}],
              "resources": [{"id": "chacdk86lnnboviihd7g", "type": "host"}]
            }
            """
        )
        assertEquals(2, group.peersCount)
        assertEquals("stage-host-1", group.peers.single().name)
    }

    @Test
    fun accountWithSettings() {
        val account = AppJson.decodeFromString<ApiAccount>(
            """
            {
              "id": "ch8i4ug6lnn4g9hqv7l0",
              "settings": {"peer_login_expiration_enabled": true, "peer_login_expiration": 43200, "dns_domain": "my-org.com", "network_range": "100.64.0.0/16", "extra": {"peer_approval_enabled": true}},
              "domain": "netbird.io", "domain_category": "private",
              "created_at": "2023-05-05T09:00:35.477782Z", "created_by": "google-oauth2|277474792786460067937",
              "onboarding": {"signup_form_pending": true, "onboarding_flow_pending": false}
            }
            """
        )
        assertEquals("netbird.io", account.domain)
        assertTrue(account.settings.peerLoginExpirationEnabled)
        assertEquals(43200L, account.settings.peerLoginExpiration)
        assertEquals("100.64.0.0/16", account.settings.networkRange)
    }

    @Test
    fun nullsAndMissingFieldsTakeDefaults() {
        val peer = AppJson.decodeFromJsonElement<ApiPeer>(Json.parseToJsonElement("""{"id":"x","name":"n","groups":null,"connected":null}"""))
        assertEquals(emptyList<ApiGroupMinimum>(), peer.groups)
        assertFalse(peer.connected)
    }

    @Test
    fun errorBodyMessage() {
        assertEquals("peer not found", NetbirdApi.apiMessage("""{"message":"peer not found","code":404}"""))
        assertNull(NetbirdApi.apiMessage("<html>bad gateway</html>"))
        assertNull(NetbirdApi.apiMessage("""{"message":"  ","code":500}"""))
    }
}
