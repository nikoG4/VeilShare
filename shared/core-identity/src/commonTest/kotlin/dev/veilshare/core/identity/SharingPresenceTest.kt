package dev.veilshare.core.identity

import dev.veilshare.core.model.RandomBytesSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SharingPresenceTest {
    @Test
    fun `getOrCreate is stable within one context`() = runTest {
        val manager = manager()
        val context = SharingContextId("context-a")

        val first = manager.getOrCreate(context)
        val second = manager.getOrCreate(context)

        assertEquals(first.referenceCode, second.referenceCode)
    }

    @Test
    fun `different contexts receive unrelated routing codes`() = runTest {
        val manager = manager()

        val a = manager.getOrCreate(SharingContextId("context-a"))
        val b = manager.getOrCreate(SharingContextId("context-b"))

        assertNotEquals(a.referenceCode, b.referenceCode)
    }

    @Test
    fun `rotation changes only routing token for same context`() = runTest {
        val manager = manager()
        val context = SharingContextId("context-a")

        val before = manager.getOrCreate(context)
        val after = manager.rotate(context)

        assertEquals(context, after.contextId)
        assertNotEquals(before.referenceCode, after.referenceCode)
        assertEquals(after, manager.getOrCreate(context))
    }

    @Test
    fun `delete followed by create produces a fresh routing token`() = runTest {
        val manager = manager()
        val context = SharingContextId("context-a")
        val before = manager.getOrCreate(context)

        manager.delete(context)
        val after = manager.getOrCreate(context)

        assertNotEquals(before.referenceCode, after.referenceCode)
    }

    private fun manager() = SharingPresenceManager(
        store = InMemorySharingPresenceStore(),
        random = CountingRandom(),
    )

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 29 + index) and 0xff).toByte() }
        }
    }
}
