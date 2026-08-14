package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*

data class BlobWriteResult(val blobId: dev.veilshare.core.model.BlobId, val size: Long)
class EncryptedBlobWriter(private val cipher: AuthenticatedCipher, private val random: SecureRandom) {
    suspend fun write(source: suspend (Int)->ByteArray, target: BlobWriteHandle, key: FileKey, context: ByteArray): BlobWriteResult {
        val prefix=random.bytes(4); var index=0uL; var total=0L
        try { target.write(header(prefix,context)); val noncePrefix=u32(prefix,0).toUInt(); while(true) { val plain=source(VeilCryptoSuites.v1.chunkBytes); if(plain.isEmpty()) break; if(plain.size>VeilCryptoSuites.v1.chunkBytes) throw VaultFormatException("Source exceeded chunk limit"); val sealed=cipher.sealWithNonce(key.material,ChunkNonce.from(noncePrefix,index),plain,aad(context,index,plain.size)); target.write(frame(index,plain.size,sealed.ciphertext)); total+=plain.size; if(index==ULong.MAX_VALUE-1u) throw VaultFormatException("Chunk index overflow"); index++ }; val terminal=cipher.sealWithNonce(key.material,ChunkNonce.from(noncePrefix,index),ByteArray(0),aad(context,index,-1)); target.write(frame(index,-1,terminal.ciphertext)); return BlobWriteResult(target.commit(),total) } catch(t:Throwable) { target.abort(); throw t }
    }
}
class EncryptedBlobReader(private val cipher: AuthenticatedCipher) {
    suspend fun read(source: BlobReadHandle, key: FileKey, context: ByteArray, consume:suspend(ByteArray)->Unit):Long { var offset=0L; val header=take(source,offset,14); offset+=14; if(header.copyOfRange(0,4).decodeToString()!="VBL1" || header[4].toInt()!=1 || header[5].toInt()!=0) throw VaultFormatException("Unsupported blob header"); val prefix=header.copyOfRange(6,10); val noncePrefix=u32(prefix,0).toUInt(); val contextLen=u32(header,10); if(contextLen !in 0..128) throw VaultFormatException("Bad blob context"); val stored=take(source,offset,contextLen);offset+=contextLen; if(!stored.contentEquals(context)) throw VaultFormatException("Wrong blob context"); var expected=0uL;var total=0L; while(true){val f=take(source,offset,12);offset+=12;val i=u64(f,0);val n=u32(f,8);if(i!=expected)throw VaultFormatException("Reordered or missing chunk");if(n==-1){val tag=take(source,offset,16);offset+=16; cipher.open(key.material,SealedBytes(ChunkNonce.from(noncePrefix,i),tag),aad(context,i,-1));if(offset!=source.size)throw VaultFormatException("Trailing blob data");return total};if(n !in 0..VeilCryptoSuites.v1.chunkBytes)throw VaultFormatException("Bad chunk size");val enc=take(source,offset,n+16);offset+=(n+16);val plain=cipher.open(key.material,SealedBytes(ChunkNonce.from(noncePrefix,i),enc),aad(context,i,n)); if(plain.size!=n)throw VaultFormatException("Bad plaintext size");consume(plain);total+=n;expected++ }
    }
    private suspend fun take(s:BlobReadHandle,o:Long,n:Int):ByteArray { if(o<0||n<0||o+n>s.size)throw VaultFormatException("Truncated blob");val b=s.readAt(o,n);if(b.size!=n)throw VaultFormatException("Truncated blob");return b }
}
private fun header(prefix:ByteArray,context:ByteArray):ByteArray { require(prefix.size==4&&context.size<=128); return "VBL1".encodeToByteArray()+byteArrayOf(1,0)+prefix+be(context.size)+context }
private fun frame(i:ULong,n:Int,c:ByteArray)=be64(i)+be(n)+c
private fun aad(context:ByteArray,i:ULong,n:Int)="VEIL/V1/BLOB".encodeToByteArray()+be(context.size)+context+be64(i)+be(n)
private fun be(v:Int)=ByteArray(4){((v ushr (24-it*8))and 255).toByte()}; private fun be64(v:ULong)=ByteArray(8){((v shr (56-it*8))and 255u).toByte()}
private fun u32(b:ByteArray,o:Int):Int { var r=0;repeat(4){r=(r shl 8)or(b[o+it].toInt()and 255)};return r };private fun u64(b:ByteArray,o:Int):ULong {var r=0uL;repeat(8){r=(r shl 8)or(b[o+it].toUByte().toULong())};return r}
