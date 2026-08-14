package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

class AndroidVaultJournal(root: File, private val persistence: AndroidPersistenceOps = AndroidDurableFiles) : VaultJournal {
    private val file = File(File(root, "state"), "j-9c4e0a.bin")
    private val current = read().associateByTo(mutableMapOf()) { it.id.value }
    init { check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) }
    override suspend fun put(entry: JournalEntry) { current[entry.id.value]=entry; persist() }
    override suspend fun entries(): List<JournalEntry> = current.values.toList()
    override suspend fun remove(id: TransactionId) { current.remove(id.value); persist() }

    private fun persist() {
        val temp = File.createTempFile(".j-", ".tmp", file.parentFile)
        try {
            val bytes = ByteArrayOutputStream().use { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(0x564A3032); out.writeInt(current.size)
                current.values.forEach { entry ->
                    out.writeUTF(entry.id.value); out.writeUTF(entry.blobNamespace); out.writeByte(entry.state.ordinal)
                    out.writeInt(entry.provisionalBlobs.size); entry.provisionalBlobs.forEach { out.writeUTF(it.value) }
                }
            }; buffer.toByteArray() }
            persistence.writeAndSync(temp, bytes); persistence.replace(temp, file)
        } finally { temp.delete() }
    }

    private fun read(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        try {
            return DataInputStream(file.inputStream()).use { input ->
                if (input.readInt()!=0x564A3032) throw VaultFormatException("Bad journal magic or version")
                val count=input.readInt(); if(count !in 0..10_000) throw VaultFormatException("Unsafe journal record count")
                val ids=mutableSetOf<String>()
                List(count) {
                    val rawId=input.readUTF(); if(rawId.length !in 1..128 || !ids.add(rawId)) throw VaultFormatException("Invalid or duplicate journal id")
                    val namespace=input.readUTF(); if(namespace.length !in 1..128 || namespace.any { it.code<33 }) throw VaultFormatException("Invalid journal namespace")
                    val state=TransactionState.entries.getOrNull(input.readUnsignedByte()) ?: throw VaultFormatException("Bad journal state")
                    val blobCount=input.readInt(); if(blobCount !in 0..10_000) throw VaultFormatException("Unsafe journal blob count")
                    val blobs=mutableSetOf<BlobId>(); repeat(blobCount) { val raw=input.readUTF(); if(raw.length !in 1..128 || !blobs.add(BlobId(raw))) throw VaultFormatException("Invalid or duplicate journal blob") }
                    JournalEntry(TransactionId(rawId), state, blobs, namespace)
                }.also { if(input.read()!=-1) throw VaultFormatException("Trailing journal bytes") }
            }
        } catch (e: VaultFormatException) { throw e }
        catch (e: EOFException) { throw VaultFormatException("Truncated journal") }
        catch (e: IOException) { throw VaultFormatException("Unreadable journal") }
    }
}
