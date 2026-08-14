package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/** Opaque-ID filesystem store. All writes are temp files followed by an atomic move when supported. */
/** Namespace is an opaque authenticated descriptor value, never a REAL/DECOY label. */
class DesktopBlobStore(root: Path, namespace: String) : BlobStore {
    private val root: Path = root.resolve(namespace)
    init { Files.createDirectories(this.root) }
    override suspend fun create(): BlobWriteHandle { val temp = Files.createTempFile(root, ".writing-", ".tmp"); return Writer(temp) }
    override suspend fun open(id: BlobId): BlobReadHandle { val path = pathFor(id); require(Files.isRegularFile(path)); return Reader(path) }
    override suspend fun exists(id: BlobId) = Files.isRegularFile(pathFor(id))
    override suspend fun delete(id: BlobId) { Files.deleteIfExists(pathFor(id)) }
    override suspend fun size(id: BlobId) = Files.size(pathFor(id))
    override suspend fun listIds() = Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".vblob") }.map { BlobId(it.fileName.toString().removeSuffix(".vblob")) }.toList().toSet() }
    private fun pathFor(id: BlobId) = root.resolve("${id.value}.vblob")
    private inner class Writer(private val temp: Path) : BlobWriteHandle { private var closed=false
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) { check(!closed); Files.newOutputStream(temp, StandardOpenOption.APPEND).use { it.write(bytes,offset,length) } }
        override suspend fun commit(): BlobId { check(!closed); val id=BlobId(ByteArray(16).also(SecureRandom()::nextBytes).joinToString(""){it.toUByte().toString(16).padStart(2,'0')}); DesktopDurableFiles.force(temp); DesktopDurableFiles.promoteAtomic(temp,pathFor(id)); closed=true; return id }
        override suspend fun abort() { if(!closed) Files.deleteIfExists(temp); closed=true }
    }
    private class Reader(private val path: Path) : BlobReadHandle { override val size get() = Files.size(path)
        override suspend fun readAt(offset: Long, maxBytes: Int): ByteArray { require(offset >= 0 && maxBytes >= 0); RandomAccessFile(path.toFile(),"r").use { it.seek(offset); val bounded=(size-offset).coerceIn(0L,maxBytes.toLong()).toInt(); val b=ByteArray(bounded); val n=it.read(b); return if(n < 0) ByteArray(0) else b.copyOf(n) } }
        override suspend fun close() = Unit
    }
}
