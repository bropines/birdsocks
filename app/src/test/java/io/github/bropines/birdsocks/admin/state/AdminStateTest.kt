package io.github.bropines.birdsocks.admin.state

import io.github.bropines.birdsocks.admin.api.ApiPeer
import io.github.bropines.birdsocks.admin.api.ApiService
import io.github.bropines.birdsocks.admin.api.ApiServiceAuth
import io.github.bropines.birdsocks.admin.api.ApiPasswordAuth
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.ApiUserInvite
import io.github.bropines.birdsocks.admin.api.Listing
import io.github.bropines.birdsocks.admin.secure.SealedBlob
import io.github.bropines.birdsocks.admin.secure.SecretBox
import io.github.bropines.birdsocks.admin.secure.SecretUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AdminStateTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The Keystore box's algorithm with a software key. */
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

    @Test
    fun theNeverCachedKeysAreNotCacheable() {
        for (k in listOf(AdminKeys.USER_INVITES, AdminKeys.EVENT_STREAMS, AdminKeys.AUDIT_EVENTS)) assertFalse(k.id, k.cacheable)
        for (k in AdminKeys.HOME) assertTrue(k.id, k.cacheable)
        assertEquals("key ids are unique", AdminKeys.ALL.size, AdminKeys.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun snapshotSkipsWhatMayNotBeCachedAndStripsSecrets() {
        val data = AdminData()
        data.set(AdminKeys.PEERS, Loadable(Listing(listOf(ApiPeer(id = "p1", name = "a"))), loadedAt = 5))
        data.set(AdminKeys.SETUP_KEYS, Loadable(Listing(listOf(ApiSetupKey(id = "k", key = "A616097E-FCF0-48FA-9354-CA4A61142761"))), loadedAt = 5))
        data.set(AdminKeys.USER_INVITES, Loadable(Listing(listOf(ApiUserInvite(id = "i", inviteToken = "nbi_secret"))), loadedAt = 5))
        data.set(AdminKeys.PROXY_SERVICES, Loadable(Listing(listOf(ApiService(id = "s", auth = ApiServiceAuth(passwordAuth = ApiPasswordAuth(true, "pw_secret"))))), loadedAt = 5))
        val snap = data.snapshot(AdminKeys.ALL)
        assertEquals(setOf("peers", "setup_keys", "proxy_services"), snap.keys)
        val text = snap.values.joinToString { it.value.toString() }
        assertFalse(text.contains("A616097E"))
        assertFalse(text.contains("nbi_secret"))
        assertFalse(text.contains("pw_secret"))
    }

    @Test
    fun theCacheRoundTripsSealedAndPerAccount() {
        val dir = tmp.newFolder("cache")
        val cache = AdminCache(dir, JvmSecretBox())
        val data = AdminData()
        data.set(AdminKeys.PEERS, Loadable(Listing(listOf(ApiPeer(id = "p1", name = "laptop-of-ann"))), loadedAt = 42))
        cache.write("acc1", data.snapshot(AdminKeys.ALL))
        assertFalse(dir.listFiles()!!.single().readText().contains("laptop-of-ann"))

        val fresh = AdminData()
        fresh.seed(AdminKeys.ALL, cache.read("acc1"))
        val seeded = fresh[AdminKeys.PEERS]
        assertEquals("laptop-of-ann", seeded.value!!.items.single().name)
        assertTrue(seeded.fromDisk)
        assertEquals(42, seeded.loadedAt)

        // Another account reads nothing; a file moved under another account's name does not open.
        assertTrue(cache.read("acc2").isEmpty())
        dir.listFiles()!!.single().renameTo(java.io.File(dir, "acc2"))
        assertTrue(cache.read("acc2").isEmpty())
        assertFalse(java.io.File(dir, "acc2").exists())
    }

    @Test
    fun writesMergeAndSeedDoesNotOverwriteNewerValues() {
        val cache = AdminCache(tmp.newFolder("c"), JvmSecretBox())
        val a = AdminData().apply { set(AdminKeys.PEERS, Loadable(Listing(listOf(ApiPeer(id = "old"))), loadedAt = 1)) }
        cache.write("acc", a.snapshot(AdminKeys.ALL))
        val b = AdminData().apply { set(AdminKeys.GROUPS, Loadable(Listing(emptyList()), loadedAt = 2)) }
        cache.write("acc", b.snapshot(AdminKeys.ALL))
        assertEquals(setOf("peers", "groups"), cache.read("acc").keys)

        val live = AdminData().apply { set(AdminKeys.PEERS, Loadable(Listing(listOf(ApiPeer(id = "new"))), loadedAt = 9)) }
        live.seed(AdminKeys.ALL, cache.read("acc"))
        assertEquals("new", live[AdminKeys.PEERS].value!!.items.single().id)
        assertFalse(live[AdminKeys.PEERS].fromDisk)
    }

    @Test
    fun anUnreadableEntryIsDroppedAlone() {
        val data = AdminData()
        data.seed(AdminKeys.ALL, mapOf("peers" to AdminCache.Entry(1, kotlinx.serialization.json.JsonPrimitive("not a list"))))
        assertNull(data[AdminKeys.PEERS].value)
        assertTrue(data[AdminKeys.PEERS].isInitialLoad)
    }
}
