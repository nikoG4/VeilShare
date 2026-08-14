package dev.veilshare.core.vault
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.Salt
data class VaultBootstrapSlot(val magic: String = "VSH1", val vaultId: dev.veilshare.core.model.VaultId, val formatVersion: VaultFormatVersion, val metadataVersion: VaultMetadataVersion, val cryptoSuite: dev.veilshare.core.crypto.CryptoSuiteId = dev.veilshare.core.crypto.CryptoSuiteId.VEIL_CRYPTO_V1, val kdfId: String = "argon2id", val kdfParameters: dev.veilshare.core.crypto.Argon2Parameters, val salt: Salt, val wrappedVaultKey: SealedBytes, val encryptedDescriptor: SealedBytes)
interface VaultFormatWriter { fun encode(slot: VaultBootstrapSlot): ByteArray }
interface VaultFormatReader { fun decode(bytes: ByteArray): VaultBootstrapSlot }
