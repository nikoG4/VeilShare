package dev.veilshare.ui.features

import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem

sealed interface RootState {
    data object Initializing : RootState
    data class FirstRun(val busy: Boolean = false, val error: String? = null) : RootState
    data class Locked(val busy: Boolean = false, val error: String? = null) : RootState
    data class Unlocked(val browser: BrowserState) : RootState
    data class Fatal(val message: String) : RootState
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

interface LocalFilePicker { suspend fun pick(): ImportSource? }
interface VaultFileOpener { suspend fun open(vault: VaultHandle, file: VaultItem.File); fun cleanup() = Unit }
