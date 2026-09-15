package dev.veilshare.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.veilshare.app.AppEnvironment
import dev.veilshare.app.AppRoot
import dev.veilshare.app.createDesktopSharingRuntime
import dev.veilshare.core.vault.*
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.SharingFilePicker
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.UnavailableSharingRuntime
import dev.veilshare.ui.features.VaultFileOpener
import java.awt.Desktop
import java.awt.Dimension
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Comparator
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun main() = application {
    val dataRoot = desktopDataRoot().also(Files::createDirectories)
    val tempCache = DesktopOwnedPlaintextCache(Paths.get(System.getProperty("java.io.tmpdir"), "vs-open-4f16a9"))
    val picker = DesktopPicker()
    val allowInsecureLoopback = System.getenv("VEILSHARE_ALLOW_INSECURE_LOOPBACK")?.toBooleanStrictOrNull()
        ?: System.getProperty("veilshare.allowInsecureLoopback")?.toBooleanStrictOrNull()
        ?: false
    val signalingEndpoint = System.getenv("VEILSHARE_SIGNALING_URL")?.trim()?.takeIf(String::isNotEmpty)
        ?: System.getProperty("veilshare.signalingUrl")?.trim()?.takeIf(String::isNotEmpty)
        ?: if (allowInsecureLoopback) "ws://127.0.0.1:8080/v1/ws" else null
    val sharingRuntime = signalingEndpoint?.let { endpoint ->
        runCatching {
            createDesktopSharingRuntime(
                stateRoot = dataRoot.resolve("sharing-state"),
                endpointUrl = endpoint,
                allowInsecureLoopback = allowInsecureLoopback,
            )
        }.getOrElse { UnavailableSharingRuntime }
    } ?: UnavailableSharingRuntime
    val environment = AppEnvironment(
        vaults = DesktopLocalVaultService(dataRoot),
        picker = picker,
        opener = DesktopOpener(tempCache),
        sharingFilePicker = picker,
        sharingRuntime = sharingRuntime,
    )
    Window(onCloseRequest = ::exitApplication, title = "Archivos", state = androidx.compose.ui.window.rememberWindowState(width = 1100.dp, height = 760.dp)) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(720, 520) }
        BoxWithConstraints {
            val widthClass = when { maxWidth < 600.dp -> VeilWindowClass.Compact; maxWidth < 900.dp -> VeilWindowClass.Medium; else -> VeilWindowClass.Expanded }
            AppRoot(environment, widthClass)
        }
    }
}

internal class DesktopPicker : LocalFilePicker, SharingFilePicker {
    override suspend fun pick(): ImportSource? = selectPath()?.let(::DesktopImportSource)

    override suspend fun pickFile(): SharingPickedFile? = selectPath()?.let(::DesktopSharingPickedFile)

    private suspend fun selectPath(): Path? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = false; fileSelectionMode = JFileChooser.FILES_ONLY }
            val selected = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
            if (continuation.isActive) continuation.resume(selected)
        }
    }
}

internal class DesktopSharingPickedFile(private val path: Path) : SharingPickedFile {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    override val displayName: String = path.fileName?.toString()?.takeIf(String::isNotBlank) ?: "archivo"
    override val size: Long = Files.size(path).also { require(it > 0) { "Empty files are not supported by Sharing V1" } }
    override val mimeType: String? = runCatching { Files.probeContentType(path) }.getOrNull()
    private var closed = false

    override suspend fun readChunk(offset: Long, size: Int): ByteArray = withContext(Dispatchers.IO) {
        check(!closed) { "Sharing file is closed" }
        require(offset >= 0 && offset <= this@DesktopSharingPickedFile.size) { "Invalid sharing read offset" }
        require(size > 0) { "Sharing read size must be positive" }
        if (offset == this@DesktopSharingPickedFile.size) return@withContext ByteArray(0)

        val requested = minOf(size.toLong(), this@DesktopSharingPickedFile.size - offset).toInt()
        val buffer = ByteBuffer.allocate(requested)
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count < 0) error("Selected file ended before its declared size")
            if (count == 0) continue
            position += count
        }
        buffer.array()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        closed = true
        channel.close()
    }
}

internal class DesktopOpener(private val cache: DesktopOwnedPlaintextCache) : VaultFileOpener {
    override suspend fun open(vault: VaultHandle, file: VaultItem.File) {
        val suffix = file.displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
        val output = cache.create(suffix)
        try {
            Files.newOutputStream(output).use { stream -> vault.readFile(file.id) { stream.write(it) } }
            output.toFile().deleteOnExit()
            check(Desktop.isDesktopSupported()) { "Desktop open is unavailable" }
            Desktop.getDesktop().open(output.toFile())
        } catch (failure: Throwable) { Files.deleteIfExists(output); throw failure }
    }
    override fun cleanup() { cache.cleanup() }
}

private fun desktopDataRoot(): Path {
    val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Paths::get) ?: Paths.get(System.getProperty("user.home"), ".local", "share")
    return base.resolve("VeilShare").resolve("v-8e61c4a0")
}

internal class DesktopOwnedPlaintextCache(internal val root: Path) {
    init { Files.createDirectories(root); cleanup() }
    fun create(suffix: String): Path = Files.createTempFile(root, "item-", suffix)
    /** Best effort: a viewer may still hold a file on Windows; the next startup retries. */
    fun cleanup(): Boolean = runCatching {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).filter { it != root }.forEach { Files.deleteIfExists(it) } }
        true
    }.getOrDefault(false)
}
