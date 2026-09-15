package dev.veilshare.core.crypto

import java.security.MessageDigest
import java.util.Base64

actual object Hash {
    actual fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}

actual fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

actual fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

actual fun String.decodeFromBase64(): ByteArray = Base64.getDecoder().decode(this)

/** Desktop uses the common authenticated handshake implementation. */
class JvmHandshakeProtocol : DefaultHandshakeProtocol()
