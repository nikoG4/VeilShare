package dev.veilshare.app

import dev.veilshare.core.crypto.JvmChaCha20Poly1305Cipher
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmSecureRandom
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.platform.KtorSignalingClient
import dev.veilshare.core.securestore.DesktopSecureStateFactory
import dev.veilshare.ui.features.SharingRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Windows production composition. Desktop secure persistence intentionally fails closed off Windows. */
fun createDesktopSharingRuntime(
    stateRoot: Path,
    endpointUrl: String,
    allowInsecureLoopback: Boolean = false,
): SharingRuntime {
    val validatedEndpoint = validateSignalingEndpoint(endpointUrl, allowInsecureLoopback)
    val cryptoRandom = JvmSecureRandom()
    val idRandom = SecureRandomIdSource(cryptoRandom)
    val cipher = JvmChaCha20Poly1305Cipher(cryptoRandom)
    val httpClient = HttpClient(CIO) { install(WebSockets) }
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val signaling = KtorSignalingClient(httpClient, validatedEndpoint, idRandom)
    val runtime = try {
        composeDefaultSharingRuntime(
            protectedStateStore = DesktopSecureStateFactory.windows(stateRoot),
            signalingClient = signaling,
            signer = JvmEd25519Signer(),
            keyAgreement = JvmX25519KeyAgreement(),
            keyDeriver = JvmHkdfSha256KeyDeriver(),
            cipher = cipher,
            cryptoRandom = cryptoRandom,
            idRandom = idRandom,
            runtimeScope = runtimeScope,
        )
    } catch (failure: Throwable) {
        httpClient.close()
        throw failure
    }
    return OwnedSharingRuntime(runtime, runtimeScope, httpClient::close)
}
