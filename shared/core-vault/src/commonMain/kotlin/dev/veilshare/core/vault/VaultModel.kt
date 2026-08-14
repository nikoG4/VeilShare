package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.model.VaultId
@JvmInline value class VaultFormatVersion(val value: Int) { init { require(value >= 1) } }
@JvmInline value class VaultMetadataVersion(val value: Int) { init { require(value >= 1) } }
@JvmInline value class VaultItemId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class VaultDirectoryId(val value: String) { init { require(value.isNotBlank()) } }
typealias EncryptedBlobId = BlobId
enum class VaultType { REAL, DECOY }
data class VaultDescriptor(val vaultId: VaultId, val type: VaultType, val formatVersion: VaultFormatVersion, val metadataVersion: VaultMetadataVersion, val catalogBlob: BlobId, val blobNamespace:String = vaultId.value)
sealed interface VaultItem { val id: VaultItemId; val parentId: VaultDirectoryId?; val displayName: String
    data class Directory(override val id: VaultItemId, override val parentId: VaultDirectoryId?, override val displayName: String) : VaultItem
    data class File(override val id: VaultItemId, override val parentId: VaultDirectoryId?, override val displayName: String, val mimeType: String?, val size: Long, val blobId: BlobId, val thumbnailId: BlobId? = null, val wrappedFileKey: SealedBytes? = null) : VaultItem
}
sealed interface VaultHealth { data object Healthy: VaultHealth; data object NeedsCleanup: VaultHealth; data object PartiallyDamaged: VaultHealth; data object UnsupportedVersion: VaultHealth; data object Corrupt: VaultHealth }
class VaultFormatException(message: String) : IllegalStateException(message)
