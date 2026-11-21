package dev.veilshare.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

/** Adversarial tests for the authenticated transport; never touches the installed vault root. */
class AndroidVaultBackupCodecTest {
    private fun workspace(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "backup-test-${System.nanoTime()}").also { assertTrue(it.mkdirs()) }
    }

    @Test fun roundTripPreservesBothSlotsAndNestedEncryptedBytes() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            val expected = mapOf(
                "real/slot.bin" to ByteArray(4096) { it.toByte() },
                "decoy/slot.bin" to ByteArray(3072) { (it * 7).toByte() },
                "journal.bin" to byteArrayOf(0, 1, 2, -1)
            )
            expected.forEach { (path, bytes) ->
                File(root, path).also { it.parentFile!!.mkdirs() }.writeBytes(bytes)
            }
            val key = AndroidVaultBackupCodec.newRecoveryKey()
            assertEquals(32, key.size)
            val output = ByteArrayOutputStream()
            AndroidVaultBackupCodec.export(root, output, key)
            val stage = File(work, "stage").also { assertTrue(it.mkdirs()) }
            AndroidVaultBackupCodec.importToStage(ByteArrayInputStream(output.toByteArray()), stage, key)
            expected.forEach { (path, bytes) -> assertContentEquals(bytes, File(stage, path).readBytes()) }
            assertFalse(File(stage, ".veilshare-backup-end").exists())
        } finally { work.deleteRecursively() }
    }

    @Test fun wrongKeyTruncationAndTamperingNeverExtractFiles() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            File(root, "real.bin").writeBytes(ByteArray(1024) { it.toByte() })
            val key = AndroidVaultBackupCodec.newRecoveryKey()
            val output = ByteArrayOutputStream()
            AndroidVaultBackupCodec.export(root, output, key)
            val good = output.toByteArray()
            val tampered = good.clone().also { it[it.lastIndex - 20] = (it[it.lastIndex - 20].toInt() xor 1).toByte() }
            val cases = listOf(
                good to AndroidVaultBackupCodec.newRecoveryKey(),
                good.copyOf(good.size - 8) to key,
                tampered to key
            )
            cases.forEachIndexed { index, (bytes, candidateKey) ->
                val stage = File(work, "stage-$index").also { assertTrue(it.mkdirs()) }
                assertFails { AndroidVaultBackupCodec.importToStage(ByteArrayInputStream(bytes), stage, candidateKey) }
                assertTrue(stage.listFiles().isNullOrEmpty(), "Unauthenticated data was extracted")
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun exportRejectsSymbolicLinksWithoutWritingHeader() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            val outside = File(work, "outside").also { it.writeText("secret-outside-root") }
            File(root, "safe.bin").writeBytes(byteArrayOf(1, 2, 3))
            val linkedFile = File(root, "link")
            java.nio.file.Files.createSymbolicLink(linkedFile.toPath(), outside.toPath())

            val key = AndroidVaultBackupCodec.newRecoveryKey()
            val output = ByteArrayOutputStream()
            assertFails { AndroidVaultBackupCodec.export(root, output, key) }
            assertEquals(0, output.size(), "Symlink wrote a misleading backup header")
            assertEquals("secret-outside-root", outside.readText())
            assertTrue(linkedFile.delete())

            val externalDirectory = File(work, "external").also { assertTrue(it.mkdirs()) }
            File(externalDirectory, "hidden.bin").writeText("hidden")
            java.nio.file.Files.createSymbolicLink(File(root, "linked-directory").toPath(), externalDirectory.toPath())
            val second = ByteArrayOutputStream()
            assertFails { AndroidVaultBackupCodec.export(root, second, key) }
            assertEquals(0, second.size(), "Directory symlink wrote a backup header")
            assertEquals("hidden", File(externalDirectory, "hidden.bin").readText())
        } finally { work.deleteRecursively() }
    }

    @Test fun importRejectsSymbolicLinkStageWithoutTouchingItsTarget() {
        val work = workspace()
        try {
            val realStage = File(work, "outside-stage").also { assertTrue(it.mkdirs()) }
            val linkedStage = File(work, "stage-link")
            java.nio.file.Files.createSymbolicLink(linkedStage.toPath(), realStage.toPath())
            assertFails {
                AndroidVaultBackupCodec.importToStage(
                    ByteArrayInputStream(byteArrayOf()),
                    linkedStage,
                    AndroidVaultBackupCodec.newRecoveryKey()
                )
            }
            assertTrue(realStage.listFiles().isNullOrEmpty())
        } finally { work.deleteRecursively() }
    }
    @Test fun importRejectsNonemptyStageWithoutChangingExistingFiles() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            File(root, "real.bin").writeText("encrypted-source")
            val key = AndroidVaultBackupCodec.newRecoveryKey()
            val output = ByteArrayOutputStream()
            AndroidVaultBackupCodec.export(root, output, key)
            val stage = File(work, "stage").also { assertTrue(it.mkdirs()) }
            val existing = File(stage, "keep.bin").also { it.writeText("do-not-touch") }
            assertFails {
                AndroidVaultBackupCodec.importToStage(ByteArrayInputStream(output.toByteArray()), stage, key)
            }
            assertEquals("do-not-touch", existing.readText())
            assertEquals(listOf("keep.bin"), stage.listFiles()!!.map { it.name })
        } finally { work.deleteRecursively() }
    }

    @Test fun modifiedHeaderAndAppendedCiphertextAreRejectedBeforeExtraction() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            File(root, "decoy.bin").writeBytes(byteArrayOf(4, 5, 6))
            val key = AndroidVaultBackupCodec.newRecoveryKey()
            val output = ByteArrayOutputStream()
            AndroidVaultBackupCodec.export(root, output, key)
            val good = output.toByteArray()
            val alteredVersion = good.clone().also { it[8] = 2 }
            val alteredNonce = good.clone().also { it[9] = (it[9].toInt() xor 1).toByte() }
            val appended = good + byteArrayOf(1, 2, 3, 4)
            listOf(alteredVersion, alteredNonce, appended).forEachIndexed { index, bytes ->
                val stage = File(work, "stage-$index").also { assertTrue(it.mkdirs()) }
                assertFails {
                    AndroidVaultBackupCodec.importToStage(ByteArrayInputStream(bytes), stage, key)
                }
                assertTrue(stage.listFiles().isNullOrEmpty())
            }
        } finally { work.deleteRecursively() }
    }

    @Test fun invalidRecoveryKeyDoesNotWriteBackupHeader() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            File(root, "real.bin").writeBytes(byteArrayOf(1, 2, 3))
            listOf(ByteArray(0), ByteArray(16), ByteArray(31), ByteArray(33)).forEach { invalidKey ->
                val output = ByteArrayOutputStream()
                assertFails {
                    AndroidVaultBackupCodec.export(root, output, invalidKey)
                }
                assertEquals(0, output.size(), "Invalid recovery key wrote a partial backup")
            }
        } finally { work.deleteRecursively() }
    }


    @Test fun invalidEntryNamesDoNotWriteBackupHeader() {
        val work = workspace()
        try {
            val root = File(work, "source").also { assertTrue(it.mkdirs()) }
            val key = AndroidVaultBackupCodec.newRecoveryKey()
            val reserved = File(root, ".veilshare-backup-end")
            reserved.writeBytes(byteArrayOf(1))
            val first = ByteArrayOutputStream()
            assertFails { AndroidVaultBackupCodec.export(root, first, key) }
            assertEquals(0, first.size(), "Reserved marker wrote a backup header")
            assertTrue(reserved.delete())

            // This name is legal on common Android filesystems but exceeds the
            // backup format's UTF-8 path limit when nested.
            var directory = root
            repeat(4) { index ->
                directory = File(directory, "segment-${index}-" + "x".repeat(120))
                assertTrue(directory.mkdirs())
            }
            File(directory, "encrypted.bin").writeBytes(byteArrayOf(2))
            val second = ByteArrayOutputStream()
            assertFails { AndroidVaultBackupCodec.export(root, second, key) }
            assertEquals(0, second.size(), "Overlong path wrote a backup header")
        } finally { work.deleteRecursively() }
    }

}
