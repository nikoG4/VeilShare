package dev.veilshare.signaling

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.platform.KtorSignalingClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Opt-in live compatibility probe. Normal unit runs skip network access; CI enables it with
 * VEILSHARE_PRODUCTION_SIGNALING_URL. Two distinct WebSocket clients are intentional: the
 * production failure this protects against is REGISTER on one connection followed by LOOKUP
 * from another connection.
 */
class ProductionSignalingProbeTest {
    @Test
    fun productionEndpointSharesPresenceAcrossIndependentConnections() = runBlocking {
        val endpoint = System.getenv("VEILSHARE_PRODUCTION_SIGNALING_URL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@runBlocking

        val receiverEntropy = ProbeEntropy((System.nanoTime() and 0xff).toInt())
        val senderEntropy = ProbeEntropy(((System.nanoTime() ushr 8) and 0xff).toInt() + 97)
        val receiverIdentity = OpaqueIds.sharingIdentityId(receiverEntropy)
        val senderIdentity = OpaqueIds.sharingIdentityId(senderEntropy)
        val referenceCode = ReferenceCodes.generate(receiverEntropy)
        val http = HttpClient(CIO) { install(WebSockets) }
        val receiver = KtorSignalingClient(
            httpClient = http,
            endpointUrl = endpoint,
            random = receiverEntropy,
            timeoutMillis = 12_000,
        )
        val sender = KtorSignalingClient(
            httpClient = http,
            endpointUrl = endpoint,
            random = senderEntropy,
            timeoutMillis = 12_000,
        )

        try {
            receiver.connect()
            sender.connect()
            receiver.register(
                RegisterRequest(
                    receiverIdentity,
                    referenceCode,
                    "ci-production-probe-public-key",
                ),
            )

            val visible = sender.lookup(LookupRequest(referenceCode, senderIdentity))
            assertEquals(LookupStatus.FOUND, visible.status)
            assertEquals(receiverIdentity, visible.sharingIdentityId)
        } finally {
            runCatching { sender.close() }
            runCatching { receiver.close() }
            http.close()
        }
    }
}

private class ProbeEntropy(seed: Int) : RandomBytesSource {
    private var value = seed
    override fun nextBytes(size: Int): ByteArray = ByteArray(size) { ((value++ and 0xff)).toByte() }
}
