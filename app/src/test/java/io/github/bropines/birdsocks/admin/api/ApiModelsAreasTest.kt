package io.github.bropines.birdsocks.admin.api

import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The area objects, decoded from sample answers written from NetBird's
 * interface description (openapi.yml, v0.83), and the requests built from
 * them encoded back with what a PUT needs.
 */
class ApiModelsAreasTest {
    private inline fun <reified T> decode(json: String): T = AppJson.decodeFromString(json)

    @Test
    fun peerWithEveryNewField() {
        val p = decode<ApiPeer>(
            """{"id":"chacbco6lnnbn6cg5s90","name":"stage-host-1","created_at":"2023-05-05T09:00:35.477782Z","ip":"10.64.0.1",
            "ipv6":"fd00::1","network_addresses":[{"net_ip":"192.168.1.5/24","mac":"aa:bb"}],"connection_ip":"35.64.0.1","connected":true,
            "last_seen":"2023-05-05T10:05:26.420578Z","os":"Darwin 13.2.1","kernel_version":"23.2.0","geoname_id":2643743,"version":"0.14.0",
            "groups":[{"id":"g1","name":"All","peers_count":2,"resources_count":0,"issued":"api"}],"ssh_enabled":true,"user_id":"u1",
            "hostname":"stage-host-1","ui_version":"0.14.0","dns_label":"stage-host-1.netbird.cloud","login_expiration_enabled":false,
            "login_expired":false,"last_login":"2023-05-05T09:00:35.477782Z","inactivity_expiration_enabled":false,"approval_required":true,
            "disapproval_reason":"","country_code":"DE","city_name":"Berlin","serial_number":"C02XJ0J0JGH7","extra_dns_labels":["a"],
            "ephemeral":false,"local_flags":{"rosenpass_enabled":true,"block_inbound":true},"accessible_peers_count":5}"""
        )
        assertEquals(listOf("192.168.1.5/24"), p.networkAddresses.map { it.netIp })
        assertTrue(p.approvalRequired)
        assertTrue(p.localFlags!!.rosenpassEnabled && p.localFlags!!.blockInbound)
        assertEquals(5, p.accessiblePeersCount)
        assertEquals(2643743, p.geonameId)
    }

    @Test
    fun peerRequestLeavesOptionalFieldsOut() {
        val body = AppJson.encodeToString(ApiPeerRequest.serializer(), ApiPeerRequest.of(ApiPeer(name = "a", sshEnabled = true)))
        val obj = AppJson.parseToJsonElement(body).jsonObject
        assertEquals(setOf("name", "ssh_enabled", "login_expiration_enabled", "inactivity_expiration_enabled"), obj.keys)
        val approve = AppJson.parseToJsonElement(AppJson.encodeToString(ApiPeerRequest.serializer(), ApiPeerRequest.of(ApiPeer(name = "a")).copy(approvalRequired = false))).jsonObject
        assertEquals("false", approve["approval_required"].toString())
    }

    @Test
    fun accountWithFullSettings() {
        val a = decode<ApiAccount>(
            """{"id":"ch8i4ug6lnn4g9hqv7l0","domain":"netbird.io","domain_category":"private","created_at":"2023-05-05T09:00:35.477782Z",
            "created_by":"google-oauth2|277474792786460067937","onboarding":{"signup_form_pending":true,"onboarding_flow_pending":false},
            "settings":{"peer_login_expiration_enabled":true,"peer_login_expiration":43200,"peer_inactivity_expiration_enabled":true,
            "peer_inactivity_expiration":43200,"regular_users_view_blocked":true,"groups_propagation_enabled":true,"jwt_groups_enabled":true,
            "jwt_groups_claim_name":"roles","jwt_allow_groups":["Admin"],"routing_peer_dns_resolution_enabled":true,"dns_domain":"my-org.org",
            "network_range":"100.64.0.0/16","peer_expose_enabled":false,"peer_expose_groups":[],"lazy_connection_enabled":true,
            "extra":{"peer_approval_enabled":true,"user_approval_required":false,"network_traffic_logs_enabled":true,
            "network_traffic_logs_groups":["g1"],"network_traffic_packet_counter_enabled":true},"auto_update_version":"0.51.2"}}"""
        )
        assertEquals(43200, a.settings.peerLoginExpiration)
        assertTrue(a.settings.extra!!.peerApprovalEnabled)
        assertEquals(listOf("Admin"), a.settings.jwtAllowGroups)
        assertTrue(a.onboarding!!.signupFormPending)
        // Sent back, what was read is what goes.
        val out = AppJson.parseToJsonElement(AppJson.encodeToString(ApiAccountRequest.serializer(), ApiAccountRequest(a.settings))).jsonObject
        assertEquals("\"roles\"", out["settings"]!!.jsonObject["jwt_groups_claim_name"].toString())
    }

    @Test
    fun policyAndItsRequest() {
        val p = decode<ApiPolicy>(
            """{"id":"ch8i4ug6lnn4g9hqv7mg","name":"ch8i4ug6lnn4g9hqv7mg","description":"This is a default policy","enabled":true,
            "source_posture_checks":["chacdk86lnnboviihd70"],"rules":[{"id":"r1","name":"Default","description":"","enabled":true,
            "action":"accept","bidirectional":true,"protocol":"tcp","ports":["80","443"],"port_ranges":[{"start":80,"end":320}],
            "authorized_groups":{"group1":["user1"]},"sources":[{"id":"g1","name":"devs","peers_count":2,"resources_count":0}],
            "destinations":[],"destinationResource":{"id":"res1","type":"host"}}]}"""
        )
        val r = p.rules.single()
        assertEquals(ApiPolicyRule.PROTOCOL_TCP, r.protocol)
        assertEquals(320, r.portRanges.single().end)
        assertEquals("host", r.destinationResource!!.type)
        val req = ApiPolicyRequest.of(p)
        val rr = req.rules.single()
        assertEquals(listOf("g1"), rr.sources)
        // A resource destination is sent instead of the (empty) group list.
        assertNull(rr.destinations)
        assertEquals("r1", rr.id)
    }

    @Test
    fun postureCheckWithEveryCheck() {
        val c = decode<ApiPostureCheck>(
            """{"id":"ch8i4ug6lnn4g9hqv7mg","name":"Default","description":"desc","checks":{
            "nb_version_check":{"min_version":"14.3"},
            "os_version_check":{"android":{"min_version":"13"},"linux":{"min_kernel_version":"5.3.3"}},
            "geo_location_check":{"locations":[{"country_code":"DE","city_name":"Berlin"}],"action":"allow"},
            "peer_network_range_check":{"ranges":["192.168.1.0/24"],"action":"deny"},
            "process_check":{"processes":[{"linux_path":"/usr/bin/x"}]},
            "certificate_check":{"ca_certificates":["-----BEGIN CERTIFICATE-----"]}}}"""
        )
        assertEquals("14.3", c.checks.nbVersionCheck!!.minVersion)
        assertEquals("5.3.3", c.checks.osVersionCheck!!.linux!!.minKernelVersion)
        assertEquals("deny", c.checks.peerNetworkRangeCheck!!.action)
        assertEquals("/usr/bin/x", c.checks.processCheck!!.processes.single().linuxPath)
        assertEquals(1, c.checks.certificateCheck!!.caCertificates.size)
    }

    @Test
    fun networksResourcesRouters() {
        val n = decode<ApiNetwork>("""{"id":"n1","routers":["r1"],"routing_peers_count":2,"resources":["res1"],"policies":["p1"],"name":"Remote","description":"d"}""")
        assertEquals(2, n.routingPeersCount)
        val r = decode<ApiNetworkResource>("""{"id":"res1","type":"subnet","groups":[{"id":"g1","name":"devs"}],"name":"LAN","address":"192.168.0.0/24","enabled":true}""")
        assertEquals("subnet", r.type)
        assertEquals(listOf("g1"), ApiNetworkResourceRequest.of(r).groups)
        val router = decode<ApiNetworkRouter>("""{"id":"rt1","peer_groups":["g2"],"metric":9999,"masquerade":true,"enabled":true}""")
        assertNull(router.peer)
        assertEquals(listOf("g2"), router.peerGroups)
    }

    @Test
    fun routeAndExitNode() {
        val r = decode<ApiRoute>(
            """{"id":"rt","network_type":"IPv4","description":"exit","network_id":"exit","enabled":true,"peer":"p1",
            "network":"0.0.0.0/0","metric":9999,"masquerade":true,"groups":["g1"],"keep_route":true,"access_control_groups":["g2"]}"""
        )
        assertTrue(r.isExitNode)
        assertEquals(listOf("g2"), ApiRouteRequest.of(r).accessControlGroups)
    }

    @Test
    fun dnsObjects() {
        val g = decode<ApiNameserverGroup>(
            """{"id":"ns","name":"Google DNS","description":"d","nameservers":[{"ip":"8.8.8.8","ns_type":"udp","port":53}],
            "enabled":true,"groups":["g1"],"primary":true,"domains":["example.com"],"search_domains_enabled":true}"""
        )
        assertEquals("8.8.8.8", g.nameservers.single().ip)
        val z = decode<ApiDnsZone>(
            """{"id":"z1","name":"Office","domain":"office.internal","enabled":true,"enable_search_domain":false,
            "distribution_groups":["g1"],"records":[{"id":"rec","name":"www.office.internal","type":"A","content":"10.0.0.1","ttl":300}]}"""
        )
        assertEquals(300, z.records.single().ttl)
        assertEquals(listOf("g1"), ApiDnsZoneRequest.of(z).distributionGroups)
    }

    @Test
    fun usersTokensInvites() {
        val u = decode<ApiUser>("""{"id":"u","email":"a@b.c","name":"A","role":"user","status":"active","auto_groups":[],"is_blocked":false,"pending_approval":true,"issued":"jwt","idp_id":"okta"}""")
        assertTrue(u.pendingApproval)
        assertEquals("okta", u.idpId)
        val t = decode<ApiPersonalAccessToken>("""{"id":"t1","name":"My first token","expiration_date":"2023-05-05T14:38:28.977616Z","created_by":"u","created_at":"2023-05-02T14:48:20.465209Z","last_used":"2023-05-04T12:45:25.9723616Z"}""")
        assertEquals("My first token", t.name)
        val gen = decode<ApiPersonalAccessTokenGenerated>("""{"plain_token":"nbp_secret_value","personal_access_token":{"id":"t1","name":"x"}}""")
        assertEquals("nbp_secret_value", gen.plainToken)
        assertFalse("nbp_secret_value" in gen.toString())
        val inv = decode<ApiUserInvite>("""{"id":"i","email":"a@b.c","name":"A","role":"user","auto_groups":["g"],"expires_at":"2024-01-01T00:00:00Z","created_at":"2023-12-25T00:00:00Z","expired":false,"invite_token":"nbi_secret"}""")
        assertEquals("nbi_secret", inv.inviteToken)
        assertFalse("nbi_secret" in inv.toString())
    }

    @Test
    fun eventsAndTraffic() {
        val e = decode<ApiEvent>(
            """{"id":"10","timestamp":"2023-05-05T10:04:37.473542Z","activity":"Route created","activity_code":"route.add",
            "initiator_id":"u","initiator_name":"John","initiator_email":"j@x","target_id":"rt","meta":{"name":"my route"}}"""
        )
        assertEquals("route.add", e.activityCode)
        assertEquals("my route", e.meta["name"])
        val t = decode<ApiNetworkTrafficEvent>(
            """{"flow_id":"f","reporter_id":"p","source":{"id":"p1","type":"PEER","name":"a","geo_location":{"city_name":"Berlin","country_code":"DE"},"os":"Linux","address":"100.64.0.1:5432","dns_label":"a.netbird.cloud"},
            "destination":{"id":"p2","type":"PEER","name":"b","geo_location":{"city_name":"","country_code":""},"os":"","address":"x","dns_label":""},
            "user":{"id":"u","email":"e","name":"n"},"policy":{"id":"pl","name":"All"},"icmp":{"type":0,"code":0},"protocol":6,"direction":"INGRESS",
            "rx_bytes":1234,"rx_packets":5,"tx_bytes":1234,"tx_packets":5,"num_of_starts":1,"num_of_ends":1,"num_of_drops":0,
            "window_start":"2024-01-01T00:00:00Z","window_end":"2024-01-01T00:05:00Z","events":[{"type":"TYPE_START","timestamp":"2024-01-01T00:00:00Z"}]}"""
        )
        assertEquals(6, t.protocol)
        assertEquals("DE", t.source.geoLocation.countryCode)
    }

    @Test
    fun integrationKeepsItsSecretsOutOfToString() {
        val i = decode<ApiIntegration>("""{"id":1,"account_id":"a","enabled":true,"platform":"datadog","config":{"api_key":"dd_secret","site":"datadoghq.eu"}}""")
        assertEquals("dd_secret", i.config["api_key"])
        assertFalse("dd_secret" in i.toString())
        val req = ApiIntegrationRequest("s3", mapOf("secret" to "aws_secret"), true)
        assertFalse("aws_secret" in req.toString())
    }

    @Test
    fun identityProviderInstanceAndJobs() {
        val idp = decode<ApiIdentityProvider>("""{"id":"i","type":"okta","name":"Okta","issuer":"https://x.okta.com","client_id":"cid"}""")
        assertEquals("okta", idp.type)
        val req = ApiIdentityProviderRequest("oidc", "n", "https://i", "c", "very_secret")
        assertFalse("very_secret" in req.toString())
        val v = decode<ApiInstanceVersion>("""{"management_current_version":"0.83.1","management_available_version":"0.84.0","management_update_available":true}""")
        assertTrue(v.managementUpdateAvailable)
        assertTrue(decode<ApiInstanceStatus>("""{"setup_required":true}""").setupRequired)
        val job = decode<ApiJob>(
            """{"id":"j","created_at":"2024-01-01T00:00:00Z","triggered_by":"u","status":"succeeded",
            "workload":{"type":"bundle","parameters":{"bundle_for":true,"bundle_for_time":5,"log_file_count":2,"anonymize":false},"result":{"upload_key":"k"}}}"""
        )
        assertEquals("k", job.workload.result!!.uploadKey)
        assertEquals(5, job.workload.parameters.bundleForTime)
    }

    @Test
    fun reverseProxyServiceAndDomain() {
        val s = decode<ApiService>(
            """{"id":"s","name":"app","domain":"app.example.com","mode":"http","targets":[{"target_id":"p1","target_type":"peer","protocol":"http","port":8080,"enabled":true,"options":{"skip_tls_verify":true,"custom_headers":{"X-A":"b"}}}],
            "enabled":true,"auth":{"password_auth":{"enabled":true,"password":"pw_secret"},"pin_auth":{"enabled":false,"pin":""},"bearer_auth":{"enabled":true,"distribution_groups":["g"]},"link_auth":{"enabled":false},"header_auths":[{"enabled":true,"header":"X-Key","value":"hv_secret"}]},
            "access_restrictions":{"allowed_countries":["DE"],"crowdsec_mode":"enforce"},"meta":{"created_at":"2024-01-01T00:00:00Z","status":"active"},"private":true,"access_groups":["g"]}"""
        )
        assertEquals(8080, s.targets.single().port)
        assertEquals("active", s.meta.status)
        assertEquals(true, s.isPrivate)
        assertFalse("pw_secret" in s.auth.passwordAuth.toString())
        assertFalse("hv_secret" in s.auth.headerAuths!!.single().toString())
        val req = AppJson.parseToJsonElement(AppJson.encodeToString(ApiServiceRequest.serializer(), ApiServiceRequest.of(s))).jsonObject
        assertEquals("true", req["private"].toString())
        val d = decode<ApiReverseProxyDomain>("""{"id":"d","domain":"example.com","validated":false,"type":"custom","target_cluster":"eu.proxy.netbird.io"}""")
        assertEquals("custom", d.type)
    }

    @Test
    fun groupRequestCarriesMembersById() {
        val g = decode<ApiGroup>("""{"id":"g","name":"devs","peers_count":1,"resources_count":1,"issued":"api","peers":[{"id":"p1","name":"a"}],"resources":[{"id":"r1","type":"host"}]}""")
        val req = ApiGroupRequest.of(g)
        assertEquals(listOf("p1"), req.peers)
        assertEquals("host", req.resources!!.single().type)
        assertFalse(g.isAll)
    }
}
