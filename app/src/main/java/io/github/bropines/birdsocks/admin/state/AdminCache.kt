package io.github.bropines.birdsocks.admin.state

import io.github.bropines.birdsocks.admin.secure.KeystoreSecretBox
import io.github.bropines.birdsocks.admin.secure.SecretBox
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * The panel's last good lists, one file per account (`files/admin/cache/`),
 * so it opens on them at once and refreshes in place. Sealed under a Keystore
 * key of its own with the account bound in, so a copied data directory holds
 * only ciphertext and one account's file renamed to another's does not open;
 * files/admin is out of backups and device transfer. Nothing is ever written
 * to the server from the cache.
 *
 * What goes in is decided per [DataKey] ([DataKey.codec]); invites, tokens,
 * event-streaming configs and audit events never do.
 */
class AdminCache(private val dir: File, private val box: SecretBox) {

    @Serializable
    data class Entry(val at: Long, val value: JsonElement)

    @Serializable
    internal data class Snapshot(val version: Int = VERSION, val entries: Map<String, Entry> = emptyMap())

    /** The account's entries; empty when there are none or the file cannot be opened (it is then removed). */
    fun read(accountId: String): Map<String, Entry> {
        val f = file(accountId)
        if (!f.exists()) return emptyMap()
        return try {
            val s = AppJson.decodeFromString(Snapshot.serializer(), box.open(f.readText(), aad(accountId)))
            if (s.version == VERSION) s.entries else emptyMap<String, Entry>().also { f.delete() }
        } catch (_: Exception) {
            // Another phone's key, a reset Keystore, a damaged file: start empty.
            f.delete()
            emptyMap()
        }
    }

    /** Merges [entries] into the account's file; one too large to be worth keeping removes the file instead. */
    @Synchronized
    fun write(accountId: String, entries: Map<String, Entry>) {
        if (entries.isEmpty()) return
        val merged = read(accountId) + entries
        val json = AppJson.encodeToString(Snapshot.serializer(), Snapshot(entries = merged))
        val f = file(accountId)
        if (json.length > MAX_CHARS) {
            f.delete()
            return
        }
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(box.seal(json, aad(accountId)))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    fun clear(accountId: String) {
        file(accountId).delete()
    }

    private fun file(accountId: String) = File(dir, accountId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "account" })

    companion object {
        const val VERSION = 1
        /** A few thousand peers; past that the copy costs more to seal than it saves. */
        private const val MAX_CHARS = 8_000_000
        private const val KEY_ALIAS = "birdsocks_admin_cache"

        fun of(filesDir: File) = AdminCache(File(filesDir, "admin/cache"), KeystoreSecretBox(KEY_ALIAS))

        private fun aad(accountId: String) = "birdsocks-admin-cache/$accountId"
    }
}
