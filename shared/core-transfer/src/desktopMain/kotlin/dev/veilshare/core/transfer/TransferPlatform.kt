package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Hash

actual object TransferPlatform {
    actual object Hash {
        actual fun sha256(input: ByteArray): ByteArray {
            return dev.veilshare.core.crypto.Hash.sha256(input)
        }
    }

    actual fun sha256ToHex(input: ByteArray): String {
        val hash = dev.veilshare.core.crypto.Hash.sha256(input)
        return hash.joinToString("") { "%02x".format(it) }
    }
}