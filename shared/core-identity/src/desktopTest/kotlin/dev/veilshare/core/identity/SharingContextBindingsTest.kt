package dev.veilshare.core.identity

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.securestore.AeadStateProtector
import dev.veilshare.core.securestore.InMemoryAtomicStateStorage
import dev.veilshare.core.securestore.ProtectedStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SharingContextBindingsTest {
    @Test
    fun `different vault personas keep independent random sharing contexts across restart`() = runTest {
        val storage = InMemoryAtomicStateStorage()
        val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
        val random = CountingRandom(11)
        val personaA = LocalPersonaId("a".repeat(64))
        val personaB = LocalPersonaId("b".repeat(64))

        val firstProtector = AeadStateProtector(
            DesktopProductionCrypto.create().cipher,
            SensitiveBytes(key.copyOf()),
        )
        val firstStore = ProtectedStateStore(storage, firstProtector)
        val firstManager = SharingContextBindingManager(
            PersistentSharingContextBindingStore(firstStore),
            random,
        )

        val contextA = firstManager.getOrCreate(personaA)
        val contextAAgain = firstManager.getOrCreate(personaA)
        val contextB = firstManager.getOrCreate(personaB)
        assertEquals(contextA, contextAAgain)
        assertNotEquals(contextA, contextB)

        val protectedBytes = requireNotNull(storage.snapshot("sharing.context-bindings.v1.vss"))
        assertFalse(protectedBytes.containsSequence(personaA.value.encodeToByteArray()))
        assertFalse(protectedBytes.containsSequence(personaB.value.encodeToByteArray()))
        assertFalse(protectedBytes.containsSequence(contextA.value.encodeToByteArray()))
        assertFalse(protectedBytes.containsSequence(contextB.value.encodeToByteArray()))
        protectedBytes.fill(0)
        firstProtector.close()

        val secondProtector = AeadStateProtector(
            DesktopProductionCrypto.create().cipher,
            SensitiveBytes(key.copyOf()),
        )
        val secondManager = SharingContextBindingManager(
            PersistentSharingContextBindingStore(ProtectedStateStore(storage, secondProtector)),
            CountingRandom(90),
        )
        assertEquals(contextA, secondManager.getOrCreate(personaA))
        assertEquals(contextB, secondManager.getOrCreate(personaB))

        secondManager.delete(personaA)
        assertNull(secondManager.existing(personaA))
        assertEquals(contextB, secondManager.existing(personaB))
        secondProtector.close()
        key.fill(0)
    }
}

private class CountingRandom(seed: Int) : RandomBytesSource {
    private var next = seed
    override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (next++ and 0xff).toByte() }
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    for (start in 0..size - needle.size) {
        var match = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                match = false
                break
            }
        }
        if (match) return true
    }
    return false
}
