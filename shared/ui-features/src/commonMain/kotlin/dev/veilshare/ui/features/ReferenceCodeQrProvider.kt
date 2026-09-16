package dev.veilshare.ui.features

import androidx.compose.ui.graphics.ImageBitmap

/** Platform QR renderer for the short-lived sharing reference code. */
interface ReferenceCodeQrProvider {
    val available: Boolean
    suspend fun render(referenceCode: String, sizePx: Int = 768): ImageBitmap?
}

object UnavailableReferenceCodeQrProvider : ReferenceCodeQrProvider {
    override val available: Boolean = false
    override suspend fun render(referenceCode: String, sizePx: Int): ImageBitmap? = null
}
