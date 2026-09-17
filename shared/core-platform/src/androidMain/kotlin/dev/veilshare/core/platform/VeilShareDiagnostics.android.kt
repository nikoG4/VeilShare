package dev.veilshare.core.platform

import android.util.Log

actual object VeilShareDiagnostics {
    actual fun signal(event: String, fields: String) = emit("VeilShareSignal", event, fields)
    actual fun share(event: String, fields: String) = emit("VeilShareShare", event, fields)
    actual fun e2e(event: String, fields: String) = emit("VeilShareE2E", event, fields)

    private fun emit(tag: String, event: String, fields: String) {
        Log.i(tag, buildString {
            append("event=").append(event)
            if (fields.isNotBlank()) append(' ').append(fields)
        })
    }
}
