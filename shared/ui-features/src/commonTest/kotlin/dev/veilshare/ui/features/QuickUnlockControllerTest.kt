package dev.veilshare.ui.features

import dev.veilshare.core.model.BlobId
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.vault.ImportProgress
import dev.veilshare.core.vault.ImportReadHandle
import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.LocalStorageState
import dev.veilshare.core.vault.LocalUnlockResult
import dev.veilshare.core.vault.LocalVaultService
import dev.veilshare.core.vault.VaultDirectoryId
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import dev.veilshare.core.vault.VaultItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuickUnlockControllerTest {
    @Test
    fun enrollmentCompletesOnlyAfterCredentialIsValidated() = runTest {
        val quick = FakeQuickUnlock(hasStoredCredential = false)
        val service = QuickUnlockService()
        val controller = controller(service, quick)
        controller.initialize()

        controller.unlock("bad".toCharArray(), enrollQuickUnlock = true)
        advanceUntilIdle()
        assertIs<RootState.Locked>(controller.state.value)
        assertEquals(1, quick.stageCalls)
        assertEquals(0, quick.completeCalls)

        controller.unlock("1111".toCharArray(), enrollQuickUnlock = true)
        advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)
        assertEquals(2, quick.stageCalls)
        assertEquals(1, quick.completeCalls)
        assertTrue(quick.hasCredential)
    }

    @Test
    fun biometricCredentialReusesAuthoritativeUnlockPathAndIsZeroed() = runTest {
        val quick = FakeQuickUnlock(hasStoredCredential = true, requestValue = "1111")
        val service = QuickUnlockService()
        val controller = controller(service, quick)
        controller.initialize()

        controller.unlockWithQuickUnlock()
        advanceUntilIdle()

        assertIs<RootState.Unlocked>(controller.state.value)
        assertEquals(1, quick.requestCalls)
        assertEquals(1, service.unlockCalls)
        assertTrue(quick.lastReturned?.all { it == '\u0000' } == true)
    }

    @Test
    fun changingCredentialInvalidatesStoredQuickUnlock() = runTest {
        val quick = FakeQuickUnlock(hasStoredCredential = true)
        val service = QuickUnlockService()
        val controller = controller(service, quick)
        controller.initialize()
        controller.unlock("1111".toCharArray())
        advanceUntilIdle()
        assertIs<RootState.Unlocked>(controller.state.value)

        controller.changeCredential("3333".toCharArray(), "3333".toCharArray())
        advanceUntilIdle()

        assertEquals(1, quick.clearCalls)
        assertEquals("3333", service.vault.changedTo)
        assertIs<RootState.Locked>(controller.state.value)
    }

    private fun TestScope.controller(service: QuickUnlockService, quick: QuickUnlockProvider): LocalAppController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(SupervisorJob() + dispatcher)
        backgroundScope.coroutineContext[Job]?.invokeOnCompletion {
            controllerScope.coroutineContext[Job]?.cancel()
        }
        return LocalAppController(
            service = service,
            picker = object : LocalFilePicker { override suspend fun pick(): ImportSource? = null },
            opener = object : VaultFileOpener { override suspend fun open(vault: VaultHandle, file: VaultItem.File) = Unit },
            sharingFilePicker = object : SharingFilePicker { override suspend fun pickFile(): SharingPickedFile? = null },
            sharingRuntime = UnavailableSharingRuntime,
            scope = controllerScope,
            workDispatcher = dispatcher,
            quickUnlock = quick,
        )
    }
}

private class FakeQuickUnlock(
    hasStoredCredential: Boolean,
    private val requestValue: String = "1111",
) : QuickUnlockProvider {
    override val available = true
    private var stored = hasStoredCredential
    override val hasCredential: Boolean get() = stored
    private var pending: CharArray? = null

    var stageCalls = 0
    var completeCalls = 0
    var requestCalls = 0
    var clearCalls = 0
    var lastReturned: CharArray? = null

    override fun stageEnrollment(credential: CharArray) {
        stageCalls++
        discardPendingEnrollment()
        pending = credential.copyOf()
    }

    override fun discardPendingEnrollment() {
        pending?.fill('\u0000')
        pending = null
    }

    override suspend fun completePendingEnrollment(): Boolean {
        completeCalls++
        val hadPending = pending != null
        if (hadPending) stored = true
        discardPendingEnrollment()
        return hadPending
    }

    override suspend fun requestCredential(): CharArray? {
        requestCalls++
        if (!stored) return null
        return requestValue.toCharArray().also { lastReturned = it }
    }

    override fun clearCredential() {
        clearCalls++
        stored = false
        discardPendingEnrollment()
    }
}

private class QuickUnlockService : LocalVaultService {
    val vault = QuickUnlockVault()
    var unlockCalls = 0

    override suspend fun storageState() = LocalStorageState.READY
    override suspend fun createPair(primary: CharArray, alternate: CharArray) = Unit
    override suspend fun unlock(credential: CharArray): LocalUnlockResult {
        unlockCalls++
        return if (credential.concatToString() == "1111") {
            LocalUnlockResult.Ready(vault, LocalPersonaId("c".repeat(64)))
        } else {
            LocalUnlockResult.InvalidCredential
        }
    }
}

private class QuickUnlockVault : VaultHandle {
    private val entries = mutableListOf<VaultItem>(
        VaultItem.File(VaultItemId("seed"), null, "file.txt", "text/plain", 1, BlobId("blobseed")),
    )
    override var isOpen = true
    var changedTo: String? = null

    override suspend fun recover() = Unit
    override suspend fun reload() = Unit
    override fun items(parent: VaultDirectoryId?) = entries.filter { it.parentId == parent }
    override fun find(id: VaultItemId) = entries.firstOrNull { it.id == id }
    override suspend fun createDirectory(parent: VaultDirectoryId?, name: String) =
        VaultItem.Directory(VaultItemId("dir"), parent, name).also(entries::add)
    override suspend fun rename(id: VaultItemId, name: String) = Unit
    override suspend fun import(
        source: ImportSource,
        parent: VaultDirectoryId?,
        progress: suspend (ImportProgress) -> Unit,
    ): VaultItem.File = error("not used")
    override suspend fun deleteFile(id: VaultItemId) = Unit
    override suspend fun deleteEmptyDirectory(id: VaultItemId) = Unit
    override suspend fun readFile(id: VaultItemId, consume: suspend (ByteArray) -> Unit) = 0L
    override suspend fun changeCredential(newCredential: CharArray) { changedTo = newCredential.concatToString() }
    override fun close() { isOpen = false }
}
