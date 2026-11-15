package io.github.bropines.birdsocks.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class AdminSessionDiskTest {
    private fun profile(name: String, scheme: String, host: String) = """
        {"Name": "$name", "PrivateKey": "never-read",
         "ManagementURL": {"Scheme": "$scheme", "Opaque": "", "User": null, "Host": "$host", "Path": ""},
         "AdminURL": {"Scheme": "https", "Host": "app.netbird.io:443"}}
    """.trimIndent()

    @Test fun theDefaultProfileHasNoNameInItsFile() {
        val k = AdminSession.knownFromProfile("default", profile("", "https", "nb.example.org:443"))
        assertEquals(AdminSession.KnownAccount("default", "https://nb.example.org:443", null), k)
    }

    @Test fun aNamedProfileKeepsItsDisplayName() {
        val k = AdminSession.knownFromProfile("a1b2c3", profile("work", "https", "api.netbird.io:443"))
        assertEquals("work", k?.profile)
        assertEquals("https://api.netbird.io:443", k?.managementUrl)
    }

    @Test fun noServerInTheFileMeansNetBirdCloud() {
        val k = AdminSession.knownFromProfile("default", """{"Name": ""}""")
        assertEquals("https://api.netbird.io:443", k?.managementUrl)
    }
}
