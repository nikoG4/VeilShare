package dev.veilshare.core.identity

import dev.veilshare.core.crypto.Ed25519KeyPair
import dev.veilshare.core.crypto.Ed25519PrivateKey
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.RandomBytesSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingIdentityTest {
    @Test
    fun getOrCreateIsStableForSameContextAndIsolatedAcrossContexts() = runTest {
        val manager = manager()
        val realContext = SharingContextId("ctx-real-random")
        val decoyContext = SharingContextId("ctx-decoy-random")

        val first = manager.getOrCreate(realContext)
        val second = manager.getOrCreate(realContext)
        val decoy = manager.getOrCreate(decoyContext)

        assertEquals(first.publicIdentity.identityId, second.publicIdentity.identityId)
        assertContentEquals(first.publicIdentity.publicKey.bytes, second.publicIdentity.publicKey.bytes)
        assertNotEquals(first.publicIdentity.identityId, decoy.publicIdentity.identityId)
        assertFalse(first.publicIdentity.publicKey.bytes.contentEquals(decoy.publicIdentity.publicKey.bytes))
        assertNotEquals(first.publicIdentity.fingerprint, decoy.publicIdentity.fingerprint)

        first.close()
        second.close()
        decoy.close()
    }

    @Test
    fun rotationReplacesIdentityIdAndEd25519Key() = runTest {
        val manager = manager()
        val context = SharingContextId("ctx-rotation")

        val before = manager.getOrCreate(context)
        val beforeId = before.publicIdentity.identityId
        val beforeKey = before.publicIdentity.publicKey.bytes.copyOf()
        val beforeFingerprint = before.publicIdentity.fingerprint
        before.close()

        val after = manager.rotate(context)

        assertNotEquals(beforeId, after.publicIdentity.identityId)
        assertFalse(beforeKey.contentEquals(after.publicIdentity.publicKey.bytes))
        assertNotEquals(beforeFingerprint, after.publicIdentity.fingerprint)

        val loadedAgain = manager.getOrCreate(context)
        assertEquals(after.publicIdentity.identityId, loadedAgain.publicIdentity.identityId)
        assertContentEquals(after.publicIdentity.publicKey.bytes, loadedAgain.publicIdentity.publicKey.bytes)

        after.close()
        loadedAgain.close()
    }

    @Test
    fun withKeyPairUsesStoredSeedAndHandleCanBeClosed() = runTest {
        val manager = manager()
        val handle = manager.getOrCreate(SharingContextId("ctx-handle"))

        val privateSeed = handle.withKeyPair { pair ->
            assertContentEquals(handle.publicIdentity.publicKey.bytes, pair.publicKey.bytes)
            pair.privateKey.material.copy()
        }

        assertEquals(32, privateSeed.size)
        assertTrue(handle.isOpen)
        handle.close()
        assertFalse(handle.isOpen)
    }

    @Test
    fun deleteRemovesIdentityAndNextCreateIsFresh() = runTest {
        val store = InMemorySharingIdentityStore()
        val manager = SharingIdentityManager(store, CountingRandom(), FakeSigner())
        val context = SharingContextId("ctx-delete")

        val first = manager.getOrCreate(context)
        val firstId = first.publicIdentity.identityId
        first.close()

        manager.delete(context)
        assertNull(manager.publicIdentity(context))

        val recreated = manager.getOrCreate(context)
        assertNotEquals(firstId, recreated.publicIdentity.identityId)
        assertNotNull(manager.publicIdentity(context))
        recreated.close()
    }

    @Test
    fun storeReturnsDefensiveCopies() = runTest {
        val store = InMemorySharingIdentityStore()
        val context = SharingContextId("ctx-copy")
        val original = StoredSharingIdentity(
            contextId = context,
            identityId = dev.veilshare.core.model.SharingIdentityId("identity-copy"),
            privateKeySeed = ByteArray(32) { 1 },
            publicKey = ByteArray(32) { 2 },
        )
        store.replace(original)

        val loaded = requireNotNull(store.load(context))
        loaded.privateKeySeed.fill(9)
        loaded.publicKey.fill(9)

        val loadedAgain = requireNotNull(store.load(context))
        assertTrue(loadedAgain.privateKeySeed.all { it == 1.toByte() })
        assertTrue(loadedAgain.publicKey.all { it == 2.toByte() })
    }

    private fun manager(): SharingIdentityManager = SharingIdentityManager(
        store = InMemorySharingIdentityStore(),
        random = CountingRandom(),
        signer = FakeSigner(),
    )

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 31 + index) and 0xff).toByte() }
        }
    }

    private class FakeSigner : Ed25519Signer {
        private var counter = 1

        override fun generateKeyPair(): Ed25519KeyPair {
            val marker = counter++
            val seed = ByteArray(32) { index -> ((marker * 17 + index) and 0xff).toByte() }
            val public = ByteArray(32) { index -> ((marker * 29 + index) and 0xff).toByte() }
            return Ed25519KeyPair(
                Ed25519PrivateKey(SensitiveBytes(seed)),
                Ed25519PublicKey(public),
            )
        }

        override fun sign(privateKey: Ed25519PrivateKey, message: ByteArray): ByteArray =
            privateKey.material.copy().take(16).toByteArray() + message.take(16).toByteArray()

        override fun verify(publicKey: Ed25519PublicKey, message: ByteArray, signature: ByteArray): Boolean =
            signature.isNotEmpty() && publicKey.bytes.size == 32 && message.isNotEmpty()
    }
}
