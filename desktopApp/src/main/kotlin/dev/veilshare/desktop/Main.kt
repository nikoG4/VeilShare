package dev.veilshare.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.veilshare.app.AppEnvironment
import dev.veilshare.app.AppRoot
import dev.veilshare.core.vault.*
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.VaultFileOpener
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

fun main() = application {
    val dataRoot = desktopDataRoot().also(Files::createDirectories)
    val tempRoot = cleanOwnedTemps(Paths.get(System.getProperty("java.io.tmpdir"), "vs-open-4f16a9"))
    val environment = AppEnvironment(DesktopLocalVaultService(dataRoot), DesktopPicker(), DesktopOpener(tempRoot))
    Window(onCloseRequest = ::exitApplication, title = "Archivos") {
        BoxWithConstraints {
            val widthClass = when { maxWidth < 600.dp -> VeilWindowClass.Compact; maxWidth < 900.dp -> VeilWindowClass.Medium; else -> VeilWindowClass.Expanded }
            AppRoot(environment, widthClass)
        }
    }
}

private class DesktopPicker : LocalFilePicker {
    override suspend fun pick(): ImportSource? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = false; fileSelectionMode = JFileChooser.FILES_ONLY }
            val selected = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
            if (continuation.isActive) continuation.resume(selected?.let(::DesktopImportSource))
        }
    }
}

private class DesktopOpener(private val root: Path) : VaultFileOpener {
    init { Files.createDirectories(root) }
    override suspend fun open(vault: VaultHandle, file: VaultItem.File) {
        val suffix = file.displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
        val output = Files.createTempFile(root, "view-", suffix)
        try {
            Files.newOutputStream(output).use { stream -> vault.readFile(file.id) { stream.write(it) } }
            output.toFile().deleteOnExit()
            check(Desktop.isDesktopSupported()) { "Desktop open is unavailable" }
            Desktop.getDesktop().open(output.toFile())
        } catch (failure: Throwable) { Files.deleteIfExists(output); throw failure }
    }
    override fun cleanup() { Files.list(root).use { paths -> paths.filter(Files::isRegularFile).forEach { runCatching { Files.deleteIfExists(it) } } } }
}

private fun desktopDataRoot(): Path {
    val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Paths::get) ?: Paths.get(System.getProperty("user.home"), ".local", "share")
    return base.resolve("VeilShare").resolve("v-8e61c4a0")
}

private fun cleanOwnedTemps(root: Path): Path {
    Files.createDirectories(root)
    Files.list(root).use { paths -> paths.filter(Files::isRegularFile).forEach { Files.deleteIfExists(it) } }
    return root
}
