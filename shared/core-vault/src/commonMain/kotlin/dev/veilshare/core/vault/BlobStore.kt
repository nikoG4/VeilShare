package dev.veilshare.core.vault
import dev.veilshare.core.model.BlobId
interface BlobWriteHandle { suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size); suspend fun commit(): BlobId; suspend fun abort() }
interface BlobReadHandle { val size: Long; suspend fun readAt(offset: Long, maxBytes: Int): ByteArray; suspend fun close() }
interface BlobStore { suspend fun create(): BlobWriteHandle; suspend fun open(id: BlobId): BlobReadHandle; suspend fun exists(id: BlobId): Boolean; suspend fun delete(id: BlobId); suspend fun size(id: BlobId): Long; suspend fun listIds(): Set<BlobId> }
