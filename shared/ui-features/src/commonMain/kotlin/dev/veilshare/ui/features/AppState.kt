package dev.veilshare.ui.features

import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface RootState {
    data object Initializing : RootState
    data class FirstRun(val busy: Boolean = false, val error: String? = null) : RootState
    data class Locked(val busy: Boolean = false, val error: String? = null) : RootState
    data class Unlocked(val browser: BrowserState) : RootState
    data class Fatal(val message: String) : RootState
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

enum class SharingVerificationReason { NEW_PEER, IDENTITY_CHANGED }

sealed interface SharingSenderState {
    data class Preparing(val referenceCode: String? = null, val selectedFile: String? = null) : SharingSenderState
    data class Connecting(val referenceCode: ReferenceCode) : SharingSenderState
    data class VerificationRequired(
        val referenceCode: ReferenceCode,
        val fingerprint: String,
        val reason: SharingVerificationReason,
        val existingAlias: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : SharingSenderState
    data class Sending(val progress: SharingProgress) : SharingSenderState
    data object Completed : SharingSenderState
    data class Error(val message: String, val canRetry: Boolean = true) : SharingSenderState
    data object Cancelled : SharingSenderState
}

sealed interface SharingReceiverState {
    data class Waiting(val referenceCode: ReferenceCode) : SharingReceiverState
    data class Incoming(val senderIdentity: String, val fileName: String, val fileSize: Long) : SharingReceiverState
    data class Receiving(val progress: SharingProgress) : SharingReceiverState
    data object Completed : SharingReceiverState
    data class Error(val message: String) : SharingReceiverState
    data object Rejected : SharingReceiverState
    data object Cancelled : SharingReceiverState
}

data class SharingProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val currentChunk: Int,
    val totalChunks: Int,
)

interface LocalFilePicker { suspend fun pick(): ImportSource? }
interface VaultFileOpener { suspend fun open(vault: VaultHandle, file: VaultItem.File); fun cleanup() = Unit }

/**
 * UI-neutral readable file selected for Sharing V1.
 *
 * Ownership belongs to the controller until it invokes SharingRuntime.send(). From that
 * point the runtime owns the object and must close it exactly once on every terminal path.
 */
interface SharingPickedFile {
    val displayName: String
    val size: Long
    val mimeType: String?
    suspend fun readChunk(offset: Long, size: Int): ByteArray
    suspend fun close()
}

interface SharingFilePicker { suspend fun pickFile(): SharingPickedFile? }

interface SharingReferenceCodeInput { suspend fun getReferenceCode(): String? }
interface SharingNotificationPresenter { fun showIncomingTransfer(senderIdentity: String, fileName: String, fileSize: Long) }

sealed interface SharingRuntimeActivation {
    data class Ready(val referenceCode: ReferenceCode) : SharingRuntimeActivation
    data class Unavailable(val reason: String? = null) : SharingRuntimeActivation
}

sealed interface SharingSendResult {
    data object Completed : SharingSendResult
    data class NeedsVerification(
        val fingerprint: String,
        val reason: SharingVerificationReason = SharingVerificationReason.NEW_PEER,
        val existingAlias: String? = null,
    ) : SharingSendResult
    data class KeyMismatch(val expectedFingerprint: String, val presentedFingerprint: String) : SharingSendResult
    data class Unavailable(val reason: String? = null) : SharingSendResult
    data class Failed(val reason: String? = null) : SharingSendResult
}

sealed interface SharingVerificationResult {
    data object Verified : SharingVerificationResult
    data class Failed(val reason: String? = null) : SharingVerificationResult
}

sealed interface SharingRuntimeEvent {
    data class IncomingOffer(
        val senderIdentity: String,
        val fileName: String,
        val fileSize: Long,
    ) : SharingRuntimeEvent

    data class Receiving(val progress: SharingProgress) : SharingRuntimeEvent
    data object IncomingCompleted : SharingRuntimeEvent
    data class Failed(val reason: String? = null) : SharingRuntimeEvent
    data object Cancelled : SharingRuntimeEvent
}

/**
 * High-level boundary consumed by Compose/controller code. Implementations own all trust,
 * handshake, session-key and transfer-orchestration objects; none of those may leak here.
 */
interface SharingRuntime : AutoCloseable {
    val events: Flow<SharingRuntimeEvent>

    suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation

    /** Takes ownership of [file] immediately and must close it exactly once. */
    suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult

    /** Confirms only the currently pending fingerprint verification; no key material leaves the runtime. */
    suspend fun confirmPendingPeer(alias: String): SharingVerificationResult
    suspend fun dismissPendingPeerVerification()
    suspend fun acceptIncoming()
    suspend fun rejectIncoming()
    suspend fun cancelCurrent()
    suspend fun deactivate()
}

object UnavailableSharingRuntime : SharingRuntime {
    override val events: Flow<SharingRuntimeEvent> = emptyFlow()
    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation =
        SharingRuntimeActivation.Unavailable("Sharing runtime is not configured")

    override suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult {
        try {
            return SharingSendResult.Unavailable("Sharing runtime is not configured")
        } finally {
            file.close()
        }
    }

    override suspend fun confirmPendingPeer(alias: String) =
        SharingVerificationResult.Failed("Sharing runtime is not configured")
    override suspend fun dismissPendingPeerVerification() = Unit
    override suspend fun acceptIncoming() = Unit
    override suspend fun rejectIncoming() = Unit
    override suspend fun cancelCurrent() = Unit
    override suspend fun deactivate() = Unit
    override fun close() = Unit
}
