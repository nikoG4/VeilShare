package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Hash

expect object TransferPlatform {
    object Hash {
        fun sha256(input: ByteArray): ByteArray
    }

    fun sha256ToHex(input: ByteArray): String
}