from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, content):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")

def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"anchor not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise RuntimeError(f"anchor not unique in {path}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))

def replace_between(path, start, end, replacement):
    text = read(path)
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f"start anchor not found in {path}: {start!r}")
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f"end anchor not found in {path}: {end!r}")
    write(path, text[:a] + replacement + "\n\n" + text[b:])

# ---------------------------------------------------------------------------
# Core import contract: optional source deletion occurs only after durable commit.
# ---------------------------------------------------------------------------
import_coordinator = "shared/core-vault/src/commonMain/kotlin/dev/veilshare/core/vault/ImportCoordinator.kt"
replace_once(
    import_coordinator,
    '''interface ImportSource {
    val displayName: String
    val mimeHint: String?
    val sizeHint: Long?
    suspend fun openRead(): ImportReadHandle
}''',
    '''interface ImportSource {
    val displayName: String
    val mimeHint: String?
    val sizeHint: Long?
    /**
     * Explicit one-shot request from the caller. Implementations must not infer deletion.
     * Deletion is attempted only after blob + encrypted catalog + journal commit are durable.
     */
    val deleteOriginalRequested: Boolean get() = false
    val originalDeleted: Boolean get() = false
    suspend fun openRead(): ImportReadHandle
    suspend fun deleteOriginalAfterCommit(): Boolean = false
}''',
)
replace_once(
    import_coordinator,
    '''            journal.append(ImportJournalRecord(txId, ImportStage.COMMITTED, item.id.value, blobId, namespace))
            fault(ImportFaultPoint.Committed)
            journal.remove(txId)
            progress(ImportProgress.Complete(item))
            item''',
    '''            journal.append(ImportJournalRecord(txId, ImportStage.COMMITTED, item.id.value, blobId, namespace))
            fault(ImportFaultPoint.Committed)
            journal.remove(txId)
            // Source deletion is deliberately outside the encrypted commit transaction.
            // A provider refusal/failure must never roll back or invalidate the durable import.
            if (source.deleteOriginalRequested) runCatching { source.deleteOriginalAfterCommit() }
            progress(ImportProgress.Complete(item))
            item''',
)

# ---------------------------------------------------------------------------
# UI/runtime contract extensions: media index, contacts and quick unlock boundary.
# ---------------------------------------------------------------------------
app_state = "shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/AppState.kt"
replace_once(
    app_state,
    '''data class BrowserState(
    val currentFolderId: String? = null,
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb(null, "Archivos")),
    val items: List<BrowserItem> = emptyList(),
    val operation: BrowserOperation = BrowserOperation.Idle,
    val message: String? = null,
)''',
    '''data class BrowserState(
    val currentFolderId: String? = null,
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb(null, "Archivos")),
    val items: List<BrowserItem> = emptyList(),
    /** All image/video files in this vault, recursively indexed for the Gallery tab. */
    val mediaItems: List<BrowserItem> = emptyList(),
    val operation: BrowserOperation = BrowserOperation.Idle,
    val message: String? = null,
)''',
)
replace_once(
    app_state,
    '''interface LocalFilePicker { suspend fun pick(): ImportSource? }
interface VaultFileOpener''',
    '''interface LocalFilePicker {
    suspend fun pick(): ImportSource?
    /** Applies to the next interactive import only. Unsupported platforms may ignore it. */
    fun setDeleteOriginalAfterImport(enabled: Boolean) = Unit
}
interface VaultFileOpener''',
)
replace_once(
    app_state,
    '''interface SharingNotificationPresenter { fun showIncomingTransfer(senderIdentity: String, fileName: String, fileSize: Long) }

sealed interface SharingRuntimeActivation''',
    '''interface SharingNotificationPresenter { fun showIncomingTransfer(senderIdentity: String, fileName: String, fileSize: Long) }

data class SharingContactSummary(
    val alias: String,
    val fingerprint: String,
    val referenceCode: String? = null,
)

/**
 * Platform boundary for optional biometric quick unlock. PIN unlock remains authoritative
 * and always available; implementations must persist only protected credential material.
 */
interface QuickUnlockProvider {
    val available: Boolean
    val hasCredential: Boolean
    fun stageEnrollment(credential: CharArray)
    fun discardPendingEnrollment()
    suspend fun completePendingEnrollment(): Boolean
    suspend fun requestCredential(): CharArray?
    fun clearCredential()
}

object UnavailableQuickUnlockProvider : QuickUnlockProvider {
    override val available = false
    override val hasCredential = false
    override fun stageEnrollment(credential: CharArray) = Unit
    override fun discardPendingEnrollment() = Unit
    override suspend fun completePendingEnrollment() = false
    override suspend fun requestCredential(): CharArray? = null
    override fun clearCredential() = Unit
}

sealed interface SharingRuntimeActivation''',
)
replace_once(
    app_state,
    '''    suspend fun refreshPresence(): SharingRuntimeActivation

    /** Performs only LOOKUP + trust evaluation.''',
    '''    suspend fun refreshPresence(): SharingRuntimeActivation

    /** Snapshot of explicitly verified/pinned contacts for Contacts UI. */
    suspend fun trustedContacts(): List<SharingContactSummary> = emptyList()

    /** Performs only LOOKUP + trust evaluation.''',
)

# ---------------------------------------------------------------------------
# Controller: resilient sharing activation, contacts, biometric opt-in, gallery.
# ---------------------------------------------------------------------------
controller = "shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/LocalAppController.kt"
replace_once(
    controller,
    '''    private val sharingRuntime: SharingRuntime,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {''',
    '''    private val sharingRuntime: SharingRuntime,
    private val scope: CoroutineScope,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val quickUnlock: QuickUnlockProvider = UnavailableQuickUnlockProvider,
) : AutoCloseable {''',
)
replace_once(
    controller,
    '''    val state: StateFlow<RootState> = mutableState.asStateFlow()
    private var active: VaultHandle? = null''',
    '''    val state: StateFlow<RootState> = mutableState.asStateFlow()
    private val mutableContacts = MutableStateFlow<List<SharingContactSummary>>(emptyList())
    val contacts: StateFlow<List<SharingContactSummary>> = mutableContacts.asStateFlow()
    private var active: VaultHandle? = null
    private var activePersonaId: dev.veilshare.core.model.LocalPersonaId? = null
    private var deleteOriginalAfterImport = false''',
)
replace_between(
    controller,
    "    fun unlock(credential: CharArray)",
    "    fun enterFolder",
    '''    fun unlock(credential: CharArray, enrollQuickUnlock: Boolean = false) {
        val state = mutableState.value as? RootState.Locked ?: run {
            credential.fill('\u0000')
            return
        }
        if (state.busy) {
            credential.fill('\u0000')
            return
        }
        if (enrollQuickUnlock) quickUnlock.stageEnrollment(credential) else quickUnlock.discardPendingEnrollment()
        mutableState.value = RootState.Locked(busy = true)
        scope.launch {
            try {
                when (val result = withContext(workDispatcher) { service.unlock(credential) }) {
                    LocalUnlockResult.InvalidCredential -> {
                        quickUnlock.discardPendingEnrollment()
                        mutableState.value = RootState.Locked(error = "No se pudo continuar.")
                    }
                    LocalUnlockResult.Corrupt -> {
                        quickUnlock.discardPendingEnrollment()
                        mutableState.value = RootState.Fatal("No se pudo verificar el almacenamiento local. No se eliminó ningún dato.")
                    }
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
                        if (enrollQuickUnlock) runCatching { quickUnlock.completePendingEnrollment() }
                    }
                }
            } catch (_: Exception) {
                quickUnlock.discardPendingEnrollment()
                mutableState.value = RootState.Locked(error = "No se pudo continuar.")
            } finally {
                credential.fill('\u0000')
            }
        }
    }

    val quickUnlockAvailable: Boolean get() = quickUnlock.available
    val quickUnlockEnrolled: Boolean get() = quickUnlock.hasCredential

    fun unlockWithQuickUnlock() {
        val state = mutableState.value as? RootState.Locked ?: return
        if (state.busy || !quickUnlock.available || !quickUnlock.hasCredential) return
        mutableState.value = RootState.Locked(busy = true)
        scope.launch {
            val credential = try { quickUnlock.requestCredential() } catch (_: Exception) { null }
            if (credential == null) {
                mutableState.value = RootState.Locked(error = "No se pudo usar el desbloqueo biométrico. Usa tu código.")
                return@launch
            }
            mutableState.value = RootState.Locked()
            unlock(credential)
        }
    }

    fun setDeleteOriginalAfterImport(enabled: Boolean) {
        deleteOriginalAfterImport = enabled
    }

''',
)
replace_once(
    controller,
    '''        importJob = scope.launch {
            try {
                val source = picker.pick() ?: return@launch''',
    '''        importJob = scope.launch {
            try {
                picker.setDeleteOriginalAfterImport(deleteOriginalAfterImport)
                deleteOriginalAfterImport = false
                val source = picker.pick() ?: return@launch''',
)
replace_once(
    controller,
    '''        ownSharingReferenceCode = null
        scope.launch {''',
    '''        ownSharingReferenceCode = null
        activePersonaId = null
        mutableContacts.value = emptyList()
        quickUnlock.discardPendingEnrollment()
        scope.launch {''',
)
replace_between(
    controller,
    "    fun startSharingSender()",
    "    fun selectSharingFile()",
    '''    fun startSharingSender() {
        if (mutableState.value !is RootState.Unlocked) return
        mutableState.value = RootState.SharingSender(SharingSenderState.Preparing())
    }

''',
)
replace_once(
    controller,
    '''        // Runtime owns the file from this point and must close it exactly once.
        selectedSharingFile = null
        mutableState.value = RootState.SharingSender(SharingSenderState.Connecting(referenceCode))
        sharingJob = scope.launch {
            try {
                val result = withContext(workDispatcher) {
                    sharingRuntime.send(referenceCode, file) { progress ->''',
    '''        // Runtime owns the file from this point and must close it exactly once.
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
                val result = withContext(workDispatcher) {
                    sharingRuntime.send(referenceCode, file) { progress ->''',
)
replace_between(
    controller,
    "    fun startContactVerification()",
    "    fun enterContactVerificationReferenceCode",
    '''    fun startContactVerification() {
        if (mutableState.value !is RootState.Unlocked) return
        mutableState.value = RootState.SharingContactVerification(SharingContactVerificationState.Entering())
    }

''',
)
replace_once(
    controller,
    '''        mutableState.value = RootState.SharingContactVerification(entering.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                mutableState.value = when (val result = withContext(workDispatcher) { sharingRuntime.inspectPeer(referenceCode) }) {''',
    '''        mutableState.value = RootState.SharingContactVerification(entering.copy(busy = true, error = null))
        sharingJob = scope.launch {
            try {
                val presence = withContext(workDispatcher) { ensureSharingPresence() }
                if (presence !is SharingRuntimeActivation.Ready) {
                    mutableState.value = RootState.SharingContactVerification(
                        entering.copy(
                            busy = false,
                            error = (presence as? SharingRuntimeActivation.Unavailable)?.reason
                                ?: "No se pudo restablecer el canal de compartir.",
                        ),
                    )
                    return@launch
                }
                mutableState.value = when (val result = withContext(workDispatcher) { sharingRuntime.inspectPeer(referenceCode) }) {''',
)
replace_once(
    controller,
    '''                    SharingVerificationResult.Verified -> mutableState.value = RootState.SharingContactVerification(
                        SharingContactVerificationState.Completed(safeAlias),
                    )''',
    '''                    SharingVerificationResult.Verified -> {
                        mutableState.value = RootState.SharingContactVerification(
                            SharingContactVerificationState.Completed(safeAlias),
                        )
                        refreshContactsAsync()
                    }''',
)
replace_between(
    controller,
    "    fun startSharingReceiver()",
    "    fun acceptIncomingSharing()",
    '''    fun startSharingReceiver() {
        if (mutableState.value !is RootState.Unlocked) return
        if (sharingJob?.isActive == true) return
        sharingJob = scope.launch {
            try {
                when (val presence = withContext(workDispatcher) { ensureSharingPresence() }) {
                    is SharingRuntimeActivation.Ready -> {
                        ownSharingReferenceCode = presence.referenceCode
                        mutableState.value = RootState.SharingReceiver(SharingReceiverState.Waiting(presence.referenceCode))
                    }
                    is SharingRuntimeActivation.Unavailable -> mutableState.value = RootState.SharingReceiver(
                        SharingReceiverState.Error(presence.reason ?: "No se pudo restablecer el canal de compartir."),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingReceiver(
                    SharingReceiverState.Error("No se pudo restablecer el canal de compartir."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

''',
)
replace_once(
    controller,
    '''    private fun closeSelectedSharingFileAsync() {''',
    '''    private suspend fun ensureSharingPresence(): SharingRuntimeActivation {
        val vault = active ?: return SharingRuntimeActivation.Unavailable("La bóveda está bloqueada.")
        val personaId = activePersonaId ?: return SharingRuntimeActivation.Unavailable("Compartir no está configurado para esta bóveda.")
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

    private fun closeSelectedSharingFileAsync() {''',
)
replace_once(
    controller,
    '''        return BrowserState(folder?.value, crumbs, vault.items(folder).map {
            when (it) { is VaultItem.Directory -> BrowserItem(it.id.value, it.displayName, true); is VaultItem.File -> BrowserItem(it.id.value, it.displayName, false, it.size, it.mimeType) }
        })''',
    '''        val currentItems = vault.items(folder).map(::browserItem)
        val media = collectMedia(vault)
        return BrowserState(folder?.value, crumbs, currentItems, mediaItems = media)
    }
    private fun browserItem(item: VaultItem): BrowserItem = when (item) {
        is VaultItem.Directory -> BrowserItem(item.id.value, item.displayName, true)
        is VaultItem.File -> BrowserItem(item.id.value, item.displayName, false, item.size, item.mimeType)
    }
    private fun collectMedia(vault: VaultHandle): List<BrowserItem> {
        val result = mutableListOf<BrowserItem>()
        fun walk(parent: VaultDirectoryId?) {
            vault.items(parent).forEach { item ->
                when (item) {
                    is VaultItem.Directory -> walk(VaultDirectoryId(item.id.value))
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
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "mp4", "m4v", "mov", "webm", "mkv", "avi"
        )''',
)

# ---------------------------------------------------------------------------
# Runtime exposes persisted verified contacts.
# ---------------------------------------------------------------------------
runtime = "shared/app/src/commonMain/kotlin/dev/veilshare/app/DefaultSharingRuntime.kt"
replace_once(
    runtime,
    "import dev.veilshare.ui.features.SharingPeerLookupResult",
    "import dev.veilshare.ui.features.SharingContactSummary\nimport dev.veilshare.ui.features.SharingPeerLookupResult",
)
replace_once(
    runtime,
    '''    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult {''',
    '''    override suspend fun trustedContacts(): List<SharingContactSummary> = contacts.all().map { contact ->
        SharingContactSummary(
            alias = contact.alias,
            fingerprint = contact.identity.fingerprint.value,
            referenceCode = contact.lastReferenceCode?.value,
        )
    }

    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult {''',
)

# ---------------------------------------------------------------------------
# App composition injects optional quick unlock.
# ---------------------------------------------------------------------------
app_root = "shared/app/src/commonMain/kotlin/dev/veilshare/app/AppRoot.kt"
replace_once(
    app_root,
    "import dev.veilshare.ui.features.LocalAppController",
    "import dev.veilshare.ui.features.LocalAppController\nimport dev.veilshare.ui.features.QuickUnlockProvider",
)
replace_once(
    app_root,
    "import dev.veilshare.ui.features.UnavailableSharingRuntime",
    "import dev.veilshare.ui.features.UnavailableQuickUnlockProvider\nimport dev.veilshare.ui.features.UnavailableSharingRuntime",
)
replace_once(
    app_root,
    '''    val sharingRuntime: SharingRuntime = UnavailableSharingRuntime,
    val lockSignals: Flow<Unit> = emptyFlow(),''',
    '''    val sharingRuntime: SharingRuntime = UnavailableSharingRuntime,
    val quickUnlock: QuickUnlockProvider = UnavailableQuickUnlockProvider,
    val lockSignals: Flow<Unit> = emptyFlow(),''',
)
replace_once(
    app_root,
    '''            sharingRuntime = environment.sharingRuntime,
            scope = scope,''',
    '''            sharingRuntime = environment.sharingRuntime,
            scope = scope,
            quickUnlock = environment.quickUnlock,''',
)

# ---------------------------------------------------------------------------
# Existing screen: use new workspace shell, expose legacy Files view internally,
# add opt-in biometric controls and import-delete confirmation.
# ---------------------------------------------------------------------------
foundation = "shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/FoundationScreen.kt"
replace_once(
    foundation,
    "                is RootState.Unlocked -> BrowserScreen(value.browser, controller, windowClass)",
    "                is RootState.Unlocked -> WorkspaceScreen(value.browser, controller, windowClass)",
)
replace_between(
    foundation,
    "@Composable private fun UnlockScreen",
    "@Composable private fun BrowserScreen",
    '''@Composable private fun UnlockScreen(state: RootState.Locked, controller: LocalAppController) {
    var credential by remember { mutableStateOf("") }
    var enrollBiometric by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun submit() {
        if (credential.isNotEmpty() && !state.busy) {
            controller.unlock(credential.toCharArray(), enrollQuickUnlock = enrollBiometric)
            credential = ""
            enrollBiometric = false
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    ElevatedCard(Modifier.widthIn(max = 430.dp)) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(Res.string.files), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(Res.string.continue_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.unlock_description))
            OutlinedTextField(
                credential, { credential = it }, label = { Text(stringResource(Res.string.code)) }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("unlock_input"),
                supportingText = { Text(stringResource(Res.string.code_not_stored)) },
            )
            if (controller.quickUnlockAvailable) {
                Row(
                    Modifier.fillMaxWidth().clickable { enrollBiometric = !enrollBiometric },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = enrollBiometric,
                        onCheckedChange = { enrollBiometric = it },
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Activar desbloqueo biométrico después de validar este código")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { submit() }, enabled = !state.busy && credential.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.unlock)) }
            if (controller.quickUnlockAvailable && controller.quickUnlockEnrolled) {
                OutlinedButton(
                    onClick = { controller.unlockWithQuickUnlock() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Usar biometría") }
            }
        }
    }
}''',
)
replace_once(
    foundation,
    "@Composable private fun BrowserScreen",
    "@Composable internal fun BrowserScreen",
)
# Turn Add into an explicit confirmation dialog rather than immediate picker.
replace_once(
    foundation,
    '''    var newFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BrowserItem?>(null) }''',
    '''    var newFolderOpen by remember { mutableStateOf(false) }
    var importOptionsOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BrowserItem?>(null) }''',
)
replace_once(
    foundation,
    '''        if (newFolderOpen) NameDialog(stringResource(Res.string.new_folder), stringResource(Res.string.create), onDismiss = { newFolderOpen = false }) { controller.createFolder(it); newFolderOpen = false }
        renameTarget?.let { item -> NameDialog(stringResource(Res.string.rename_title), stringResource(Res.string.save), item.name, onDismiss = { renameTarget = null }) { controller.rename(item.id, it); renameTarget = null } }''',
    '''        if (newFolderOpen) NameDialog(stringResource(Res.string.new_folder), stringResource(Res.string.create), onDismiss = { newFolderOpen = false }) { controller.createFolder(it); newFolderOpen = false }
        if (importOptionsOpen) ImportOptionsDialog(
            onDismiss = { importOptionsOpen = false },
            onImport = { deleteOriginal ->
                controller.setDeleteOriginalAfterImport(deleteOriginal)
                controller.importFile()
                importOptionsOpen = false
            },
        )
        renameTarget?.let { item -> NameDialog(stringResource(Res.string.rename_title), stringResource(Res.string.save), item.name, onDismiss = { renameTarget = null }) { controller.rename(item.id, it); renameTarget = null } }''',
)
replace_once(
    foundation,
    '''                        Button(onClick = { controller.importFile() }, enabled = !busy) { Text(stringResource(Res.string.add)) }''',
    '''                        Button(onClick = { importOptionsOpen = true }, enabled = !busy) { Text(stringResource(Res.string.add)) }''',
)
# Add dialog before NameDialog definition.
replace_once(
    foundation,
    '''@Composable private fun NameDialog(''',
    '''@Composable private fun ImportOptionsDialog(
    onDismiss: () -> Unit,
    onImport: (Boolean) -> Unit,
) {
    var deleteOriginal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar archivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("El archivo se cifra dentro de la bóveda antes de modificar el original.")
                Row(
                    Modifier.fillMaxWidth().clickable { deleteOriginal = !deleteOriginal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(deleteOriginal, { deleteOriginal = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Eliminar el original después de cifrar")
                }
                if (deleteOriginal) Text(
                    "Android o el proveedor del documento puede impedir el borrado. La copia cifrada se conservará aunque eso ocurra.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = { onImport(deleteOriginal) }) { Text("Seleccionar archivo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable private fun NameDialog(''',
)

# ---------------------------------------------------------------------------
# New responsive workspace navigation.
# ---------------------------------------------------------------------------
workspace = r'''package dev.veilshare.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.veilshare.ui.design.VeilWindowClass

private enum class WorkspaceSection(val title: String) { Gallery("Galería"), Files("Archivos"), Contacts("Contactos") }

@Composable
internal fun WorkspaceScreen(browser: BrowserState, controller: LocalAppController, windowClass: VeilWindowClass) {
    var section by remember { mutableStateOf(WorkspaceSection.Files) }
    val contacts by controller.contacts.collectAsState()
    if (windowClass == VeilWindowClass.Compact) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    WorkspaceSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = section == item,
                            onClick = { section = item },
                            icon = { Icon(sectionIcon(item), item.title) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { WorkspaceContent(section, browser, contacts, controller, windowClass) }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(12.dp))
                WorkspaceSection.entries.forEach { item ->
                    NavigationRailItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Icon(sectionIcon(item), item.title) },
                        label = { Text(item.title) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { WorkspaceContent(section, browser, contacts, controller, windowClass) }
        }
    }
}

@Composable
private fun sectionIcon(section: WorkspaceSection) = when (section) {
    WorkspaceSection.Gallery -> Icons.Default.Collections
    WorkspaceSection.Files -> Icons.Default.Folder
    WorkspaceSection.Contacts -> Icons.Default.People
}

@Composable
private fun WorkspaceContent(
    section: WorkspaceSection,
    browser: BrowserState,
    contacts: List<SharingContactSummary>,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
) {
    when (section) {
        WorkspaceSection.Files -> BrowserScreen(browser, controller, windowClass)
        WorkspaceSection.Gallery -> GalleryScreen(browser.mediaItems, controller)
        WorkspaceSection.Contacts -> ContactsScreen(contacts, controller)
    }
}

@Composable
private fun GalleryScreen(items: List<BrowserItem>, controller: LocalAppController) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Galería", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Fotos y videos cifrados de toda la bóveda", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Todavía no hay fotos o videos.") }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ElevatedCard(
                        Modifier.fillMaxWidth().clickable { controller.openFile(item.id) },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(36.dp))
                            Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2)
                            item.mime?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(contacts: List<SharingContactSummary>, controller: LocalAppController) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Contactos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Identidades verificadas manualmente", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { controller.startSharingReceiver() }) { Text("Recibir") }
            Button(onClick = { controller.startContactVerification() }) { Text("Agregar") }
        }
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay contactos verificados todavía.") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(contacts, key = { it.fingerprint }) { contact ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.People, contentDescription = null)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.alias, fontWeight = FontWeight.SemiBold)
                                contact.referenceCode?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Text(contact.fingerprint.chunked(4).take(4).joinToString(" "), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { controller.startSharingSender() }) { Text("Enviar") }
                        }
                    }
                }
            }
        }
    }
}
'''
write("shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/WorkspaceScreen.kt", workspace)

# ---------------------------------------------------------------------------
# Android biometric quick unlock: per-use BiometricPrompt + Keystore AES-GCM.
# ---------------------------------------------------------------------------
biometric = r'''package dev.veilshare.android

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.veilshare.ui.features.QuickUnlockProvider
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class AndroidBiometricQuickUnlock(
    private val activity: FragmentActivity,
) : QuickUnlockProvider {
    private val root = File(activity.noBackupFilesDir, "quick-unlock-v1")
    private val blob = File(root, "credential.bin")
    private var pending: CharArray? = null

    override val available: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override val hasCredential: Boolean get() = blob.isFile

    override fun stageEnrollment(credential: CharArray) {
        discardPendingEnrollment()
        pending = credential.copyOf()
    }

    override fun discardPendingEnrollment() {
        pending?.fill('\u0000')
        pending = null
    }

    override suspend fun completePendingEnrollment(): Boolean {
        val chars = pending ?: return false
        return try {
            if (!available) return false
            val bytes = chars.concatToString().encodeToByteArray()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
                }
                val authenticated = authenticate("Activar desbloqueo biométrico", cipher) ?: return false
                val encrypted = authenticated.doFinal(bytes)
                val payload = ByteArray(1 + authenticated.iv.size + encrypted.size)
                payload[0] = authenticated.iv.size.toByte()
                authenticated.iv.copyInto(payload, 1)
                encrypted.copyInto(payload, 1 + authenticated.iv.size)
                atomicWrite(payload)
                true
            } finally {
                bytes.fill(0)
            }
        } finally {
            discardPendingEnrollment()
        }
    }

    override suspend fun requestCredential(): CharArray? {
        if (!available || !blob.isFile) return null
        val payload = runCatching { blob.readBytes() }.getOrNull() ?: return null
        if (payload.size < 2) return null
        val ivSize = payload[0].toInt() and 0xff
        if (ivSize !in 12..32 || payload.size <= 1 + ivSize) return null
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(128, iv))
            }
            val authenticated = authenticate("Desbloquear archivos", cipher) ?: return null
            val clear = authenticated.doFinal(encrypted)
            try { clear.decodeToString().toCharArray() } finally { clear.fill(0) }
        } catch (_: Exception) {
            null
        }
    }

    override fun clearCredential() {
        discardPendingEnrollment()
        blob.delete()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(createIfMissing) { "Quick unlock key is missing" }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val builder = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private suspend fun authenticate(title: String, cipher: Cipher): Cipher? = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(null)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("VeilShare")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Usar código")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private fun atomicWrite(bytes: ByteArray) {
        check(root.mkdirs() || root.isDirectory)
        val temp = File(root, "credential.tmp")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (blob.exists() && !blob.delete()) error("Unable to replace quick unlock blob")
        if (!temp.renameTo(blob)) {
            temp.delete()
            error("Unable to commit quick unlock blob")
        }
    }

    private companion object {
        const val KEY_ALIAS = "veilshare.quick.unlock.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
'''
write("androidApp/src/main/kotlin/dev/veilshare/android/AndroidBiometricQuickUnlock.kt", biometric)

# ---------------------------------------------------------------------------
# Android: FragmentActivity, quick unlock, post-commit provider deletion.
# ---------------------------------------------------------------------------
main = "androidApp/src/main/kotlin/dev/veilshare/android/MainActivity.kt"
replace_once(main, "import android.provider.OpenableColumns", "import android.provider.DocumentsContract\nimport android.provider.OpenableColumns")
replace_once(main, "import androidx.core.content.FileProvider", "import androidx.core.content.FileProvider\nimport androidx.fragment.app.FragmentActivity")
replace_once(main, "class MainActivity : ComponentActivity() {", "class MainActivity : FragmentActivity() {")
replace_once(
    main,
    '''            sharingRuntime = sharingRuntime,
            lockSignals = lockSignals,''',
    '''            sharingRuntime = sharingRuntime,
            quickUnlock = AndroidBiometricQuickUnlock(this),
            lockSignals = lockSignals,''',
)
replace_once(
    main,
    '''    private val externalUris = ArrayDeque<Uri>()
    var inFlight: Boolean = false; private set''',
    '''    private val externalUris = ArrayDeque<Uri>()
    private var deleteOriginalAfterNextImport = false
    var inFlight: Boolean = false; private set''',
)
replace_once(
    main,
    '''    override suspend fun pick(): ImportSource? {
        val uri = if (externalUris.isEmpty()) pickUri() else externalUris.removeFirst()
        return uri?.let { AndroidUriImportSource(resolver, it) }
    }''',
    '''    override fun setDeleteOriginalAfterImport(enabled: Boolean) {
        deleteOriginalAfterNextImport = enabled
    }

    override suspend fun pick(): ImportSource? {
        val deleteOriginal = deleteOriginalAfterNextImport
        deleteOriginalAfterNextImport = false
        val uri = if (externalUris.isEmpty()) pickUri() else externalUris.removeFirst()
        return uri?.let { AndroidUriImportSource(resolver, it, deleteOriginal) }
    }''',
)
replace_once(
    main,
    '''internal class AndroidUriImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {
    private val metadata by lazy { resolver.queryMetadata(uri) }
    override val displayName: String get() = metadata.first ?: "archivo"
    override val mimeHint: String? get() = resolver.getType(uri)
    override val sizeHint: Long? get() = metadata.second''',
    '''internal class AndroidUriImportSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val deleteOriginalRequested: Boolean = false,
) : ImportSource {
    private val metadata by lazy { resolver.queryMetadata(uri) }
    private var deleted = false
    override val displayName: String get() = metadata.first ?: "archivo"
    override val mimeHint: String? get() = resolver.getType(uri)
    override val sizeHint: Long? get() = metadata.second
    override val originalDeleted: Boolean get() = deleted

    override suspend fun deleteOriginalAfterCommit(): Boolean = withContext(Dispatchers.IO) {
        if (!deleteOriginalRequested || deleted) return@withContext deleted
        deleted = runCatching {
            if (DocumentsContract.isDocumentUri(null, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
        deleted
    }''',
)

manifest = "androidApp/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '''    <uses-permission android:name="android.permission.INTERNET" />''',
    '''    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />''',
)
replace_once(
    manifest,
    '''        <activity android:name=".MainActivity" android:exported="true">''',
    '''        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">''',
)

android_gradle = "androidApp/build.gradle.kts"
replace_once(
    android_gradle,
    '''    implementation(libs.androidx.core.ktx)''',
    '''    implementation(libs.androidx.core.ktx)
    implementation("androidx.biometric:biometric:1.1.0")''',
)

# ---------------------------------------------------------------------------
# Ordering regression: source deletion can only happen after durable catalog.
# ---------------------------------------------------------------------------
ordering_test = r'''package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class ImportDeleteOriginalOrderingTest {
    @Test fun `requested original deletion occurs only after encrypted catalog is durable`() = runTest {
        val root = Files.createTempDirectory("veil-delete-order-")
        val events = mutableListOf<String>()
        val crypto = DesktopProductionCrypto.create()
        val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
        val wrapper = OrderingWrapper(crypto.cipher)
        CreateVaultSetUseCase(
            DesktopVaultSlotStore(root), crypto.random, crypto.passwordKdf, wrapper, crypto.cipher, policy,
            DesktopVaultCatalogBootstrap(root, DesktopProductionCrypto.keyDeriver(), crypto.cipher),
        ).create(SensitiveChars("111111".toCharArray()), SensitiveChars("222222".toCharArray()))
        val opened = assertIs<DesktopOpenResult.Ready>(
            DesktopVaultRepository(
                root,
                UnlockVaultUseCase(DesktopVaultSlotStore(root), crypto.passwordKdf, wrapper, crypto.cipher, policy),
                DesktopProductionCrypto.keyDeriver(), crypto.cipher,
            ).open(SensitiveChars("111111".toCharArray())),
        )
        val source = OrderingSource("payload".encodeToByteArray()) { events += "delete" }
        val coordinator = ImportCoordinator(
            crypto.random,
            crypto.cipher,
            DesktopBlobStore(root.resolve("blobs"), opened.session.descriptor.blobNamespace),
            DesktopEncryptedCatalogStore(
                root, opened.session.descriptor.vaultId,
                CatalogCrypto(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
            ),
            DesktopVaultJournal(root),
            FileKeyWrapping(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
        ) { point -> if (point == ImportFaultPoint.CatalogDurable) events += "catalog-durable" }
        coordinator.import(opened.session, opened.catalog, source) { progress ->
            if (progress is ImportProgress.Complete) events += "complete"
        }
        assertEquals(listOf("catalog-durable", "delete", "complete"), events)
        assertTrue(source.originalDeleted)
        opened.session.close()
    }
}

private class OrderingSource(
    private val bytes: ByteArray,
    private val onDelete: () -> Unit,
) : ImportSource {
    override val displayName = "source.bin"
    override val mimeHint = "application/octet-stream"
    override val sizeHint = bytes.size.toLong()
    override val deleteOriginalRequested = true
    private var deleted = false
    override val originalDeleted get() = deleted
    override suspend fun deleteOriginalAfterCommit(): Boolean {
        onDelete()
        deleted = true
        return true
    }
    override suspend fun openRead() = object : ImportReadHandle {
        private var done = false
        override suspend fun read(maxBytes: Int): ByteArray = if (done) ByteArray(0) else bytes.also { done = true }
        override suspend fun close() = Unit
    }
}

private class OrderingWrapper(private val cipher: AuthenticatedCipher) : KeyWrapper {
    override suspend fun wrap(kek: KeyEncryptionKey, vaultKey: VaultKey, aad: ByteArray) =
        cipher.seal(kek.material, vaultKey.material.copy(), aad)
    override suspend fun unwrap(kek: KeyEncryptionKey, wrapped: SealedBytes, aad: ByteArray) =
        VaultKey(SensitiveBytes(cipher.open(kek.material, wrapped, aad)))
}
'''
write("shared/core-vault/src/desktopTest/kotlin/dev/veilshare/core/vault/ImportDeleteOriginalOrderingTest.kt", ordering_test)

# ---------------------------------------------------------------------------
# Controller regression: failed initial activation must recover without relock.
# ---------------------------------------------------------------------------
controller_test = "shared/ui-features/src/commonTest/kotlin/dev/veilshare/ui/features/LocalAppControllerTest.kt"
replace_once(
    controller_test,
    '''    @Test fun senderCompletesThenDoneReturnsToBrowserAndRuntimeOwnsFileExactlyOnce() = runTest {''',
    '''    @Test fun senderRecoversWhenInitialSharingActivationFailedWithoutRelock() = runTest {
        val runtime = RecordingSharingRuntime(
            activationSequence = mutableListOf(
                SharingRuntimeActivation.Unavailable("offline"),
                SharingRuntimeActivation.Ready(TEST_REFERENCE),
            ),
        )
        val picked = BytesSharingFile("recover.txt", "payload".encodeToByteArray())
        val controller = controller(
            FakeService(LocalStorageState.READY),
            runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
        controller.startSharingSender(); controller.selectSharingFile(); advanceUntilIdle()
        controller.enterSharingReferenceCode(TEST_REFERENCE.value)
        controller.startSharingTransfer(); advanceUntilIdle()
        assertIs<SharingSenderState.Completed>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals(2, runtime.activateCalls)
        assertEquals(1, runtime.sendCalls)
    }

    @Test fun senderCompletesThenDoneReturnsToBrowserAndRuntimeOwnsFileExactlyOnce() = runTest {''',
)
replace_once(
    controller_test,
    '''private class RecordingSharingRuntime(
    private val activation: SharingRuntimeActivation = SharingRuntimeActivation.Ready(TEST_REFERENCE),''',
    '''private class RecordingSharingRuntime(
    private val activation: SharingRuntimeActivation = SharingRuntimeActivation.Ready(TEST_REFERENCE),
    private val activationSequence: MutableList<SharingRuntimeActivation>? = null,''',
)
replace_once(
    controller_test,
    '''    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation {
        activateCalls++
        return activation
    }''',
    '''    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation {
        activateCalls++
        return activationSequence?.removeFirstOrNull() ?: activation
    }''',
)

# ---------------------------------------------------------------------------
# Validation matrix lives beside the implementation, not only in chat.
# ---------------------------------------------------------------------------
validation = r'''# Android UX Overhaul — objective validation

This batch is considered complete only when the listed automated gates pass.

| Objective | Implementation boundary | Automated validation |
|---|---|---|
| Sharing self-recovery | `LocalAppController.ensureSharingPresence()` re-activates the unlocked persona if initial activation failed | `senderRecoversWhenInitialSharingActivationFailedWithoutRelock` + `:shared:ui-features:desktopTest` |
| Android Share target | `ACTION_SEND`, singleTop intake, queued SAF URI | Android `assembleReleaseCheck` |
| Delete original safely | `ImportSource.deleteOriginalAfterCommit()` runs after blob/catalog/journal durability; provider failure cannot invalidate import | `ImportDeleteOriginalOrderingTest` + `:shared:core-vault:desktopTest` |
| Gallery / Files / Contacts | adaptive bottom navigation / rail, recursive media index, persisted trusted-contact list | `:shared:ui-features:desktopTest` + Android compile |
| Biometric quick unlock | explicit opt-in, Android BiometricPrompt CryptoObject, auth-per-use Keystore AES-GCM, PIN fallback | Android `assembleReleaseCheck`; final interaction still requires physical-device smoke |
| System bars | `safeDrawingPadding()` root | Android compile / beta APK |
| Production signaling | beta APK embeds Railway `wss://veilshare-signaling-production.up.railway.app/v1/ws` | beta workflow + Railway deployment status |

## Security invariants

- The PIN path remains authoritative and is never removed.
- Biometric storage contains only an authenticated-encrypted credential blob protected by an Android Keystore key requiring biometric authentication per use.
- REAL/DECOY labels are not persisted in quick-unlock metadata.
- Delete-original is explicit and one-shot; it is attempted only after the encrypted import is durable.
- Failure to delete the provider document never rolls back a completed encrypted import.
- Reference codes remain routing data, not trust evidence; Contacts lists only explicitly pinned identities.
- A failed relay connection no longer forces the user to lock/unlock the vault to retry.
'''
write("docs/ANDROID_UX_OVERHAUL_VALIDATION.md", validation)

print("Android UX overhaul batch applied")
