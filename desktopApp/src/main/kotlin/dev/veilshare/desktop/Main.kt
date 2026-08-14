package dev.veilshare.desktop
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.veilshare.app.AppEnvironment
import dev.veilshare.app.AppRoot
import dev.veilshare.core.platform.FakePlatformCapabilities
import dev.veilshare.ui.design.VeilWindowClass
fun main() = application { Window(onCloseRequest = ::exitApplication, title = "VeilShare") { AppRoot(AppEnvironment(FakePlatformCapabilities), VeilWindowClass.Expanded) } }
