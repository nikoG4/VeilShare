package dev.veilshare.app
import androidx.compose.runtime.*
import dev.veilshare.core.vault.LocalVaultService
import dev.veilshare.ui.design.VeilTheme
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalAppController
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.VaultFileOpener
import dev.veilshare.ui.features.FoundationScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
data class AppEnvironment(val vaults: LocalVaultService, val picker: LocalFilePicker, val opener: VaultFileOpener, val lockSignals: Flow<Unit> = emptyFlow())

@Composable fun AppRoot(environment: AppEnvironment, windowClass: VeilWindowClass) {
    val scope = rememberCoroutineScope()
    val controller = remember(environment) { LocalAppController(environment.vaults, environment.picker, environment.opener, scope) }
    LaunchedEffect(controller) { controller.initialize() }
    LaunchedEffect(controller, environment.lockSignals) { environment.lockSignals.collect { controller.lock() } }
    DisposableEffect(controller) { onDispose { controller.close() } }
    VeilTheme { FoundationScreen(controller, windowClass) }
}
