package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom

class AndroidBlobStore(root: File, namespace: String, private val persistence: AndroidPersistenceOps = AndroidDurableFiles) : BlobStore {
    private val root = File(root, namespace)
    init { require(namespace.matches(Regex("[0-9a-f]{32}"))); check(this.root.mkdirs() || this.root.isDirectory) }
    override suspend fun create(): BlobWriteHandle = Writer(File.createTempFile(".writing-", ".tmp", root))
    override suspend fun open(id: BlobId): BlobReadHandle = Reader(pathFor(id).also { require(it.isFile) })
    override suspend fun exists(id: BlobId): Boolean = pathFor(id).isFile
    override suspend fun delete(id: BlobId) { val file=pathFor(id); if(file.exists() && !file.delete()) throw IOException("Blob delete failed") }
    override suspend fun size(id: BlobId): Long = pathFor(id).length()
    override suspend fun listIds(): Set<BlobId> = root.listFiles { file -> file.isFile && file.name.endsWith(".vblob") }.orEmpty().map { BlobId(it.name.removeSuffix(".vblob")) }.toSet()
    private fun pathFor(id: BlobId): File { require(id.value.matches(Regex("[0-9a-f]{32}"))); return File(root, "${id.value}.vblob") }

    private inner class Writer(private val temp: File) : BlobWriteHandle {
        private var closed=false
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) { check(!closed); FileOutputStream(temp,true).use { it.write(bytes,offset,length) } }
        override suspend fun commit(): BlobId { check(!closed); val id=BlobId(ByteArray(16).also(SecureRandom()::nextBytes).joinToString(""){it.toUByte().toString(16).padStart(2,'0')}); persistence.sync(temp); persistence.replace(temp,pathFor(id)); closed=true; return id }
        override suspend fun abort() { if(!closed && temp.exists() && !temp.delete()) throw IOException("Staging delete failed"); closed=true }
    }

    private class Reader(private val file: File) : BlobReadHandle {
        override val size: Long get()=file.length()
        override suspend fun readAt(offset: Long, maxBytes: Int): ByteArray { require(offset>=0&&maxBytes>=0); RandomAccessFile(file,"r").use { input -> input.seek(offset); val bounded=(size-offset).coerceIn(0L,maxBytes.toLong()).toInt(); val bytes=ByteArray(bounded); val count=input.read(bytes); return if(count<0) ByteArray(0) else bytes.copyOf(count) } }
        override suspend fun close() = Unit
    }
}
