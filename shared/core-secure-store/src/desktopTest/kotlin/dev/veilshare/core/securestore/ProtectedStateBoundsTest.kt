package dev.veilshare.core.securestore

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

class ProtectedStateBoundsTest {
    @Test
    fun `truncated protected state fails closed`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(
            crypto.cipher,
            SensitiveBytes(ByteArray(32) { index -> (index * 5 + 13).toByte() }),
        )
        val store = ProtectedStateStore(storage, protector)
        val scope = SecureStateScope("sharing.identity.v1")
        try {
            store.write(scope, "bounded-state".encodeToByteArray())
            val valid = requireNotNull(storage.snapshot("sharing.identity.v1.vss"))
            storage.overwriteForTest("sharing.identity.v1.vss", valid.copyOf(8))
            expectFailure { store.read(scope) }
        } finally {
            protector.close()
        }
    }

    @Test
    fun `oversized protected state is rejected before unprotect`() = runTest {
        val storage = InMemoryAtomicStateStorage()
        val protector = object : SecureStateProtector {
            var unprotectCalled = false
            override suspend fun protect(scope: SecureStateScope, plaintext: ByteArray): ByteArray = plaintext.copyOf()
            override suspend fun unprotect(scope: SecureStateScope, protectedBytes: ByteArray): ByteArray {
                unprotectCalled = true
                return protectedBytes.copyOf()
            }
        }
        val store = ProtectedStateStore(storage, protector, maxPlaintextBytes = 32, maxProtectedBytes = 64)
        val scope = SecureStateScope("sharing.contacts.v1")
        storage.overwriteForTest("sharing.contacts.v1.vss", ByteArray(65) { 1 })
        expectFailure { store.read(scope) }
        if (protector.unprotectCalled) fail("Oversized file reached the protector")
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected operation to fail closed")
        } catch (_: Throwable) {
            // Expected.
        }
    }
}
