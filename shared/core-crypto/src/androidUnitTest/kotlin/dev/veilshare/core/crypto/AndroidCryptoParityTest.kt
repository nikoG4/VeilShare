package dev.veilshare.core.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AndroidCryptoParityTest {
    @Test fun `HKDF catalog fixture matches Desktop bytes`() = runTest {
        val input = SensitiveBytes(ByteArray(32) { it.toByte() })
        val result = AndroidHkdfSha256KeyDeriver().derive(input, CryptoContexts.Catalog)
        try {
            assertEquals(
                "d4753e5337a457db837c5b15ddb39d1b8bbdffa14e6a56ce2aee4d803004b0fc",
                result.copy().joinToString("") { it.toUByte().toString(16).padStart(2, '0') },
            )
        } finally { result.close(); input.close() }
    }

    @Test fun `ChaCha fixture opens exact plaintext`() = runTest {
        val cipher = AndroidChaCha20Poly1305Cipher()
        val key = SensitiveBytes(ByteArray(32) { it.toByte() })
        val nonce = Nonce(ByteArray(12) { (it + 1).toByte() })
        val plaintext = "VEIL Android parity".encodeToByteArray()
        val aad = "fixture-aad".encodeToByteArray()
        try {
            val sealed = cipher.sealWithNonce(key, nonce, plaintext, aad)
            assertContentEquals(plaintext, cipher.open(key, sealed, aad))
            assertEquals(plaintext.size + 16, sealed.ciphertext.size)
        } finally { key.close() }
    }
}
