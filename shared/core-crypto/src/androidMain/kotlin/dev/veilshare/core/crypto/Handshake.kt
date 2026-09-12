package dev.veilshare.core.crypto

import android.util.Base64
import java.security.MessageDigest

actual object Hash {
    actual fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}

actual fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

actual fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

actual fun String.decodeFromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

/** Android uses the same common authenticated handshake implementation as Desktop. */
class AndroidHandshakeProtocol : DefaultHandshakeProtocol()
