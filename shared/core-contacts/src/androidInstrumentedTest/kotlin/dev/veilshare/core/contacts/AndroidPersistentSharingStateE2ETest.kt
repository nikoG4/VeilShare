package dev.veilshare.core.contacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.veilshare.core.crypto.AndroidProductionCrypto
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.identity.PersistentSharingIdentityStore
import dev.veilshare.core.identity.PersistentSharingPresenceStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.securestore.AndroidDirectoryStateStorage
import dev.veilshare.core.securestore.AndroidKeystoreStateProtector
import dev.veilshare.core.securestore.ProtectedStateStore
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AndroidPersistentSharingStateE2ETest {
    @Test
    fun `identity presence and contacts survive Android process-style restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val directoryName = "sharing-state-e2e-$suffix"
        val alias = "dev.veilshare.test.persistent.$suffix"
        val protectedStore = ProtectedStateStore(
            AndroidDirectoryStateStorage(context, directoryName),
            AndroidKeystoreStateProtector(alias),
        )
        val signer = AndroidProductionCrypto.ed25519Signer()
        val sharingContext = SharingContextId("android-persistent-context")
        val peerKey = ByteArray(32) { index -> (index * 7 + 33).toByte() }
        val peerRoute = ReferenceCodes.parse("2345-6789-ABCD-EFGH")

        try {
            val identities = SharingIdentityManager(
                PersistentSharingIdentityStore(protectedStore),
                CountingRandom(10),
                signer,
            )
            val presence = SharingPresenceManager(
                PersistentSharingPresenceStore(protectedStore),
                CountingRandom(20),
            )
            val contacts = TrustedContactManager(
                PersistentTrustedContactStore(protectedStore),
                CountingRandom(30),
            )

            val handle = identities.getOrCreate(sharingContext)
            val publicIdentity = handle.publicIdentity
            val seed = handle.withKeyPair { it.privateKey.material.copy() }
            handle.close()
            val route = presence.getOrCreate(sharingContext).referenceCode
            val contact = contacts.addVerified(
                alias = "Android Secret Alias",
                candidate = PeerIdentityCandidate(
                    SharingIdentityId("android-peer-id"),
                    Ed25519PublicKey(peerKey.copyOf()),
                    peerRoute,
                ),
                verificationMethod = ContactVerificationMethod.QR_CODE,
            )

            val root = File(context.noBackupFilesDir, directoryName)
            assertFalse(File(root, "sharing.identity.v1.vss").readBytes().containsSubsequence(seed))
            assertFalse(File(root, "sharing.presence.v1.vss").readBytes().containsSubsequence(route.value.encodeToByteArray()))
            assertFalse(File(root, "sharing.contacts.v1.vss").readBytes().containsSubsequence("Android Secret Alias".encodeToByteArray()))

            val reopenedStore = ProtectedStateStore(
                AndroidDirectoryStateStorage(context, directoryName),
                AndroidKeystoreStateProtector(alias),
            )
            val reopenedIdentities = SharingIdentityManager(
                PersistentSharingIdentityStore(reopenedStore),
                CountingRandom(1000),
                signer,
            )
            val reopenedPresence = SharingPresenceManager(
                PersistentSharingPresenceStore(reopenedStore),
                CountingRandom(2000),
            )
            val reopenedContacts = TrustedContactManager(
                PersistentTrustedContactStore(reopenedStore),
                CountingRandom(3000),
            )

            val reopenedHandle = reopenedIdentities.getOrCreate(sharingContext)
            val reopenedSeed = reopenedHandle.withKeyPair { it.privateKey.material.copy() }
            try {
                assertEquals(publicIdentity.identityId, reopenedHandle.publicIdentity.identityId)
                assertContentEquals(publicIdentity.publicKey.bytes, reopenedHandle.publicIdentity.publicKey.bytes)
                assertContentEquals(seed, reopenedSeed)
                assertEquals(route, reopenedPresence.getOrCreate(sharingContext).referenceCode)
                val reopenedContact = reopenedContacts.all().single()
                assertEquals(contact.contactId, reopenedContact.contactId)
                assertEquals(contact.alias, reopenedContact.alias)
                assertContentEquals(contact.pinnedPublicKey, reopenedContact.pinnedPublicKey)
            } finally {
                reopenedHandle.close()
                seed.fill(0)
                reopenedSeed.fill(0)
            }
        } finally {
            peerKey.fill(0)
            File(context.noBackupFilesDir, directoryName).deleteRecursively()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var next = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = next++
            return ByteArray(size) { index -> ((marker * 23 + index * 11) and 0xff).toByte() }
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
