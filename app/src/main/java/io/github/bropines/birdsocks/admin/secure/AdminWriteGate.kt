package io.github.bropines.birdsocks.admin.secure

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.resume

/** What the phone can unlock writes with. */
enum class LockState {
    /** API 30+: a per-use key, unlocked by a strong biometric or the screen lock through a CryptoObject. */
    CRYPTO_PER_USE,
    /** API 24–29: crypto with the screen lock is not supported; a key valid a few seconds after the prompt. */
    TIME_BOUND,
    /** No PIN, pattern or password: nothing can prove who holds the phone. The panel stays read-only. */
    NO_SCREEN_LOCK,
}

sealed class UnlockResult {
    data object Granted : UnlockResult()
    /** The user closed the prompt. */
    data object Cancelled : UnlockResult()
    class Failed(val reason: UnlockFailure, val detail: String? = null) : UnlockResult()
}

enum class UnlockFailure {
    NO_SCREEN_LOCK,
    /** The prompt could not be shown on this phone. */
    PROMPT_UNAVAILABLE,
    /** Too many attempts; the phone locked biometrics for a while. */
    LOCKOUT,
    /** The prompt said yes and the Keystore said no — a class 2 face unlock, for one. */
    NOT_PROVEN,
    ERROR,
}

/**
 * The lock in front of every change the panel makes. Fails closed: a prompt
 * that cannot be shown, an error, a cancelled prompt or a Keystore that
 * refuses the result all end in no change.
 *
 * Why two paths (androidx.biometric 1.1.0):
 *  - BIOMETRIC_STRONG | DEVICE_CREDENTIAL is rejected on API 28–29, and a
 *    CryptoObject with DEVICE_CREDENTIAL needs API 30.
 *  - So on API 30+ the key requires authentication for every use and the
 *    prompt carries the cipher; the change goes ahead when that cipher works.
 *  - Below 30 the key is valid for [TIME_BOUND_SEC] seconds after any screen
 *    lock or strong biometric authentication, the prompt allows
 *    BIOMETRIC_WEAK | DEVICE_CREDENTIAL, and the change goes ahead only when
 *    the key then works: a weak face unlock does not unlock Keystore keys.
 */
object AdminWriteGate {
    private const val TAG = "AdminWriteGate"
    private const val ALIAS = "birdsocks_admin_write_gate"
    private const val TIME_BOUND_SEC = 15

    fun lockState(context: Context): LockState {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return when {
            keyguard == null || !keyguard.isDeviceSecure -> LockState.NO_SCREEN_LOCK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> LockState.CRYPTO_PER_USE
            else -> LockState.TIME_BOUND
        }
    }

    /** Asks for the unlock and suspends until it is decided. Main thread. */
    suspend fun unlock(activity: FragmentActivity, title: String, subtitle: String?): UnlockResult =
        suspendCancellableCoroutine { cont -> unlock(activity, title, subtitle) { if (cont.isActive) cont.resume(it) } }

    fun unlock(activity: FragmentActivity, title: String, subtitle: String?, onResult: (UnlockResult) -> Unit) {
        val state = lockState(activity)
        if (state == LockState.NO_SCREEN_LOCK) return onResult(UnlockResult.Failed(UnlockFailure.NO_SCREEN_LOCK))
        val cipher = try {
            readyCipher(state)
        } catch (e: Exception) {
            Log.w(TAG, "write key not usable: ${e.javaClass.simpleName}")
            return onResult(UnlockResult.Failed(UnlockFailure.ERROR, e.javaClass.simpleName))
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(prove(state, result.cryptoObject?.cipher ?: cipher.takeIf { state == LockState.TIME_BOUND }))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> UnlockResult.Cancelled
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            UnlockResult.Failed(UnlockFailure.LOCKOUT, errString.toString())
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                            UnlockResult.Failed(UnlockFailure.NO_SCREEN_LOCK, errString.toString())
                        BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> UnlockResult.Failed(UnlockFailure.PROMPT_UNAVAILABLE, errString.toString())
                        else -> UnlockResult.Failed(UnlockFailure.ERROR, errString.toString())
                    }
                )
            }
        }
        try {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
                .setAllowedAuthenticators(
                    if (state == LockState.CRYPTO_PER_USE) Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                    else Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
                )
                .setConfirmationRequired(true)
                .build()
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            if (state == LockState.CRYPTO_PER_USE) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            else prompt.authenticate(info)
        } catch (e: Exception) {
            // Never "proceed anyway": without a prompt there is no write.
            Log.w(TAG, "prompt could not be shown: ${e.javaClass.simpleName}")
            onResult(UnlockResult.Failed(UnlockFailure.PROMPT_UNAVAILABLE, e.message))
        }
    }

    /** Granted only when the Keystore accepts an operation with the write key. */
    private fun prove(state: LockState, cipher: Cipher?): UnlockResult {
        if (cipher == null) return UnlockResult.Failed(UnlockFailure.NOT_PROVEN, "no cipher in the result")
        return try {
            // Below API 30 the cipher is initialised only now, inside the key's validity window.
            if (state == LockState.TIME_BOUND) cipher.init(Cipher.ENCRYPT_MODE, key(state))
            cipher.doFinal(ByteArray(32).also { SecureRandom().nextBytes(it) })
            UnlockResult.Granted
        } catch (e: UserNotAuthenticatedException) {
            UnlockResult.Failed(UnlockFailure.NOT_PROVEN, "the Keystore did not accept this unlock")
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The screen lock changed since the key was made; the next unlock gets a new one.
            deleteKey()
            UnlockResult.Failed(UnlockFailure.NOT_PROVEN, "the write key was renewed")
        } catch (e: Exception) {
            Log.w(TAG, "proof failed: ${e.javaClass.simpleName}")
            UnlockResult.Failed(UnlockFailure.NOT_PROVEN, e.javaClass.simpleName)
        }
    }

    /** A cipher to hand to the prompt (API 30+), or one to initialise after it (below). */
    private fun readyCipher(state: LockState): Cipher {
        val cipher = Cipher.getInstance(KeystoreSecretBox.TRANSFORMATION)
        if (state == LockState.TIME_BOUND) {
            // The key exists before the prompt, so the authentication it is about to see counts.
            key(state)
            return cipher
        }
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, key(state))
            cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The screen lock was removed and set again: the old key can never be used. A new one.
            deleteKey()
            Cipher.getInstance(KeystoreSecretBox.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(state)) }
        }
    }

    private fun key(state: LockState): SecretKey {
        val ks = KeyStore.getInstance(KeystoreSecretBox.ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // The screen lock is accepted anyway; a new fingerprint must not orphan the key.
            .setInvalidatedByBiometricEnrollment(false)
        if (state == LockState.CRYPTO_PER_USE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(TIME_BOUND_SEC)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreSecretBox.ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun deleteKey() {
        runCatching { KeyStore.getInstance(KeystoreSecretBox.ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/**
 * Keeps the window out of screenshots, screen recordings and the recents
 * thumbnail while it is in the composition: a token being typed, a new setup
 * key on screen. Dialogs are windows of their own and take
 * `SecureFlagPolicy.SecureOn`.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity != null) SecureWindowCount.acquire(activity)
        onDispose { if (activity != null) SecureWindowCount.release(activity) }
    }
}

/** Counted, so two secret views on one screen do not clear each other's flag. */
private object SecureWindowCount {
    private val counts = java.util.WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) = synchronized(counts) {
        val n = (counts[activity] ?: 0) + 1
        counts[activity] = n
        if (n == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) = synchronized(counts) {
        val n = (counts[activity] ?: 1) - 1
        if (n <= 0) {
            counts.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else counts[activity] = n
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
