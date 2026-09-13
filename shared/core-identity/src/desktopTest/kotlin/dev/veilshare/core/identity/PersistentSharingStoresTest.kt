package dev.veilshare.core.identity

import dev.veilshare.core.crypto.AeadKeyWrapper
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.securestore.AeadStateProtector
import dev.veilshare.core.securestore.InMemoryAtomicStateStorage
import dev.veilshare.core.securestore.ProtectedStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class PersistentSharingStoresTest {
    @Test
    fun `identity and presence survive manager restart without plaintext state`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(
            crypto.cipher,
            SensitiveBytes(ByteArray(32) { index -> (index * 9 + 17).toByte() }),
        )
        val protectedStore = ProtectedStateStore(storage, protector)
        val signer = JvmEd25519Signer()
        val context = SharingContextId("persistent-primary-context")
        val identityStore = PersistentSharingIdentityStore(protectedStore)
        val presenceStore = PersistentSharingPresenceStore(protectedStore)
        val identities = SharingIdentityManager(identityStore, CountingRandom(10), signer)
        val presence = SharingPresenceManager(presenceStore, CountingRandom(20))

        val firstHandle = identities.getOrCreate(context)
        val firstPublic = firstHandle.publicIdentity
        val firstSeed = firstHandle.withKeyPair { it.privateKey.material.copy() }
        firstHandle.close()
        val firstRoute = presence.getOrCreate(context).referenceCode

        val identityBlob = assertNotNull(storage.snapshot("sharing.identity.v1.vss"))
        val presenceBlob = assertNotNull(storage.snapshot("sharing.presence.v1.vss"))
        assertFalse(identityBlob.containsSubsequence(firstSeed))
        assertFalse(identityBlob.containsSubsequence(firstPublic.identityId.value.encodeToByteArray()))
        assertFalse(presenceBlob.containsSubsequence(firstRoute.value.encodeToByteArray()))

        val restartedIdentities = SharingIdentityManager(
            PersistentSharingIdentityStore(protectedStore),
            CountingRandom(1000),
            signer,
        )
        val restartedPresence = SharingPresenceManager(
            PersistentSharingPresenceStore(protectedStore),
            CountingRandom(2000),
        )
        val reopened = restartedIdentities.getOrCreate(context)
        val reopenedSeed = reopened.withKeyPair { it.privateKey.material.copy() }
        try {
            assertEquals(firstPublic.identityId, reopened.publicIdentity.identityId)
            assertContentEquals(firstPublic.publicKey.bytes, reopened.publicIdentity.publicKey.bytes)
            assertContentEquals(firstSeed, reopenedSeed)
            assertEquals(firstRoute, restartedPresence.getOrCreate(context).referenceCode)
        } finally {
            reopened.close()
            firstSeed.fill(0)
            reopenedSeed.fill(0)
            protector.close()
        }
    }

    @Test
    fun `identity and route rotations persist independently`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(crypto.cipher, SensitiveBytes(ByteArray(32) { (it + 31).toByte() }))
        val protectedStore = ProtectedStateStore(storage, protector)
        val signer = JvmEd25519Signer()
        val context = SharingContextId("rotation-context")
        val identityManager = SharingIdentityManager(PersistentSharingIdentityStore(protectedStore), CountingRandom(40), signer)
        val presenceManager = SharingPresenceManager(PersistentSharingPresenceStore(protectedStore), CountingRandom(50))

        val originalIdentity = identityManager.getOrCreate(context).let { handle ->
            try { handle.publicIdentity } finally { handle.close() }
        }
        val originalRoute = presenceManager.getOrCreate(context).referenceCode
        val rotatedRoute = presenceManager.rotate(context).referenceCode
        assertNotEquals(originalRoute, rotatedRoute)
        assertEquals(originalIdentity.identityId, assertNotNull(identityManager.publicIdentity(context)).identityId)

        val rotatedIdentity = identityManager.rotate(context).let { handle ->
            try { handle.publicIdentity } finally { handle.close() }
        }
        assertNotEquals(originalIdentity.identityId, rotatedIdentity.identityId)
        assertEquals(rotatedRoute, presenceManager.getOrCreate(context).referenceCode)

        val reopenedIdentities = SharingIdentityManager(PersistentSharingIdentityStore(protectedStore), CountingRandom(999), signer)
        val reopenedPresence = SharingPresenceManager(PersistentSharingPresenceStore(protectedStore), CountingRandom(998))
        assertEquals(rotatedIdentity.identityId, assertNotNull(reopenedIdentities.publicIdentity(context)).identityId)
        assertEquals(rotatedRoute, reopenedPresence.getOrCreate(context).referenceCode)
        protector.close()
    }

    @Test
    fun `identity corruption fails closed`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(crypto.cipher, SensitiveBytes(ByteArray(32) { (it * 3 + 7).toByte() }))
        val protectedStore = ProtectedStateStore(storage, protector)
        val manager = SharingIdentityManager(
            PersistentSharingIdentityStore(protectedStore),
            CountingRandom(70),
            JvmEd25519Signer(),
        )
        val context = SharingContextId("corruption-context")
        manager.getOrCreate(context).close()

        val blob = assertNotNull(storage.snapshot("sharing.identity.v1.vss"))
        blob[blob.lastIndex] = (blob.last().toInt() xor 0x20).toByte()
        storage.overwriteForTest("sharing.identity.v1.vss", blob)

        try {
            try {
                manager.publicIdentity(context)
                fail("Corrupted protected identity state must fail closed")
            } catch (_: Throwable) {
                // Expected.
            }
        } finally {
            protector.close()
        }
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var next = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = next++
            return ByteArray(size) { index -> ((marker * 31 + index * 7) and 0xff).toByte() }
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
