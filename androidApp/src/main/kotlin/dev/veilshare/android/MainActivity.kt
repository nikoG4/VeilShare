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
import dev.veilshare.core.vault.*
import dev.veilshare.ui.design.VeilWindowClass
import dev.veilshare.ui.features.LocalFilePicker
import dev.veilshare.ui.features.VaultFileOpener
import java.io.File
import java.io.FileInputStream
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
        val openRoot = File(cacheDir, "open-4f16a9").also(::clearOwnedRoot)
        val environment = AppEnvironment(
            AndroidLocalVaultService(AndroidVaultStorage.privateRoot(this)), picker,
            AndroidFileOpener(this, openRoot), lockSignals,
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

private class AndroidDocumentPicker(activity: ComponentActivity) : LocalFilePicker {
    private var continuation: Continuation<Uri?>? = null
    var inFlight: Boolean = false; private set
    private val resolver = activity.contentResolver
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        inFlight = false; continuation?.resume(uri); continuation = null
    }

    override suspend fun pick(): ImportSource? {
        val uri = suspendCancellableCoroutine<Uri?> { pending ->
            check(continuation == null) { "Picker already active" }
            continuation = pending
            inFlight = true
            pending.invokeOnCancellation { continuation = null; inFlight = false }
            launcher.launch(arrayOf("*/*"))
        } ?: return null
        return AndroidUriImportSource(resolver, uri)
    }
}

private class AndroidUriImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {
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

private class AndroidFileOpener(private val context: Context, private val root: File) : VaultFileOpener {
    override suspend fun open(vault: VaultHandle, file: VaultItem.File) {
        val exported = withContext(Dispatchers.IO) {
            val suffix = file.displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
            File.createTempFile("view-", suffix, root).also { target ->
                try { target.outputStream().use { output -> vault.readFile(file.id) { output.write(it) } } }
                catch (failure: Throwable) { target.delete(); throw failure }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", exported)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (failure: Throwable) { exported.delete(); throw failure }
    }
    override fun cleanup() { root.listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
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

private fun clearOwnedRoot(root: File) {
    if (root.exists()) root.listFiles()?.forEach { if (it.isFile) it.delete() }
    check(root.mkdirs() || root.isDirectory)
}
