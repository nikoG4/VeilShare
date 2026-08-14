package dev.veilshare.core.vault

import dev.veilshare.core.crypto.CryptoSuiteId
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.VaultKey
import dev.veilshare.core.model.VaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Encrypted catalog in an Android app-private root. Temp files are never promoted heuristically. */
class AndroidEncryptedCatalogStore(
    root: File,
    private val vaultId: VaultId,
    private val crypto: CatalogCrypto,
    private val codec: CatalogCodec = CatalogCodec(),
    private val persistence: AndroidPersistenceOps = AndroidDurableFiles,
) : EncryptedCatalogStore {
    private val file = File(File(root, "catalogs"), vaultId.value.take(24) + ".vcat")
    init { check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) }

    override suspend fun exists(): Boolean = file.isFile
    override suspend fun createEmpty(vaultKey: VaultKey) = replaceAtomically(vaultKey, CatalogSnapshot())
    override suspend fun load(vaultKey: VaultKey): CatalogSnapshot =
        codec.decode(crypto.decrypt(vaultKey, DataInputStream(file.inputStream()).use(::read)))

    override suspend fun replaceAtomically(vaultKey: VaultKey, snapshot: CatalogSnapshot) {
        val encrypted = crypto.encrypt(vaultKey, codec.encode(snapshot))
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { write(it, encrypted) }
            buffer.toByteArray()
        }
        val temp = File.createTempFile(".catalog-", ".tmp", file.parentFile)
        try {
            persistence.writeAndSync(temp, bytes)
            persistence.replace(temp, file)
        } finally {
            temp.delete()
        }
    }

    private fun write(out: DataOutputStream, value: EncryptedCatalog) {
        out.writeInt(0x56434131); out.writeInt(value.schema.value)
        out.writeByte(CryptoSuiteId.VEIL_CRYPTO_V1.ordinal)
        out.writeInt(value.payload.nonce.bytes.size); out.write(value.payload.nonce.bytes)
        out.writeInt(value.payload.ciphertext.size); out.write(value.payload.ciphertext)
    }

    private fun read(input: DataInputStream): EncryptedCatalog {
        if (input.readInt() != 0x56434131) throw VaultFormatException("Bad catalog magic")
        if (input.readInt() != 1) throw VaultFormatException("Unsupported catalog format")
        if (input.readUnsignedByte() != CryptoSuiteId.VEIL_CRYPTO_V1.ordinal) throw VaultFormatException("Unsupported suite")
        val nonceLength = input.readInt(); if (nonceLength != 12) throw VaultFormatException("Bad nonce")
        val nonce = ByteArray(nonceLength).also(input::readFully)
        val length = input.readInt(); if (length !in 16..16_777_216) throw VaultFormatException("Unsafe catalog length")
        val ciphertext = ByteArray(length).also(input::readFully)
        if (input.read() != -1) throw VaultFormatException("Trailing catalog data")
        return EncryptedCatalog(CatalogSchemaVersion(1), SealedBytes(Nonce(nonce), ciphertext))
    }
}
