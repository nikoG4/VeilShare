package dev.veilshare.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable @JvmInline value class VaultId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class FileId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class BlobId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class FolderId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ContactId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class TransferId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class SessionId(val value: String) { init { require(value.isNotBlank()) } }

/**
 * Stable, local-only binding token for one unlocked vault persona.
 *
 * This is NOT a SharingContextId or network identity and must never be transmitted.
 * Production vault services derive it from authenticated local vault metadata only so the
 * application can look up a separately generated random SharingContextId.
 */
@Serializable
@JvmInline
value class LocalPersonaId(val value: String) {
    init {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "LocalPersonaId must be lowercase SHA-256 hex"
        }
    }
}

@Serializable
@JvmInline
value class ReferenceCode(val value: String) {
    init {
        require(ReferenceCodes.isCanonical(value)) { "Invalid reference code" }
    }
}
@Serializable @JvmInline value class Fingerprint(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class ConnectionId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class MessageId(val value: String) { init { require(value.isNotBlank()) } }
@Serializable @JvmInline value class SharingIdentityId(val value: String) { init { require(value.isNotBlank()) } }

object FormatVersions { const val VAULT = 1; const val FILE = 1; const val PROTOCOL = 1; const val SHARING = 1 }

fun interface RandomBytesSource {
    fun nextBytes(size: Int): ByteArray
}

object OpaqueIds {
    fun fromRandom(random: RandomBytesSource): String = random.nextBytes(16).toHex()
    fun messageId(random: RandomBytesSource): MessageId = MessageId(fromRandom(random))
    fun sessionId(random: RandomBytesSource): SessionId = SessionId(fromRandom(random))
    fun transferId(random: RandomBytesSource): TransferId = TransferId(fromRandom(random))
    fun connectionId(random: RandomBytesSource): ConnectionId = ConnectionId(fromRandom(random))
    fun sharingIdentityId(random: RandomBytesSource): SharingIdentityId = SharingIdentityId(fromRandom(random))
}

object ReferenceCodes {
    private const val CODE_SYMBOLS = 16
    private const val GROUP_SIZE = 4
    private const val RAW_BYTES = 10
    private val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val canonical = Regex("[$alphabet]{$GROUP_SIZE}(-[$alphabet]{$GROUP_SIZE}){3}")

    fun generate(random: RandomBytesSource): ReferenceCode {
        val raw = random.nextBytes(RAW_BYTES)
        require(raw.size == RAW_BYTES) { "Reference code entropy source returned wrong byte count" }
        return ReferenceCode(format(encodeBase32(raw)))
    }

    fun parse(input: String): ReferenceCode = ReferenceCode(normalize(input))

    fun normalize(input: String): String {
        val compact = input.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        require(compact.length == CODE_SYMBOLS) { "Reference code must contain 16 Base32 symbols" }
        require(compact.all { it in alphabet }) { "Reference code contains unsupported characters" }
        return format(compact)
    }

    fun isCanonical(value: String): Boolean = canonical.matches(value)

    private fun format(compact: String): String =
        compact.chunked(GROUP_SIZE).joinToString("-")

    private fun encodeBase32(bytes: ByteArray): String {
        val out = StringBuilder(CODE_SYMBOLS)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5 && out.length < CODE_SYMBOLS) {
                val index = (buffer ushr (bits - 5)) and 31
                out.append(alphabet[index])
                bits -= 5
            }
        }
        return out.toString()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
