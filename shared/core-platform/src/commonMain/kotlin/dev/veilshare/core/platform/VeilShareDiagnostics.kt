package dev.veilshare.core.platform

/**
 * Small, deliberately payload-free diagnostic sink for field reproduction.
 *
 * Callers must only pass event names and already-redacted scalar metadata. In
 * particular, never pass credentials, keys, plaintext, file names, reference
 * codes, or opaque frame payloads.
 */
expect object VeilShareDiagnostics {
    fun signal(event: String, fields: String = "")
    fun share(event: String, fields: String = "")
    fun e2e(event: String, fields: String = "")
}

fun diagnosticId(value: String): String = value.take(8)
