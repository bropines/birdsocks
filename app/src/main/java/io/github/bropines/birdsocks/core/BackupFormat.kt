package io.github.bropines.birdsocks.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup cannot be read or restored; the screen words it. */
class BackupRefused(val reason: Reason, val detail: String = "") : Exception("${reason.name}: $detail") {
    enum class Reason {
        /** Not a file this app writes, or one missing what every backup carries. */
        NOT_A_BACKUP,
        /** Another application's backup; [detail] is its package. */
        OTHER_APP,
        /** The file's layout is newer than this build reads. */
        FORMAT_TOO_NEW,
        /** Written by a newer version of the app; [detail] is that version. */
        APP_TOO_NEW,
        /** The password does not open it, or the file was changed. */
        WRONG_PASSWORD,
        /** An entry a restore may not write; [detail] is its name. */
        FORBIDDEN_ENTRY,
        TOO_LARGE,
        /** NetBird would not stop, and its files are not written under a running daemon. */
        DAEMON_BUSY,
    }
}

/**
 * What a backup holds and what a restore may write. Plain Kotlin and the JDK,
 * no Android: core/Backup.kt does the files, the daemon and the screen's calls.
 *
 * Two kinds:
 *  - **settings**: a JSON file of BirdSocks' own preferences without secrets
 *    and without anything bound to this device — a setup to carry to any phone;
 *  - **full**: a ZIP of every preference listed here plus NetBird's profile
 *    files (configs with the WireGuard private keys), encrypted with a password
 *    ([BackupCrypto]) — this phone again, after a reinstall.
 *
 * A restore writes only what the lists below name: preference keys, each with
 * the type this build stores it as, and NetBird's own profile files under
 * files/netbird/. Any other entry in an archive refuses the whole restore
 * before a byte is written. Every backup carries a manifest, and a file from
 * another app or a newer build is refused rather than half-applied.
 */
object BackupFormat {
    /** Layout version of both kinds. Bump it only when an older build could no longer read a file correctly. */
    const val FORMAT_VERSION = 1
    const val KIND_SETTINGS = "settings"
    const val KIND_FULL = "full"

    /** Entries of the full archive. */
    const val MANIFEST_ENTRY = "manifest.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val NETBIRD_DIR = "netbird/"

    /** The picked file's bound; NetBird's files are a few kilobytes each. */
    const val MAX_FILE_BYTES = 16 shl 20
    private const val MAX_ENTRY_BYTES = 4 shl 20
    private const val MAX_ARCHIVE_BYTES = 32 shl 20
    private const val MAX_ENTRIES = 256
    /** Longer than any setting is: tun_excluded_apps with hundreds of packages stays well under it. */
    private const val MAX_VALUE_CHARS = 64 * 1024

    // --- Provenance ---

    data class Manifest(
        val formatVersion: Int,
        val kind: String,
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        /** Milliseconds since the epoch. */
        val createdAt: Long,
    )

    fun manifestJson(m: Manifest): JsonObject = buildJsonObject {
        put("formatVersion", m.formatVersion)
        put("kind", m.kind)
        put("packageName", m.packageName)
        put("versionCode", m.versionCode)
        put("versionName", m.versionName)
        put("createdAt", m.createdAt)
    }

    fun parseManifest(element: JsonElement?): Manifest? {
        val o = element as? JsonObject ?: return null
        fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return Manifest(
            formatVersion = num("formatVersion")?.toInt() ?: return null,
            kind = str("kind") ?: return null,
            packageName = str("packageName") ?: return null,
            versionCode = num("versionCode") ?: 0,
            versionName = str("versionName").orEmpty(),
            createdAt = num("createdAt") ?: 0,
        )
    }

    /** The application a package belongs to: the debug build's ".dev" is the same app. */
    private fun appOf(packageName: String) = packageName.removeSuffix(".dev")

    /**
     * Throws unless a [kind] backup written as [m] may be restored by the build
     * [packageName] [versionCode]. A newer build's file is refused: it may hold
     * keys and files in a shape this one does not know. Version codes compare
     * without their last two digits, which only number one release's per-ABI APKs.
     */
    fun check(m: Manifest, kind: String, packageName: String, versionCode: Long) {
        when {
            appOf(m.packageName) != appOf(packageName) -> throw BackupRefused(BackupRefused.Reason.OTHER_APP, m.packageName)
            m.formatVersion > FORMAT_VERSION -> throw BackupRefused(BackupRefused.Reason.FORMAT_TOO_NEW)
            m.formatVersion < 1 || m.kind != kind -> throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
            versionCode > 0 && m.versionCode / 100 > versionCode / 100 ->
                throw BackupRefused(BackupRefused.Reason.APP_TOO_NEW, m.versionName.ifEmpty { m.versionCode.toString() })
        }
    }

    // --- Preferences ---

    enum class Type { STRING, BOOLEAN, INT }

    /** Which backups carry a key. */
    enum class Scope {
        /** Every backup: a setting that means the same on any phone. */
        SETTING,
        /** Only the encrypted one: passwords and tokens. */
        SECRET,
        /** Only a restore of everything: it belongs with this device's NetBird identity. */
        DEVICE,
    }

    class KeySpec(val type: Type, val scope: Scope)

    /**
     * Every key of `global_settings` a backup carries. A key that is not here
     * is never written by a restore, and these stay out on purpose:
     *  - `was_running` — whether NetBird ran here a moment ago; a restore that
     *    stops NetBird starts it again itself;
     *  - `tun_ula` — the VPN's IPv6 address, made once per install at random;
     *  - `autostart_ask_never`, `autostart_ask_pending` — about this phone's
     *    own autostart permission prompt;
     *  - `automation_status_to` — the app on this phone that asked for status.
     * A new setting is backed up once it is added here.
     */
    private val KEYS: Map<String, KeySpec> = buildMap {
        fun add(scope: Scope, type: Type, vararg keys: String) = keys.forEach { put(it, KeySpec(type, scope)) }
        // Appearance and language
        add(Scope.SETTING, Type.STRING, "app_theme", "theme_preset", "app_locale", "app_icon")
        add(Scope.SETTING, Type.BOOLEAN, "dynamic_color", "amoled_mode")
        // The SOCKS5 proxy; before addresses there was only a port
        add(Scope.SETTING, Type.STRING, "socks_address")
        add(Scope.SETTING, Type.INT, "socks_port")
        add(Scope.SETTING, Type.BOOLEAN, "socks_lan")
        // The DNS proxy
        add(Scope.SETTING, Type.BOOLEAN, "dns_proxy_enabled")
        add(Scope.SETTING, Type.STRING, "dns_proxy", "dns_upstream")
        // The daemon
        add(Scope.SETTING, Type.STRING, "log_level", "lazy_conn", "extra_env")
        add(Scope.SETTING, Type.BOOLEAN, "force_relay", "relay_quic", "inbound_access")
        // The control plane's way to the server
        add(Scope.SETTING, Type.STRING, "cp_mode", "cp_type", "cp_host", "cp_port", "byedpi_flags")
        add(Scope.SETTING, Type.BOOLEAN, "byedpi_ipv4")
        // Lifecycle, notifications, logs
        add(Scope.SETTING, Type.BOOLEAN, "auto_start", "event_notifications", "logs_include_logcat")
        // Automation: the switch; its token is a secret, and the app status goes to is this phone's
        add(Scope.SETTING, Type.BOOLEAN, "automation_enabled")
        // VPN mode
        add(Scope.SETTING, Type.BOOLEAN, "tun_mode_enabled", "tun_route_all", "tun_ipv6_enabled")
        add(Scope.SETTING, Type.STRING, "tun_excluded_apps", "tun_excluded_cidrs", "tun_address")
        // Secrets
        add(Scope.SECRET, Type.STRING, "socks_user", "socks_pass", "cp_user", "cp_pass", "automation_token")
        // This device: the name it registers under
        add(Scope.DEVICE, Type.STRING, "device_name")
    }

    /** Keys a restore that changes appearance or language has to redraw for. */
    val APPEARANCE_KEYS = setOf("app_theme", "theme_preset", "dynamic_color", "amoled_mode", "app_locale")

    /** Per-profile keys, the profile's name after the prefix; they come and go with NetBird's profiles. */
    private val PROFILE_PREFIXES = listOf("account_email_", "dns_labels_", "tun_overlay_")

    fun isProfileKey(key: String): Boolean {
        val prefix = PROFILE_PREFIXES.firstOrNull { key.startsWith(it) } ?: return false
        val name = key.substring(prefix.length)
        return name.isNotEmpty() && name.length <= 128 && name.none { it.isISOControl() }
    }

    fun spec(key: String): KeySpec? = KEYS[key] ?: if (isProfileKey(key)) KeySpec(Type.STRING, Scope.DEVICE) else null

    /** The entries of [prefs] that a backup of [scopes] carries, as stored. */
    fun exportPrefs(prefs: Map<String, *>, scopes: Set<Scope>): JsonObject = buildJsonObject {
        for (key in prefs.keys.sorted()) {
            val spec = spec(key)?.takeIf { it.scope in scopes } ?: continue
            when (val value = prefs[key]) {
                is String -> if (spec.type == Type.STRING) put(key, value)
                is Boolean -> if (spec.type == Type.BOOLEAN) put(key, value)
                is Int -> if (spec.type == Type.INT) put(key, value)
            }
        }
    }

    /** What a restore writes, and the keys it passed over (unknown, or not of their type). */
    class ImportedPrefs(val values: Map<String, Any>, val skipped: List<String>)

    /**
     * The entries of [json] a restore of [scopes] writes: listed keys whose
     * value has the type this build stores them as. Listed keys of another
     * scope are left alone; anything else is skipped rather than refused, as an
     * older backup may hold a key a later build dropped.
     */
    fun importPrefs(json: JsonObject, scopes: Set<Scope>): ImportedPrefs {
        val values = linkedMapOf<String, Any>()
        val skipped = mutableListOf<String>()
        for ((key, element) in json) {
            val spec = spec(key)
            if (spec != null && spec.scope !in scopes) continue
            val prim = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }
            val value: Any? = if (spec == null || prim == null) null else when (spec.type) {
                Type.STRING -> prim.takeIf { it.isString && it.content.length <= MAX_VALUE_CHARS }?.content
                Type.BOOLEAN -> prim.takeIf { !it.isString }?.booleanOrNull
                Type.INT -> prim.takeIf { !it.isString }?.intOrNull
            }
            if (value != null) values[key] = value else skipped += key
        }
        return ImportedPrefs(values, skipped)
    }

    // --- NetBird's files ---

    /** Where the daemon files every profile but the default: its USER (Netbird.USER). */
    const val PROFILE_DIR = "birdsocks"
    /** A profile file's stem as NetBird takes it (IsValidProfileFilenameStem): letters, digits, _ and -. */
    private const val STEM = "[\\p{L}\\p{Nd}_-]{1,64}"
    private val TOP_FILES = Regex("(?:default|default\\.prefs|state|active_profile)\\.json")
    private val PROFILE_FILES = Regex("$PROFILE_DIR/$STEM(?:\\.state|\\.prefs)?\\.json")

    /**
     * Whether [path], relative to files/netbird/, is one of NetBird's profile
     * files: the default profile's config (default.json) and preferences, its
     * state (state.json: the networks and exit node chosen), the active-profile
     * pointer, and every other profile's config, state and preferences under
     * birdsocks/. Not the log, and nothing else — no separators beyond that one
     * level, no dots outside the suffixes, so no way out of the directory.
     */
    fun isNetbirdFile(path: String): Boolean = TOP_FILES.matches(path) || PROFILE_FILES.matches(path)

    /** A NetBird account's config, as opposed to its state or preferences. */
    fun isProfileConfig(path: String): Boolean =
        path == "default.json" || (path.startsWith("$PROFILE_DIR/") && !path.endsWith(".state.json") && !path.endsWith(".prefs.json"))

    fun isJson(data: ByteArray): Boolean = runCatching { Json.parseToJsonElement(data.decodeToString()) }.isSuccess

    // --- The settings file ---

    private val prettyJson = Json { prettyPrint = true }

    class SettingsFile(val manifest: Manifest, val prefs: JsonObject)

    fun settingsFile(manifest: Manifest, prefs: JsonObject): String =
        prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("manifest", manifestJson(manifest))
            put("settings", prefs)
        })

    /** The settings file in [data], or null when it is not one. */
    fun parseSettingsFile(data: ByteArray): SettingsFile? {
        val o = runCatching { Json.parseToJsonElement(data.decodeToString()) }.getOrNull() as? JsonObject ?: return null
        val manifest = parseManifest(o["manifest"]) ?: return null
        val prefs = o["settings"] as? JsonObject ?: return null
        return SettingsFile(manifest, prefs)
    }

    // --- The full archive ---

    class Full(val manifest: Manifest, val prefs: JsonObject, val netbird: Map<String, ByteArray>) {
        /** NetBird accounts in it: the default profile and every other one with a config. */
        val accounts: Int get() = netbird.keys.count(::isProfileConfig)
    }

    fun zip(manifest: Manifest, prefs: JsonObject, netbird: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun entry(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            // First, so a reader knows what it holds before anything else.
            entry(MANIFEST_ENTRY, manifestJson(manifest).toString().encodeToByteArray())
            entry(SETTINGS_ENTRY, prefs.toString().encodeToByteArray())
            for ((path, data) in netbird) {
                require(isNetbirdFile(path)) { "not a NetBird profile file: $path" }
                entry(NETBIRD_DIR + path, data)
            }
        }
        return out.toByteArray()
    }

    /**
     * Reads a decrypted archive, checking every entry before anything is
     * written: an entry outside the rules, a directory, a duplicate, an
     * oversized entry or one that is not JSON refuses the whole archive.
     */
    fun unzip(archive: ByteArray): Full {
        var manifest: Manifest? = null
        var prefs: JsonObject? = null
        val files = linkedMapOf<String, ByteArray>()
        val seen = hashSetOf<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (seen.size >= MAX_ENTRIES) throw BackupRefused(BackupRefused.Reason.TOO_LARGE)
                if (entry.isDirectory || !seen.add(name)) throw BackupRefused(BackupRefused.Reason.FORBIDDEN_ENTRY, name)
                val data = readBounded(zis, MAX_ENTRY_BYTES) ?: throw BackupRefused(BackupRefused.Reason.TOO_LARGE, name)
                total += data.size
                if (total > MAX_ARCHIVE_BYTES) throw BackupRefused(BackupRefused.Reason.TOO_LARGE)
                val json = runCatching { Json.parseToJsonElement(data.decodeToString()) }.getOrNull()
                    ?: throw BackupRefused(BackupRefused.Reason.FORBIDDEN_ENTRY, name)
                val path = name.removePrefix(NETBIRD_DIR)
                when {
                    name == MANIFEST_ENTRY -> manifest = parseManifest(json) ?: throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
                    name == SETTINGS_ENTRY -> prefs = json as? JsonObject ?: throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
                    name.startsWith(NETBIRD_DIR) && isNetbirdFile(path) -> files[path] = data
                    else -> throw BackupRefused(BackupRefused.Reason.FORBIDDEN_ENTRY, name)
                }
                zis.closeEntry()
            }
        }
        return Full(
            manifest ?: throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP),
            prefs ?: throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP),
            files,
        )
    }

    /** All of [input], or null when it is longer than [max] bytes. */
    fun readBounded(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/**
 * The full backup's encryption: AES-256-GCM under a key derived from the
 * password with PBKDF2-HMAC-SHA256 ([key]).
 *
 * Layout: "BSBK", a version byte, the iteration count (4 bytes, big-endian), a
 * 16-byte salt, a 12-byte IV, then the ciphertext with its 16-byte tag. The
 * header is authenticated with the data, so it cannot be changed either. The
 * iteration count travels in it, so a later build can raise it and still open
 * older files.
 */
object BackupCrypto {
    private val MAGIC = "BSBK".encodeToByteArray()
    private const val VERSION: Byte = 1
    /** OWASP's figure for PBKDF2-HMAC-SHA256 (2023): a second or two on a phone, once per backup. */
    const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    /** A file asking for more is not ours and would only stall the phone. */
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + IV_BYTES

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > HEADER_BYTES && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (!isEncrypted(data)) throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
        val buf = ByteBuffer.wrap(data, MAGIC.size, HEADER_BYTES - MAGIC.size)
        if (buf.get() != VERSION) throw BackupRefused(BackupRefused.Reason.FORMAT_TOO_NEW)
        val iterations = buf.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupRefused(BackupRefused.Reason.NOT_A_BACKUP)
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(data, 0, HEADER_BYTES)
        return try {
            cipher.doFinal(data, HEADER_BYTES, data.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw BackupRefused(BackupRefused.Reason.WRONG_PASSWORD)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) for one 32-byte block, over the password's
     * UTF-8 bytes. Written out because SecretKeyFactory has
     * "PBKDF2WithHmacSHA256" only from Android 8, and this app runs on 7;
     * HMAC-SHA256 is there on every version, so every phone derives the key
     * the same way.
     */
    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(password))
        val secret = ByteArray(encoded.remaining()).also { encoded.get(it) }
        if (encoded.hasArray()) encoded.array().fill(0)
        require(secret.isNotEmpty()) { "an empty password" }
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            // U1 = HMAC(P, S || INT(1)); Uj = HMAC(P, Uj-1); T1 = U1 ^ ... ^ Uc.
            val u = ByteArray(mac.macLength)
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            mac.doFinal(u, 0)
            val t = u.copyOf()
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            // SecretKeySpec keeps its own copy.
            return SecretKeySpec(t, "AES").also { t.fill(0); u.fill(0) }
        } finally {
            secret.fill(0)
        }
    }
}
