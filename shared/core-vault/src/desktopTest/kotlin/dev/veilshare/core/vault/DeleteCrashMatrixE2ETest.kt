package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.Argon2Policy
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.KeyEncryptionKey
import dev.veilshare.core.crypto.KeyWrapper
import dev.veilshare.core.crypto.ProductionCryptoComponents
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.SensitiveChars
import dev.veilshare.core.crypto.VaultKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Persistent delete crash matrix. Every scenario reopens twice from filesystem state. */
class DeleteCrashMatrixE2ETest {
    @Test fun `D0 no durable intent is a recovery no-op`() = runTest {
        val f = MatrixFixture.create()
        f.initial.close()
        f.assertPreservedAcrossRestarts()
    }

    @Test fun `D1 durable intent cannot override referenced authenticated catalog`() = runTest {
        val f = MatrixFixture.create()
        f.failAt(DeleteFaultPoint.IntentDurable)
        f.assertPreservedAcrossRestarts(expectJournalBeforeRecovery = true)
    }

    @Test fun `D2 failure before temp write leaves old authenticated catalog`() = runTest {
        val f = MatrixFixture.create()
        f.failCatalogAt(CatalogFaultPoint.BeforeTempWrite)
        f.assertPreservedAcrossRestarts(expectJournalBeforeRecovery = true)
    }

    @Test fun `D2 real partial catalog writes preserve old authoritative generation`() = runTest {
        val cuts: List<(Int) -> Int> = listOf(
            { 1 },
            { size -> size / 2 },
            { size -> (size - 2).coerceAtLeast(1) },
            { size -> (size - 1).coerceAtLeast(1) },
        )
        cuts.forEach { cut ->
            val f = MatrixFixture.create()
            f.failCatalogWithPersistence(PartialCatalogPersistence(cut))
            f.assertPreservedAcrossRestarts(expectJournalBeforeRecovery = true)
            assertEquals(0, f.catalogTemps().size)
        }
    }

    @Test fun `force failure before replace preserves old authenticated generation`() = runTest {
        val f = MatrixFixture.create()
        f.failCatalogWithPersistence(ForceFailureCatalogPersistence)
        f.assertPreservedAcrossRestarts(expectJournalBeforeRecovery = true)
    }

    @Test fun `D3 complete temp before replace is not authoritative`() = runTest {
        val f = MatrixFixture.create()
        f.failCatalogAt(CatalogFaultPoint.TempWrittenBeforeReplace)
        f.assertPreservedAcrossRestarts(expectJournalBeforeRecovery = true)
        assertEquals(0, f.catalogTemps().size, "the production finally block conservatively removes its temp")
    }

    @Test fun `D4 durable delete catalog is the logical point of no return`() = runTest {
        val f = MatrixFixture.create()
        f.failAt(DeleteFaultPoint.CatalogDurable)
        assertTrue(f.physicalBlobExists(f.a.blobId), "fault is before physical deletion")
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = true)
    }

    @Test fun `D5 physical delete failure converges after restart`() = runTest {
        val f = MatrixFixture.create()
        val faulting = FaultingBlobStore(f.initial.blobs, failOnDelete = true)
        val useCase = DeleteFileUseCase(
            f.initial.crypto.random,
            f.initial.catalogStore,
            f.initial.journal,
            VaultGarbageCollector(faulting, f.initial.journal),
        )
        assertFailsWith<IllegalStateException> {
            useCase.delete(f.initial.session, f.initial.snapshot, f.a.id)
        }
        assertTrue(f.physicalBlobExists(f.a.blobId), "delete threw before removing physical bytes")
        f.initial.close()
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = true)
    }

    @Test fun `D6 deleted blob with stale journal never rolls back`() = runTest {
        val f = MatrixFixture.create()
        f.failAt(DeleteFaultPoint.PhysicalDeleteComplete)
        assertFalse(f.physicalBlobExists(f.a.blobId))
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = false)
    }

    @Test fun `D7 real journal cleanup failure converges after restart`() = runTest {
        val f = MatrixFixture.create()
        val faultingJournal = FaultingJournalStore(f.initial.journal, failRemoveBeforeDelegate = true)
        val useCase = DeleteFileUseCase(
            f.initial.crypto.random,
            f.initial.catalogStore,
            faultingJournal,
            VaultGarbageCollector(f.initial.blobs, faultingJournal),
        )
        assertFailsWith<IllegalStateException> {
            useCase.delete(f.initial.session, f.initial.snapshot, f.a.id)
        }
        assertFalse(f.physicalBlobExists(f.a.blobId))
        f.initial.close()
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = false)
    }

    @Test fun `D8 exception after replace is resolved from authenticated durable catalog`() = runTest {
        val f = MatrixFixture.create()
        f.failCatalogAt(CatalogFaultPoint.ReplaceComplete)
        assertTrue(f.physicalBlobExists(f.a.blobId), "caller failed before delete cleanup")
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = true)
    }

    @Test fun `D4 REAL recovery cannot alter DECOY namespace`() = runTest {
        val f = MatrixFixture.create()
        val decoySource = Files.createTempFile("matrix-decoy-", ".bin")
        val decoyPlain = ByteArray(73 * 1024 + 3) { ((it * 13 + 4) and 0xff).toByte() }
        Files.write(decoySource, decoyPlain)
        val decoy = MatrixFixture.reopenRuntime(f.root, f.policy, "222222")
        val (_, decoyFile) = ImportCoordinator(
            decoy.crypto.random,
            decoy.crypto.cipher,
            decoy.blobs,
            decoy.catalogStore,
            decoy.journal,
            FileKeyWrapping(DesktopProductionCrypto.keyDeriver(), decoy.crypto.cipher),
        ).import(decoy.session, decoy.snapshot, DesktopImportSource(decoySource))
        val decoyPhysical = decoy.blobBytes(decoyFile.blobId)
        val decoyNamespace = decoy.session.descriptor.blobNamespace
        assertFalse(decoyNamespace == f.initial.session.descriptor.blobNamespace)
        decoy.close()

        f.failAt(DeleteFaultPoint.CatalogDurable)
        f.assertDeletedAcrossTwoRecoveries(expectJournalBeforeRecovery = true, expectBlobBeforeFirstRecovery = true)

        repeat(2) {
            val reopenedDecoy = MatrixFixture.reopenRuntime(f.root, f.policy, "222222")
            assertEquals(decoyNamespace, reopenedDecoy.session.descriptor.blobNamespace)
            val durableEntry = reopenedDecoy.snapshot.entries.filterIsInstance<VaultItem.File>().single { it.id == decoyFile.id }
            assertContentEquals(decoyPhysical, reopenedDecoy.blobBytes(decoyFile.blobId))
            reopenedDecoy.assertPlain(durableEntry, decoyPlain)
            reopenedDecoy.recover()
            assertContentEquals(decoyPhysical, reopenedDecoy.blobBytes(decoyFile.blobId))
            reopenedDecoy.close()
        }
    }
}

private class MatrixFixture(
    val root: Path,
    val policy: Argon2Policy,
    val initial: MatrixRuntime,
    val a: VaultItem.File,
    val b: VaultItem.File,
    private val aPlain: ByteArray,
    private val bPlain: ByteArray,
    private val aPhysical: ByteArray,
    private val bPhysical: ByteArray,
) {
    companion object {
        suspend fun create(): MatrixFixture {
            val root = Files.createTempDirectory("veil-delete-matrix-")
            val aSource = Files.createTempFile("matrix-a-", ".bin")
            val bSource = Files.createTempFile("matrix-b-", ".bin")
            val aPlain = ByteArray(96 * 1024) { ((it * 31) and 0xff).toByte() }
            val bPlain = ByteArray(128 * 1024 + 17) { ((it * 17 + 9) and 0xff).toByte() }
            Files.write(aSource, aPlain)
            Files.write(bSource, bPlain)
            val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
            val runtime = createVaultRuntime(root, policy)
            val coordinator = ImportCoordinator(
                runtime.crypto.random,
                runtime.crypto.cipher,
                runtime.blobs,
                runtime.catalogStore,
                runtime.journal,
                FileKeyWrapping(DesktopProductionCrypto.keyDeriver(), runtime.crypto.cipher),
            )
            val (afterA, a) = coordinator.import(runtime.session, runtime.snapshot, DesktopImportSource(aSource))
            val (afterB, b) = coordinator.import(runtime.session, afterA, DesktopImportSource(bSource))
            runtime.snapshot = afterB
            return MatrixFixture(
                root,
                policy,
                runtime,
                a,
                b,
                aPlain,
                bPlain,
                runtime.blobBytes(a.blobId),
                runtime.blobBytes(b.blobId),
            )
        }

        private suspend fun createVaultRuntime(root: Path, policy: Argon2Policy): MatrixRuntime {
            val crypto = DesktopProductionCrypto.create()
            val wrapper = MatrixKeyWrapper(crypto.cipher)
            CreateVaultSetUseCase(
                DesktopVaultSlotStore(root),
                crypto.random,
                crypto.passwordKdf,
                wrapper,
                crypto.cipher,
                policy,
                DesktopVaultCatalogBootstrap(root, DesktopProductionCrypto.keyDeriver(), crypto.cipher),
            ).create(SensitiveChars("111111".toCharArray()), SensitiveChars("222222".toCharArray()))
            return reopenRuntime(root, policy)
        }

        suspend fun reopenRuntime(root: Path, policy: Argon2Policy, pin: String = "111111"): MatrixRuntime {
            val crypto = DesktopProductionCrypto.create()
            val wrapper = MatrixKeyWrapper(crypto.cipher)
            val opened = assertIs<DesktopOpenResult.Ready>(
                DesktopVaultRepository(
                    root,
                    UnlockVaultUseCase(DesktopVaultSlotStore(root), crypto.passwordKdf, wrapper, crypto.cipher, policy),
                    DesktopProductionCrypto.keyDeriver(),
                    crypto.cipher,
                ).open(SensitiveChars(pin.toCharArray())),
            )
            val blobs = DesktopBlobStore(root.resolve("blobs"), opened.session.descriptor.blobNamespace)
            return MatrixRuntime(
                crypto,
                opened.session,
                opened.catalog,
                DesktopEncryptedCatalogStore(
                    root,
                    opened.session.descriptor.vaultId,
                    CatalogCrypto(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
                ),
                DesktopVaultJournal(root),
                blobs,
            )
        }
    }

    suspend fun failAt(point: DeleteFaultPoint) {
        assertFailsWith<IllegalStateException> {
            DeleteFileUseCase(
                initial.crypto.random,
                initial.catalogStore,
                initial.journal,
                VaultGarbageCollector(initial.blobs, initial.journal),
            ) { reached -> if (reached == point) throw IllegalStateException("injected $point") }
                .delete(initial.session, initial.snapshot, a.id)
        }
        initial.close()
    }

    suspend fun failCatalogAt(point: CatalogFaultPoint) {
        val faultingCatalog = DesktopEncryptedCatalogStore(
            root,
            initial.session.descriptor.vaultId,
            CatalogCrypto(DesktopProductionCrypto.keyDeriver(), initial.crypto.cipher),
        ) { reached -> if (reached == point) throw IllegalStateException("injected $point") }
        assertFailsWith<IllegalStateException> {
            DeleteFileUseCase(
                initial.crypto.random,
                faultingCatalog,
                initial.journal,
                VaultGarbageCollector(initial.blobs, initial.journal),
            ).delete(initial.session, initial.snapshot, a.id)
        }
        initial.close()
    }

    suspend fun failCatalogWithPersistence(persistence: CatalogPersistenceOps) {
        val faultingCatalog = DesktopEncryptedCatalogStore(
            root,
            initial.session.descriptor.vaultId,
            CatalogCrypto(DesktopProductionCrypto.keyDeriver(), initial.crypto.cipher),
            persistence = persistence,
        )
        assertFailsWith<IOException> {
            DeleteFileUseCase(
                initial.crypto.random,
                faultingCatalog,
                initial.journal,
                VaultGarbageCollector(initial.blobs, initial.journal),
            ).delete(initial.session, initial.snapshot, a.id)
        }
        initial.close()
    }

    suspend fun assertPreservedAcrossRestarts(expectJournalBeforeRecovery: Boolean = false) {
        repeat(3) { pass ->
            val runtime = reopenRuntime(root, policy)
            if (pass == 0 && expectJournalBeforeRecovery) assertTrue(runtime.journal.entries().isNotEmpty())
            assertTrue(runtime.snapshot.entries.any { it.id == a.id })
            assertContentEquals(aPhysical, runtime.blobBytes(a.blobId))
            runtime.recover()
            assertTrue(runtime.snapshot.entries.any { it.id == a.id })
            assertContentEquals(aPhysical, runtime.blobBytes(a.blobId))
            runtime.assertPlain(a, aPlain)
            assertB(runtime)
            assertTrue(runtime.journal.entries().isEmpty())
            runtime.close()
        }
    }

    suspend fun assertDeletedAcrossTwoRecoveries(
        expectJournalBeforeRecovery: Boolean = false,
        expectBlobBeforeFirstRecovery: Boolean? = null,
    ) {
        repeat(3) { pass ->
            val runtime = reopenRuntime(root, policy)
            if (pass == 0 && expectJournalBeforeRecovery) assertTrue(runtime.journal.entries().isNotEmpty())
            assertFalse(runtime.snapshot.entries.any { it.id == a.id })
            if (pass == 0 && expectBlobBeforeFirstRecovery != null) {
                assertEquals(expectBlobBeforeFirstRecovery, runtime.blobs.exists(a.blobId))
                if (expectBlobBeforeFirstRecovery) assertContentEquals(aPhysical, runtime.blobBytes(a.blobId))
            }
            runtime.recover()
            assertFalse(runtime.snapshot.entries.any { it.id == a.id })
            assertFalse(runtime.blobs.exists(a.blobId))
            assertB(runtime)
            assertTrue(runtime.journal.entries().isEmpty())
            runtime.close()
        }
    }

    suspend fun physicalBlobExists(id: dev.veilshare.core.model.BlobId): Boolean = initial.blobs.exists(id)

    fun catalogTemps(): List<Path> = Files.walk(root).use { stream ->
        stream.filter { it.fileName.toString().startsWith("catalog-") && it.fileName.toString().endsWith(".tmp") }
            .toList()
    }

    private suspend fun assertB(runtime: MatrixRuntime) {
        assertTrue(runtime.snapshot.entries.any { it.id == b.id })
        assertContentEquals(bPhysical, runtime.blobBytes(b.blobId))
        runtime.assertPlain(b, bPlain)
        assertEquals(initial.session.descriptor.blobNamespace, runtime.session.descriptor.blobNamespace)
    }
}

private class MatrixRuntime(
    val crypto: ProductionCryptoComponents,
    val session: VaultSession,
    var snapshot: CatalogSnapshot,
    val catalogStore: DesktopEncryptedCatalogStore,
    val journal: DesktopVaultJournal,
    val blobs: DesktopBlobStore,
) {
    suspend fun recover() {
        val state = AuthenticatedVaultState(
            session.descriptor.vaultId,
            session.descriptor.blobNamespace,
            snapshot.entries.filterIsInstance<VaultItem.File>().map { it.blobId }.toSet(),
        )
        VaultRecoveryCoordinator(journal, VaultGarbageCollector(blobs, journal)).reconcileAuthenticated(state)
    }

    suspend fun blobBytes(id: dev.veilshare.core.model.BlobId): ByteArray {
        val handle = blobs.open(id)
        return try {
            require(handle.size <= Int.MAX_VALUE)
            handle.readAt(0, handle.size.toInt())
        } finally {
            handle.close()
        }
    }

    suspend fun assertPlain(file: VaultItem.File, expected: ByteArray) {
        val key = FileKeyWrapping(DesktopProductionCrypto.keyDeriver(), crypto.cipher).unwrap(
            session.key(), assertNotNull(file.wrappedFileKey), session.descriptor.vaultId, file.id, file.blobId,
        )
        val handle = blobs.open(file.blobId)
        val chunks = mutableListOf<ByteArray>()
        try {
            EncryptedBlobReader(crypto.cipher).read(handle, key, file.id.value.encodeToByteArray()) { chunks += it }
        } finally {
            handle.close()
            key.material.close()
        }
        val actual = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk -> chunk.copyInto(actual, offset); offset += chunk.size }
        assertContentEquals(expected, actual)
    }

    fun close() = session.close()
}

private class MatrixKeyWrapper(private val cipher: AuthenticatedCipher) : KeyWrapper {
    override suspend fun wrap(kek: KeyEncryptionKey, vaultKey: VaultKey, aad: ByteArray): SealedBytes =
        cipher.seal(kek.material, vaultKey.material.copy(), aad)

    override suspend fun unwrap(kek: KeyEncryptionKey, wrapped: SealedBytes, aad: ByteArray): VaultKey =
        VaultKey(SensitiveBytes(cipher.open(kek.material, wrapped, aad)))
}

private class PartialCatalogPersistence(private val cut: (Int) -> Int) : CatalogPersistenceOps {
    override fun writeAndForce(temp: Path, bytes: ByteArray) {
        val length = cut(bytes.size).coerceIn(1, bytes.size - 1)
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val partial = ByteBuffer.wrap(bytes, 0, length)
            while (partial.hasRemaining()) channel.write(partial)
        }
        throw IOException("injected partial catalog write at $length/${bytes.size}")
    }

    override fun replace(temp: Path, target: Path): Nothing =
        error("replace must not run after a partial write")
}

private object ForceFailureCatalogPersistence : CatalogPersistenceOps {
    override fun writeAndForce(temp: Path, bytes: ByteArray) {
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val source=ByteBuffer.wrap(bytes)
            while(source.hasRemaining()) channel.write(source)
        }
        throw IOException("injected force failure")
    }
    override fun replace(temp: Path, target: Path): Nothing = error("replace must not run after force failure")
}
