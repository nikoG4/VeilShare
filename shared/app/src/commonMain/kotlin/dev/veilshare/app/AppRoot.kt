package dev.veilshare.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.veilshare.core.vault.LocalVaultService
import dev.veilshare.ui.design.VeilTheme
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalAppController
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.QuickUnlockProvider
import dev.veilshare.ui.features.RootState
import dev.veilshare.ui.features.SharingFilePicker
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.SharingRuntime
import dev.veilshare.ui.features.UnavailableQuickUnlockProvider
import dev.veilshare.ui.features.UnavailableSharingRuntime
import dev.veilshare.ui.features.VaultFileOpener
import dev.veilshare.ui.features.WorkspaceFoundationScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

private object UnavailableSharingFilePicker : SharingFilePicker {
    override suspend fun pickFile(): SharingPickedFile? = null
}

data class AppEnvironment(
    val vaults: LocalVaultService,
    val picker: LocalFilePicker,
    val opener: VaultFileOpener,
    val sharingFilePicker: SharingFilePicker = UnavailableSharingFilePicker,
    val sharingRuntime: SharingRuntime = UnavailableSharingRuntime,
    val quickUnlock: QuickUnlockProvider = UnavailableQuickUnlockProvider,
    val lockSignals: Flow<Unit> = emptyFlow(),
    val externalImportSignals: Flow<Unit> = emptyFlow(),
)

@Composable
fun AppRoot(environment: AppEnvironment, windowClass: VeilWindowClass) {
    val scope = rememberCoroutineScope()
    val controller = remember(environment) {
        LocalAppController(
            service = environment.vaults,
            picker = environment.picker,
            opener = environment.opener,
            sharingFilePicker = environment.sharingFilePicker,
            sharingRuntime = environment.sharingRuntime,
            scope = scope,
            quickUnlock = environment.quickUnlock,
        )
    }
    LaunchedEffect(controller) { controller.initialize() }
    LaunchedEffect(controller, environment.lockSignals) {
        environment.lockSignals.collect { controller.lock() }
    }
    LaunchedEffect(controller, environment.externalImportSignals) {
        environment.externalImportSignals.collect {
            controller.state.first { it is RootState.Unlocked }
            controller.importFile()
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    VeilTheme { WorkspaceFoundationScreen(controller, windowClass) }
}
