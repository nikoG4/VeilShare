package dev.veilshare.app
import androidx.compose.runtime.Composable
import dev.veilshare.core.platform.PlatformCapabilities
import dev.veilshare.ui.design.VeilTheme
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.AppPresenter
import dev.veilshare.ui.features.FoundationScreen
data class AppEnvironment(val platform: PlatformCapabilities)
@Composable fun AppRoot(environment: AppEnvironment, windowClass: VeilWindowClass) = VeilTheme { FoundationScreen(AppPresenter(environment.platform, windowClass)) }
