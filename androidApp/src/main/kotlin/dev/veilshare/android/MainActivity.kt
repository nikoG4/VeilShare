package dev.veilshare.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.veilshare.app.AppEnvironment
import dev.veilshare.app.AppRoot
import dev.veilshare.app.createAndroidSharingRuntime
import dev.veilshare.core.vault.*
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.SharingFilePicker
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.UnavailableSharingRuntime
import dev.veilshare.ui.features.VaultFileOpener
import java.io.File
import java.io.InputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val lockSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var picker: AndroidDocumentPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        picker = AndroidDocumentPicker(this)
        val openCache = AndroidOwnedPlaintextCache(File(cacheDir, "open-4f16a9"))
        val endpoint = BuildConfig.SHARING_SIGNALING_URL.trim().takeIf(String::isNotEmpty)
            ?: if (BuildConfig.DEBUG) "ws://10.0.2.2:8080/v1/ws" else null
        val sharingRuntime = endpoint?.let { url ->
            runCatching {
                createAndroidSharingRuntime(
                    context = this,
                    endpointUrl = url,
                    allowInsecureLoopback = BuildConfig.DEBUG,
                )
            }.getOrElse { UnavailableSharingRuntime }
        } ?: UnavailableSharingRuntime
        val environment = AppEnvironment(
            vaults = AndroidLocalVaultService(AndroidVaultStorage.privateRoot(this)),
            picker = picker,
            opener = AndroidFileOpener(this, openCache),
            sharingFilePicker = picker,
            sharingRuntime = sharingRuntime,
            lockSignals = lockSignals,
        )
        setContent {
            BoxWithConstraints {
                val widthClass = when { maxWidth < 600.dp -> VeilWindowClass.Compact; maxWidth < 840.dp -> VeilWindowClass.Medium; else -> VeilWindowClass.Expanded }
                AppRoot(environment, widthClass)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // System picker transitions are not treated as abandonment of the session.
        if (!isChangingConfigurations && !picker.inFlight) lockSignals.tryEmit(Unit)
    }
}

internal class AndroidDocumentPicker(activity: ComponentActivity) : LocalFilePicker, SharingFilePicker {
    private var continuation: Continuation<Uri?>? = null
    var inFlight: Boolean = false; private set
    private val resolver = activity.contentResolver
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        inFlight = false; continuation?.resume(uri); continuation = null
    }

    override suspend fun pick(): ImportSource? = pickUri()?.let { AndroidUriImportSource(resolver, it) }

    override suspend fun pickFile(): SharingPickedFile? = pickUri()?.let { AndroidUriSharingFile(resolver, it) }

    private suspend fun pickUri(): Uri? = suspendCancellableCoroutine { pending ->
        check(continuation == null) { "Picker already active" }
        continuation = pending
        inFlight = true
        pending.invokeOnCancellation { continuation = null; inFlight = false }
        launcher.launch(arrayOf("*/*"))
    }
}

internal class AndroidUriImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {
    private val metadata by lazy { resolver.queryMetadata(uri) }
    override val displayName: String get() = metadata.first ?: "archivo"
    override val mimeHint: String? get() = resolver.getType(uri)
    override val sizeHint: Long? get() = metadata.second
    override suspend fun openRead(): ImportReadHandle {
        val stream = resolver.openInputStream(uri) ?: error("Source unavailable")
        return object : ImportReadHandle {
            override suspend fun read(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
                val buffer = ByteArray(maxBytes); val count = stream.read(buffer)
                if (count < 0) ByteArray(0) else buffer.copyOf(count)
            }
            override suspend fun close() = withContext(Dispatchers.IO) { stream.close() }
        }
    }
}

/**
 * SAF-backed source owned by the sharing runtime after send(). No plaintext cache copy is
 * created. Reads are normally sequential; a retry/backward offset safely reopens and seeks
 * the provider stream from the beginning.
 */
internal class AndroidUriSharingFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : SharingPickedFile {
    private val metadata = resolver.queryMetadata(uri)
    override val displayName: String = metadata.first ?: "archivo"
    override val size: Long = requireNotNull(metadata.second) { "Selected document does not expose a stable size" }.also {
        require(it > 0) { "Empty documents are not supported by Sharing V1" }
    }
    override val mimeType: String? = resolver.getType(uri)

    private var stream: InputStream? = null
    private var streamOffset = 0L
    private var closed = false

    override suspend fun readChunk(offset: Long, size: Int): ByteArray = withContext(Dispatchers.IO) {
        check(!closed) { "Sharing file is closed" }
        require(offset >= 0 && offset <= this@AndroidUriSharingFile.size) { "Invalid sharing read offset" }
        require(size > 0) { "Sharing read size must be positive" }
        if (offset == this@AndroidUriSharingFile.size) return@withContext ByteArray(0)

        positionAt(offset)
        val requested = minOf(size.toLong(), this@AndroidUriSharingFile.size - offset).toInt()
        val buffer = ByteArray(requested)
        var written = 0
        val input = requireNotNull(stream)
        while (written < requested) {
            val count = input.read(buffer, written, requested - written)
            if (count < 0) error("Selected document ended before its declared size")
            if (count == 0) continue
            written += count
            streamOffset += count
        }
        buffer
    }

    private fun positionAt(target: Long) {
        if (stream == null || target < streamOffset) {
            stream?.close()
            stream = resolver.openInputStream(uri) ?: error("Selected document is no longer available")
            streamOffset = 0L
        }
        val input = requireNotNull(stream)
        while (streamOffset < target) {
            val remaining = target - streamOffset
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                streamOffset += skipped
            } else {
                if (input.read() < 0) error("Selected document ended before requested offset")
                streamOffset++
            }
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        closed = true
        stream?.close()
        stream = null
    }
}

internal class AndroidFileOpener(private val context: Context, private val cache: AndroidOwnedPlaintextCache) : VaultFileOpener {
    override suspend fun open(vault: VaultHandle, file: VaultItem.File) {
        val exported = withContext(Dispatchers.IO) {
            val suffix = file.displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
            cache.create(suffix).also { target ->
                try { target.outputStream().use { output -> vault.readFile(file.id) { output.write(it) } } }
                catch (failure: Throwable) { target.delete(); throw failure }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", exported)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (failure: Throwable) { exported.delete(); throw failure }
    }
    override fun cleanup() { cache.cleanup() }
}

private fun ContentResolver.queryMetadata(uri: Uri): Pair<String?, Long?> {
    val cursor: Cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null) ?: return null to null
    return try {
        if (!cursor.moveToFirst()) return null to null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { value -> value >= 0 } else null
        name to size
    } finally { cursor.close() }
}

internal class AndroidOwnedPlaintextCache(internal val root: File) {
    init { cleanup(); check(root.mkdirs() || root.isDirectory) }

    fun create(suffix: String): File = File.createTempFile("item-", suffix, root)

    /** Best effort: lock/startup must remain available even if the OS keeps a file busy. */
    fun cleanup(): Boolean {
        val children = root.listFiles() ?: return !root.exists() || root.isDirectory
        return children.fold(true) { clean, child -> child.deleteRecursively() && clean }
    }
}
