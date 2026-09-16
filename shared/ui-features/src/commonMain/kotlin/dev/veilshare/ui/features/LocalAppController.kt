package dev.veilshare.ui.features

import dev.veilshare.core.model.ReferenceCodes
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAppController(
    private val service: LocalVaultService,
    private val picker: LocalFilePicker,
    private val opener: VaultFileOpener,
    private val sharingFilePicker: SharingFilePicker,
    private val sharingRuntime: SharingRuntime,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<RootState>(RootState.Initializing)
    val state: StateFlow<RootState> = mutableState.asStateFlow()
    private val mutableContacts = MutableStateFlow<List<SharingContactSummary>>(emptyList())
    val contacts: StateFlow<List<SharingContactSummary>> = mutableContacts.asStateFlow()
    private var active: VaultHandle? = null
    private var activePersonaId: dev.veilshare.core.model.LocalPersonaId? = null
    private var importJob: Job? = null
    private var sharingJob: Job? = null
    private var sharingEventsJob: Job? = null
    private var selectedSharingFile: SharingPickedFile? = null
    private var ownSharingReferenceCode: dev.veilshare.core.model.ReferenceCode? = null

    suspend fun initialize() {
        ensureSharingEvents()
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
                    is LocalUnlockResult.Ready -> {
                        active?.close()
                        active = result.vault
                        activePersonaId = result.personaId
                        mutableState.value = RootState.Unlocked(browserState(result.vault, null))
                        ownSharingReferenceCode = result.personaId?.let { personaId ->
                            try {
                                when (val activation = withContext(workDispatcher) {
                                    sharingRuntime.activate(personaId, result.vault)
                                }) {
                                    is SharingRuntimeActivation.Ready -> activation.referenceCode
                                    is SharingRuntimeActivation.Unavailable -> null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        refreshContactsAsync()
                    }
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
        importJob?.cancel(); importJob = null
        sharingJob?.cancel(); sharingJob = null
        closeSelectedSharingFileAsync()
        ownSharingReferenceCode = null
        activePersonaId = null
        mutableContacts.value = emptyList()
        scope.launch {
            withContext(NonCancellable + workDispatcher) {
                runCatching { sharingRuntime.dismissPendingPeerVerification() }
                runCatching { sharingRuntime.cancelCurrent() }
                runCatching { sharingRuntime.deactivate() }
            }
        }
        active?.close(); active = null; opener.cleanup()
        mutableState.value = RootState.Locked(error = message)
    }

    override fun close() {
        lock()
        sharingEventsJob?.cancel()
        sharingEventsJob = null
        sharingRuntime.close()
    }

    fun startSharingSender() {
        if (mutableState.value !is RootState.Unlocked) return
        mutableState.value = RootState.SharingSender(SharingSenderState.Preparing())
    }

    fun selectSharingFile() {
        if ((mutableState.value as? RootState.SharingSender)?.state !is SharingSenderState.Preparing) return
        scope.launch {
            val result = try { sharingFilePicker.pickFile() } catch (_: Exception) { null } ?: return@launch
            val current = (mutableState.value as? RootState.SharingSender)?.state as? SharingSenderState.Preparing
            if (current == null) {
                withContext(NonCancellable + workDispatcher) { result.close() }
                return@launch
            }
            val previous = selectedSharingFile
            selectedSharingFile = result
            if (previous != null) withContext(NonCancellable + workDispatcher) { previous.close() }
            mutableState.value = RootState.SharingSender(current.copy(selectedFile = result.displayName))
        }
    }

    fun enterSharingReferenceCode(referenceCode: String) {
        val preparing = (mutableState.value as? RootState.SharingSender)?.state as? SharingSenderState.Preparing ?: return
        mutableState.value = RootState.SharingSender(preparing.copy(referenceCode = referenceCode))
    }

    fun startSharingTransfer() {
        val preparing = (mutableState.value as? RootState.SharingSender)?.state as? SharingSenderState.Preparing ?: return
        if (sharingJob?.isActive == true) return
        val file = selectedSharingFile ?: return
        val referenceCode = try {
            ReferenceCodes.parse(preparing.referenceCode ?: return)
        } catch (_: IllegalArgumentException) {
            mutableState.value = RootState.SharingSender(SharingSenderState.Error("El código de referencia no es válido."))
            return
        }

        selectedSharingFile = null
        mutableState.value = RootState.SharingSender(SharingSenderState.Connecting(referenceCode))
        sharingJob = scope.launch {
            try {
                val presence = withContext(workDispatcher) { ensureSharingPresence() }
                if (presence !is SharingRuntimeActivation.Ready) {
                    withContext(NonCancellable + workDispatcher) { runCatching { file.close() } }
                    mutableState.value = RootState.SharingSender(
                        SharingSenderState.Error(
                            (presence as? SharingRuntimeActivation.Unavailable)?.reason
                                ?: "No se pudo restablecer el canal de compartir.",
                        ),
                    )
                    return@launch
                }
                // Runtime owns the file from this point and must close it exactly once.
                val result = withContext(workDispatcher) {
                    sharingRuntime.send(referenceCode, file) { progress ->
                        mutableState.value = RootState.SharingSender(SharingSenderState.Sending(progress))
                    }
                }
                mutableState.value = when (result) {
                    SharingSendResult.Completed -> RootState.SharingSender(SharingSenderState.Completed)
                    is SharingSendResult.NeedsVerification -> RootState.SharingSender(
                        SharingSenderState.VerificationRequired(
                            referenceCode = referenceCode,
                            fingerprint = result.fingerprint,
                            reason = result.reason,
                            existingAlias = result.existingAlias,
                        ),
                    )
                    is SharingSendResult.KeyMismatch -> RootState.SharingSender(
                        SharingSenderState.Error("La clave del contacto no coincide con la identidad guardada. No se enviará nada.", canRetry = false),
                    )
                    is SharingSendResult.Unavailable -> RootState.SharingSender(
                        SharingSenderState.Error(result.reason ?: "Compartir no está disponible.", canRetry = false),
                    )
                    is SharingSendResult.Failed -> RootState.SharingSender(
                        SharingSenderState.Error(result.reason ?: "No se pudo enviar el archivo."),
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.cancelCurrent() } }
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingSender(SharingSenderState.Error("No se pudo enviar el archivo."))
            } finally {
                sharingJob = null
            }
        }
    }

    fun confirmSharingPeer(alias: String) {
        val verification = (mutableState.value as? RootState.SharingSender)?.state as? SharingSenderState.VerificationRequired ?: return
        if (verification.busy || sharingJob?.isActive == true) return
        val safeAlias = when (verification.reason) {
            SharingVerificationReason.NEW_PEER -> alias.trim()
            SharingVerificationReason.IDENTITY_CHANGED -> verification.existingAlias ?: alias.trim()
        }
        if (safeAlias.isBlank()) {
            mutableState.value = RootState.SharingSender(verification.copy(error = "Escribe un nombre para este contacto."))
            return
        }
        mutableState.value = RootState.SharingSender(verification.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                when (val result = withContext(workDispatcher) { sharingRuntime.confirmPendingPeer(safeAlias) }) {
                    SharingVerificationResult.Verified -> {
                        mutableState.value = RootState.SharingSender(
                            SharingSenderState.Preparing(referenceCode = verification.referenceCode.value),
                        )
                        refreshContactsAsync()
                    }
                    is SharingVerificationResult.Failed -> mutableState.value = RootState.SharingSender(
                        verification.copy(busy = false, error = result.reason ?: "No se pudo guardar la verificación."),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingSender(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissSharingVerification() {
        val verification = (mutableState.value as? RootState.SharingSender)?.state as? SharingSenderState.VerificationRequired ?: return
        if (verification.busy) return
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        mutableState.value = RootState.SharingSender(
            SharingSenderState.Preparing(referenceCode = verification.referenceCode.value),
        )
    }

    fun startContactVerification() {
        if (mutableState.value !is RootState.Unlocked) return
        mutableState.value = RootState.SharingContactVerification(SharingContactVerificationState.Entering())
    }

    fun enterContactVerificationReferenceCode(referenceCode: String) {
        val entering = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.Entering ?: return
        if (entering.busy) return
        mutableState.value = RootState.SharingContactVerification(
            entering.copy(referenceCode = referenceCode, error = null),
        )
    }

    fun lookupContactForVerification() {
        val entering = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.Entering ?: return
        if (entering.busy || sharingJob?.isActive == true) return
        val referenceCode = try {
            ReferenceCodes.parse(entering.referenceCode)
        } catch (_: IllegalArgumentException) {
            mutableState.value = RootState.SharingContactVerification(
                entering.copy(error = "El código de referencia no es válido."),
            )
            return
        }
        mutableState.value = RootState.SharingContactVerification(entering.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                val presence = withContext(workDispatcher) { ensureSharingPresence() }
                if (presence !is SharingRuntimeActivation.Ready) {
                    mutableState.value = RootState.SharingContactVerification(
                        entering.copy(
                            referenceCode = referenceCode.value,
                            busy = false,
                            error = (presence as? SharingRuntimeActivation.Unavailable)?.reason
                                ?: "No se pudo restablecer el canal de compartir.",
                        ),
                    )
                    return@launch
                }
                mutableState.value = when (val result = withContext(workDispatcher) { sharingRuntime.inspectPeer(referenceCode) }) {
                    is SharingPeerLookupResult.Trusted -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Completed(result.alias),
                    )
                    is SharingPeerLookupResult.NeedsVerification -> RootState.SharingContactVerification(
                        SharingContactVerificationState.VerificationRequired(
                            referenceCode = referenceCode,
                            fingerprint = result.fingerprint,
                            reason = result.reason,
                            existingAlias = result.existingAlias,
                        ),
                    )
                    is SharingPeerLookupResult.KeyMismatch -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(
                            referenceCode = referenceCode.value,
                            error = "La clave presentada no coincide con la identidad ya guardada. No se modificó la confianza.",
                        ),
                    )
                    is SharingPeerLookupResult.Unavailable -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(referenceCode.value, error = result.reason ?: "El contacto no está disponible."),
                    )
                    is SharingPeerLookupResult.Failed -> RootState.SharingContactVerification(
                        SharingContactVerificationState.Entering(referenceCode.value, error = result.reason ?: "No se pudo comprobar el contacto."),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    SharingContactVerificationState.Entering(referenceCode.value, error = "No se pudo comprobar el contacto."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun confirmContactVerification(alias: String) {
        val verification = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.VerificationRequired ?: return
        if (verification.busy || sharingJob?.isActive == true) return
        val safeAlias = when (verification.reason) {
            SharingVerificationReason.NEW_PEER -> alias.trim()
            SharingVerificationReason.IDENTITY_CHANGED -> verification.existingAlias ?: alias.trim()
        }
        if (safeAlias.isBlank()) {
            mutableState.value = RootState.SharingContactVerification(
                verification.copy(error = "Escribe un nombre para este contacto."),
            )
            return
        }
        mutableState.value = RootState.SharingContactVerification(verification.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                when (val result = withContext(workDispatcher) { sharingRuntime.confirmPendingPeer(safeAlias) }) {
                    SharingVerificationResult.Verified -> {
                        mutableState.value = RootState.SharingContactVerification(
                            SharingContactVerificationState.Completed(safeAlias),
                        )
                        refreshContactsAsync()
                    }
                    is SharingVerificationResult.Failed -> mutableState.value = RootState.SharingContactVerification(
                        verification.copy(busy = false, error = result.reason ?: "No se pudo guardar la verificación."),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissContactVerification() {
        val verification = (mutableState.value as? RootState.SharingContactVerification)?.state as? SharingContactVerificationState.VerificationRequired ?: return
        if (verification.busy) return
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        mutableState.value = RootState.SharingContactVerification(
            SharingContactVerificationState.Entering(referenceCode = verification.referenceCode.value),
        )
    }

    fun finishContactVerification() {
        sharingJob?.cancel(); sharingJob = null
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        returnToBrowser()
    }

    fun cancelSharing() {
        sharingJob?.cancel(); sharingJob = null
        closeSelectedSharingFileAsync()
        scope.launch {
            withContext(NonCancellable + workDispatcher) {
                runCatching { sharingRuntime.dismissPendingPeerVerification() }
                runCatching { sharingRuntime.cancelCurrent() }
            }
        }
        val current = mutableState.value
        mutableState.value = when (current) {
            is RootState.SharingSender -> RootState.SharingSender(SharingSenderState.Cancelled)
            is RootState.SharingReceiver -> RootState.SharingReceiver(SharingReceiverState.Cancelled)
            else -> current
        }
    }

    fun finishSharing() {
        closeSelectedSharingFileAsync()
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { sharingRuntime.dismissPendingPeerVerification() } } }
        returnToBrowser()
    }

    fun startSharingReceiver() {
        if (mutableState.value !is RootState.Unlocked) return
        if (sharingJob?.isActive == true) return

        // If activation during unlock already produced a reference code, move into the
        // receiver state synchronously so a fast incoming OFFER cannot race with refresh.
        ownSharingReferenceCode?.let { referenceCode ->
            mutableState.value = RootState.SharingReceiver(SharingReceiverState.Waiting(referenceCode))
        }

        sharingJob = scope.launch {
            try {
                when (val presence = withContext(workDispatcher) { ensureSharingPresence() }) {
                    is SharingRuntimeActivation.Ready -> {
                        ownSharingReferenceCode = presence.referenceCode
                        val current = mutableState.value
                        if (current is RootState.Unlocked ||
                            (current is RootState.SharingReceiver && current.state is SharingReceiverState.Waiting)
                        ) {
                            mutableState.value = RootState.SharingReceiver(SharingReceiverState.Waiting(presence.referenceCode))
                        }
                    }
                    is SharingRuntimeActivation.Unavailable -> {
                        val current = mutableState.value
                        if (current is RootState.Unlocked ||
                            (current is RootState.SharingReceiver && current.state is SharingReceiverState.Waiting)
                        ) {
                            mutableState.value = RootState.SharingReceiver(
                                SharingReceiverState.Error(presence.reason ?: "No se pudo restablecer el canal de compartir."),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val current = mutableState.value
                if (current is RootState.Unlocked ||
                    (current is RootState.SharingReceiver && current.state is SharingReceiverState.Waiting)
                ) {
                    mutableState.value = RootState.SharingReceiver(
                        SharingReceiverState.Error("No se pudo restablecer el canal de compartir."),
                    )
                }
            } finally {
                sharingJob = null
            }
        }
    }

    fun acceptIncomingSharing() {
        if ((mutableState.value as? RootState.SharingReceiver)?.state !is SharingReceiverState.Incoming) return
        if (sharingJob?.isActive == true) return
        sharingJob = scope.launch {
            try { withContext(workDispatcher) { sharingRuntime.acceptIncoming() } }
            catch (_: Exception) { mutableState.value = RootState.SharingReceiver(SharingReceiverState.Error("No se pudo aceptar el archivo.")) }
            finally { sharingJob = null }
        }
    }

    fun rejectIncomingSharing() {
        if ((mutableState.value as? RootState.SharingReceiver)?.state !is SharingReceiverState.Incoming) return
        if (sharingJob?.isActive == true) return
        sharingJob = scope.launch {
            try { withContext(workDispatcher) { sharingRuntime.rejectIncoming() }; mutableState.value = RootState.SharingReceiver(SharingReceiverState.Rejected) }
            catch (_: Exception) { mutableState.value = RootState.SharingReceiver(SharingReceiverState.Error("No se pudo rechazar el archivo.")) }
            finally { sharingJob = null }
        }
    }

    private fun ensureSharingEvents() {
        if (sharingEventsJob != null) return
        sharingEventsJob = scope.launch {
            sharingRuntime.events.collect { event ->
                val receiver = mutableState.value as? RootState.SharingReceiver ?: return@collect
                mutableState.value = when (event) {
                    is SharingRuntimeEvent.IncomingOffer -> RootState.SharingReceiver(
                        SharingReceiverState.Incoming(event.senderIdentity, event.fileName, event.fileSize),
                    )
                    is SharingRuntimeEvent.Receiving -> RootState.SharingReceiver(SharingReceiverState.Receiving(event.progress))
                    SharingRuntimeEvent.IncomingCompleted -> RootState.SharingReceiver(SharingReceiverState.Completed)
                    is SharingRuntimeEvent.Failed -> RootState.SharingReceiver(
                        SharingReceiverState.Error(event.reason ?: "La transferencia falló."),
                    )
                    SharingRuntimeEvent.Cancelled -> RootState.SharingReceiver(SharingReceiverState.Cancelled)
                }
            }
        }
    }

    private suspend fun ensureSharingPresence(): SharingRuntimeActivation {
        val vault = active ?: return SharingRuntimeActivation.Unavailable("La bóveda está bloqueada.")
        val personaId = activePersonaId
            ?: return SharingRuntimeActivation.Unavailable("Compartir no está configurado para esta bóveda.")
        return try {
            val activation = if (ownSharingReferenceCode == null) {
                sharingRuntime.activate(personaId, vault)
            } else {
                sharingRuntime.refreshPresence()
            }
            if (activation is SharingRuntimeActivation.Ready) ownSharingReferenceCode = activation.referenceCode
            activation
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SharingRuntimeActivation.Unavailable("No se pudo restablecer el canal de compartir.")
        }
    }

    private fun refreshContactsAsync() {
        if (active == null) return
        scope.launch {
            mutableContacts.value = try {
                withContext(workDispatcher) { sharingRuntime.trustedContacts() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun closeSelectedSharingFileAsync() {
        val selected = selectedSharingFile ?: return
        selectedSharingFile = null
        scope.launch { withContext(NonCancellable + workDispatcher) { runCatching { selected.close() } } }
    }

    private fun returnToBrowser() {
        val activeVault = active
        if (activeVault != null) mutableState.value = RootState.Unlocked(browserState(activeVault, null))
        else mutableState.value = RootState.Locked()
    }

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
        val currentItems = vault.items(folder).map(::browserItem)
        return BrowserState(
            currentFolderId = folder?.value,
            breadcrumbs = crumbs,
            items = currentItems,
            mediaItems = collectMedia(vault),
        )
    }

    private fun browserItem(item: VaultItem): BrowserItem = when (item) {
        is VaultItem.Directory -> BrowserItem(item.id.value, item.displayName, true)
        is VaultItem.File -> BrowserItem(item.id.value, item.displayName, false, item.size, item.mimeType)
    }

    private fun collectMedia(vault: VaultHandle): List<BrowserItem> {
        val result = mutableListOf<BrowserItem>()
        val visitedDirectories = mutableSetOf<String>()
        fun walk(parent: VaultDirectoryId?) {
            vault.items(parent).forEach { item ->
                when (item) {
                    is VaultItem.Directory -> if (visitedDirectories.add(item.id.value)) {
                        walk(VaultDirectoryId(item.id.value))
                    }
                    is VaultItem.File -> if (item.isGalleryMedia()) result += browserItem(item)
                }
            }
        }
        walk(null)
        return result.sortedBy { it.name.lowercase() }
    }

    private fun VaultItem.File.isGalleryMedia(): Boolean {
        val type = mimeType?.lowercase()
        if (type?.startsWith("image/") == true || type?.startsWith("video/") == true) return true
        return displayName.substringAfterLast('.', "").lowercase() in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp",
            "mp4", "m4v", "mov", "webm", "mkv", "avi",
        )
    }

    private fun setOperation(operation: BrowserOperation) { val state=mutableState.value as? RootState.Unlocked?:return; mutableState.value=RootState.Unlocked(state.browser.copy(operation=operation,message=null)) }
    private fun setMessage(message: String) { val state=mutableState.value as? RootState.Unlocked?:return; mutableState.value=RootState.Unlocked(state.browser.copy(operation=BrowserOperation.Idle,message=message)) }
    private fun activeOrThrow() = checkNotNull(active) { "Vault is locked" }
    private fun clear(vararg values: CharArray) = values.forEach { it.fill('\u0000') }
}
