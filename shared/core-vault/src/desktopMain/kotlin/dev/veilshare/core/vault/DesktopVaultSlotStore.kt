package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.CryptoSuiteId
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.Salt
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.model.VaultId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Persistent opaque slot storage. Header fields are bounded; descriptor and VMK remain AEAD ciphertext. */
class DesktopVaultSlotStore(private val root: Path) : VaultSlotStore {
    private val dir = root.resolve("bootstrap").resolve("slots")
    init { Files.createDirectories(dir) }
    override suspend fun write(slotId: String, slot: VaultBootstrapSlot) {
        require(slotId.matches(Regex("[0-9a-f]{24}")))
        val target=dir.resolve("$slotId.vslot"); val temp=Files.createTempFile(dir,".slot-",".tmp")
        try { val bytes=ByteArrayOutputStream().use { buffer -> DataOutputStream(buffer).use { out -> writeSlot(out,slot) }; buffer.toByteArray() }; DesktopDurableFiles.writeAndForce(temp,bytes); DesktopDurableFiles.replaceAtomic(temp,target) } finally { Files.deleteIfExists(temp) }
    }
    override suspend fun all(): List<Pair<String, VaultBootstrapSlot>> = Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".vslot") }.map { p -> p.fileName.toString().removeSuffix(".vslot") to DataInputStream(Files.newInputStream(p)).use(::readSlot) }.toList() }
    private fun writeSlot(o:DataOutputStream,s:VaultBootstrapSlot) { o.writeInt(0x56534C31); o.writeInt(s.formatVersion.value); o.writeInt(s.metadataVersion.value); o.writeByte(s.cryptoSuite.ordinal); o.writeUTF(s.vaultId.value); o.writeInt(s.kdfParameters.memoryKiB); o.writeInt(s.kdfParameters.iterations); o.writeInt(s.kdfParameters.parallelism); bytes(o,s.salt.bytes); sealed(o,s.wrappedVaultKey); sealed(o,s.encryptedDescriptor) }
    private fun readSlot(i:DataInputStream):VaultBootstrapSlot { if(i.readInt()!=0x56534C31) throw VaultFormatException("Bad slot magic"); val format=VaultFormatVersion(i.readInt()); val metadata=VaultMetadataVersion(i.readInt()); val suite=CryptoSuiteId.entries.getOrNull(i.readUnsignedByte())?:throw VaultFormatException("Unknown suite"); val id=VaultId(i.readUTF()); val p=Argon2Parameters(i.readInt(),i.readInt(),i.readInt()); val slot=VaultBootstrapSlot(vaultId=id,formatVersion=format,metadataVersion=metadata,cryptoSuite=suite,kdfParameters=p,salt=Salt(bytes(i,64)),wrappedVaultKey=sealed(i),encryptedDescriptor=sealed(i));if(i.read()!=-1)throw VaultFormatException("Trailing slot data");return slot }
    private fun bytes(o:DataOutputStream,b:ByteArray){o.writeInt(b.size);o.write(b)}
    private fun bytes(i:DataInputStream,max:Int):ByteArray { val n=i.readInt(); if(n !in 0..max) throw VaultFormatException("Unsafe slot length"); return ByteArray(n).also(i::readFully) }
    private fun sealed(o:DataOutputStream,s:SealedBytes){bytes(o,s.nonce.bytes);bytes(o,s.ciphertext)}
    private fun sealed(i:DataInputStream)=SealedBytes(Nonce(bytes(i,32)),bytes(i,1024))
}
