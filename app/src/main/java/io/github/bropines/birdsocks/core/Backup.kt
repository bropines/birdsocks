package io.github.bropines.birdsocks.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.pm.PackageInfoCompat
import appctr.Appctr
import io.github.bropines.birdsocks.core.BackupFormat.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * Backups and restores: the files, the preferences and the daemon around
 * [BackupFormat]'s rules. Each call does its own work off the main thread.
 */
object Backup {
    private val lock = Mutex()
    private val busyFlow = MutableStateFlow(false)
    /** A backup or a restore is running; the screen shows it and starts no second one. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    /** What changed that the screen has to follow: a redraw for appearance, the platform for the language. */
    class Restored(val appearanceChanged: Boolean, val localeChanged: Boolean)

    /** A file picked to restore from. */
    sealed interface Opened {
        class Settings(val file: BackupFormat.SettingsFile) : Opened
        /** A full backup, still locked: [unlock] it with the password. */
        class Locked(val data: ByteArray) : Opened
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            busyFlow.value = true
            try {
                block()
            } finally {
                busyFlow.value = false
            }
        }
    }

    private fun log(level: String, message: String) = runCatching { Appctr.logAndroid(level, "BACKUP", message) }

    private fun installedVersionCode(context: Context): Long =
        runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0)

    private fun manifest(context: Context, kind: String): BackupFormat.Manifest {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return BackupFormat.Manifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            kind = kind,
            packageName = context.packageName,
            versionCode = info?.let(PackageInfoCompat::getLongVersionCode) ?: 0,
            versionName = info?.versionName.orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun check(context: Context, manifest: BackupFormat.Manifest, kind: String) =
        BackupFormat.check(manifest, kind, context.packageName, installedVersionCode(context))

    // NB_STATE_DIR, which appctr/core.go starts the daemon with.
    private fun netbirdDir(context: Context) = File(context.filesDir, "netbird")

    /** NetBird's profile files on disk now, by their path under files/netbird/. */
    private fun netbirdFiles(context: Context): Map<String, File> {
        val root = netbirdDir(context)
        val found = sortedMapOf<String, File>()
        root.listFiles()?.forEach { if (it.isFile && BackupFormat.isNetbirdFile(it.name)) found[it.name] = it }
        File(root, BackupFormat.PROFILE_DIR).listFiles()?.forEach {
            val path = "${BackupFormat.PROFILE_DIR}/${it.name}"
            if (it.isFile && BackupFormat.isNetbirdFile(path)) found[path] = it
        }
        return found
    }

    // --- Saving ---

    /** BirdSocks' settings without secrets or device-bound entries, as a JSON file at [uri]. */
    suspend fun exportSettings(context: Context, uri: Uri) = exclusive {
        val prefs = BackupFormat.exportPrefs(GlobalSettings.snapshot(context), setOf(Scope.SETTING))
        write(context, uri, BackupFormat.settingsFile(manifest(context, BackupFormat.KIND_SETTINGS), prefs).encodeToByteArray())
    }

    /** Every listed setting with its secrets, and NetBird's profiles, encrypted with [password] (cleared after). */
    suspend fun exportFull(context: Context, uri: Uri, password: CharArray) = exclusive {
        try {
            val files = netbirdFiles(context).mapValues { it.value.readBytes() }.filter { (path, data) ->
                // The daemon writes JSON; a file that is not would refuse the whole restore.
                BackupFormat.isJson(data).also { if (!it) log("WARN", "Backup leaves out netbird/$path: not JSON") }
            }
            val prefs = BackupFormat.exportPrefs(GlobalSettings.snapshot(context), Scope.entries.toSet())
            val archive = BackupFormat.zip(manifest(context, BackupFormat.KIND_FULL), prefs, files)
            write(context, uri, BackupCrypto.encrypt(archive, password))
            log("INFO", "Full backup saved: ${files.size} NetBird files")
        } finally {
            password.fill('\u0000')
        }
    }

    private fun write(context: Context, uri: Uri, data: ByteArray) {
        try {
            // "wt": a file picked to overwrite is truncated, not patched over.
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                ?: throw IOException("the file cannot be opened")
        } catch (e: Exception) {
            // The picker created the document already; an empty one is no backup.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    // --- Restoring ---

    /** Reads the file at [uri] and tells what it is; a settings file is checked here already. */
    suspend fun open(context: Context, uri: Uri): Opened = exclusive {
        val data = context.contentResolver.openInputStream(uri)?.use {
            BackupFormat.readBounded(it, BackupFormat.MAX_FILE_BYTES) ?: throw BackupRefused(BackupRefused.Reason.TOO_LARGE)
        } ?: throw IOException("the file cannot be opened")
        if (BackupCrypto.isEncrypted(data)) return@exclusive Opened.Locked(data)
        val file = BackupFormat.parseSettingsFile(data) ?: throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
        check(context, file.manifest, BackupFormat.KIND_SETTINGS)
        Opened.Settings(file)
    }

    /** Decrypts a full backup with [password] (cleared after) and checks all of it; writes nothing. */
    suspend fun unlock(context: Context, data: ByteArray, password: CharArray): BackupFormat.Full = exclusive {
        try {
            val full = BackupFormat.unzip(BackupCrypto.decrypt(data, password))
            check(context, full.manifest, BackupFormat.KIND_FULL)
            full
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Writes the settings in [prefs]; with [secrets] (a full backup) their
     * passwords too. Preferences only: NetBird's profiles and this device's
     * entries stay as they are, and a running daemon reads the change at its
     * next start.
     */
    suspend fun restoreSettings(context: Context, prefs: JsonObject, secrets: Boolean): Restored = exclusive {
        val restored = applyPrefs(context, prefs, if (secrets) setOf(Scope.SETTING, Scope.SECRET) else setOf(Scope.SETTING), replaceProfileKeys = false)
        log("INFO", if (secrets) "Settings restored from a full backup" else "Settings restored from a settings file")
        restored
    }

    /**
     * Everything in [full]: NetBird's profiles in place of the ones here, and
     * every setting with this device's entries. NetBird is stopped first — its
     * files are never written under a running daemon — and started again if it
     * was running.
     */
    suspend fun restoreEverything(context: Context, full: BackupFormat.Full): Restored = exclusive {
        val app = context.applicationContext
        val wasRunning = NetbirdState.daemon.value.let { it == NetbirdState.Daemon.Running || it == NetbirdState.Daemon.Starting }
        try {
            stopDaemon(app)
            writeNetbirdFiles(app, full.netbird)
            // The profiles are another set now; the daemon names the active one when it starts.
            NetbirdState.profileFlow.value = null
            applyPrefs(app, full.prefs, Scope.entries.toSet(), replaceProfileKeys = true)
                .also { log("INFO", "Everything restored: ${full.netbird.size} NetBird files") }
        } finally {
            // Also when NetBird would not stop: stop() took it off the
            // was-running mark, and start() of a running daemon is a no-op.
            if (wasRunning) withContext(NonCancellable + Dispatchers.Main) {
                runCatching { NetbirdService.start(app) }.onFailure { log("ERROR", "NetBird did not start after the restore: ${it.message}") }
            }
        }
    }

    /** Stops NetBird and waits until its process is gone. */
    private suspend fun stopDaemon(context: Context) {
        if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped && !Appctr.isRunning()) return
        withContext(Dispatchers.Main) { NetbirdService.stop(context) }
        val stopped = withTimeoutOrNull(30_000) {
            while (NetbirdState.daemon.value != NetbirdState.Daemon.Stopped || Appctr.isRunning()) {
                // A daemon the service no longer tracks: stop it through the bridge.
                if (NetbirdState.daemon.value == NetbirdState.Daemon.Stopped) runCatching { Appctr.stop() }
                delay(200)
            }
            true
        } ?: false
        if (!stopped) throw BackupRefused(BackupRefused.Reason.DAEMON_BUSY)
    }

    /**
     * Replaces NetBird's profile files with [files]: what a backup would take
     * goes, nothing else (the log stays). The new set is on disk in a staging
     * directory before the old one moves aside, and if moving the new one in
     * fails, the old one comes back.
     */
    private fun writeNetbirdFiles(context: Context, files: Map<String, ByteArray>) {
        val root = netbirdDir(context).apply { mkdirs() }
        val staging = File(context.filesDir, "netbird-restore")
        val previous = File(context.filesDir, "netbird-previous")
        for (dir in listOf(staging, previous)) {
            dir.deleteRecursively()
            dir.mkdirs()
        }
        try {
            for ((path, data) in files) inside(staging, path).apply { parentFile?.mkdirs() }.writeBytes(data)
            val current = netbirdFiles(context)
            val movedOut = mutableListOf<String>()
            val movedIn = mutableListOf<String>()
            try {
                for ((path, file) in current) {
                    move(file, inside(previous, path))
                    movedOut += path
                }
                for (path in files.keys) {
                    move(inside(staging, path), inside(root, path))
                    movedIn += path
                }
            } catch (e: Exception) {
                movedIn.forEach { inside(root, it).delete() }
                movedOut.forEach { runCatching { move(inside(previous, it), inside(root, it)) } }
                throw e
            }
        } finally {
            staging.deleteRecursively()
            previous.deleteRecursively()
        }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    /** [path] under [dir], or a refusal if it would land anywhere else. */
    private fun inside(dir: File, path: String): File {
        if (!BackupFormat.isNetbirdFile(path)) throw BackupRefused(BackupRefused.Reason.FORBIDDEN_ENTRY, path)
        val base = dir.canonicalFile
        val file = File(base, path).canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) throw BackupRefused(BackupRefused.Reason.FORBIDDEN_ENTRY, path)
        return file
    }

    /**
     * Writes the listed entries of [json] for [scopes] in one commit. With
     * [replaceProfileKeys] the per-profile keys here go first, as the profiles
     * they belong to were just replaced.
     */
    private fun applyPrefs(context: Context, json: JsonObject, scopes: Set<Scope>, replaceProfileKeys: Boolean): Restored {
        val imported = BackupFormat.importPrefs(json, scopes)
        if (imported.skipped.isNotEmpty()) log("WARN", "Restore skipped unknown or mistyped settings: ${imported.skipped.joinToString()}")
        val before = GlobalSettings.snapshot(context)
        val values = imported.values.toMutableMap()
        // A settings file carries no proxy password: sharing the proxy on the
        // network without one here would open it to everyone on the Wi-Fi.
        if (values["socks_lan"] == true) {
            val user = values["socks_user"] as? String ?: before["socks_user"] as? String ?: ""
            val pass = values["socks_pass"] as? String ?: before["socks_pass"] as? String ?: ""
            if (user.isBlank() || pass.isBlank()) {
                values["socks_lan"] = false
                log("WARN", "Restore left local-network sharing off: the proxy has no username and password here")
            }
        }
        val saved = GlobalSettings.commit(context) {
            if (replaceProfileKeys) before.keys.filter(BackupFormat::isProfileKey).forEach { remove(it) }
            for ((key, value) in values) {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }
        if (!saved) throw IOException("the settings could not be saved")
        val after = GlobalSettings.snapshot(context)
        return Restored(
            appearanceChanged = BackupFormat.APPEARANCE_KEYS.any { before[it] != after[it] },
            localeChanged = before["app_locale"] != after["app_locale"],
        )
    }
}
