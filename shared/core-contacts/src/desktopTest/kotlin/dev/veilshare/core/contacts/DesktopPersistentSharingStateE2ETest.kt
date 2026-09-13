package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.identity.PersistentSharingIdentityStore
import dev.veilshare.core.identity.PersistentSharingPresenceStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.securestore.DesktopSecureStateFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.fail

class DesktopPersistentSharingStateE2ETest {
    @Test
    fun `Windows DPAPI persists identity presence and contacts across process-style restart`() = runTest {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runTest
        val root = Files.createTempDirectory("veilshare-sharing-state-dpapi-")
        val signer = JvmEd25519Signer()
        val context = SharingContextId("desktop-persistent-context")
        val peerKey = ByteArray(32) { index -> (index * 17 + 9).toByte() }
        val peerRoute = ReferenceCodes.parse("2345-6789-ABCD-EFGM")

        try {
            val firstProtected = DesktopSecureStateFactory.windows(root)
            val identities = SharingIdentityManager(PersistentSharingIdentityStore(firstProtected), CountingRandom(10), signer)
            val presence = SharingPresenceManager(PersistentSharingPresenceStore(firstProtected), CountingRandom(20))
            val contacts = TrustedContactManager(PersistentTrustedContactStore(firstProtected), CountingRandom(30))

            val handle = identities.getOrCreate(context)
            val firstPublic = handle.publicIdentity
            val firstSeed = handle.withKeyPair { it.privateKey.material.copy() }
            handle.close()
            val firstRoute = presence.getOrCreate(context).referenceCode
            val createdContact = contacts.addVerified(
                alias = "Desktop Private Contact",
                candidate = PeerIdentityCandidate(
                    sharingIdentityId = SharingIdentityId("desktop-peer-id"),
                    publicKey = Ed25519PublicKey(peerKey.copyOf()),
                    referenceCode = peerRoute,
                ),
                verificationMethod = ContactVerificationMethod.QR_CODE,
            )

            val identityRaw = Files.readAllBytes(root.resolve("sharing.identity.v1.vss"))
            val presenceRaw = Files.readAllBytes(root.resolve("sharing.presence.v1.vss"))
            val contactsRaw = Files.readAllBytes(root.resolve("sharing.contacts.v1.vss"))
            assertFalse(identityRaw.containsSubsequence(firstSeed))
            assertFalse(presenceRaw.containsSubsequence(firstRoute.value.encodeToByteArray()))
            assertFalse(contactsRaw.containsSubsequence("Desktop Private Contact".encodeToByteArray()))

            val reopenedProtected = DesktopSecureStateFactory.windows(root)
            val reopenedIdentities = SharingIdentityManager(PersistentSharingIdentityStore(reopenedProtected), CountingRandom(1000), signer)
            val reopenedPresence = SharingPresenceManager(PersistentSharingPresenceStore(reopenedProtected), CountingRandom(2000))
            val reopenedContacts = TrustedContactManager(PersistentTrustedContactStore(reopenedProtected), CountingRandom(3000))

            val reopenedHandle = reopenedIdentities.getOrCreate(context)
            val reopenedSeed = reopenedHandle.withKeyPair { it.privateKey.material.copy() }
            try {
                assertEquals(firstPublic.identityId, reopenedHandle.publicIdentity.identityId)
                assertContentEquals(firstPublic.publicKey.bytes, reopenedHandle.publicIdentity.publicKey.bytes)
                assertContentEquals(firstSeed, reopenedSeed)
                assertEquals(firstRoute, reopenedPresence.getOrCreate(context).referenceCode)
                val contact = reopenedContacts.all().single()
                assertEquals(createdContact.contactId, contact.contactId)
                assertEquals(createdContact.alias, contact.alias)
                assertContentEquals(createdContact.pinnedPublicKey, contact.pinnedPublicKey)
            } finally {
                reopenedHandle.close()
                firstSeed.fill(0)
                reopenedSeed.fill(0)
            }

            Files.copy(
                root.resolve("sharing.identity.v1.vss"),
                root.resolve("sharing.contacts.v1.vss"),
                StandardCopyOption.REPLACE_EXISTING,
            )
            try {
                reopenedContacts.all()
                fail("Cross-scope DPAPI file swap must fail closed")
            } catch (_: Throwable) {
                // Expected.
            }
        } finally {
            peerKey.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var next = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = next++
            return ByteArray(size) { index -> ((marker * 29 + index * 5) and 0xff).toByte() }
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
