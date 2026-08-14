package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.VaultId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

/** Desktop V1 encrypted catalog: opaque path, binary framing, bounded parser, atomic replacement. */
enum class CatalogFaultPoint { BeforeTempWrite, TempWrittenBeforeReplace, BeforeReplace, ReplaceComplete }
/** Injectable at the actual byte-writing boundary; production always uses [ForcedAtomicCatalogPersistence]. */
interface CatalogPersistenceOps {
    fun writeAndForce(temp: Path, bytes: ByteArray)
    fun replace(temp: Path, target: Path)
}

object ForcedAtomicCatalogPersistence : CatalogPersistenceOps {
    override fun writeAndForce(temp: Path, bytes: ByteArray) = DesktopDurableFiles.writeAndForce(temp, bytes)

    override fun replace(temp: Path, target: Path) {
        // Fail closed when the filesystem cannot provide an atomic replacement.
        DesktopDurableFiles.replaceAtomic(temp, target)
    }
}

class DesktopEncryptedCatalogStore(private val root:Path, private val vaultId:VaultId, private val crypto:CatalogCrypto, private val codec:CatalogCodec = CatalogCodec(), private val persistence:CatalogPersistenceOps = ForcedAtomicCatalogPersistence, private val fault:(CatalogFaultPoint)->Unit={}) : EncryptedCatalogStore {
    private val file=root.resolve("catalogs").resolve(vaultId.value.take(24)+".vcat")
    init { Files.createDirectories(file.parent) }
    override suspend fun exists()=Files.isRegularFile(file)
    override suspend fun createEmpty(vaultKey:VaultKey) = replaceAtomically(vaultKey,CatalogSnapshot())
    override suspend fun load(vaultKey:VaultKey):CatalogSnapshot { val bytes=DataInputStream(Files.newInputStream(file)).use(::read); return codec.decode(crypto.decrypt(vaultKey,bytes)) }
    override suspend fun replaceAtomically(vaultKey:VaultKey,snapshot:CatalogSnapshot) { val encrypted=crypto.encrypt(vaultKey,codec.encode(snapshot)); val bytes=ByteArrayOutputStream().use { buffer -> DataOutputStream(buffer).use { write(it,encrypted) }; buffer.toByteArray() }; fault(CatalogFaultPoint.BeforeTempWrite); val temp=Files.createTempFile(file.parent,".catalog-",".tmp"); try { persistence.writeAndForce(temp,bytes); fault(CatalogFaultPoint.TempWrittenBeforeReplace); fault(CatalogFaultPoint.BeforeReplace); persistence.replace(temp,file); fault(CatalogFaultPoint.ReplaceComplete) } finally { Files.deleteIfExists(temp) } }
    private fun write(o:DataOutputStream,e:EncryptedCatalog){o.writeInt(0x56434131);o.writeInt(e.schema.value);o.writeByte(CryptoSuiteId.VEIL_CRYPTO_V1.ordinal);o.writeInt(e.payload.nonce.bytes.size);o.write(e.payload.nonce.bytes);o.writeInt(e.payload.ciphertext.size);o.write(e.payload.ciphertext)}
    private fun read(i:DataInputStream):EncryptedCatalog { if(i.readInt()!=0x56434131)throw VaultFormatException("Bad catalog magic"); val v=i.readInt(); if(v!=1)throw VaultFormatException("Unsupported catalog format"); if(i.readUnsignedByte()!=CryptoSuiteId.VEIL_CRYPTO_V1.ordinal)throw VaultFormatException("Unsupported suite"); val n=i.readInt();if(n!=12)throw VaultFormatException("Bad nonce");val nonce=ByteArray(n).also(i::readFully);val len=i.readInt();if(len !in 16..16_777_216)throw VaultFormatException("Unsafe catalog length");val c=ByteArray(len).also(i::readFully);if(i.read()!=-1)throw VaultFormatException("Trailing catalog data");return EncryptedCatalog(CatalogSchemaVersion(1),SealedBytes(Nonce(nonce),c)) }
}
