package dev.veilshare.app

import android.content.Context
import dev.veilshare.core.crypto.AndroidChaCha20Poly1305Cipher
import dev.veilshare.core.crypto.AndroidProductionCrypto
import dev.veilshare.core.crypto.AndroidSecureRandom
import dev.veilshare.core.platform.KtorSignalingClient
import dev.veilshare.core.securestore.AndroidSecureStateFactory
import dev.veilshare.ui.features.SharingRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun createAndroidSharingRuntime(
    context: Context,
    endpointUrl: String,
): SharingRuntime {
    require(endpointUrl.startsWith("ws://") || endpointUrl.startsWith("wss://")) {
        "Sharing signaling endpoint must use ws:// or wss://"
    }
    val applicationContext = context.applicationContext
    val cryptoRandom = AndroidSecureRandom()
    val idRandom = SecureRandomIdSource(cryptoRandom)
    val cipher = AndroidChaCha20Poly1305Cipher(cryptoRandom)
    val httpClient = HttpClient(CIO) { install(WebSockets) }
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val signaling = KtorSignalingClient(httpClient, endpointUrl, idRandom)
    val runtime = try {
        composeDefaultSharingRuntime(
            protectedStateStore = AndroidSecureStateFactory.create(applicationContext),
            signalingClient = signaling,
            signer = AndroidProductionCrypto.ed25519Signer(),
            keyAgreement = AndroidProductionCrypto.x25519KeyAgreement(),
            keyDeriver = AndroidProductionCrypto.keyDeriver(),
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
