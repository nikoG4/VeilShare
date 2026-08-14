package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.EOFException
import java.io.IOException

/** Small bounded, atomically replaced journal. It contains opaque IDs only. */
class DesktopVaultJournal(private val root: Path) : VaultJournal {
    private val file = root.resolve("state").resolve("j-9c4e0a.bin")
    private var current: MutableMap<String, JournalEntry> = read().associateByTo(mutableMapOf()) { it.id.value }
    init { Files.createDirectories(file.parent) }
    override suspend fun put(entry: JournalEntry) { current[entry.id.value] = entry; persist() }
    override suspend fun entries(): List<JournalEntry> = current.values.toList()
    override suspend fun remove(id: TransactionId) { current.remove(id.value); persist() }
    private fun persist() { val temp=Files.createTempFile(file.parent,".j-",".tmp"); try { val bytes=ByteArrayOutputStream().use { buffer -> DataOutputStream(buffer).use { o -> o.writeInt(0x564A3032); o.writeInt(current.size); current.values.forEach { e -> o.writeUTF(e.id.value); o.writeUTF(e.blobNamespace); o.writeByte(e.state.ordinal); o.writeInt(e.provisionalBlobs.size); e.provisionalBlobs.forEach { o.writeUTF(it.value) } } }; buffer.toByteArray() }; DesktopDurableFiles.writeAndForce(temp,bytes); DesktopDurableFiles.replaceAtomic(temp,file) } finally { Files.deleteIfExists(temp) } }
    private fun read(): List<JournalEntry> { if(!Files.exists(file)) return emptyList(); try { return DataInputStream(Files.newInputStream(file)).use { i -> if(i.readInt()!=0x564A3032) throw VaultFormatException("Bad journal magic or version"); val n=i.readInt(); if(n !in 0..10_000) throw VaultFormatException("Unsafe journal record count"); val seen=mutableSetOf<String>(); List(n) { val rawId=i.readUTF(); if(rawId.length !in 1..128 || !seen.add(rawId)) throw VaultFormatException("Invalid or duplicate journal id"); val id=TransactionId(rawId); val ns=i.readUTF().also { if(it.length !in 1..128 || it.any { c->c.code<33 }) throw VaultFormatException("Invalid journal namespace") }; val state=TransactionState.entries.getOrNull(i.readUnsignedByte()) ?: throw VaultFormatException("Bad journal state"); val blobs=i.readInt(); if(blobs !in 0..10_000) throw VaultFormatException("Unsafe journal blob count"); val blobSet=mutableSetOf<BlobId>(); repeat(blobs) { val raw=i.readUTF(); if(raw.length !in 1..128 || !blobSet.add(BlobId(raw))) throw VaultFormatException("Invalid or duplicate journal blob") }; JournalEntry(id,state,blobSet,ns) }.also { if(i.read()!=-1) throw VaultFormatException("Trailing journal bytes") } } } catch(e:VaultFormatException){throw e} catch(e:EOFException){throw VaultFormatException("Truncated journal")} catch(e:IOException){throw VaultFormatException("Unreadable journal")} }
}
