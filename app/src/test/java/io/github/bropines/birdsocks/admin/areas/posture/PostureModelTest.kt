package io.github.bropines.birdsocks.admin.areas.posture

import io.github.bropines.birdsocks.admin.api.ApiPolicy
import io.github.bropines.birdsocks.admin.ui.JsonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The posture check document, its validation and who uses it. */
class PostureModelTest {

    private val server = Json.parseToJsonElement(
        """
        {
          "id": "pc1", "name": "Strict", "description": "",
          "checks": {
            "nb_version_check": {"min_version": "0.28.0"},
            "os_version_check": {"android": {"min_version": "13"}, "linux": {"min_kernel_version": "5.3.3"}},
            "geo_location_check": {"locations": [{"country_code": "DE", "city_name": "Berlin"}, {"country_code": "NL"}], "action": "allow"},
            "peer_network_range_check": {"ranges": ["192.168.1.0/24", "2001:db8::/32"], "action": "deny"},
            "process_check": {"processes": [{"linux_path": "/usr/bin/netbird", "mac_path": ""}]},
            "certificate_check": {"ca_certificates": ["-----BEGIN CERTIFICATE-----"]},
            "future_check": {"x": true}
          },
          "top_extra": 7
        }
        """
    ).jsonObject

    @Test
    fun everyTypeReadsAndWritesBack() {
        val f = PostureJson.fromJson(server)
        assertEquals("0.28.0", f.nbVersion?.minVersion)
        assertEquals("13", f.os?.android)
        assertNull(f.os?.ios)
        assertEquals("5.3.3", f.os?.linux)
        assertEquals(listOf(LocationForm("DE", "Berlin"), LocationForm("NL", "")), f.geo?.locations)
        assertEquals(DENY, f.network?.action)
        assertEquals("/usr/bin/netbird", f.process?.processes?.single()?.linuxPath)
        assertEquals(setOf("certificate_check", "future_check"), f.otherChecks.keys)
        val back = PostureJson.toJson(f)
        assertEquals(server["top_extra"], back["top_extra"])
        val checks = back["checks"]!!.jsonObject
        assertEquals(server["checks"]!!.jsonObject["certificate_check"], checks["certificate_check"])
        // An empty path is left out; the rest is as it came.
        assertFalse("mac_path" in checks["process_check"].toString())
        val again = PostureJson.fromJson(JsonText.parse(PostureJson.text(f)) as JsonObject)
        assertTrue(PostureJson.same(f, again))
        assertFalse("id" in PostureJson.request(f))
    }

    @Test
    fun togglingAddsAndRemovesParts() {
        val f = PostureForm(name = "x").toggle(CheckType.GEO_LOCATION, true).toggle(CheckType.PROCESS, true)
        assertEquals(listOf("geo_location_check", "process_check"), f.types)
        assertTrue(f.toggle(CheckType.GEO_LOCATION, false).geo == null)
        // Switching on again keeps what was there.
        val g = f.copy(geo = GeoForm(DENY, listOf(LocationForm("FR"))))
        assertEquals(DENY, g.toggle(CheckType.GEO_LOCATION, true).geo?.action)
    }

    @Test
    fun validationFollowsTheServer() {
        assertTrue(PostureValidation.problems(PostureJson.fromJson(server)).isEmpty())
        fun kinds(f: PostureForm) = PostureValidation.problems(f).map { it.kind }.toSet()
        assertEquals(setOf(PostureProblem.Kind.NO_NAME, PostureProblem.Kind.NO_CHECKS), kinds(PostureForm()))
        assertEquals(setOf(PostureProblem.Kind.BAD_VERSION), kinds(PostureForm("i", "n", nbVersion = VersionForm("latest"))))
        assertEquals(setOf(PostureProblem.Kind.NO_OS), kinds(PostureForm(name = "n", os = OsForm())))
        assertEquals(setOf(PostureProblem.Kind.BAD_COUNTRY), kinds(PostureForm(name = "n", geo = GeoForm(locations = listOf(LocationForm("Germany"))))))
        assertEquals(setOf(PostureProblem.Kind.NO_RANGES), kinds(PostureForm(name = "n", network = NetworkForm())))
        assertEquals(setOf(PostureProblem.Kind.BAD_RANGE), kinds(PostureForm(name = "n", network = NetworkForm(ranges = listOf("10.0.0.0/33")))))
        assertEquals(setOf(PostureProblem.Kind.EMPTY_PROCESS), kinds(PostureForm(name = "n", process = ProcessesForm(listOf(ProcessForm())))))
        // Only a check type this editor does not draw still counts as a check.
        assertTrue(PostureValidation.problems(PostureJson.fromJson(Json.parseToJsonElement("""{"name":"c","checks":{"certificate_check":{"ca_certificates":["x"]}}}""").jsonObject)).isEmpty())
    }

    @Test
    fun versionsAndRanges() {
        listOf("0.28.0", "v1.2", "13", "17.3.1", "0.28.0-beta.1", "6.6.12+build").forEach { assertTrue(it, PostureValidation.versionOk(it)) }
        listOf("", "latest", "1..2", "1.2.").forEach { assertFalse(it, PostureValidation.versionOk(it)) }
        listOf("192.168.1.0/24", "2.2.2.2/32", "2001:db8:1234:1a00::/56", "::/0").forEach { assertTrue(it, PostureValidation.cidrOk(it)) }
        listOf("192.168.1.0", "256.1.1.1/8", "10.0.0.0/33", "2001:db8::1::/64", "fe80::/129").forEach { assertFalse(it, PostureValidation.cidrOk(it)) }
    }

    @Test
    fun usedByNamesThePolicies() {
        val policies = listOf(ApiPolicy(id = "a", name = "A", sourcePostureChecks = listOf("pc1")), ApiPolicy(id = "b", name = "B"))
        assertEquals(listOf("A"), PostureValidation.usedBy("pc1", policies).map { it.name })
        assertTrue(PostureValidation.usedBy("pc2", policies).isEmpty())
    }
}
