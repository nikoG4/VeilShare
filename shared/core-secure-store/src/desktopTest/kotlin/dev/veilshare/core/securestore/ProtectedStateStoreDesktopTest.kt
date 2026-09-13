package dev.veilshare.core.securestore

import com.sun.jna.Platform
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveBytes
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProtectedStateStoreDesktopTest {
    @Test
    fun `real AEAD roundtrip rejects tamper and cross-scope swap`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val protector = AeadStateProtector(
            cipher = crypto.cipher,
            key = SensitiveBytes(ByteArray(32) { index -> (index * 7 + 11).toByte() }),
        )
        val storage = InMemoryAtomicStateStorage()
        val store = ProtectedStateStore(storage, protector)
        val identityScope = SecureStateScope("sharing.identity.v1")
        val presenceScope = SecureStateScope("sharing.presence.v1")
        val plaintext = "private-seed-marker::contact-alias::route".encodeToByteArray()

        try {
            store.write(identityScope, plaintext)
            val persisted = assertNotNull(storage.snapshot("sharing.identity.v1.vss"))
            assertFalse(persisted.containsSubsequence(plaintext))
            assertContentEquals(plaintext, store.read(identityScope))

            storage.overwriteForTest("sharing.presence.v1.vss", persisted)
            expectFailure { store.read(presenceScope) }

            val tampered = persisted.copyOf()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
            storage.overwriteForTest("sharing.identity.v1.vss", tampered)
            expectFailure { store.read(identityScope) }
        } finally {
            protector.close()
            plaintext.fill(0)
        }
    }

    @Test
    fun `desktop atomic storage survives replacement and deletion`() = runTest {
        val root = Files.createTempDirectory("veilshare-secure-state-")
        try {
            val storage = DesktopDirectoryStateStorage(root)
            assertNull(storage.read("state.vss"))
            storage.replaceAtomic("state.vss", byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), storage.read("state.vss"))
            storage.replaceAtomic("state.vss", byteArrayOf(9, 8, 7, 6))
            assertContentEquals(byteArrayOf(9, 8, 7, 6), storage.read("state.vss"))
            storage.delete("state.vss")
            assertNull(storage.read("state.vss"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Windows DPAPI roundtrip binds ciphertext to state scope`() = runTest {
        if (!Platform.isWindows()) return@runTest
        val protector = WindowsDpapiStateProtector()
        val a = SecureStateScope("sharing.identity.v1")
        val b = SecureStateScope("sharing.contacts.v1")
        val plaintext = ByteArray(64) { index -> (index * 5 + 3).toByte() }
        val protected = protector.protect(a, plaintext)
        try {
            assertFalse(protected.contentEquals(plaintext))
            assertContentEquals(plaintext, protector.unprotect(a, protected))
            expectFailure { protector.unprotect(b, protected) }
        } finally {
            plaintext.fill(0)
            protected.fill(0)
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected operation to fail closed")
        } catch (_: Throwable) {
            // Expected.
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
