package dev.veilshare.android

import android.content.Context
import android.view.Window
import android.view.WindowManager
import dev.veilshare.ui.features.ScreenCaptureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Secure by default. Screenshots are enabled only for foreground authenticated sessions.
 */
internal class AndroidScreenCaptureSettings(context: Context, private val window: Window) : ScreenCaptureSettings {
    private val preferences = context.applicationContext.getSharedPreferences("capture_policy", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("allowed_when_unlocked", false))
    override val supported: Boolean = true
    override val allowed: StateFlow<Boolean> = state

    private var privateSession = false
    private var foreground = false

    init { applyWindowPolicy() }

    override fun setAllowed(value: Boolean): Boolean {
        if (!preferences.edit().putBoolean("allowed_when_unlocked", value).commit()) return false
        state.value = value
        applyWindowPolicy()
        return true
    }

    override fun onPrivateSessionChanged(active: Boolean) {
        privateSession = active
        applyWindowPolicy()
    }

    override fun onForegroundChanged(foreground: Boolean) {
        this.foreground = foreground
        applyWindowPolicy()
    }

    private fun applyWindowPolicy() {
        if (privateSession && foreground && state.value) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
