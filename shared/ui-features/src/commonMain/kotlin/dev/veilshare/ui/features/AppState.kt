package dev.veilshare.ui.features
import dev.veilshare.core.platform.PlatformCapabilities
import dev.veilshare.ui.design.VeilWindowClass
sealed interface RootState { data object Booting : RootState; data object NeedsOnboarding : RootState; data object Locked : RootState; data class Unlocked(val route: VaultRoute) : RootState }
sealed interface VaultRoute { data object Home : VaultRoute; data object Contacts : VaultRoute; data object Settings : VaultRoute }
data class AppPresenter(val capabilities: PlatformCapabilities, val windowClass: VeilWindowClass, val state: RootState = RootState.NeedsOnboarding)
