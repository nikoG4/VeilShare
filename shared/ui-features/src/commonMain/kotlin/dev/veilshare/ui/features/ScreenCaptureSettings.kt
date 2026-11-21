package dev.veilshare.ui.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local screenshot preference; the Android implementation must always guard locked UI.
 */
interface ScreenCaptureSettings {
    val supported: Boolean
    val allowed: StateFlow<Boolean>
    fun setAllowed(value: Boolean): Boolean
    fun onPrivateSessionChanged(active: Boolean) {}
    fun onForegroundChanged(foreground: Boolean) {}
}

object UnavailableScreenCaptureSettings : ScreenCaptureSettings {
    override val supported: Boolean = false
    override val allowed: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setAllowed(value: Boolean): Boolean = false
}
