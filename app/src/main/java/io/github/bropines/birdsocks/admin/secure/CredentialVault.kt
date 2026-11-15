package io.github.bropines.birdsocks.admin.secure

import io.github.bropines.birdsocks.admin.api.AdminUrls
import java.security.MessageDigest

/**
 * The admin panel's credentials: one sealed personal access token per account,
 * and the account's panel settings beside it. An account is a NetBird profile
 * on one management server ([accountId]); a changed server is another account,
 * so a token never reaches a server it was not made for.
 *
 * Nothing secret is ever in plain text on disk. The preferences file holding
 * this is excluded from Android's backups and device transfer and is not in
 * BirdSocks' own backup; the Keystore key that opens it never leaves the phone.
 */
class CredentialVault(private val store: KeyValueStore, private val box: SecretBox) {

    /** The account [profile] on [managementUrl], as the vault keys it: no names, a hash of both. */
    fun accountId(profile: String, managementUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$profile\n${AdminUrls.normalize(managementUrl)}".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun tokenKey(id: String) = "$id/pat"
    private fun readOnlyKey(id: String) = "$id/read_only"
    private fun aad(id: String) = "birdsocks-admin/$id/pat"

    /** Seals and stores [token]; a blank one removes it. False when the write did not land. */
    fun putToken(id: String, token: String): Boolean =
        if (token.isBlank()) store.putString(tokenKey(id), null)
        else store.putString(tokenKey(id), box.seal(token.trim(), aad(id)))

    /** The token, null when none is stored; throws when one is stored and cannot be opened. */
    @Throws(SecretUnavailableException::class)
    fun token(id: String): String? = store.getString(tokenKey(id))?.let { box.open(it, aad(id)) }

    fun hasToken(id: String): Boolean = store.getString(tokenKey(id)) != null

    /** The account's read-only switch; on by default, so a new token cannot change anything until the user says. */
    fun isReadOnly(id: String): Boolean = store.getString(readOnlyKey(id)) != "false"

    fun setReadOnly(id: String, readOnly: Boolean) {
        store.putString(readOnlyKey(id), readOnly.toString())
    }

    /** Forgets everything about the account. */
    fun forget(id: String) {
        store.putString(tokenKey(id), null)
        store.putString(readOnlyKey(id), null)
    }

    companion object {
        /** The preferences file; named in backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS_NAME = "admin_vault"
    }
}
