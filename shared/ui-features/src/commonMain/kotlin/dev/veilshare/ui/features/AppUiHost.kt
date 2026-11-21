package dev.veilshare.ui.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import dev.veilshare.ui.design.VeilTheme
import dev.veilshare.ui.design.VeilWindowClass

/**
 * Owns the visual composition for the app while keeping shared:app dependent only on
 * Compose runtime. Foundation/Material/UI APIs and transient overlays belong here.
 */
@Composable
fun AppUiHost(
    root: RootState,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
    mediaPreview: MediaPreviewProvider,
    referenceQr: ReferenceCodeQrProvider,
    entryResetGeneration: Long = 0L,
    decoyStore: DecoyStore = MemoryDecoyStore(),
    screenCaptureSettings: ScreenCaptureSettings = UnavailableScreenCaptureSettings,
) {
    // Sharing V1 keeps transport/presence alive while a vault is unlocked. A failed,
    // cancelled or already-finished transfer must not leave an inbound session behind after
    // the UI returns to the vault: refreshPresence() deliberately refuses to run while such
    // work exists and would otherwise surface as "Hay una transferencia activa." on the next
    // Receive attempt. cancelSharing() is safe from RootState.Unlocked: it preserves the vault,
    // persona/reference code and presence, and only clears transient sharing work.
    LaunchedEffect(root) {
        if (root is RootState.Unlocked) controller.cancelSharing()
    }

    val inPrivateWorkspace = root is RootState.Unlocked ||
        root is RootState.SharingSender ||
        root is RootState.SharingReceiver ||
        root is RootState.SharingContactVerification
    val preferences by decoyStore.preferences.collectAsState()
    var appearanceOpen by remember { mutableStateOf(false) }
    LaunchedEffect(root is RootState.Unlocked) { if (root !is RootState.Unlocked) appearanceOpen = false }
    VeilTheme(privateWorkspace = inPrivateWorkspace) {
        CompositionLocalProvider(LocalReferenceCodeQrProvider provides referenceQr) {
            Box(Modifier.fillMaxSize()) {
                when (root) {
                    is RootState.FirstRun -> key(entryResetGeneration) {
                        FirstRunScreen(root, controller)
                    }
                    is RootState.Locked -> DecoyEntryHost(root, controller, entryResetGeneration, preferences, decoyStore)
                    is RootState.SharingSender -> EnhancedSenderScreen(root.state, controller)
                    else -> WorkspaceFoundationScreen(controller, windowClass, mediaPreview, onAppearance = { appearanceOpen = true })
                }
                if (root is RootState.Unlocked && appearanceOpen) {
                    DecoySettings(preferences, decoyStore, screenCaptureSettings, onClose = { appearanceOpen = false }, onChanged = { appearanceOpen = false; controller.lock() })
                }
                BatchShareLauncherOverlay(root, controller)
                VaultPhotoViewerOverlay(controller, mediaPreview)
                ReceiverCodeActionsOverlay(root)
            }
        }
    }
}
