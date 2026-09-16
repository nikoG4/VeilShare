package dev.veilshare.ui.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.veilshare.ui.design.VeilTheme
import dev.veilshare.ui.design.VeilWindowClass

/**
 * Owns the visual composition for the app while keeping shared:app dependent only on
 * Compose runtime. Foundation/Material/UI APIs belong in ui-features.
 */
@Composable
fun AppUiHost(
    root: RootState,
    controller: LocalAppController,
    windowClass: VeilWindowClass,
    mediaPreview: MediaPreviewProvider,
    referenceQr: ReferenceCodeQrProvider,
) {
    VeilTheme {
        CompositionLocalProvider(LocalReferenceCodeQrProvider provides referenceQr) {
            Box(Modifier.fillMaxSize()) {
                when (root) {
                    is RootState.Locked -> QuickUnlockScreen(root, controller)
                    else -> WorkspaceFoundationScreen(controller, windowClass, mediaPreview)
                }
                VaultPhotoViewerOverlay(controller, mediaPreview)
                ReceiverCodeActionsOverlay(root)
            }
        }
    }
}
