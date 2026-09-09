package dev.veilshare.core.transfer

import dev.veilshare.core.vault.ImportReadHandle
import dev.veilshare.core.vault.ImportSource

class TransferImportSourceAdapter(
    private val transferImportSource: TransferImportSource,
) : ImportSource {
    override val displayName: String = transferImportSource.displayName
    override val mimeHint: String? = transferImportSource.mimeHint
    override val sizeHint: Long? = transferImportSource.sizeHint

    override suspend fun openRead(): ImportReadHandle {
        val handle = transferImportSource.openRead()
        return TransferImportReadHandleAdapter(handle)
    }
}

private class TransferImportReadHandleAdapter(
    private val handle: TransferImportReadHandle,
) : ImportReadHandle {
    override suspend fun read(maxBytes: Int): ByteArray {
        return handle.read(maxBytes)
    }

    override suspend fun close() {
        handle.close()
    }
}