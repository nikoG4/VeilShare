package dev.veilshare.ui.features

import dev.veilshare.core.transfer.TransferSource

/**
 * Interface for picking a file to share.
 */
interface SharingFilePicker {
    /**
     * Picks a file to share.
     *
     * @return The picked file, or null if the user cancelled or an error occurred.
     */
    suspend fun pickFile(): SharingFilePickerResult?
}

/**
 * Interface representing a file picked for sharing.
 * Extends [TransferSource] to be used directly in transfer operations.
 */
interface SharingPickedFile : TransferSource {
    /**
     * The display name of the file.
     */
    val displayName: String

    /**
     * The MIME type hint of the file, if known.
     */
    val mimeType: String?
}

/**
 * Result of picking a file for sharing.
 * Extends [SharingPickedFile] and adds lifecycle management.
 */
interface SharingFilePickerResult : SharingPickedFile {
    /**
     * Closes the file and releases any associated resources.
     * Must be called exactly once when the file is no longer needed.
     */
    suspend fun close()
}