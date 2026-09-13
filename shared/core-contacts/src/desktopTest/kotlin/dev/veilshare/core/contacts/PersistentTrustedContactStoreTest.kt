package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.securestore.AeadStateProtector
import dev.veilshare.core.securestore.InMemoryAtomicStateStorage
import dev.veilshare.core.securestore.ProtectedStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.fail

class PersistentTrustedContactStoreTest {
    @Test
    fun `verified contact survives restart without alias identity route or key in persisted bytes`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(
            crypto.cipher,
            SensitiveBytes(ByteArray(32) { index -> (index * 11 + 5).toByte() }),
        )
        val protectedStore = ProtectedStateStore(storage, protector)
        val manager = TrustedContactManager(PersistentTrustedContactStore(protectedStore), CountingRandom(10))
        val key = ByteArray(32) { index -> (index * 13 + 7).toByte() }
        val route = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val candidate = PeerIdentityCandidate(
            sharingIdentityId = SharingIdentityId("persistent-peer-identity"),
            publicKey = Ed25519PublicKey(key.copyOf()),
            referenceCode = route,
        )
        val created = manager.addVerified("Private Alias", candidate, ContactVerificationMethod.QR_CODE)

        val raw = assertNotNull(storage.snapshot("sharing.contacts.v1.vss"))
        assertFalse(raw.containsSubsequence("Private Alias".encodeToByteArray()))
        assertFalse(raw.containsSubsequence(candidate.sharingIdentityId.value.encodeToByteArray()))
        assertFalse(raw.containsSubsequence(route.value.encodeToByteArray()))
        assertFalse(raw.containsSubsequence(key))

        val restarted = TrustedContactManager(PersistentTrustedContactStore(protectedStore), CountingRandom(1000))
        val contacts = restarted.all()
        assertEquals(1, contacts.size)
        val reopened = contacts.single()
        assertEquals(created.contactId, reopened.contactId)
        assertEquals(created.alias, reopened.alias)
        assertEquals(created.sharingIdentityId, reopened.sharingIdentityId)
        assertContentEquals(created.pinnedPublicKey, reopened.pinnedPublicKey)
        assertEquals(created.referenceCode, reopened.referenceCode)
        assertEquals(created.fingerprint, reopened.fingerprint)

        protector.close()
        key.fill(0)
    }

    @Test
    fun `rename and authenticated route update persist while key remains pinned`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(crypto.cipher, SensitiveBytes(ByteArray(32) { (it + 23).toByte() }))
        val protectedStore = ProtectedStateStore(storage, protector)
        val manager = TrustedContactManager(PersistentTrustedContactStore(protectedStore), CountingRandom(20))
        val originalKey = ByteArray(32) { index -> (index + 41).toByte() }
        val initialRoute = ReferenceCodes.parse("2345-6789-ABCD-EFGJ")
        val nextRoute = ReferenceCodes.parse("2345-6789-ABCD-EFGK")
        val created = manager.addVerified(
            "Peer A",
            PeerIdentityCandidate(SharingIdentityId("peer-a"), Ed25519PublicKey(originalKey.copyOf()), initialRoute),
            ContactVerificationMethod.MANUAL_FINGERPRINT,
        )

        manager.rename(created.contactId, "Peer Renamed")
        manager.updateReferenceCodeFromAuthenticatedSession(created.contactId, nextRoute)

        val reopened = TrustedContactManager(PersistentTrustedContactStore(protectedStore), CountingRandom(2000))
            .all().single()
        assertEquals("Peer Renamed", reopened.alias)
        assertEquals(nextRoute, reopened.referenceCode)
        assertContentEquals(originalKey, reopened.pinnedPublicKey)
        assertEquals(created.sharingIdentityId, reopened.sharingIdentityId)

        protector.close()
        originalKey.fill(0)
    }

    @Test
    fun `contact corruption fails closed`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val storage = InMemoryAtomicStateStorage()
        val protector = AeadStateProtector(crypto.cipher, SensitiveBytes(ByteArray(32) { (it * 5 + 1).toByte() }))
        val protectedStore = ProtectedStateStore(storage, protector)
        val manager = TrustedContactManager(PersistentTrustedContactStore(protectedStore), CountingRandom(30))
        manager.addVerified(
            "Corruption Peer",
            PeerIdentityCandidate(
                SharingIdentityId("corruption-peer"),
                Ed25519PublicKey(ByteArray(32) { it.toByte() }),
                ReferenceCodes.parse("2345-6789-ABCD-EFGL"),
            ),
            ContactVerificationMethod.QR_CODE,
        )
        val raw = assertNotNull(storage.snapshot("sharing.contacts.v1.vss"))
        raw[raw.lastIndex / 2] = (raw[raw.lastIndex / 2].toInt() xor 0x04).toByte()
        storage.overwriteForTest("sharing.contacts.v1.vss", raw)

        try {
            try {
                manager.all()
                fail("Corrupted protected contact state must fail closed")
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
            return ByteArray(size) { index -> ((marker * 19 + index * 3) and 0xff).toByte() }
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
