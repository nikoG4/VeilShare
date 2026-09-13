package dev.veilshare.core.securestore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.fail

class AndroidSecureStateE2ETest {
    @Test
    fun `Keystore state roundtrip rejects swap and tamper without plaintext on disk`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val directoryName = "veilshare-secure-state-test-$suffix"
        val alias = "dev.veilshare.test.sharing.state.$suffix"
        val storage = AndroidDirectoryStateStorage(context, directoryName)
        val protector = AndroidKeystoreStateProtector(alias)
        val store = ProtectedStateStore(storage, protector)
        val a = SecureStateScope("sharing.identity.v1")
        val b = SecureStateScope("sharing.contacts.v1")
        val plaintext = "android-private-seed-marker".encodeToByteArray()

        try {
            store.write(a, plaintext)
            assertContentEquals(plaintext, store.read(a))

            val raw = assertNotNull(storage.read("sharing.identity.v1.vss"))
            assertFalse(raw.containsSubsequence(plaintext))

            storage.replaceAtomic("sharing.contacts.v1.vss", raw)
            expectFailure { store.read(b) }

            val tampered = raw.copyOf()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
            storage.replaceAtomic("sharing.identity.v1.vss", tampered)
            expectFailure { store.read(a) }
        } finally {
            plaintext.fill(0)
            File(context.noBackupFilesDir, directoryName).deleteRecursively()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected protected state operation to fail closed")
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
