package dev.veilshare.core.crypto

object DesktopProductionCrypto {
    fun create() = ProductionCryptoComponents(JvmArgon2idPasswordKdf(), JvmChaCha20Poly1305Cipher(), JvmSecureRandom())
    fun keyDeriver(): KeyDeriver = JvmHkdfSha256KeyDeriver()
}
