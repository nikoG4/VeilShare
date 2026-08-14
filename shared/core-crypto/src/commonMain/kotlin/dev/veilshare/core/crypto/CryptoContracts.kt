package dev.veilshare.core.crypto

/** A persisted vault must reject unknown suites rather than silently selecting current defaults. */
enum class CryptoSuiteId { VEIL_CRYPTO_V1 }
data class CryptoSuiteDefinition(
    val id: CryptoSuiteId,
    val kdf: String,
    val aead: String,
    val keyBytes: Int,
    val nonceBytes: Int,
    val saltBytes: Int,
    val chunkBytes: Int,
)
object VeilCryptoSuites {
    val v1 = CryptoSuiteDefinition(CryptoSuiteId.VEIL_CRYPTO_V1, "Argon2id v1.3", "ChaCha20-Poly1305 IETF", 32, 12, 16, 1_048_576)
}

class SensitiveBytes(private val bytes: ByteArray) : AutoCloseable {
    fun copy(): ByteArray = bytes.copyOf()
    override fun close() { bytes.fill(0) }
    override fun toString() = "SensitiveBytes(REDACTED)"
}
class SensitiveChars(private val chars: CharArray) : AutoCloseable {
    fun copy(): CharArray = chars.copyOf()
    override fun close() { chars.fill('\u0000') }
    override fun toString() = "SensitiveChars(REDACTED)"
}
@JvmInline value class VaultKey(val material: SensitiveBytes)
@JvmInline value class FileKey(val material: SensitiveBytes)
@JvmInline value class KeyEncryptionKey(val material: SensitiveBytes)
@JvmInline value class Salt(val bytes: ByteArray)
@JvmInline value class Nonce(val bytes: ByteArray)
data class Argon2Parameters(val memoryKiB: Int, val iterations: Int, val parallelism: Int) { init { require(memoryKiB in 8_192..1_048_576); require(iterations in 1..20); require(parallelism in 1..16) } }
data class Argon2Policy(val parameters: Argon2Parameters, val outputBytes: Int = 32) { init { require(outputBytes == 32) } }
interface SecureRandom { fun bytes(size: Int): ByteArray }
interface PasswordKdf { suspend fun derive(secret: SensitiveChars, salt: Salt, policy: Argon2Policy): KeyEncryptionKey }
data class SealedBytes(val nonce: Nonce, val ciphertext: ByteArray)
interface AuthenticatedCipher { suspend fun seal(key: SensitiveBytes, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): SealedBytes; suspend fun sealWithNonce(key: SensitiveBytes, nonce: Nonce, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): SealedBytes; suspend fun open(key: SensitiveBytes, sealed: SealedBytes, aad: ByteArray = ByteArray(0)): ByteArray }
object ChunkNonce { fun from(prefix: UInt, index: ULong): Nonce { require(index != ULong.MAX_VALUE); val bytes=ByteArray(12); fun put(value:ULong, start:Int, count:Int){for(i in 0 until count) bytes[start+i]=(value shr (8*(count-1-i))).toByte()}; put(prefix.toULong(),0,4); put(index,4,8); return Nonce(bytes) } }
interface KeyWrapper { suspend fun wrap(kek: KeyEncryptionKey, vaultKey: VaultKey, aad: ByteArray): SealedBytes; suspend fun unwrap(kek: KeyEncryptionKey, wrapped: SealedBytes, aad: ByteArray): VaultKey }
interface KeyDeriver { suspend fun derive(ikm: SensitiveBytes, context: ByteArray, outputBytes: Int = 32): SensitiveBytes }
object CryptoContexts { val Catalog = "VEIL/V1/CATALOG".encodeToByteArray(); val FileKeyWrap = "VEIL/V1/FILEKEY-WRAP".encodeToByteArray(); val SlotDescriptor = "VEIL/V1/SLOT-DESCRIPTOR".encodeToByteArray() }
class FileKeyGenerator(private val random: SecureRandom) { fun generate() = FileKey(SensitiveBytes(random.bytes(32))) }
class VaultKeyGenerator(private val random: SecureRandom) { fun generate() = VaultKey(SensitiveBytes(random.bytes(32))) }
