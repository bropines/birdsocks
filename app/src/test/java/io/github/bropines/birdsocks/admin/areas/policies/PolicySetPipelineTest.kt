package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.api.ApiGroup
import io.github.bropines.birdsocks.admin.api.ApiGroupMinimum
import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiPeerMinimum
import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole set split into a plan, the pipeline's review, the templates. */
class PolicySetPipelineTest {

    private val net = PolicyNet(
        groups = listOf(
            ApiGroup("g-all", ApiGroup.ALL, peers = listOf(ApiPeerMinimum("phone"), ApiPeerMinimum("srv"))),
            ApiGroup("g-ops", "Ops", peers = listOf(ApiPeerMinimum("phone"))),
            ApiGroup("g-srv", "Servers", peers = listOf(ApiPeerMinimum("srv"))),
        ),
        peers = listOf(
            ApiPeer(id = "phone", groups = listOf(ApiGroupMinimum("g-all"), ApiGroupMinimum("g-ops"))),
            ApiPeer(id = "srv", groups = listOf(ApiGroupMinimum("g-all"), ApiGroupMinimum("g-srv"))),
        ),
        postureCheckIds = emptySet(),
        selfPeerId = "phone",
    )

    private val default = PolicyForm("p1", "Default", rules = listOf(RuleForm(id = "r1", name = "Default", sources = listOf("g-all"), destinations = listOf("g-all"))))
    private val ssh = PolicyForm("p2", "SSH", rules = listOf(RuleForm(id = "r2", name = "SSH", protocol = RuleForm.TCP, ports = listOf(PortSpan(22)), bidirectional = false, sources = listOf("g-ops"), destinations = listOf("g-srv"))))
    private val current = listOf(default, ssh)

    private fun edited(vararg p: PolicyForm) = JsonArray(p.map(PolicyJson::toJson))

    // --- split plan ---

    @Test
    fun theDocumentSplitsIntoCreatesUpdatesAndDeletes() {
        val newOne = PolicyForm(name = "Web", rules = listOf(RuleForm(name = "Web", protocol = RuleForm.TCP, ports = listOf(PortSpan(443)), sources = listOf("g-all"), destinations = listOf("g-srv"))))
        val plan = PolicySet.plan(current, edited(ssh.copy(description = "now described"), newOne), net)
        assertTrue(plan.ok)
        assertEquals(listOf("Web"), plan.creates.map { it.name })
        assertEquals(listOf("p2"), plan.updates.map { it.after.id })
        assertEquals(listOf("p1"), plan.deletes.map { it.id })
        assertEquals(0, plan.unchanged)
        assertEquals(listOf("SSH", "Web"), plan.result(current).map { it.name })
    }

    @Test
    fun theUnchangedDocumentPlansNothing() {
        val plan = PolicySet.plan(current, JsonText.parse(PolicySet.text(current))!!, net)
        assertTrue(plan.ok && plan.empty)
        assertEquals(2, plan.unchanged)
    }

    @Test
    fun aBrokenDocumentPlansNoWritesAtAll() {
        fun kinds(e: kotlinx.serialization.json.JsonElement) = PolicySet.plan(current, e, net).let { assertTrue(it.empty); it.problems.map { p -> p.kind } }
        assertEquals(listOf(SetProblem.Kind.NOT_ARRAY), kinds(JsonObject(emptyMap())))
        assertEquals(listOf(SetProblem.Kind.NOT_OBJECT), kinds(JsonArray(listOf(JsonPrimitive(1)))))
        assertEquals(listOf(SetProblem.Kind.DUPLICATE_ID), kinds(edited(default, default)))
        assertEquals(listOf(SetProblem.Kind.UNKNOWN_ID), kinds(edited(default.copy(id = "nope"))))
        // One invalid element among good ones: the good ones are not written either.
        val broken = PolicySet.plan(current, edited(default, ssh.copy(name = "")), net)
        assertFalse(broken.ok)
        assertTrue(broken.empty)
        assertEquals(1, broken.problems.single().index)
        assertEquals(PolicyProblem.Kind.NO_NAME, broken.problems.single().policy?.kind)
    }

    // --- pipeline ---

    @Test
    fun aSaveRunsEveryStep() {
        val cand = ssh.copy(rules = ssh.rules.map { it.copy(sources = listOf("g-all")) })
        val r = PolicyPipeline.review(ssh, cand, ssh, current, net)
        assertNull(r.stoppedAt)
        assertTrue(r.changed && r.added > 0 && r.removed > 0)
        assertEquals(listOf(RiskKind.ALL_SOURCE), r.risks.map { it.kind })
        assertFalse(r.access.cutOff)
        assertTrue(r.passed(acceptCutOff = false))
    }

    @Test
    fun theFirstFailingStepStopsTheRest() {
        assertEquals(ReviewStep.LOCAL, PolicyPipeline.review(ssh, ssh.copy(name = ""), ssh, current, net).stoppedAt)
        assertEquals(ReviewStep.DIFF, PolicyPipeline.review(ssh, ssh, ssh, current, net).stoppedAt)
        // A rule without a name gets the policy's before the comparison: not a change by itself.
        assertEquals(ReviewStep.DIFF, PolicyPipeline.review(ssh, ssh.copy(rules = ssh.rules.map { it.copy(name = "") }), ssh, current, net).stoppedAt)
    }

    @Test
    fun aServerCopyChangedMeanwhileIsMergedOrRefused() {
        val theirs = ssh.copy(description = "theirs")
        val mineElsewhere = ssh.copy(rules = ssh.rules.map { it.copy(ports = listOf(PortSpan(2222))) })
        val clean = PolicyPipeline.review(ssh, mineElsewhere, theirs, current, net)
        assertEquals(ReviewStep.FRESH, clean.stoppedAt)
        val merged = clean.conflict?.merged
        assertNotNull(merged)
        assertEquals("theirs", merged!!.description)
        assertEquals(listOf(PortSpan(2222)), merged.rules[0].ports)
        // Both changed the description: a conflict, no merge.
        val clash = PolicyPipeline.review(ssh, ssh.copy(description = "mine"), theirs, current, net)
        assertNull(clash.conflict?.merged)
        assertTrue(clash.conflict!!.conflictLines.isNotEmpty())
    }

    @Test
    fun cuttingThisDeviceOffNeedsAnAcknowledgement() {
        val r = PolicyPipeline.reviewSet(current, current, PolicySet.plan(current, JsonArray(emptyList()), net), net)
        assertTrue(r.access.cutOff)
        assertFalse(r.passed(acceptCutOff = false))
        assertTrue(r.passed(acceptCutOff = true))
        // Deleting SSH alone leaves Default: fine.
        val d = PolicyPipeline.reviewDelete(ssh, current, net)
        assertFalse(d.access.cutOff)
        assertEquals(1, d.access.lost.size)
        // A set built from an older read is refused.
        val stale = PolicyPipeline.reviewSet(current, current + ssh.copy(id = "p3"), PolicySet.plan(current, edited(default, ssh), net), net)
        assertEquals(ReviewStep.FRESH, stale.stoppedAt)
    }

    @Test
    fun aToggleIsReviewedAsASave() {
        val r = PolicyPipeline.reviewToggle(default, false, current, net)
        assertTrue(r.changed)
        assertFalse(r.access.cutOff)
        assertEquals(ReviewStep.FRESH, PolicyPipeline.reviewToggle(default.copy(id = "gone"), false, current, net).stoppedAt)
    }

    // --- templates ---

    @Test
    fun everyTemplateBuildsAValidPolicy() {
        PolicyTemplates.all.forEach { t ->
            val values = PolicyTemplates.defaults(t, net.allGroupId).toMutableMap()
            t.fields.filter { it.kind == FieldKind.GROUPS && values[it.key].isNullOrEmpty() }.forEach { values[it.key] = listOf("g-ops") }
            val p = t.build(TemplateValues(values)).policy
            assertNotNull(t.id, p)
            assertEquals(t.id, emptyList<PolicyProblem>(), PolicyValidation.problems(p!!, net))
            assertTrue(t.id, p.rules.all { it.name.isNotBlank() })
            assertEquals(t.id, t.denies, p.rules.all { !it.isAccept })
        }
    }

    @Test
    fun templatesSayWhatIsMissing() {
        val t = PolicyTemplates.SSH_ADMINS
        val r = t.build(TemplateValues(PolicyTemplates.defaults(t, "g-all") + ("ports" to listOf("22, nope"))))
        assertNull(r.policy)
        assertEquals(listOf("admins", "servers", "ports"), r.missing)
        assertEquals(listOf("g-all"), PolicyTemplates.defaults(PolicyTemplates.WEB_SERVER, "g-all")["clients"])
        assertEquals(emptyList<String>(), PolicyTemplates.defaults(PolicyTemplates.WEB_SERVER, null)["clients"])
    }

    @Test
    fun dnsTemplateOpensUdpAndTcp53() {
        val p = PolicyTemplates.DNS_SERVER.build(TemplateValues(mapOf("name" to listOf("DNS"), "clients" to listOf("g-all"), "servers" to listOf("g-srv")))).policy!!
        assertEquals(listOf(RuleForm.UDP, RuleForm.TCP), p.rules.map { it.protocol })
        assertTrue(p.rules.all { it.ports == listOf(PortSpan(53)) })
        assertEquals(listOf("DNS UDP", "DNS TCP"), p.rules.map { it.name })
    }
}
