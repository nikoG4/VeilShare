package dev.veilshare.app

/**
 * Validates the user/build supplied signaling endpoint before any network client is built.
 *
 * Production callers should keep [allowInsecureLoopback] false. Plain ws:// is accepted
 * only for explicitly enabled loopback/emulator development targets so a packaged beta
 * cannot silently downgrade signaling transport security.
 */
fun validateSignalingEndpoint(
    rawEndpoint: String,
    allowInsecureLoopback: Boolean = false,
): String {
    val endpoint = rawEndpoint.trim()
    require(endpoint.isNotEmpty()) { "Sharing signaling endpoint is required" }
    require(endpoint.none(Char::isWhitespace)) { "Sharing signaling endpoint must not contain whitespace" }

    if (endpoint.startsWith("wss://", ignoreCase = true)) {
        require(authorityHost(endpoint).isNotEmpty()) { "Sharing signaling endpoint host is required" }
        return endpoint
    }

    require(endpoint.startsWith("ws://", ignoreCase = true)) {
        "Sharing signaling endpoint must use wss:// (or explicitly allowed local ws://)"
    }
    val host = authorityHost(endpoint)
    require(allowInsecureLoopback && host in INSECURE_DEVELOPMENT_HOSTS) {
        "Plain ws:// signaling is allowed only for explicit local development"
    }
    return endpoint
}

private fun authorityHost(endpoint: String): String {
    val authority = endpoint.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    require(authority.isNotEmpty() && '@' !in authority) { "Sharing signaling endpoint authority is invalid" }

    return if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        require(end > 1) { "Sharing signaling endpoint IPv6 host is invalid" }
        authority.substring(0, end + 1).lowercase()
    } else {
        authority.substringBefore(':').lowercase()
    }
}

private val INSECURE_DEVELOPMENT_HOSTS = setOf(
    "localhost",
    "127.0.0.1",
    "[::1]",
    // Android emulator's host-loopback bridge.
    "10.0.2.2",
)
