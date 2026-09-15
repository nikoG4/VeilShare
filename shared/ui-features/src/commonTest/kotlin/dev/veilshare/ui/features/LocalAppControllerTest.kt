package dev.veilshare.ui.features

import dev.veilshare.core.model.BlobId
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.vault.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalAppControllerTest {
    @Test fun startupSetupAndGenericDualCredentialUnlock() = runTest {
        val service = FakeService(LocalStorageState.EMPTY)
        val controller = controller(service)
        controller.initialize()
        assertIs<RootState.FirstRun>(controller.state.value)
        val p = "1111".toCharArray(); val pc = "1111".toCharArray(); val a = "2222".toCharArray(); val ac = "2222".toCharArray()
        controller.setup(p, pc, a, ac); advanceUntilIdle()
        assertIs<RootState.Locked>(controller.state.value)
        assertTrue(listOf(p, pc, a, ac).all { chars -> chars.all { it == '\u0000' } })

        controller.unlock("1111".toCharArray()); advanceUntilIdle()
        val first = assertIs<RootState.Unlocked>(controller.state.value); assertTrue(first.browser.items.any { it.name == "primary.txt" })
        controller.lock(); controller.unlock("2222".toCharArray()); advanceUntilIdle()
        val second = assertIs<RootState.Unlocked>(controller.state.value); assertTrue(second.browser.items.any { it.name == "alternate.txt" })
        assertFalse(second.browser.toString().contains("decoy", ignoreCase = true))
        assertFalse(second.browser.toString().contains("real", ignoreCase = true))
    }

    @Test fun wrongCredentialIsNeutralAndDoubleSubmitIsIgnored() = runTest {
        val service = FakeService(LocalStorageState.READY); val controller = controller(service); controller.initialize()
        controller.unlock("bad".toCharArray()); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        val locked = assertIs<RootState.Locked>(controller.state.value)
        assertEquals("No se pudo continuar.", locked.error); assertEquals(1, service.unlockCalls)
    }

    @Test fun browserFolderImportDeleteAndPinChangeRefreshAuthoritativeState() = runTest {
        val service = FakeService(LocalStorageState.READY); val source = BytesSource("nuevo.txt", "nuevo".encodeToByteArray())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = LocalAppController(
            service,
            object : LocalFilePicker { override suspend fun pick() = source },
            NoopOpener,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile? = null },
            RecordingSharingRuntime(),
            this,
            dispatcher,
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.createFolder("Trabajo"); advanceUntilIdle(); val folder = assertIs<RootState.Unlocked>(controller.state.value).browser.items.single { it.isDirectory }
        controller.enterFolder(folder.id); controller.importFile(); advanceUntilIdle()
        var browser = assertIs<RootState.Unlocked>(controller.state.value).browser
        val imported = browser.items.single { !it.isDirectory }; assertEquals("nuevo.txt", imported.name); assertIs<BrowserOperation.Idle>(browser.operation)
        controller.delete(imported.id); advanceUntilIdle(); browser = assertIs<RootState.Unlocked>(controller.state.value).browser; assertTrue(browser.items.isEmpty())
        controller.changeCredential("3333".toCharArray(), "3333".toCharArray()); advanceUntilIdle()
        assertIs<RootState.Locked>(controller.state.value); assertEquals("3333", service.primary.changedTo)
    }

    @Test fun lockClosesSessionAndRequestsOwnedPlaintextCleanup() = runTest {
        val service = FakeService(LocalStorageState.READY); val opener = RecordingOpener()
        val controller = LocalAppController(
            service,
            object : LocalFilePicker { override suspend fun pick(): ImportSource? = null },
            opener,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile? = null },
            RecordingSharingRuntime(),
            this,
            StandardTestDispatcher(testScheduler),
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.lock()
        assertEquals(1, opener.cleanupCalls)
        assertFalse(service.primary.isOpen)
        assertIs<RootState.Locked>(controller.state.value)
    }

    @Test fun pickerAndOpenerFailuresBecomeGenericRecoverableMessages() = runTest {
        val service = FakeService(LocalStorageState.READY); val dispatcher = StandardTestDispatcher(testScheduler)
        val picker = object : LocalFilePicker { override suspend fun pick(): ImportSource? = error("provider details must not escape") }
        val opener = object : VaultFileOpener { override suspend fun open(vault: VaultHandle, file: VaultItem.File) = error("viewer details must not escape") }
        val controller = LocalAppController(
            service,
            picker,
            opener,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile? = null },
            RecordingSharingRuntime(),
            this,
            dispatcher,
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.importFile(); advanceUntilIdle()
        var browser = assertIs<RootState.Unlocked>(controller.state.value).browser
        assertEquals("No se pudo importar el archivo.", browser.message)
        controller.openFile("seed"); advanceUntilIdle()
        browser = assertIs<RootState.Unlocked>(controller.state.value).browser
        assertEquals("No se pudo abrir este archivo. Puede estar dañado.", browser.message)
        assertFalse(browser.message!!.contains("provider")); assertFalse(browser.message!!.contains("viewer"))
    }

    @Test fun firstRunValidationRejectsShortMismatchedAndEqualCodesWithoutCreatingStorage() = runTest {
        val service = FakeService(LocalStorageState.EMPTY); val controller = controller(service); controller.initialize()
        controller.setup("1".toCharArray(), "1".toCharArray(), "2".toCharArray(), "2".toCharArray()); advanceUntilIdle()
        assertEquals("Usa códigos de al menos 4 caracteres.", assertIs<RootState.FirstRun>(controller.state.value).error)
        controller.setup("1111".toCharArray(), "0000".toCharArray(), "2222".toCharArray(), "2222".toCharArray()); advanceUntilIdle()
        assertEquals("Las confirmaciones no coinciden.", assertIs<RootState.FirstRun>(controller.state.value).error)
        controller.setup("1111".toCharArray(), "1111".toCharArray(), "1111".toCharArray(), "1111".toCharArray()); advanceUntilIdle()
        assertEquals("Los códigos deben ser diferentes.", assertIs<RootState.FirstRun>(controller.state.value).error)
        assertEquals(LocalStorageState.EMPTY, service.storage)
    }

    @Test fun senderCompletesThenDoneReturnsToBrowserAndRuntimeOwnsFileExactlyOnce() = runTest {
        val runtime = RecordingSharingRuntime()
        val picked = BytesSharingFile("send.txt", "payload".encodeToByteArray())
        val controller = controller(
            FakeService(LocalStorageState.READY),
            runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()

        controller.startSharingSender()
        controller.selectSharingFile(); advanceUntilIdle()
        val preparing = assertIs<SharingSenderState.Preparing>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals("send.txt", preparing.selectedFile)

        controller.enterSharingReferenceCode(TEST_REFERENCE.value)
        controller.startSharingTransfer(); advanceUntilIdle()
        assertIs<SharingSenderState.Completed>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals(1, runtime.sendCalls)
        assertEquals(TEST_REFERENCE, runtime.lastReferenceCode)
        assertEquals(1, picked.closeCalls)

        controller.finishSharing(); advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
        assertEquals(1, picked.closeCalls)
    }

    @Test fun firstPeerRequiresExplicitFingerprintConfirmationBeforeRetry() = runTest {
        val runtime = RecordingSharingRuntime(
            sendResult = SharingSendResult.NeedsVerification(
                fingerprint = "ab".repeat(32),
                reason = SharingVerificationReason.NEW_PEER,
            ),
        )
        val picked = BytesSharingFile("first.txt", "payload".encodeToByteArray())
        val controller = controller(
            FakeService(LocalStorageState.READY),
            runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingSender(); controller.selectSharingFile(); advanceUntilIdle()
        controller.enterSharingReferenceCode(TEST_REFERENCE.value)
        controller.startSharingTransfer(); advanceUntilIdle()

        val verification = assertIs<SharingSenderState.VerificationRequired>(
            assertIs<RootState.SharingSender>(controller.state.value).state,
        )
        assertEquals(TEST_REFERENCE, verification.referenceCode)
        assertEquals(SharingVerificationReason.NEW_PEER, verification.reason)
        assertEquals(1, picked.closeCalls)

        controller.confirmSharingPeer("Alice"); advanceUntilIdle()
        assertEquals(1, runtime.confirmCalls)
        assertEquals("Alice", runtime.lastAlias)
        val preparing = assertIs<SharingSenderState.Preparing>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals(TEST_REFERENCE.value, preparing.referenceCode)
        assertNull(preparing.selectedFile)
        assertEquals(1, picked.closeCalls)
    }

    @Test fun identityChangeConfirmationUsesExistingAlias() = runTest {
        val runtime = RecordingSharingRuntime(
            sendResult = SharingSendResult.NeedsVerification(
                fingerprint = "cd".repeat(32),
                reason = SharingVerificationReason.IDENTITY_CHANGED,
                existingAlias = "Bob",
            ),
        )
        val picked = BytesSharingFile("changed.txt", ByteArray(4))
        val controller = controller(
            FakeService(LocalStorageState.READY), runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingSender(); controller.selectSharingFile(); advanceUntilIdle()
        controller.enterSharingReferenceCode(TEST_REFERENCE.value); controller.startSharingTransfer(); advanceUntilIdle()
        val verification = assertIs<SharingSenderState.VerificationRequired>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals("Bob", verification.existingAlias)

        controller.confirmSharingPeer(""); advanceUntilIdle()
        assertEquals("Bob", runtime.lastAlias)
        assertIs<SharingSenderState.Preparing>(assertIs<RootState.SharingSender>(controller.state.value).state)
    }

    @Test fun dismissVerificationClearsRuntimePendingTrustAndKeepsRoute() = runTest {
        val runtime = RecordingSharingRuntime(
            sendResult = SharingSendResult.NeedsVerification("ef".repeat(32)),
        )
        val picked = BytesSharingFile("dismiss.txt", ByteArray(1))
        val controller = controller(
            FakeService(LocalStorageState.READY), runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingSender(); controller.selectSharingFile(); advanceUntilIdle()
        controller.enterSharingReferenceCode(TEST_REFERENCE.value); controller.startSharingTransfer(); advanceUntilIdle()
        controller.dismissSharingVerification(); advanceUntilIdle()
        assertEquals(1, runtime.dismissCalls)
        val preparing = assertIs<SharingSenderState.Preparing>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertEquals(TEST_REFERENCE.value, preparing.referenceCode)
    }

    @Test fun receiverRejectUsesRuntimeThenDoneReturnsToBrowser() = runTest {
        val runtime = RecordingSharingRuntime()
        val controller = controller(FakeService(LocalStorageState.READY), runtime)
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingReceiver()
        assertIs<SharingReceiverState.Waiting>(assertIs<RootState.SharingReceiver>(controller.state.value).state)

        runtime.emit(SharingRuntimeEvent.IncomingOffer("Alice", "photo.jpg", 42L)); advanceUntilIdle()
        val incoming = assertIs<SharingReceiverState.Incoming>(assertIs<RootState.SharingReceiver>(controller.state.value).state)
        assertEquals("Alice", incoming.senderIdentity)
        assertEquals("photo.jpg", incoming.fileName)

        controller.rejectIncomingSharing(); advanceUntilIdle()
        assertEquals(1, runtime.rejectCalls)
        assertIs<SharingReceiverState.Rejected>(assertIs<RootState.SharingReceiver>(controller.state.value).state)

        controller.finishSharing(); advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
    }

    @Test fun receiverAcceptProgressAndCompletionFollowRuntimeEvents() = runTest {
        val runtime = RecordingSharingRuntime()
        val controller = controller(FakeService(LocalStorageState.READY), runtime)
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingReceiver()
        runtime.emit(SharingRuntimeEvent.IncomingOffer("Bob", "archive.bin", 100L)); advanceUntilIdle()

        controller.acceptIncomingSharing(); advanceUntilIdle()
        assertEquals(1, runtime.acceptCalls)

        val progress = SharingProgress(50L, 100L, 1, 2)
        runtime.emit(SharingRuntimeEvent.Receiving(progress)); advanceUntilIdle()
        assertEquals(progress, assertIs<SharingReceiverState.Receiving>(assertIs<RootState.SharingReceiver>(controller.state.value).state).progress)

        runtime.emit(SharingRuntimeEvent.IncomingCompleted); advanceUntilIdle()
        assertIs<SharingReceiverState.Completed>(assertIs<RootState.SharingReceiver>(controller.state.value).state)
        controller.finishSharing(); advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
    }

    @Test fun cancelWhileSendingPropagatesAndLeavesTerminalCancelledState() = runTest {
        val runtime = RecordingSharingRuntime(suspendSend = true)
        val picked = BytesSharingFile("large.bin", ByteArray(32))
        val controller = controller(
            FakeService(LocalStorageState.READY),
            runtime,
            object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile = picked },
        )
        controller.initialize(); controller.unlock("1111".toCharArray()); advanceUntilIdle()
        controller.startSharingSender(); controller.selectSharingFile(); advanceUntilIdle()
        controller.enterSharingReferenceCode(TEST_REFERENCE.value)
        controller.startSharingTransfer(); advanceUntilIdle()
        assertIs<SharingSenderState.Connecting>(assertIs<RootState.SharingSender>(controller.state.value).state)

        controller.cancelSharing(); advanceUntilIdle()
        assertIs<SharingSenderState.Cancelled>(assertIs<RootState.SharingSender>(controller.state.value).state)
        assertTrue(runtime.cancelCalls >= 1)
        assertEquals(1, picked.closeCalls)
    }

    private fun TestScope.controller(
        service: FakeService,
        runtime: RecordingSharingRuntime = RecordingSharingRuntime(),
        sharingPicker: SharingFilePicker = object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile? = null },
    ) = LocalAppController(
        service,
        object : LocalFilePicker { override suspend fun pick(): ImportSource? = null },
        NoopOpener,
        sharingPicker,
        runtime,
        this,
        StandardTestDispatcher(testScheduler),
    )
}

private val TEST_PERSONA = LocalPersonaId("a".repeat(64))
private val TEST_REFERENCE = ReferenceCode("2345-6789-ABCD-EFGH")

private object NoopOpener : VaultFileOpener { override suspend fun open(vault: VaultHandle, file: VaultItem.File) = Unit }
private class RecordingOpener : VaultFileOpener { var cleanupCalls = 0; override suspend fun open(vault: VaultHandle, file: VaultItem.File) = Unit; override fun cleanup() { cleanupCalls++ } }

private class RecordingSharingRuntime(
    private val activation: SharingRuntimeActivation = SharingRuntimeActivation.Ready(TEST_REFERENCE),
    private val sendResult: SharingSendResult = SharingSendResult.Completed,
    private val verificationResult: SharingVerificationResult = SharingVerificationResult.Verified,
    private val suspendSend: Boolean = false,
) : SharingRuntime {
    private val mutableEvents = MutableSharedFlow<SharingRuntimeEvent>(extraBufferCapacity = 16)
    override val events: Flow<SharingRuntimeEvent> = mutableEvents

    var activateCalls = 0
    var sendCalls = 0
    var confirmCalls = 0
    var dismissCalls = 0
    var acceptCalls = 0
    var rejectCalls = 0
    var cancelCalls = 0
    var deactivateCalls = 0
    var closeCalls = 0
    var lastReferenceCode: ReferenceCode? = null
    var lastAlias: String? = null

    suspend fun emit(event: SharingRuntimeEvent) { mutableEvents.emit(event) }

    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation {
        activateCalls++
        return activation
    }

    override suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult {
        sendCalls++
        lastReferenceCode = referenceCode
        return try {
            if (suspendSend) awaitCancellation()
            if (sendResult == SharingSendResult.Completed) {
                onProgress(SharingProgress(file.size, file.size, 1, 1))
            }
            sendResult
        } finally {
            file.close()
        }
    }

    override suspend fun confirmPendingPeer(alias: String): SharingVerificationResult {
        confirmCalls++
        lastAlias = alias
        return verificationResult
    }
    override suspend fun dismissPendingPeerVerification() { dismissCalls++ }
    override suspend fun acceptIncoming() { acceptCalls++ }
    override suspend fun rejectIncoming() { rejectCalls++ }
    override suspend fun cancelCurrent() { cancelCalls++ }
    override suspend fun deactivate() { deactivateCalls++ }
    override fun close() { closeCalls++ }
}

private class BytesSharingFile(
    override val displayName: String,
    private val bytes: ByteArray,
) : SharingPickedFile {
    override val size: Long = bytes.size.toLong()
    override val mimeType: String? = "application/octet-stream"
    var closeCalls = 0

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(bytes.size, start + size)
        return bytes.copyOfRange(start, end)
    }

    override suspend fun close() { closeCalls++ }
}

private class FakeService(var storage: LocalStorageState) : LocalVaultService {
    val primary = FakeVault("primary.txt"); val alternate = FakeVault("alternate.txt"); var unlockCalls = 0
    override suspend fun storageState() = storage
    override suspend fun createPair(primary: CharArray, alternate: CharArray) { storage = LocalStorageState.READY }
    override suspend fun unlock(credential: CharArray): LocalUnlockResult {
        unlockCalls++
        return when (credential.concatToString()) {
            "1111" -> LocalUnlockResult.Ready(primary, TEST_PERSONA)
            "2222" -> LocalUnlockResult.Ready(alternate, LocalPersonaId("b".repeat(64)))
            else -> LocalUnlockResult.InvalidCredential
        }
    }
}

private class FakeVault(initial: String) : VaultHandle {
    private val entries = mutableListOf<VaultItem>(VaultItem.File(VaultItemId("seed"), null, initial, "text/plain", 1, BlobId("blobseed")))
    override var isOpen = true
    var changedTo: String? = null
    override suspend fun recover() = Unit
    override suspend fun reload() = Unit
    override fun items(parent: VaultDirectoryId?) = entries.filter { it.parentId == parent }
    override fun find(id: VaultItemId) = entries.firstOrNull { it.id == id }
    override suspend fun createDirectory(parent: VaultDirectoryId?, name: String) = VaultItem.Directory(VaultItemId("dir${entries.size}"), parent, name).also(entries::add)
    override suspend fun rename(id: VaultItemId, name: String) { val i = entries.indexOfFirst { it.id == id }; val old = entries[i]; entries[i] = when (old) { is VaultItem.File -> old.copy(displayName = name); is VaultItem.Directory -> old.copy(displayName = name) } }
    override suspend fun import(source: ImportSource, parent: VaultDirectoryId?, progress: suspend (ImportProgress) -> Unit): VaultItem.File { progress(ImportProgress.Preparing); val e = VaultItem.File(VaultItemId("file${entries.size}"), parent, source.displayName, source.mimeHint, source.sizeHint ?: 0, BlobId("blob${entries.size}")); progress(ImportProgress.Encrypting(e.size, source.sizeHint)); entries += e; progress(ImportProgress.Complete(e)); return e }
    override suspend fun deleteFile(id: VaultItemId) { entries.removeAll { it.id == id } }
    override suspend fun deleteEmptyDirectory(id: VaultItemId) { entries.removeAll { it.id == id } }
    override suspend fun readFile(id: VaultItemId, consume: suspend (ByteArray) -> Unit) = 0L
    override suspend fun changeCredential(newCredential: CharArray) { changedTo = newCredential.concatToString() }
    override fun close() { isOpen = false }
}

private class BytesSource(override val displayName: String, private val bytes: ByteArray) : ImportSource {
    override val mimeHint = "text/plain"
    override val sizeHint = bytes.size.toLong()
    override suspend fun openRead() = object : ImportReadHandle {
        var done = false
        override suspend fun read(maxBytes: Int) = if (done) ByteArray(0) else bytes.also { done = true }
        override suspend fun close() = Unit
    }
}
