package dev.veilshare.android

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.veilshare.ui.features.ReferenceCodeQrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidReferenceCodeQrProvider : ReferenceCodeQrProvider {
    override val available: Boolean = true

    override suspend fun render(referenceCode: String, sizePx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        if (referenceCode.isBlank() || sizePx <= 0) return@withContext null
        runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(referenceCode, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            var index = 0
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    pixels[index++] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }.asImageBitmap()
        }.getOrNull()
    }
}
