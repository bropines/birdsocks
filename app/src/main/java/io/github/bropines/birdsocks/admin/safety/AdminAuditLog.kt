package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.secure.KeystoreSecretBox
import io.github.bropines.birdsocks.admin.secure.SecretBox
import io.github.bropines.birdsocks.core.AppJson
import kotlinx.serialization.Serializable
import java.io.File

enum class AuditResult {
    /** Applied; the re-read confirmed it. */
    VERIFIED,
    /** Applied; nothing to re-read, or the re-read itself could not run. */
    APPLIED,
    /** Applied, but the re-read shows something else than what was asked. */
    MISMATCH,
    FAILED,
    /** Stopped by a gate before anything was sent. */
    REFUSED,
}

/** One write from this phone: what, on what, when and how it ended. */
@Serializable
data class AuditRecord(
    val time: Long,
    val accountId: String,
    /** [ChangeKind.id]. */
    val kind: String,
    val changeClass: ChangeClass,
    val targetType: TargetType,
    val targetId: String,
    val targetName: String,
    /** The change's one-line effect, in the language it was made in. */
    val effect: String,
    val result: AuditResult,
    /** Why it failed or was refused; never a credential. */
    val detail: String? = null,
    /** Set on the entry of an undo. */
    val undo: Boolean = false,
)

/**
 * The panel's own record of every write it attempted, on this phone only
 * (`files/admin/audit.jsonl`, outside backups): the server's audit log says
 * what changed, this one says what this phone asked for, including what
 * failed or was refused. Newest last on disk, the newest [maxEntries] kept.
 * With a [box] every line is sealed on its own, so names and effects stay
 * off the disk in the clear.
 */
class AdminAuditLog(private val file: File, private val maxEntries: Int = 500, private val box: SecretBox? = null) {

    private fun encode(record: AuditRecord): String {
        val json = AppJson.encodeToString(AuditRecord.serializer(), record)
        return box?.seal(json, AAD) ?: json
    }

    private fun decode(line: String): AuditRecord? = runCatching {
        val json = if (line.startsWith("{") || box == null) line else box.open(line, AAD)
        AppJson.decodeFromString(AuditRecord.serializer(), json)
    }.getOrNull()

    @Synchronized
    fun append(record: AuditRecord) {
        file.parentFile?.mkdirs()
        file.appendText(encode(record) + "\n")
        val lines = file.readLines()
        if (lines.size > maxEntries + maxEntries / 5) file.writeText(lines.takeLast(maxEntries).joinToString("\n", postfix = "\n"))
    }

    /** Newest first; [accountId] narrows to one account. Lines that do not parse or open are skipped. */
    @Synchronized
    fun records(accountId: String? = null): List<AuditRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull(::decode)
            .asReversed().filter { accountId == null || it.accountId == accountId }.take(maxEntries)
    }

    @Synchronized
    fun clear(accountId: String? = null) {
        if (accountId == null) {
            file.delete()
            return
        }
        val kept = records().filter { it.accountId != accountId }.asReversed()
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("") { encode(it) + "\n" })
    }

    companion object {
        private const val AAD = "birdsocks-admin-audit"
        private const val KEY_ALIAS = "birdsocks_admin_audit"

        fun fileIn(filesDir: File) = File(File(filesDir, "admin"), "audit.jsonl")

        /** The log as the app keeps it: sealed under a Keystore key of its own. */
        fun of(filesDir: File) = AdminAuditLog(fileIn(filesDir), box = KeystoreSecretBox(KEY_ALIAS))
    }
}
