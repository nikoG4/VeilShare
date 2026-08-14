package dev.veilshare.core.crypto

data class ProductionCryptoComponents(val passwordKdf: PasswordKdf, val cipher: AuthenticatedCipher, val random: SecureRandom)
