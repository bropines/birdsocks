package io.github.bropines.birdsocks.core

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.bropines.birdsocks.BuildConfig
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The GitHub updater: asks for the latest release, picks the APK for this
 * device's ABI, downloads it, checks it against the release's SHA256SUMS and
 * hands it to the system installer.
 *
 * Only where [UpdateChannel.selfUpdate] allows it; the callers check. The state
 * is process-wide, so a download goes on when the About dialog is closed and
 * the main screen can mark the Info button while an update waits.
 *
 * A debug build (.dev) is its own app: the release would install beside it,
 * not over it, so it is told a newer release exists and offered the page.
 */
object Updater {
    const val REPO_URL = "https://github.com/bropines/birdsocks"
    const val RELEASES_URL = "$REPO_URL/releases"
    /** Where the project takes donations (also .github/FUNDING.yml and the README). */
    const val DONATE_URL = "https://boosty.to/pinus"
    private const val API_LATEST = "https://api.github.com/repos/bropines/birdsocks/releases/latest"
    private const val SUMS = "SHA256SUMS"
    private const val DIR = "updates"

    /** The PackageInstaller session's answer, delivered to MainActivity. */
    const val ACTION_INSTALL_STATUS = "io.github.bropines.birdsocks.action.INSTALL_STATUS"

    class Release(
        /** 0.2.0, the tag without its v. */
        val version: String,
        val pageUrl: String,
        /** The release body: the CHANGELOG section, without its heading. */
        val notes: String,
        /** The APK for this device, null when the release has none for it. */
        val apkName: String?,
        val apkUrl: String?,
        val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val release: Release) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Downloaded and verified; [apk] waits for the installer. */
        data class Ready(val release: Release, val apk: File) : State
        data class Failed(@param:StringRes val reason: Int, val detail: String?, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the release can replace this build: not a .dev build, which is another package. */
    val canInstall: Boolean get() = !BuildConfig.IS_DEV

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private class UpdateException(@param:StringRes val reason: Int, detail: String? = null) : Exception(detail)

    /**
     * Whether [latest] is a later version than [current], by their numbers:
     * 0.1.0-dev and 0.1.0 are the same version, never an update.
     */
    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { p -> p.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val c = parts(current)
        val l = parts(latest)
        for (i in 0 until maxOf(c.size, l.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    /**
     * The release asset for this device: BirdSocks-v<version>-<abi>-release.apk
     * for its preferred ABI, the universal APK when that one is missing. Exact
     * names, so x86 never picks x86_64.
     */
    fun pickApk(names: Collection<String>, version: String, abis: Array<String> = Build.SUPPORTED_ABIS): String? =
        listOfNotNull(abis.firstOrNull(), "universal")
            .map { "BirdSocks-v$version-$it-release.apk" }
            .firstOrNull { it in names }

    /** Asks GitHub; updates [state] and returns it. Never throws. */
    suspend fun check(context: Context): State {
        when (_state.value) {
            is State.Checking, is State.Downloading -> return _state.value
            else -> Unit
        }
        val app = context.applicationContext
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val release = fetchLatest()
                prune(app, keep = release.version)
                when {
                    !isNewer(BuildConfig.VERSION_NAME, release.version) -> State.UpToDate(release)
                    else -> cached(app, release)?.let { State.Ready(release, it) } ?: State.Available(release)
                }
            }.getOrElse { failure(it, null) }
        }
        _state.value = result
        return result
    }

    private fun fetchLatest(): Release {
        val request = Request.Builder().url(API_LATEST).header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException(R.string.update_failed_check, "HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val json = AppJson.parseToJsonElement(body).jsonObject
        val tag = json["tag_name"]?.jsonPrimitive?.content ?: throw UpdateException(R.string.update_failed_check, "no tag_name")
        val version = tag.removePrefix("v")
        val assets = json["assets"]?.jsonArray.orEmpty().associate { a ->
            val o = a.jsonObject
            o["name"]?.jsonPrimitive?.content.orEmpty() to o["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
        }
        val apk = pickApk(assets.keys, version)
        return Release(
            version = version,
            pageUrl = json["html_url"]?.jsonPrimitive?.content ?: "$RELEASES_URL/tag/$tag",
            notes = json["body"]?.jsonPrimitive?.content.orEmpty(),
            apkName = apk,
            apkUrl = apk?.let { assets[it] },
            sumsUrl = assets[SUMS],
        )
    }

    /**
     * Downloads the [State.Available] release's APK, verifies it and opens the
     * installer. The checksum comes from the release's SHA256SUMS; the APK must
     * also be this app's package and a higher versionCode, or it is dropped.
     */
    suspend fun download(context: Context) {
        val release = (_state.value as? State.Available)?.release
            ?: (_state.value as? State.Failed)?.release
            ?: return
        val app = context.applicationContext
        val url = release.apkUrl ?: return
        val name = release.apkName ?: return
        _state.value = State.Downloading(release, 0)
        val result = withContext(Dispatchers.IO) {
            val dir = updatesDir(app)
            val tmp = File(dir, "$name.tmp")
            runCatching {
                val sumsUrl = release.sumsUrl ?: throw UpdateException(R.string.update_failed_no_sums)
                val expected = expectedSha256(sumsUrl, name)
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw UpdateException(R.string.update_failed_download, "HTTP ${r.code}")
                    val body = r.body ?: throw UpdateException(R.string.update_failed_download)
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != shown) { shown = pct; _state.value = State.Downloading(release, pct) }
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw UpdateException(R.string.update_failed_checksum)
                verifyArchive(app, tmp)
                val apk = File(dir, name)
                apk.delete()
                if (!tmp.renameTo(apk)) throw UpdateException(R.string.update_failed_download, "rename")
                State.Ready(release, apk)
            }.getOrElse {
                tmp.delete()
                failure(it, release)
            }
        }
        _state.value = result
        if (result is State.Ready) install(app, result.apk)
    }

    private fun expectedSha256(sumsUrl: String, name: String): String {
        val text = http.newCall(Request.Builder().url(sumsUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw UpdateException(R.string.update_failed_no_sums, "HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        // sha256sum's format: "<hex>  <name>", or "<hex> *<name>" in binary mode.
        return text.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
            ?.get(0)
            ?.takeIf { it.length == 64 }
            ?: throw UpdateException(R.string.update_failed_no_sums, name)
    }

    /** The APK is this app, and newer than what is installed. */
    private fun verifyArchive(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw UpdateException(R.string.update_failed_not_apk)
        if (archive.packageName != context.packageName) throw UpdateException(R.string.update_failed_not_apk, archive.packageName)
        val installed = pm.getPackageInfo(context.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateException(R.string.update_failed_not_newer, archive.versionName)
        }
    }

    private fun failure(e: Throwable, release: Release?): State.Failed =
        if (e is UpdateException) State.Failed(e.reason, e.message, release)
        else State.Failed(if (release == null) R.string.update_failed_check else R.string.update_failed_download, e.message ?: e.javaClass.simpleName, release)

    private fun updatesDir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    /** A verified download of [release] from an earlier run. */
    private fun cached(context: Context, release: Release): File? =
        release.apkName?.let { File(updatesDir(context), it) }?.takeIf { it.isFile && it.length() > 0 }

    /**
     * Drops every download that is not of a version newer than the installed
     * one, except [keep]'s, and every unfinished one.
     */
    fun prune(context: Context, keep: String? = null) {
        val files = File(context.cacheDir, DIR).listFiles() ?: return
        for (f in files) {
            val version = f.name.removePrefix("BirdSocks-v").substringBefore('-')
            val wanted = f.name.endsWith(".apk") && (version == keep || isNewer(BuildConfig.VERSION_NAME, version))
            if (!wanted) f.delete()
        }
    }

    /**
     * Opens the system installer on [apk]: first permission to install apps,
     * then ACTION_VIEW through the FileProvider; a PackageInstaller session if
     * no installer answers that.
     */
    fun install(context: Context, apk: File) {
        if (!canInstallPackages(context)) return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.recoverCatching {
            installWithSession(context, apk)
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.update_install_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Whether this app may ask the installer for an install (Android 8+ asks
     * the user once per app). When not, opens the screen that grants it and
     * says so — before a download, not after one.
     */
    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(context, context.getString(R.string.update_grant_permission), Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }

    private fun installWithSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("birdsocks_update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
            // Mutable: the installer fills in the status and the confirmation intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getActivity(context, 0, callback, flags).intentSender)
        }
    }

    /**
     * The session's answer. It cannot install without the user: it reports
     * STATUS_PENDING_USER_ACTION with the confirmation screen as EXTRA_INTENT,
     * which the app has to start itself.
     */
    fun onInstallStatus(activity: Activity, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { confirm ->
                    runCatching { activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS, Int.MIN_VALUE -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Toast.makeText(activity, activity.getString(R.string.update_install_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
