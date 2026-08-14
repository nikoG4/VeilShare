package dev.veilshare.core.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ProductionCryptoSpikeTest {
    @Test fun argon2idRawDerivationMatchesPinnedVector() = runTest {
        val key = JvmArgon2idPasswordKdf().derive(SensitiveChars("password".toCharArray()), Salt("somesalt".encodeToByteArray()), Argon2Policy(Argon2Parameters(8192, 1, 1)))
        assertContentEquals("07200454a4c2369a893741c6bf6355f29bf357857cc5eb8e8795104fbe70a38e".hex(), key.material.copy())
        key.material.close()
    }
    @Test fun chacha20Poly1305PinnedFixtureMatches() = runTest {
        val fixed = object : SecureRandom { override fun bytes(size: Int) = ByteArray(size) }
        val key = SensitiveBytes(ByteArray(32) { it.toByte() })
        val sealed = JvmChaCha20Poly1305Cipher(fixed).seal(key, "veil plaintext".encodeToByteArray(), "veil-aad".encodeToByteArray())
        assertContentEquals("6edd2b5d8d96cab07a0f2804d7370900da15e36961bb67186be5a1b1069a".hex(), sealed.ciphertext)
        key.close()
    }
    @Test fun aeadAuthenticatesWrappedKeyAndChunkContext() = runTest {
        val cipher = JvmChaCha20Poly1305Cipher(); val key = SensitiveBytes(ByteArray(32) { it.toByte() }); val aad = "v1|blob-a|0|3".encodeToByteArray(); val sealed = cipher.seal(key, "one".encodeToByteArray(), aad)
        assertContentEquals("one".encodeToByteArray(), cipher.open(key, sealed, aad))
        assertFailsWith<SecurityException> { cipher.open(key, sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }), aad) }
        assertFailsWith<SecurityException> { cipher.open(key, sealed, "v1|blob-a|1|3".encodeToByteArray()) }
        key.close()
    }
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
