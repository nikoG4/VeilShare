package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.FileKey
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveChars

enum class LocalStorageState { EMPTY, READY, INCOMPLETE, CORRUPT }

sealed interface LocalUnlockResult {
    data class Ready(val vault: VaultHandle) : LocalUnlockResult
    data object InvalidCredential : LocalUnlockResult
    data object Corrupt : LocalUnlockResult
}

interface LocalVaultService {
    suspend fun storageState(): LocalStorageState
    suspend fun createPair(primary: CharArray, alternate: CharArray)
    suspend fun unlock(credential: CharArray): LocalUnlockResult
}

interface VaultHandle : AutoCloseable {
    val isOpen: Boolean
    suspend fun recover()
    suspend fun reload()
    fun items(parent: VaultDirectoryId? = null): List<VaultItem>
    fun find(id: VaultItemId): VaultItem?
    suspend fun createDirectory(parent: VaultDirectoryId?, name: String): VaultItem.Directory
    suspend fun rename(id: VaultItemId, name: String)
    suspend fun import(source: ImportSource, parent: VaultDirectoryId? = null, progress: suspend (ImportProgress) -> Unit = {}): VaultItem.File
    suspend fun deleteFile(id: VaultItemId)
    suspend fun deleteEmptyDirectory(id: VaultItemId)
    suspend fun readFile(id: VaultItemId, consume: suspend (ByteArray) -> Unit): Long
    suspend fun changeCredential(newCredential: CharArray)
}

/**
 * Owns the authenticated session and is the only UI-facing object allowed to
 * orchestrate operations requiring the VMK. Plain keys never leave this class.
 */
class ActiveVault internal constructor(
    private val session: VaultSession,
    initialCatalog: CatalogSnapshot,
    private val random: SecureRandom,
    private val cipher: AuthenticatedCipher,
    private val deriver: KeyDeriver,
    private val catalogStore: EncryptedCatalogStore,
    private val blobStore: BlobStore,
    private val journal: VaultJournal,
    private val credentialChange: ChangeCredentialUseCase,
) : VaultHandle {
    private var snapshot = initialCatalog
    private val wrapping = FileKeyWrapping(deriver, cipher)

    override val isOpen: Boolean get() = session.isOpen

    override suspend fun recover() {
        requireOpen()
        // Authenticate the durable catalog before deriving any destructive GC authority.
        snapshot = catalogStore.load(session.key())
        VaultRecoveryCoordinator(journal, VaultGarbageCollector(blobStore, journal))
            .reconcileAuthenticated(authenticatedState())
        snapshot = catalogStore.load(session.key())
    }

    override suspend fun reload() {
        requireOpen()
        snapshot = catalogStore.load(session.key())
    }

    override fun items(parent: VaultDirectoryId?): List<VaultItem> {
        requireOpen()
        return snapshot.entries.filter { it.parentId == parent }
            .sortedWith(compareBy<VaultItem> { it !is VaultItem.Directory }.thenBy { it.displayName.lowercase() })
    }

    override fun find(id: VaultItemId): VaultItem? {
        requireOpen()
        return snapshot.entries.firstOrNull { it.id == id }
    }

    override suspend fun createDirectory(parent: VaultDirectoryId?, name: String): VaultItem.Directory {
        requireOpen()
        val id = VaultItemId(random.bytes(16).hex())
        val directory = VaultItem.Directory(id, parent, name.trim())
        persist(snapshot.copy(entries = snapshot.entries + directory))
        return directory
    }

    override suspend fun rename(id: VaultItemId, name: String) {
        requireOpen()
        val trimmed = name.trim()
        val next = snapshot.entries.map { item ->
            if (item.id != id) item else when (item) {
                is VaultItem.Directory -> item.copy(displayName = trimmed)
                is VaultItem.File -> item.copy(displayName = trimmed)
            }
        }
        if (next == snapshot.entries) throw VaultFormatException("Item not found")
        persist(snapshot.copy(entries = next))
    }

    override suspend fun import(
        source: ImportSource,
        parent: VaultDirectoryId?,
        progress: suspend (ImportProgress) -> Unit,
    ): VaultItem.File {
        requireOpen()
        val coordinator = ImportCoordinator(random, cipher, blobStore, catalogStore, journal, wrapping)
        val (next, item) = coordinator.import(session, snapshot, source, parent, progress)
        snapshot = next
        return item
    }

    override suspend fun deleteFile(id: VaultItemId) {
        requireOpen()
        snapshot = DeleteFileUseCase(
            random, catalogStore, journal, VaultGarbageCollector(blobStore, journal),
        ).delete(session, snapshot, id)
    }

    override suspend fun deleteEmptyDirectory(id: VaultItemId) {
        requireOpen()
        val target = snapshot.entries.firstOrNull { it.id == id }
        if (target !is VaultItem.Directory) throw VaultFormatException("Directory not found")
        if (snapshot.entries.any { it.parentId?.value == id.value }) throw VaultFormatException("Directory is not empty")
        persist(snapshot.copy(entries = snapshot.entries.filterNot { it.id == id }))
    }

    override suspend fun readFile(id: VaultItemId, consume: suspend (ByteArray) -> Unit): Long {
        requireOpen()
        val file = snapshot.entries.filterIsInstance<VaultItem.File>().firstOrNull { it.id == id }
            ?: throw VaultFormatException("File not found")
        val wrapped = file.wrappedFileKey ?: throw VaultFormatException("Missing wrapped file key")
        val key: FileKey = wrapping.unwrap(session.key(), wrapped, session.descriptor.vaultId, file.id, file.blobId)
        val source = blobStore.open(file.blobId)
        return try {
            EncryptedBlobReader(cipher).read(source, key, file.id.value.encodeToByteArray(), consume)
        } finally {
            source.close()
            key.material.close()
        }
    }

    override suspend fun changeCredential(newCredential: CharArray) {
        requireOpen()
        val sensitive = SensitiveChars(newCredential)
        try { credentialChange.change(session, sensitive) } finally { sensitive.close() }
    }

    override fun close() = session.close()

    private suspend fun persist(next: CatalogSnapshot) {
        // Reuse the canonical validator before making the encrypted catalog authoritative.
        CatalogCodec().encode(next)
        catalogStore.replaceAtomically(session.key(), next)
        snapshot = next
    }

    private fun authenticatedState() = AuthenticatedVaultState(
        session.descriptor.vaultId,
        session.descriptor.blobNamespace,
        snapshot.entries.filterIsInstance<VaultItem.File>().map { it.blobId }.toSet(),
    )

    private fun requireOpen() = check(session.isOpen) { "Vault is locked" }
}

private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
