package dev.veilshare.android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.veilshare.ui.features.QuickUnlockProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Stores only an AES-GCM encrypted credential blob under noBackupFilesDir. The AES key is
 * non-exportable in Android Keystore and requires BIOMETRIC_STRONG authentication per use.
 * PIN unlock remains available if the key/blob is missing, invalidated or authentication is cancelled.
 */
internal class AndroidBiometricQuickUnlock(
    private val activity: FragmentActivity,
) : QuickUnlockProvider {
    private val root = File(activity.noBackupFilesDir, "quick-unlock-v1")
    private val blob = File(root, "credential.bin")
    private var pending: CharArray? = null

    override val available: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override val hasCredential: Boolean
        get() = blob.isFile

    override fun stageEnrollment(credential: CharArray) {
        discardPendingEnrollment()
        pending = credential.copyOf()
    }

    override fun discardPendingEnrollment() {
        pending?.fill('\u0000')
        pending = null
    }

    override suspend fun completePendingEnrollment(): Boolean {
        val chars = pending ?: return false
        return try {
            if (!available) return false
            val clear = encode(chars)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
                    updateAAD(AAD)
                }
                val authenticated = authenticate("Activar desbloqueo biométrico", cipher) ?: return false
                val encrypted = authenticated.doFinal(clear)
                val iv = authenticated.iv
                require(iv.size in 12..32)
                val payload = ByteArray(2 + iv.size + encrypted.size)
                payload[0] = FORMAT_VERSION
                payload[1] = iv.size.toByte()
                iv.copyInto(payload, 2)
                encrypted.copyInto(payload, 2 + iv.size)
                atomicWrite(payload)
                true
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            discardPendingEnrollment()
        }
    }

    override suspend fun requestCredential(): CharArray? {
        if (!available || !blob.isFile) return null
        val payload = runCatching { blob.readBytes() }.getOrNull() ?: return null
        if (payload.size < 3 || payload[0] != FORMAT_VERSION) return null
        val ivSize = payload[1].toInt() and 0xff
        if (ivSize !in 12..32 || payload.size <= 2 + ivSize) return null
        val iv = payload.copyOfRange(2, 2 + ivSize)
        val encrypted = payload.copyOfRange(2 + ivSize, payload.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(128, iv))
                updateAAD(AAD)
            }
            val authenticated = authenticate("Desbloquear archivos", cipher) ?: return null
            val clear = authenticated.doFinal(encrypted)
            try {
                decode(clear)
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            // A missing/invalidated key makes the shortcut unusable, never the vault itself.
            clearCredential()
            null
        } finally {
            iv.fill(0)
            encrypted.fill(0)
            payload.fill(0)
        }
    }

    override fun clearCredential() {
        discardPendingEnrollment()
        runCatching { blob.delete() }
        runCatching { File(root, TEMP_NAME).delete() }
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(createIfMissing) { "Quick unlock key is missing" }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private suspend fun authenticate(title: String, cipher: Cipher): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("VeilShare")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Usar código")
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private fun atomicWrite(bytes: ByteArray) {
        check(root.mkdirs() || root.isDirectory)
        val temp = File(root, TEMP_NAME)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (blob.exists() && !blob.delete()) {
            temp.delete()
            error("Unable to replace quick unlock blob")
        }
        if (!temp.renameTo(blob)) {
            temp.delete()
            error("Unable to commit quick unlock blob")
        }
    }

    private fun encode(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun decode(bytes: ByteArray): CharArray {
        val buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        return CharArray(buffer.remaining()).also { buffer.get(it) }
    }

    private companion object {
        const val KEY_ALIAS = "veilshare.quick.unlock.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TEMP_NAME = "credential.tmp"
        const val FORMAT_VERSION: Byte = 1
        val AAD = "veilshare.quick.unlock.v1".encodeToByteArray()
    }
}
