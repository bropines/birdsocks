package io.github.bropines.birdsocks.admin.areas.policies

import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The policy document: GET → document → form → document, and the request bodies. */
class PolicyModelTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val server = obj(
        """
        {
          "id": "p1", "name": "SSH", "description": "admins", "enabled": true,
          "source_posture_checks": ["pc1"],
          "future_field": {"x": 1},
          "rules": [
            {
              "id": "r1", "name": "SSH", "description": "", "enabled": true, "action": "accept",
              "bidirectional": false, "protocol": "tcp", "ports": ["22", "2222"],
              "sources": [{"id": "g-admins", "name": "Admins", "peers_count": 2}],
              "destinations": [{"id": "g-servers", "name": "Servers"}],
              "sourceResource": null,
              "rule_extra": "keep me"
            },
            {
              "id": "r2", "name": "res", "enabled": true, "action": "drop", "bidirectional": true, "protocol": "udp",
              "port_ranges": [{"start": 53, "end": 53}, {"start": 8000, "end": 8100}],
              "sources": null,
              "sourceResource": {"id": "res1", "type": "host"},
              "destinations": [{"id": "g-servers", "name": "Servers"}]
            }
          ]
        }
        """
    )

    @Test
    fun normalizeTurnsGroupsIntoIdsAndDropsNulls() {
        val doc = PolicyJson.normalize(server)
        val r1 = (doc["rules"] as JsonArray)[0].jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("g-admins"))), r1["sources"])
        assertFalse("a null resource is left out", "sourceResource" in r1)
        val r2 = (doc["rules"] as JsonArray)[1].jsonObject
        assertFalse("sources go when a resource stands in", "sources" in r2)
        assertEquals("res1", r2["sourceResource"]!!.jsonObject["id"].let { (it as JsonPrimitive).content })
    }

    @Test
    fun unknownFieldsSurviveEditsAndTheJsonMode() {
        val form = PolicyJson.fromJson(server)
        val edited = PolicyEdits.updateRule(PolicyEdits.rename(form, "SSH v2"), 0) { PolicyEdits.togglePreset(it, PortPreset.HTTPS) }
        val doc = PolicyJson.toJson(edited)
        assertEquals(obj("""{"x": 1}"""), doc["future_field"])
        assertEquals(JsonPrimitive("keep me"), (doc["rules"] as JsonArray)[0].jsonObject["rule_extra"])
        // Through the text and back: the same document.
        val again = PolicyJson.fromJson(JsonText.parse(PolicyJson.text(edited)) as JsonObject)
        assertEquals(doc, PolicyJson.toJson(again))
        assertTrue(PolicyJson.same(edited, again))
    }

    @Test
    fun portsKeepTheirShape() {
        val form = PolicyJson.fromJson(server)
        assertEquals(listOf(PortSpan(22), PortSpan(2222)), form.rules[0].ports)
        assertFalse(form.rules[0].portsAsRanges)
        val r2 = PolicyJson.ruleToJson(form.rules[1])
        assertTrue("ranges stay ranges, even a one-port range", "port_ranges" in r2 && "ports" !in r2)
        // A range added to plain ports switches the rule to port_ranges, each port a range.
        val mixed = PolicyJson.ruleToJson(PolicyEdits.setPorts(form.rules[0], listOf(PortSpan(22), PortSpan(8000, 8100))))
        assertTrue("port_ranges" in mixed && "ports" !in mixed)
        assertEquals(obj("""{"start": 22, "end": 22}"""), (mixed["port_ranges"] as JsonArray)[0])
        // A port that is not a number is kept for the check to name.
        val bad = PolicyJson.ruleFromJson(obj("""{"protocol": "tcp", "ports": ["80", "http"]}"""))
        assertEquals(listOf("http"), bad.badPorts)
        assertEquals(JsonArray(listOf(JsonPrimitive("80"), JsonPrimitive("http"))), PolicyJson.ruleToJson(bad)["ports"])
    }

    @Test
    fun requestBodies() {
        val doc = PolicyJson.normalize(server)
        val put = PolicyJson.request(doc, create = false)
        assertFalse("id" in put)
        assertEquals(JsonPrimitive("r1"), (put["rules"] as JsonArray)[0].jsonObject["id"])
        val post = PolicyJson.request(doc, create = true)
        assertNull((post["rules"] as JsonArray)[0].jsonObject["id"])
        assertEquals(JsonArray(listOf(JsonPrimitive("pc1"))), post["source_posture_checks"])
    }

    @Test
    fun aMissingFieldReadsAsItsDefault() {
        val f = PolicyJson.fromJson(obj("""{"name": "x", "rules": [{"sources": ["a"], "destinations": ["b"]}]}"""))
        assertNull(f.id)
        assertFalse(f.enabled)
        assertEquals(RuleForm.ALL, f.rules[0].protocol)
        assertEquals(RuleForm.ACCEPT, f.rules[0].action)
    }

    @Test
    fun meaningIgnoresIdsAndOrder() {
        val a = PolicyJson.fromJson(server)
        val b = a.copy(id = null, rules = a.rules.map { it.copy(id = null, sources = it.sources.reversed()) })
        assertEquals(PolicyMeaning.of(a), PolicyMeaning.of(b))
        assertFalse(PolicyMeaning.of(a) == PolicyMeaning.of(a.copy(enabled = false)))
    }
}
