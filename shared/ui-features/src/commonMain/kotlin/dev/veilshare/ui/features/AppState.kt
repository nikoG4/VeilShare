package dev.veilshare.ui.features

import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem

sealed interface RootState {
    data object Initializing : RootState
    data class FirstRun(val busy: Boolean = false, val error: String? = null) : RootState
    data class Locked(val busy: Boolean = false, val error: String? = null) : RootState
    data class Unlocked(val browser: BrowserState) : RootState
    data class Fatal(val message: String) : RootState
    // Sharing states
    data class SharingSender(val state: SharingSenderState) : RootState
    data class SharingReceiver(val state: SharingReceiverState) : RootState
}

data class BrowserItem(val id: String, val name: String, val isDirectory: Boolean, val size: Long? = null, val mime: String? = null)
data class Breadcrumb(val id: String?, val label: String)
sealed interface BrowserOperation { data object Idle : BrowserOperation; data class Importing(val bytes: Long, val total: Long?) : BrowserOperation; data class Busy(val label: String) : BrowserOperation }
data class BrowserState(
    val currentFolderId: String? = null,
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb(null, "Archivos")),
    val items: List<BrowserItem> = emptyList(),
    val operation: BrowserOperation = BrowserOperation.Idle,
    val message: String? = null,
)

sealed interface SharingSenderState {
    data class Preparing(val referenceCode: String? = null, val selectedFile: String? = null) : SharingSenderState
    data class Connecting(val referenceCode: ReferenceCode) : SharingSenderState
    data class Sending(val progress: SharingProgress) : SharingSenderState
    object Completed : SharingSenderState
    data class Error(val message: String, val canRetry: Boolean = true) : SharingSenderState
    object Cancelled : SharingSenderState
}

sealed interface SharingReceiverState {
    data class Waiting(val referenceCode: ReferenceCode) : SharingReceiverState
    data class Incoming(val senderIdentity: String, val fileName: String, val fileSize: Long) : SharingReceiverState
    data class Receiving(val progress: SharingProgress) : SharingReceiverState
    object Completed : SharingReceiverState
    data class Error(val message: String) : SharingReceiverState
    object Rejected : SharingReceiverState
    object Cancelled : SharingReceiverState
}

data class SharingProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val currentChunk: Int,
    val totalChunks: Int,
)

interface LocalFilePicker { suspend fun pick(): ImportSource? }
interface VaultFileOpener { suspend fun open(vault: VaultHandle, file: VaultItem.File); fun cleanup() = Unit }

// Sharing UI interfaces
interface SharingFilePicker { suspend fun pickFile(): SharingFilePickerResult? }
data class SharingFilePickerResult(val path: String, val displayName: String, val size: Long, val mimeType: String?)

interface SharingReferenceCodeInput { suspend fun getReferenceCode(): String? }
interface SharingNotificationPresenter { fun showIncomingTransfer(senderIdentity: String, fileName: String, fileSize: Long) }
