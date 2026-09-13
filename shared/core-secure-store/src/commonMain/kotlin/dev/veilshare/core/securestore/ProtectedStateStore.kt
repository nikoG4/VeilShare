package dev.veilshare.core.securestore

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.SensitiveBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

@JvmInline
value class SecureStateScope(val value: String) {
    init {
        require(value.length in 1..64) { "Secure state scope length is invalid" }
        require(value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }) {
            "Secure state scope contains unsafe characters"
        }
    }

    internal fun fileName(): String = "$value.vss"
    internal fun aad(): ByteArray = "VEILSHARE/SECURE-STATE/V1/$value".encodeToByteArray()
}

interface SecureStateProtector {
    suspend fun protect(scope: SecureStateScope, plaintext: ByteArray): ByteArray
    suspend fun unprotect(scope: SecureStateScope, protectedBytes: ByteArray): ByteArray
}

interface AtomicStateStorage {
    suspend fun read(fileName: String): ByteArray?
    suspend fun replaceAtomic(fileName: String, bytes: ByteArray)
    suspend fun delete(fileName: String)
}

class ProtectedStateStore(
    private val storage: AtomicStateStorage,
    private val protector: SecureStateProtector,
    private val maxPlaintextBytes: Int = DEFAULT_MAX_PLAINTEXT_BYTES,
    private val maxProtectedBytes: Int = DEFAULT_MAX_PROTECTED_BYTES,
) {
    init {
        require(maxPlaintextBytes > 0)
        require(maxProtectedBytes >= maxPlaintextBytes)
    }

    suspend fun read(scope: SecureStateScope): ByteArray? {
        val protected = storage.read(scope.fileName()) ?: return null
        try {
            require(protected.isNotEmpty()) { "Protected state file is empty" }
            require(protected.size <= maxProtectedBytes) { "Protected state file exceeds size limit" }
            val plaintext = protector.unprotect(scope, protected)
            try {
                require(plaintext.size <= maxPlaintextBytes) { "Decrypted state exceeds size limit" }
                return plaintext
            } catch (failure: Throwable) {
                plaintext.fill(0)
                throw failure
            }
        } finally {
            protected.fill(0)
        }
    }

    suspend fun write(scope: SecureStateScope, plaintext: ByteArray) {
        require(plaintext.size <= maxPlaintextBytes) { "Plaintext state exceeds size limit" }
        val protected = protector.protect(scope, plaintext)
        try {
            require(protected.isNotEmpty()) { "State protector returned empty output" }
            require(protected.size <= maxProtectedBytes) { "Protected state exceeds size limit" }
            storage.replaceAtomic(scope.fileName(), protected)
        } finally {
            protected.fill(0)
        }
    }

    suspend fun delete(scope: SecureStateScope) {
        storage.delete(scope.fileName())
    }

    companion object {
        const val DEFAULT_MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_PROTECTED_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Common AEAD protector used by tests and by platforms that already have a securely-held
 * 32-byte state key. The caller transfers ownership of [key] to this object.
 */
class AeadStateProtector(
    private val cipher: AuthenticatedCipher,
    private val key: SensitiveBytes,
) : SecureStateProtector, AutoCloseable {
    override suspend fun protect(scope: SecureStateScope, plaintext: ByteArray): ByteArray {
        val sealed = cipher.seal(key, plaintext, scope.aad())
        require(sealed.nonce.bytes.size == NONCE_BYTES) { "Unexpected AEAD nonce size" }
        return ByteArray(MAGIC.size + NONCE_BYTES + sealed.ciphertext.size).also { output ->
            MAGIC.copyInto(output, 0)
            sealed.nonce.bytes.copyInto(output, MAGIC.size)
            sealed.ciphertext.copyInto(output, MAGIC.size + NONCE_BYTES)
        }
    }

    override suspend fun unprotect(scope: SecureStateScope, protectedBytes: ByteArray): ByteArray {
        require(protectedBytes.size > MAGIC.size + NONCE_BYTES) { "Protected AEAD state is truncated" }
        require(protectedBytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Protected AEAD state magic mismatch"
        }
        val nonceBytes = protectedBytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val ciphertext = protectedBytes.copyOfRange(MAGIC.size + NONCE_BYTES, protectedBytes.size)
        return try {
            cipher.open(key, SealedBytes(Nonce(nonceBytes), ciphertext), scope.aad())
        } finally {
            nonceBytes.fill(0)
            ciphertext.fill(0)
        }
    }

    override fun close() {
        key.close()
    }

    private companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
        const val NONCE_BYTES = 12
    }
}

class InMemoryAtomicStateStorage : AtomicStateStorage {
    private val mutex = Mutex()
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun read(fileName: String): ByteArray? = mutex.withLock {
        files[fileName]?.copyOf()
    }

    override suspend fun replaceAtomic(fileName: String, bytes: ByteArray) {
        mutex.withLock {
            files.put(fileName, bytes.copyOf())?.fill(0)
        }
    }

    override suspend fun delete(fileName: String) {
        mutex.withLock { files.remove(fileName)?.fill(0) }
    }

    suspend fun snapshot(fileName: String): ByteArray? = read(fileName)

    suspend fun overwriteForTest(fileName: String, bytes: ByteArray) = replaceAtomic(fileName, bytes)
}
