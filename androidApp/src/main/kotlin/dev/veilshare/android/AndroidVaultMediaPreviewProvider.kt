package dev.veilshare.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.veilshare.core.vault.LocalUnlockResult
import dev.veilshare.core.vault.LocalVaultService
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import dev.veilshare.core.vault.VaultItemId
import dev.veilshare.ui.features.MediaPreviewProvider
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Produces gallery/viewer images without ever materializing plaintext media on disk.
 * The cache contains only downsampled Bitmaps and is cleared as soon as the vault locks.
 */
internal class AndroidVaultMediaPreviewProvider : MediaPreviewProvider {
    private val bindingLock = Any()
    private val decodeMutex = Mutex()
    private var boundVault: VaultHandle? = null
    private val cache = object : LruCache<String, Bitmap>(PREVIEW_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024)
    }

    fun bind(vault: VaultHandle) {
        synchronized(bindingLock) {
            if (boundVault !== vault) {
                cache.evictAll()
                boundVault = vault
            }
        }
    }

    override suspend fun load(itemId: String, maxDimensionPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (maxDimensionPx <= 0) return@withContext null
        val cacheKey = "$itemId@$maxDimensionPx"
        cache.get(cacheKey)?.let { return@withContext it.asImageBitmap() }

        decodeMutex.withLock {
            cache.get(cacheKey)?.let { return@withLock it.asImageBitmap() }
            val vault = synchronized(bindingLock) { boundVault } ?: return@withLock null
            if (!vault.isOpen) return@withLock null
            val file = runCatching { vault.find(VaultItemId(itemId)) as? VaultItem.File }.getOrNull()
                ?: return@withLock null
            if (!file.isPreviewableImage()) return@withLock null
            if (file.size > MAX_PREVIEW_SOURCE_BYTES) return@withLock null

            val bytes = try {
                val initialCapacity = minOf(file.size, 4L * 1024 * 1024, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1024)
                ByteArrayOutputStream(initialCapacity).use { output ->
                    var written = 0L
                    vault.readFile(file.id) { chunk ->
                        written += chunk.size
                        check(written <= MAX_PREVIEW_SOURCE_BYTES) { "Preview source is too large" }
                        output.write(chunk)
                    }
                    output.toByteArray()
                }
            } catch (_: Throwable) {
                return@withLock null
            }

            val bitmap = try {
                decodeDownsampled(bytes, maxDimensionPx)
            } catch (_: Throwable) {
                null
            } finally {
                bytes.fill(0)
            } ?: return@withLock null

            val stillBound = synchronized(bindingLock) { boundVault === vault && vault.isOpen }
            if (!stillBound) return@withLock null
            cache.put(cacheKey, bitmap)
            bitmap.asImageBitmap()
        }
    }

    override fun clear() {
        synchronized(bindingLock) {
            boundVault = null
            cache.evictAll()
        }
    }

    private fun VaultItem.File.isPreviewableImage(): Boolean {
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) return true
        return displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private fun decodeDownsampled(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val longest = max(width, height)
                if (longest > maxDimensionPx && longest > 0) {
                    val scale = maxDimensionPx.toFloat() / longest.toFloat()
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimensionPx * 2) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }
    }

    private companion object {
        const val PREVIEW_CACHE_KIB = 32 * 1024
        const val MAX_PREVIEW_SOURCE_BYTES = 64L * 1024 * 1024
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    }
}

/** Keeps the preview provider bound to exactly the same authenticated VaultHandle as the UI. */
internal class PreviewTrackingLocalVaultService(
    private val delegate: LocalVaultService,
    private val previews: AndroidVaultMediaPreviewProvider,
) : LocalVaultService by delegate {
    override suspend fun unlock(credential: CharArray): LocalUnlockResult {
        return delegate.unlock(credential).also { result ->
            when (result) {
                is LocalUnlockResult.Ready -> previews.bind(result.vault)
                LocalUnlockResult.InvalidCredential,
                LocalUnlockResult.Corrupt -> previews.clear()
            }
        }
    }
}
