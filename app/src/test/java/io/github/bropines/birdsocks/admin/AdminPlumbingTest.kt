package io.github.bropines.birdsocks.admin

import io.github.bropines.birdsocks.admin.api.AdminUrls
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.ProxyRoute
import io.github.bropines.birdsocks.admin.secure.CredentialVault
import io.github.bropines.birdsocks.admin.secure.MemoryKeyValueStore
import io.github.bropines.birdsocks.admin.secure.SealedBlob
import io.github.bropines.birdsocks.admin.secure.SecretBox
import io.github.bropines.birdsocks.admin.secure.SecretUnavailableException
import io.github.bropines.birdsocks.ui.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AdminPlumbingTest {
    /** The Keystore box's algorithm with a software key: what the JVM can run. */
    private class JvmSecretBox : SecretBox {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plaintext: String, aad: String): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray()) }
            return SealedBlob.encode(iv, c.doFinal(plaintext.toByteArray()))
        }
        override fun open(blob: String, aad: String): String {
            val (iv, ct) = SealedBlob.decode(blob)
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray()) }
                String(c.doFinal(ct))
            } catch (e: Exception) {
                throw SecretUnavailableException("cannot open", e)
            }
        }
    }

    // --- Vault ---

    @Test
    fun vaultSealsAndNeverStoresPlaintext() {
        val store = MemoryKeyValueStore()
        val vault = CredentialVault(store, JvmSecretBox())
        val id = vault.accountId("default", "https://api.netbird.io:443")
        assertTrue(vault.putToken(id, "  nbp_abc123  "))
        assertTrue(vault.hasToken(id))
        assertEquals("nbp_abc123", vault.token(id))
        assertTrue(store.keys().none { store.getString(it)!!.contains("nbp_abc123") })
        assertTrue(store.keys().none { it.contains("default") || it.contains("netbird") })
    }

    @Test
    fun aBlobMovedToAnotherAccountDoesNotOpen() {
        val store = MemoryKeyValueStore()
        val vault = CredentialVault(store, JvmSecretBox())
        val a = vault.accountId("default", "https://a.example")
        val b = vault.accountId("work", "https://a.example")
        vault.putToken(a, "nbp_a")
        store.putString("$b/pat", store.getString("$a/pat"))
        try {
            vault.token(b)
            fail("the blob is bound to its account")
        } catch (_: SecretUnavailableException) {}
    }

    @Test
    fun accountIdsFollowTheServerNotItsSpelling() {
        val vault = CredentialVault(MemoryKeyValueStore(), JvmSecretBox())
        assertEquals(vault.accountId("p", "https://Example.com"), vault.accountId("p", "https://example.com:443/"))
        assertNotEquals(vault.accountId("p", "https://example.com"), vault.accountId("p", "https://example.com:33073"))
        assertNotEquals(vault.accountId("p", "https://example.com"), vault.accountId("q", "https://example.com"))
    }

    @Test
    fun readOnlyByDefaultAndForgetClearsAll() {
        val store = MemoryKeyValueStore()
        val vault = CredentialVault(store, JvmSecretBox())
        val id = vault.accountId("default", "https://x.example")
        assertTrue(vault.isReadOnly(id))
        vault.setReadOnly(id, false)
        assertFalse(vault.isReadOnly(id))
        vault.putToken(id, "nbp_x")
        vault.forget(id)
        assertNull(vault.token(id))
        assertTrue(vault.isReadOnly(id))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun aCorruptBlobIsReportedNotCrashed() {
        val store = MemoryKeyValueStore()
        val vault = CredentialVault(store, JvmSecretBox())
        store.putString("x/pat", "v2:garbage")
        try { vault.token("x"); fail() } catch (_: SecretUnavailableException) {}
        store.putString("x/pat", "v1:!!!:###")
        try { vault.token("x"); fail() } catch (_: SecretUnavailableException) {}
    }

    // --- URLs ---

    @Test
    fun apiBaseKeepsThePortAndRefusesPlainHttp() {
        assertEquals("https://api.netbird.io:443/api", AdminUrls.apiBase("https://api.netbird.io:443"))
        assertEquals("https://nb.example.com:33073/api", AdminUrls.apiBase("https://NB.example.com:33073/"))
        assertEquals("https://nb.example.com/api", AdminUrls.apiBase("https://nb.example.com"))
        assertNull(AdminUrls.apiBase("http://nb.example.com"))
        assertEquals("http://127.0.0.1:8080/api", AdminUrls.apiBase("http://127.0.0.1:8080"))
        assertNull(AdminUrls.apiBase("https://user:pw@nb.example.com"))
        assertNull(AdminUrls.apiBase("ftp://nb.example.com"))
        assertNull(AdminUrls.apiBase("not a url"))
        assertTrue(AdminUrls.isCloud("https://api.netbird.io:443"))
        assertFalse(AdminUrls.isCloud("https://netbird.example.com"))
    }

    // --- The control plane's route ---

    @Test
    fun proxyRoutes() {
        assertEquals(ProxyRoute.Direct, ProxyRoute.parse(""))
        assertEquals(ProxyRoute.Socks("127.4.5.6", 41234), ProxyRoute.parse("socks5h://127.4.5.6:41234"))
        assertEquals(ProxyRoute.Socks("proxy.lan", 1080), ProxyRoute.parse("socks5://proxy.lan"))
        assertEquals(ProxyRoute.Http("proxy.lan", 3128, "me", "p@ss"), ProxyRoute.parse("http://me:p%40ss@proxy.lan:3128"))
        assertEquals(ProxyRoute.Unusable(ProxyRoute.Reason.SOCKS_AUTH), ProxyRoute.parse("socks5h://me:pw@proxy.lan:1080"))
        assertEquals(ProxyRoute.Unusable(ProxyRoute.Reason.BAD_URL), ProxyRoute.parse("ftp://x"))
        assertEquals(ProxyRoute.Unusable(ProxyRoute.Reason.BAD_URL), ProxyRoute.parse("::::"))
        assertFalse(ProxyRoute.parse("http://me:secret@proxy.lan:3128").toString().contains("secret"))
    }

    // --- Capabilities ---

    @Test
    fun aRefusalNarrowsWhatIsOffered() {
        val admin = Capabilities(emptyMap(), fromServer = false, role = "admin")
        assertTrue(admin.can(Capabilities.SETUP_KEYS, Capabilities.Op.CREATE))
        val narrowed = admin.denied(Capabilities.SETUP_KEYS, Capabilities.Op.CREATE)
        assertFalse(narrowed.can(Capabilities.SETUP_KEYS, Capabilities.Op.CREATE))
        assertTrue(narrowed.can(Capabilities.SETUP_KEYS, Capabilities.Op.READ))
        assertTrue(narrowed.can(Capabilities.PEERS, Capabilities.Op.DELETE))
        assertFalse(narrowed.readOnly)

        val fromServer = Capabilities(mapOf("peers" to mapOf("read" to true, "update" to true)), fromServer = true, role = "user")
        assertFalse(fromServer.readOnly)
        assertTrue(fromServer.denied("peers", Capabilities.Op.UPDATE).readOnly)
    }

    // --- The invite a new setup key is shown with ---

    @Test
    fun inviteLinkMatchesWhatAddAccountReads() {
        assertEquals("birdsocks://add-account?key=ABC-123", DeepLinks.inviteLink("https://api.netbird.io:443", "", "ABC-123"))
        assertEquals(
            "birdsocks://add-account?server=https%3A%2F%2Fnb.example.com%3A33073&name=Work&key=ABC-123",
            DeepLinks.inviteLink("https://nb.example.com:33073/", "Work", "ABC-123")
        )
    }
}
