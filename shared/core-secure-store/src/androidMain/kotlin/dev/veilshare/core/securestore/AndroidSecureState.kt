package dev.veilshare.core.securestore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreStateProtector(
    private val alias: String = DEFAULT_ALIAS,
) : SecureStateProtector {
    init {
        require(alias.isNotBlank())
    }

    override suspend fun protect(scope: SecureStateScope, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(scope.aad())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == GCM_NONCE_BYTES) { "Unexpected Android Keystore GCM nonce size" }
        return ByteArray(MAGIC.size + GCM_NONCE_BYTES + ciphertext.size).also { output ->
            MAGIC.copyInto(output, 0)
            iv.copyInto(output, MAGIC.size)
            ciphertext.copyInto(output, MAGIC.size + GCM_NONCE_BYTES)
        }
    }

    override suspend fun unprotect(scope: SecureStateScope, protectedBytes: ByteArray): ByteArray {
        require(protectedBytes.size > MAGIC.size + GCM_NONCE_BYTES) { "Protected Android state is truncated" }
        require(protectedBytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Protected Android state magic mismatch"
        }
        val iv = protectedBytes.copyOfRange(MAGIC.size, MAGIC.size + GCM_NONCE_BYTES)
        val ciphertext = protectedBytes.copyOfRange(MAGIC.size + GCM_NONCE_BYTES, protectedBytes.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(scope.aad())
            cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "dev.veilshare.sharing.state.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    }
}

class AndroidDirectoryStateStorage(
    context: Context,
    directoryName: String = "veilshare-sharing-state",
    private val maxFileBytes: Long = ProtectedStateStore.DEFAULT_MAX_PROTECTED_BYTES.toLong(),
) : AtomicStateStorage {
    private val root = File(context.noBackupFilesDir, directoryName).also { directory ->
        require(directoryName.isNotBlank() && '/' !in directoryName && '\\' !in directoryName)
        check(directory.exists() || directory.mkdirs()) { "Unable to create secure sharing-state directory" }
    }

    override suspend fun read(fileName: String): ByteArray? {
        val target = resolve(fileName)
        if (!target.exists()) return null
        require(target.isFile) { "Secure state path is not a file" }
        require(target.length() in 1..maxFileBytes) { "Secure state file size is invalid" }
        return target.readBytes()
    }

    override suspend fun replaceAtomic(fileName: String, bytes: ByteArray) {
        require(bytes.isNotEmpty())
        require(bytes.size.toLong() <= maxFileBytes) { "Secure state file exceeds size limit" }
        val target = resolve(fileName)
        val temp = File.createTempFile(".$fileName.", ".tmp", root)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override suspend fun delete(fileName: String) {
        val target = resolve(fileName)
        if (target.exists() && !target.delete()) {
            error("Unable to delete secure state file")
        }
    }

    private fun resolve(fileName: String): File {
        require(fileName.length in 1..80)
        require(fileName.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "Unsafe secure state filename"
        }
        val target = File(root, fileName).canonicalFile
        require(target.parentFile == root.canonicalFile) { "Secure state path escaped root" }
        return target
    }
}

object AndroidSecureStateFactory {
    fun create(context: Context): ProtectedStateStore = ProtectedStateStore(
        storage = AndroidDirectoryStateStorage(context),
        protector = AndroidKeystoreStateProtector(),
    )
}
