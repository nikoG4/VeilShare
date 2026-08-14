package dev.veilshare.ui.features

import dev.veilshare.core.vault.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAppController(
    private val service: LocalVaultService,
    private val picker: LocalFilePicker,
    private val opener: VaultFileOpener,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<RootState>(RootState.Initializing)
    val state: StateFlow<RootState> = mutableState.asStateFlow()
    private var active: VaultHandle? = null
    private var importJob: Job? = null

    suspend fun initialize() {
        mutableState.value = when (withContext(workDispatcher) { service.storageState() }) {
            LocalStorageState.EMPTY -> RootState.FirstRun()
            LocalStorageState.READY -> RootState.Locked()
            LocalStorageState.INCOMPLETE -> RootState.Fatal("La configuración está incompleta. No se modificó ningún dato.")
            LocalStorageState.CORRUPT -> RootState.Fatal("No se pudo verificar el almacenamiento local.")
        }
    }

    fun setup(primary: CharArray, confirmation: CharArray, alternate: CharArray, alternateConfirmation: CharArray) {
        val state = mutableState.value as? RootState.FirstRun ?: return
        if (state.busy) return
        val error = when {
            primary.size < 4 || alternate.size < 4 -> "Usa códigos de al menos 4 caracteres."
            !primary.contentEquals(confirmation) || !alternate.contentEquals(alternateConfirmation) -> "Las confirmaciones no coinciden."
            primary.contentEquals(alternate) -> "Los códigos deben ser diferentes."
            else -> null
        }
        if (error != null) { mutableState.value = RootState.FirstRun(error = error); clear(primary, confirmation, alternate, alternateConfirmation); return }
        mutableState.value = RootState.FirstRun(busy = true)
        scope.launch {
            try { withContext(workDispatcher) { service.createPair(primary, alternate) }; mutableState.value = RootState.Locked() }
            catch (_: Exception) { mutableState.value = RootState.Fatal("No se pudo completar la configuración. Reinicia para verificar el estado guardado.") }
            finally { clear(primary, confirmation, alternate, alternateConfirmation) }
        }
    }

    fun unlock(credential: CharArray) {
        val state = mutableState.value as? RootState.Locked ?: return
        if (state.busy) { credential.fill('\u0000'); return }
        mutableState.value = RootState.Locked(busy = true)
        scope.launch {
            try {
                when (val result = withContext(workDispatcher) { service.unlock(credential) }) {
                    LocalUnlockResult.InvalidCredential -> mutableState.value = RootState.Locked(error = "No se pudo continuar.")
                    LocalUnlockResult.Corrupt -> mutableState.value = RootState.Fatal("No se pudo verificar el almacenamiento local. No se eliminó ningún dato.")
                    is LocalUnlockResult.Ready -> { active?.close(); active = result.vault; mutableState.value = RootState.Unlocked(browserState(result.vault, null)) }
                }
            } catch (_: Exception) { mutableState.value = RootState.Locked(error = "No se pudo continuar.") }
            finally { credential.fill('\u0000') }
        }
    }

    fun enterFolder(id: String) = updateBrowser { browserState(it, VaultDirectoryId(id)) }
    fun navigateTo(id: String?) = updateBrowser { browserState(it, id?.let(::VaultDirectoryId)) }
    fun createFolder(name: String) = mutate("Creando carpeta…") { vault, current -> vault.createDirectory(current.currentFolderId?.let(::VaultDirectoryId), name) }
    fun rename(id: String, name: String) = mutate("Guardando…") { vault, _ -> vault.rename(VaultItemId(id), name) }

    fun importFile() {
        val state = mutableState.value as? RootState.Unlocked ?: return
        if (importJob?.isActive == true || state.browser.operation !is BrowserOperation.Idle) return
        val folder = state.browser.currentFolderId?.let(::VaultDirectoryId)
        importJob = scope.launch {
            try {
                val source = picker.pick() ?: return@launch
                withContext(workDispatcher) { activeOrThrow().import(source, folder) { progress ->
                    val current = (mutableState.value as? RootState.Unlocked)?.browser ?: return@import
                    val operation = when (progress) {
                        ImportProgress.Preparing -> BrowserOperation.Busy("Preparando…")
                        is ImportProgress.Encrypting -> BrowserOperation.Importing(progress.bytes, progress.total)
                        ImportProgress.Committing -> BrowserOperation.Busy("Guardando…")
                        is ImportProgress.Complete -> BrowserOperation.Busy("Finalizando…")
                    }
                    mutableState.value = RootState.Unlocked(current.copy(operation = operation, message = null))
                } }
                refreshAuthoritative(folder, "Importación completada.")
            } catch (cancelled: CancellationException) {
                // Narrow non-cancellable region: authenticated read only. It never performs GC
                // and exists solely to resolve whether CatalogDurable was crossed.
                withContext(NonCancellable + workDispatcher) { active?.reload() }
                refreshFromMemory(folder, "Importación cancelada.")
                throw cancelled
            } catch (_: Exception) { refreshAuthoritative(folder, "No se pudo importar el archivo.") }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun openFile(id: String) {
        val vault = active ?: return
        val file = vault.find(VaultItemId(id)) as? VaultItem.File ?: return
        scope.launch {
            setOperation(BrowserOperation.Busy("Abriendo…"))
            try { withContext(workDispatcher) { opener.open(vault, file) }; setOperation(BrowserOperation.Idle) }
            catch (_: Exception) { setMessage("No se pudo abrir este archivo. Puede estar dañado.") }
        }
    }

    fun delete(id: String) = mutate("Eliminando…") { vault, _ ->
        when (vault.find(VaultItemId(id))) {
            is VaultItem.File -> vault.deleteFile(VaultItemId(id))
            is VaultItem.Directory -> vault.deleteEmptyDirectory(VaultItemId(id))
            null -> Unit
        }
    }

    fun changeCredential(newCredential: CharArray, confirmation: CharArray) {
        if (!newCredential.contentEquals(confirmation) || newCredential.size < 4) { clear(newCredential, confirmation); setMessage("Verifica el nuevo código y su confirmación."); return }
        val vault = active ?: run { clear(newCredential, confirmation); return }
        scope.launch {
            setOperation(BrowserOperation.Busy("Actualizando código…"))
            try { withContext(workDispatcher) { vault.changeCredential(newCredential) }; lock() }
            catch (_: Exception) { lock("No se pudo verificar el cambio. Ingresa tu código nuevamente.") }
            finally { clear(newCredential, confirmation) }
        }
    }

    fun lock(message: String? = null) {
        importJob?.cancel(); importJob = null; active?.close(); active = null; opener.cleanup()
        mutableState.value = RootState.Locked(error = message)
    }

    override fun close() = lock()

    private fun mutate(label: String, operation: suspend (VaultHandle, BrowserState) -> Unit) {
        val state = mutableState.value as? RootState.Unlocked ?: return
        if (state.browser.operation !is BrowserOperation.Idle) return
        scope.launch {
            setOperation(BrowserOperation.Busy(label))
            try { withContext(workDispatcher) { operation(activeOrThrow(), state.browser) }; refreshAuthoritative(state.browser.currentFolderId?.let(::VaultDirectoryId), null) }
            catch (_: Exception) { refreshAuthoritative(state.browser.currentFolderId?.let(::VaultDirectoryId), "No se pudo completar la operación.") }
        }
    }

    private fun updateBrowser(block: (VaultHandle) -> BrowserState) { active?.let { mutableState.value = RootState.Unlocked(block(it)) } }
    private suspend fun refreshAuthoritative(folder: VaultDirectoryId?, message: String?) {
        val vault=active?:return
        try { withContext(workDispatcher) { vault.reload() } } catch (_: Exception) { lock("No se pudo verificar el almacenamiento local."); return }
        refreshFromMemory(folder,message)
    }
    private fun refreshFromMemory(folder: VaultDirectoryId?, message: String?) { active?.let { mutableState.value = RootState.Unlocked(browserState(it, folder).copy(message = message)) } }
    private fun browserState(vault: VaultHandle, folder: VaultDirectoryId?): BrowserState {
        val crumbs = mutableListOf(Breadcrumb(null, "Archivos")); val lineage = mutableListOf<VaultItem.Directory>()
        var cursor = folder?.let { vault.find(VaultItemId(it.value)) as? VaultItem.Directory }
        while (cursor != null) { lineage += cursor; cursor = cursor.parentId?.let { vault.find(VaultItemId(it.value)) as? VaultItem.Directory } }
        lineage.asReversed().forEach { crumbs += Breadcrumb(it.id.value, it.displayName) }
        return BrowserState(folder?.value, crumbs, vault.items(folder).map {
            when (it) { is VaultItem.Directory -> BrowserItem(it.id.value, it.displayName, true); is VaultItem.File -> BrowserItem(it.id.value, it.displayName, false, it.size, it.mimeType) }
        })
    }
    private fun setOperation(operation: BrowserOperation) { val state=mutableState.value as? RootState.Unlocked?:return; mutableState.value=RootState.Unlocked(state.browser.copy(operation=operation,message=null)) }
    private fun setMessage(message: String) { val state=mutableState.value as? RootState.Unlocked?:return; mutableState.value=RootState.Unlocked(state.browser.copy(operation=BrowserOperation.Idle,message=message)) }
    private fun activeOrThrow() = checkNotNull(active) { "Vault is locked" }
    private fun clear(vararg values: CharArray) = values.forEach { it.fill('\u0000') }
}
