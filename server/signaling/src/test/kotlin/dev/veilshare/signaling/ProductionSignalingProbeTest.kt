package dev.veilshare.signaling

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.KtorSignalingClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Opt-in live compatibility probe. Normal unit runs skip network access; CI enables it with
 * VEILSHARE_PRODUCTION_SIGNALING_URL so a client/server wire-format mismatch cannot ship.
 */
class ProductionSignalingProbeTest {
    @Test
    fun productionEndpointAcceptsCurrentRegisterLookupAndUnregisterWireFormat() = runBlocking {
        val endpoint = System.getenv("VEILSHARE_PRODUCTION_SIGNALING_URL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@runBlocking
        val entropy = ProbeEntropy((System.nanoTime() and 0xff).toInt())
        val identity = OpaqueIds.sharingIdentityId(entropy)
        val referenceCode = ReferenceCodes.generate(entropy)
        val http = HttpClient(CIO) { install(WebSockets) }
        val client = KtorSignalingClient(
            httpClient = http,
            endpointUrl = endpoint,
            random = entropy,
            timeoutMillis = 12_000,
            registrationKeepAliveMillis = 30_000,
        )
        try {
            client.connect()
            client.register(RegisterRequest(identity, referenceCode, "ci-production-probe-public-key"))
            val visible = client.lookup(LookupRequest(referenceCode, identity))
            assertEquals(LookupStatus.FOUND, visible.status)
            assertEquals(identity, visible.sharingIdentityId)

            client.unregister(UnregisterRequest(identity))
            val removed = client.lookup(LookupRequest(referenceCode, identity))
            assertEquals(LookupStatus.NOT_FOUND, removed.status)
        } finally {
            runCatching { client.close() }
            http.close()
        }
    }
}

private class ProbeEntropy(seed: Int) : RandomBytesSource {
    private var value = seed
    override fun nextBytes(size: Int): ByteArray = ByteArray(size) { ((value++ and 0xff)).toByte() }
}
