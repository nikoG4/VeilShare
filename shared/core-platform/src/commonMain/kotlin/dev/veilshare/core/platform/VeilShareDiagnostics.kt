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

/**
 * Stable correlation-only fingerprint for high-entropy identifiers such as ReferenceCode.
 *
 * This deliberately never emits any substring of [value]. It is not a cryptographic digest
 * and must not be used for credentials or other low-entropy secrets; its only purpose is to
 * tell whether two diagnostic events refer to the same random routing identifier.
 */
fun diagnosticFingerprint(value: String): String {
    var hash = 0x811c9dc5.toInt()
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toInt() and 0xff)
        hash *= 0x01000193
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}
