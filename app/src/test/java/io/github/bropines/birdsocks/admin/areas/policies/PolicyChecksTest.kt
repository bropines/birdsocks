package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerMinimum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edits, validation, lint, the access check, coverage, who can reach. */
class PolicyChecksTest {

    private val all = ApiGroup("g-all", ApiGroup.ALL, issued = "api", peers = listOf(ApiPeerMinimum("phone"), ApiPeerMinimum("laptop"), ApiPeerMinimum("srv")))
    private val admins = ApiGroup("g-admins", "Admins", peers = listOf(ApiPeerMinimum("phone")))
    private val servers = ApiGroup("g-servers", "Servers", peers = listOf(ApiPeerMinimum("srv")))
    private val net = PolicyNet(
        groups = listOf(all, admins, servers),
        peers = listOf(
            ApiPeer(id = "phone", name = "phone", groups = listOf(ApiGroupMinimum("g-all"), ApiGroupMinimum("g-admins"))),
            ApiPeer(id = "laptop", name = "laptop", groups = listOf(ApiGroupMinimum("g-all"))),
            ApiPeer(id = "srv", name = "srv", groups = listOf(ApiGroupMinimum("g-all"), ApiGroupMinimum("g-servers"))),
        ),
        postureCheckIds = setOf("pc1"),
        selfPeerId = "phone",
    )

    private fun rule(src: List<String>, dst: List<String>, protocol: String = RuleForm.TCP, ports: List<PortSpan> = listOf(PortSpan(22)), bidi: Boolean = false, action: String = RuleForm.ACCEPT) =
        RuleForm(name = "r", sources = src, destinations = dst, protocol = protocol, ports = if (protocol == RuleForm.TCP || protocol == RuleForm.UDP) ports else emptyList(), bidirectional = bidi, action = action)

    private val defaultPolicy = PolicyForm("p-default", "Default", rules = listOf(rule(listOf("g-all"), listOf("g-all"), RuleForm.ALL, bidi = true)))
    private val ssh = PolicyForm("p-ssh", "SSH", rules = listOf(rule(listOf("g-admins"), listOf("g-servers"))))

    // --- edits ---

    @Test
    fun portsParseAndPresetsToggle() {
        assertEquals(listOf(PortSpan(22), PortSpan(80, 90), PortSpan(443)), PortWords.parse("22, 80-90 443;22").spans)
        assertEquals("70000", PortWords.parse("22, 70000").bad)
        assertEquals("9-1", PortWords.parse("9-1").bad)
        assertEquals(emptyList<PortSpan>(), PortWords.parse("  ").spans)
        val web = PortWords.toggle(listOf(PortSpan(22)), PortPreset.WEB)
        assertEquals(listOf(PortSpan(22), PortSpan(80), PortSpan(443)), web)
        assertTrue(PortWords.isOn(web, PortPreset.HTTPS))
        assertEquals(listOf(PortSpan(22)), PortWords.toggle(web, PortPreset.WEB))
        assertEquals(PortPreset.SSH, PortWords.preset(listOf(PortSpan(22))))
    }

    @Test
    fun protocolDecidesPortsAndSshUsers() {
        val r = RuleForm(protocol = RuleForm.SSH, sources = listOf("g-admins"), authorizedGroups = mapOf("g-admins" to listOf("root")))
        val tcp = PolicyEdits.setPorts(PolicyEdits.setProtocol(r, RuleForm.TCP), listOf(PortSpan(22)))
        assertTrue(tcp.authorizedGroups.isEmpty())
        assertTrue(PolicyEdits.setProtocol(tcp, RuleForm.ICMP).ports.isEmpty())
        assertEquals(listOf(PortSpan(22)), PolicyEdits.setProtocol(tcp, RuleForm.UDP).ports)
        // Users of a group that is no longer a source go with it.
        val ssh = PolicyEdits.setSources(r, listOf("g-servers"))
        assertTrue(ssh.authorizedGroups.isEmpty())
        assertEquals(mapOf("g-admins" to listOf("root", "admin")), PolicyEdits.setAuthorizedUsers(r, "g-admins", listOf(" root", "admin", "", "root")).authorizedGroups)
    }

    @Test
    fun sidesTakeGroupsOrOneResource() {
        val r = rule(listOf("g-admins"), listOf("g-servers"))
        val res = PolicyEdits.setDestinationResource(r, ResourceRef("res1", "host"))
        assertTrue(res.destinations.isEmpty())
        val swapped = PolicyEdits.swap(res)
        assertEquals(ResourceRef("res1", "host"), swapped.sourceResource)
        assertEquals(listOf("g-admins"), swapped.destinations)
        assertNull(PolicyEdits.setSources(swapped, listOf("g-all")).sourceResource)
    }

    @Test
    fun ruleListEdits() {
        val p = PolicyForm(name = "Web", rules = listOf(RuleForm(id = "a"), RuleForm(id = "b", name = "named")))
        assertEquals(listOf("a", null, "b"), PolicyEdits.duplicateRule(p, 0).rules.map { it.id })
        assertEquals(listOf("b", "a"), PolicyEdits.moveRule(p, 1, 0).rules.map { it.id })
        assertEquals(listOf("Web 1", "named"), PolicyEdits.fillRuleNames(p).rules.map { it.name })
        assertEquals("Solo", PolicyEdits.fillRuleNames(PolicyForm(name = "Solo", rules = listOf(RuleForm()))).rules[0].name)
    }

    // --- LOCAL ---

    @Test
    fun validationFindsWhatTheServerRefuses() {
        fun kinds(p: PolicyForm) = PolicyValidation.problems(p, net).map { it.kind }.toSet()
        assertEquals(setOf(PolicyProblem.Kind.NO_NAME, PolicyProblem.Kind.NO_RULES), kinds(PolicyForm()))
        assertTrue(kinds(ssh).isEmpty())
        assertTrue(PolicyProblem.Kind.NO_SOURCES in kinds(ssh.copy(rules = listOf(rule(emptyList(), listOf("g-servers"))))))
        assertTrue(PolicyProblem.Kind.PORTS_NOT_ALLOWED in kinds(ssh.copy(rules = listOf(RuleForm(protocol = RuleForm.ALL, ports = listOf(PortSpan(1)), sources = listOf("g-all"), destinations = listOf("g-all"))))))
        assertTrue(PolicyProblem.Kind.BAD_PORT in kinds(ssh.copy(rules = listOf(rule(listOf("g-all"), listOf("g-all"), ports = listOf(PortSpan(0)))))))
        assertTrue(PolicyProblem.Kind.UNKNOWN_GROUP in kinds(ssh.copy(rules = listOf(rule(listOf("g-gone"), listOf("g-all"))))))
        assertTrue(PolicyProblem.Kind.UNKNOWN_POSTURE_CHECK in kinds(ssh.copy(postureChecks = listOf("pc-gone"))))
        val sshUsers = RuleForm(protocol = RuleForm.SSH, sources = listOf("g-admins", "g-servers"), destinations = listOf("g-all"), authorizedGroups = mapOf("g-admins" to listOf("root")))
        val p = PolicyValidation.problems(ssh.copy(rules = listOf(sshUsers)), net).single()
        assertEquals(PolicyProblem.Kind.SSH_USERS_MISSING, p.kind)
        assertEquals("Servers", p.value)
        assertEquals(0, p.rule)
        // Without the group list, ids cannot be judged and are not.
        assertTrue(PolicyValidation.problems(ssh.copy(rules = listOf(rule(listOf("g-gone"), listOf("g-all")))), PolicyNet()).isEmpty())
    }

    // --- RISKS ---

    @Test
    fun lintNamesWhatOpensUp() {
        val kinds = PolicyLint.findings(defaultPolicy, net).map { it.kind }
        assertEquals(listOf(RiskKind.ALL_TO_ALL, RiskKind.ALL_PROTOCOL), kinds)
        assertTrue(PolicyLint.findings(ssh, net).isEmpty())
        assertTrue(PolicyLint.findings(defaultPolicy.copy(enabled = false), net).isEmpty())
        val wide = ssh.copy(rules = listOf(rule(listOf("g-all"), listOf("g-servers"), ports = listOf(PortSpan(1, 65535)))))
        assertEquals(listOf(RiskKind.ALL_SOURCE, RiskKind.WIDE_PORTS), PolicyLint.findings(wide, net).map { it.kind })
        val sshAny = ssh.copy(rules = listOf(RuleForm(protocol = RuleForm.SSH, sources = listOf("g-admins"), destinations = listOf("g-servers"))))
        assertEquals(listOf(RiskKind.SSH_ANY_USER), PolicyLint.findings(sshAny, net).map { it.kind })
        // Drop rules open nothing.
        assertTrue(PolicyLint.findings(defaultPolicy.copy(rules = defaultPolicy.rules.map { it.copy(action = RuleForm.DROP) }), net).isEmpty())
    }

    @Test
    fun lintReportsOnlyWhatIsNew() {
        assertTrue(PolicyLint.introduced(defaultPolicy, defaultPolicy.copy(description = "x"), net).isEmpty())
        assertEquals(2, PolicyLint.introduced(null, defaultPolicy, net).size)
        val widened = ssh.copy(rules = listOf(rule(listOf("g-all"), listOf("g-servers"))))
        assertEquals(listOf(RiskKind.ALL_SOURCE), PolicyLint.introduced(ssh, widened, net).map { it.kind })
    }

    // --- ACCESS ---

    @Test
    fun accessCheckFindsTheCutOff() {
        val before = listOf(defaultPolicy, ssh)
        // Turning Default off: the phone keeps SSH (it is an admin) — not cut off, one rule lost both ways.
        val r1 = PolicyAccess.check(before, PolicyAccess.replace(before, listOf(defaultPolicy.copy(enabled = false))), net)
        assertFalse(r1.cutOff)
        assertEquals(2, r1.lost.size)
        assertEquals(listOf(true), r1.after.map { it.outgoing })
        // Both off: cut off.
        val r2 = PolicyAccess.check(before, before.map { it.copy(enabled = false) }, net)
        assertTrue(r2.cutOff)
        // Deleting a policy that never touched the phone loses nothing.
        val other = PolicyForm("p-x", "X", rules = listOf(rule(listOf("g-servers"), listOf("g-servers"))))
        val r3 = PolicyAccess.check(before + other, before, net)
        assertTrue(r3.lost.isEmpty())
        // Without this device among the peers, nothing is claimed.
        assertTrue(PolicyAccess.check(before, emptyList(), PolicyNet(net.groups, net.peers, selfPeerId = null)).skipped)
        assertFalse(PolicyAccess.check(before, emptyList(), PolicyNet(net.groups, net.peers, selfPeerId = "nobody")).cutOff)
    }

    @Test
    fun aPeerResourceCountsAsTheDevice() {
        val p = PolicyForm("p", "to phone", rules = listOf(RuleForm(sources = listOf("g-servers"), destinationResource = ResourceRef("phone", "peer"), protocol = RuleForm.ALL, bidirectional = false)))
        val g = PolicyAccess.grants(listOf(p), "phone", net.groupsOf("phone"))
        assertEquals(listOf(false), g.map { it.outgoing })
    }

    @Test
    fun replacePutsChangedPoliciesInPlace() {
        val newOne = PolicyForm(name = "new")
        val r = PolicyAccess.replace(listOf(defaultPolicy, ssh), listOf(ssh.copy(name = "SSH2"), newOne), removed = setOf("p-default"))
        assertEquals(listOf("SSH2", "new"), r.map { it.name })
    }

    // --- coverage and reach ---

    @Test
    fun coverageCountsPeersOnBothSides() {
        assertEquals(setOf("phone", "srv"), PolicyCoverage.peers(ssh, net))
        assertEquals(3, PolicyCoverage.peers(defaultPolicy, net).size)
        assertTrue("g-admins" in net.groupsOf("phone") && "g-all" in net.groupsOf("phone"))
    }

    @Test
    fun whoCanReachAServer() {
        val block = PolicyForm("p-block", "Block laptop", rules = listOf(rule(listOf("g-all"), listOf("g-servers"), RuleForm.ALL, action = RuleForm.DROP)))
        val reaches = WhoCanReach.of(WhoCanReach.Target.Peer("srv"), listOf(defaultPolicy, ssh, block), net)
        assertTrue("blocks come first", reaches.first().blocks)
        assertEquals(setOf("Default", "SSH", "Block laptop"), reaches.map { it.policy }.toSet())
        // Default matches twice: as a destination and, both ways, as a source.
        assertEquals(listOf(false, true), reaches.filter { it.policy == "Default" }.map { it.reverse })
        assertEquals(setOf("phone", "laptop", "srv"), WhoCanReach.peers(reaches, net))
        // Only through SSH: the admins.
        val only = WhoCanReach.of(WhoCanReach.Target.Group("g-servers"), listOf(ssh), net)
        assertEquals(setOf("phone"), WhoCanReach.peers(only, net))
        // A one-way rule does not reach its sources.
        assertTrue(WhoCanReach.of(WhoCanReach.Target.Peer("phone"), listOf(ssh), net).isEmpty())
    }
}
