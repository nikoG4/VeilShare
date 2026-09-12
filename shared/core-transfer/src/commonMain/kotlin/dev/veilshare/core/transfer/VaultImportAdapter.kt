package dev.veilshare.core.transfer

import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.vault.ImportProgress
import dev.veilshare.core.vault.ImportReadHandle
import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.VaultDirectoryId
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem

class TransferImportSourceAdapter(
    private val transferImportSource: TransferImportSource,
    displayNameOverride: String? = null,
    mimeHintOverride: String? = null,
) : ImportSource {
    override val displayName: String = displayNameOverride ?: transferImportSource.displayName
    override val mimeHint: String? = mimeHintOverride ?: transferImportSource.mimeHint
    override val sizeHint: Long? = transferImportSource.sizeHint

    override suspend fun openRead(): ImportReadHandle {
        val handle = transferImportSource.openRead()
        return TransferImportReadHandleAdapter(handle)
    }
}

/**
 * Narrow integration boundary from authenticated transfer state into the existing
 * crash-safe Vault import pipeline. The transfer layer never receives a VMK/FileKey;
 * it only exposes a bounded plaintext read handle to VaultHandle.import().
 */
class ReceivedTransferVaultImporter(
    private val receiver: TransferReceiver,
) {
    /** Preferred Sharing V1 path: metadata comes from the validated E2E OFFER. */
    suspend fun importCompleted(
        transferId: TransferId,
        offer: TransferOffer,
        vault: VaultHandle,
        parent: VaultDirectoryId? = null,
        progress: suspend (ImportProgress) -> Unit = {},
    ): VaultItem.File {
        val transferSource = receiver.getImportSource(transferId, offer.fileId)
        require(transferSource.sizeHint == offer.sizeBytes) {
            "Authenticated transfer size ${transferSource.sizeHint} does not match offered size ${offer.sizeBytes}"
        }
        return vault.import(
            source = TransferImportSourceAdapter(
                transferImportSource = transferSource,
                displayNameOverride = offer.displayName,
                mimeHintOverride = offer.mimeHint,
            ),
            parent = parent,
            progress = progress,
        )
    }

    /** Compatibility path for callers that have not yet migrated to TransferOffer. */
    suspend fun importCompleted(
        transferId: TransferId,
        fileId: FileId,
        vault: VaultHandle,
        displayName: String,
        mimeHint: String? = null,
        parent: VaultDirectoryId? = null,
        progress: suspend (ImportProgress) -> Unit = {},
    ): VaultItem.File {
        val safeName = validateDisplayName(displayName)
        val safeMime = validateMimeHint(mimeHint)
        val transferSource = receiver.getImportSource(transferId, fileId)
        return vault.import(
            source = TransferImportSourceAdapter(
                transferImportSource = transferSource,
                displayNameOverride = safeName,
                mimeHintOverride = safeMime,
            ),
            parent = parent,
            progress = progress,
        )
    }

    private fun validateDisplayName(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "displayName is required" }
        require(trimmed.length <= MAX_DISPLAY_NAME_CHARS) { "displayName is too long" }
        require(trimmed.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            "displayName contains unsafe path/control characters"
        }
        return trimmed
    }

    private fun validateMimeHint(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.length <= MAX_MIME_HINT_CHARS) { "mimeHint is too long" }
        require(trimmed.none { it.code < 0x20 || it.code == 0x7f }) { "mimeHint contains control characters" }
        return trimmed
    }

    private companion object {
        const val MAX_DISPLAY_NAME_CHARS = 255
        const val MAX_MIME_HINT_CHARS = 255
    }
}

private class TransferImportReadHandleAdapter(
    private val handle: TransferImportReadHandle,
) : ImportReadHandle {
    override suspend fun read(maxBytes: Int): ByteArray = handle.read(maxBytes)

    override suspend fun close() {
        handle.close()
    }
}
