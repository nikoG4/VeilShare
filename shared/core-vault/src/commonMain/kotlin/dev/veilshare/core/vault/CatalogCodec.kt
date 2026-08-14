package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.model.BlobId

/** Canonical, bounded V1 payload codec. Crypto and filesystem framing deliberately live elsewhere. */
class CatalogCodec {
    fun encode(snapshot: CatalogSnapshot): ByteArray {
        require(snapshot.schema.value == 1) { "Unsupported catalog schema" }
        validate(snapshot.entries)
        val out = BytesOut()
        out.u8(snapshot.schema.value)
        out.u32(snapshot.entries.size)
        snapshot.entries.sortedBy { it.id.value }.forEach { entry ->
            when (entry) {
                is VaultItem.Directory -> { out.u8(1); common(out, entry); }
                is VaultItem.File -> {
                    out.u8(2); common(out, entry)
                    out.nullableString(entry.mimeType, 1024)
                    out.i64(entry.size)
                    out.string(entry.blobId.value, 128)
                    out.nullableString(entry.thumbnailId?.value, 128)
                    out.sealed(entry.wrappedFileKey)
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CatalogSnapshot {
        val input = BytesIn(bytes)
        val schema = input.u8()
        if (schema != 1) throw VaultFormatException("Unsupported catalog schema")
        val count = input.u32()
        if (count !in 0..100_000) throw VaultFormatException("Unsafe catalog entry count")
        val entries = ArrayList<VaultItem>(count)
        repeat(count) {
            when (input.u8()) {
                1 -> entries += VaultItem.Directory(input.itemId(), input.parent(), input.string(4096))
                2 -> {
                    val id = input.itemId(); val parent = input.parent(); val name = input.string(4096)
                    val mime = input.nullableString(1024); val size = input.i64()
                    if (size < 0) throw VaultFormatException("Negative file size")
                    val blob = BlobId(input.string(128)); val thumbnail = input.nullableString(128)?.let(::BlobId)
                    entries += VaultItem.File(id, parent, name, mime, size, blob, thumbnail, input.sealed())
                }
                else -> throw VaultFormatException("Unknown catalog entry type")
            }
        }
        input.finish()
        validate(entries)
        return CatalogSnapshot(CatalogSchemaVersion(schema), entries)
    }

    private fun common(out: BytesOut, e: VaultItem) { out.string(e.id.value, 128); out.nullableString(e.parentId?.value, 128); out.string(e.displayName, 4096) }
    private fun validate(entries: List<VaultItem>) {
        val byId = entries.associateBy { it.id.value }
        if (byId.size != entries.size) throw VaultFormatException("Duplicate catalog id")
        entries.forEach { e ->
            if (e.displayName.isBlank() || e.displayName.any { it == '/' || it == '\\' || it.code < 32 }) throw VaultFormatException("Invalid catalog name")
            e.parentId?.let { p -> if (byId[p.value] !is VaultItem.Directory) throw VaultFormatException("Invalid catalog parent") }
            if (e is VaultItem.File && (e.wrappedFileKey == null || e.wrappedFileKey.nonce.bytes.size != 12 || e.wrappedFileKey.ciphertext.size != 48)) throw VaultFormatException("Invalid wrapped file key")
        }
        entries.filterIsInstance<VaultItem.Directory>().forEach { start ->
            val seen = mutableSetOf<String>(); var cursor: VaultItem? = start
            while (cursor?.parentId != null) { if (!seen.add(cursor.id.value)) throw VaultFormatException("Directory cycle"); cursor = byId[cursor.parentId!!.value] }
        }
    }
}

private class BytesOut { private val b = ArrayList<Byte>()
    fun u8(v:Int) { b += v.toByte() }; fun u32(v:Int) { require(v >= 0); repeat(4) { n -> u8(v ushr (24 - n * 8)) } }
    fun i64(v:Long) { for (n in 7 downTo 0) u8((v ushr (n * 8)).toInt()) }
    fun string(v:String, max:Int) { val raw=v.encodeToByteArray(); require(raw.size <= max); u32(raw.size); raw.forEach { b += it } }
    fun nullableString(v:String?, max:Int) { u8(if(v==null) 0 else 1); if(v!=null) string(v,max) }
    fun sealed(v:SealedBytes?) { requireNotNull(v); u8(v.nonce.bytes.size); v.nonce.bytes.forEach { b+=it }; u32(v.ciphertext.size); v.ciphertext.forEach { b+=it } }
    fun toByteArray()=b.toByteArray()
}
private class BytesIn(private val b:ByteArray) { private var p=0
    fun u8()=take(1)[0].toInt() and 0xff
    fun u32():Int { var r=0; repeat(4) { r=(r shl 8) or u8() }; return r }
    fun i64():Long { var r=0L; repeat(8) { r=(r shl 8) or u8().toLong() }; return r }
    fun string(max:Int):String { val n=u32(); if(n<0 || n>max) throw VaultFormatException("Unsafe string length"); return take(n).decodeToString() }
    fun nullableString(max:Int)=when(u8()) { 0->null; 1->string(max); else->throw VaultFormatException("Bad nullable field") }
    fun itemId()=VaultItemId(string(128)); fun parent()=nullableString(128)?.let(::VaultDirectoryId)
    fun sealed():SealedBytes { val n=u8(); if(n!=12) throw VaultFormatException("Invalid key wrap nonce"); val nonce=Nonce(take(n)); val c=u32(); if(c!=48) throw VaultFormatException("Invalid wrapped key length"); return SealedBytes(nonce,take(c)) }
    fun finish() { if(p!=b.size) throw VaultFormatException("Trailing catalog payload") }
    private fun take(n:Int):ByteArray { if(n<0 || p> b.size-n) throw VaultFormatException("Truncated catalog payload"); return b.copyOfRange(p,p+n).also { p+=n } }
}
