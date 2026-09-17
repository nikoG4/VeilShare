package dev.veilshare.core.platform

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.UnregisterRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface SignalingTransportEvent {
    data object Connected : SignalingTransportEvent
    data class Disconnected(val reason: String) : SignalingTransportEvent
}

interface SignalingClient {
    val incoming: Flow<SignalingEnvelope>
    val transportEvents: Flow<SignalingTransportEvent> get() = emptyFlow()

    suspend fun connect()
    suspend fun register(request: RegisterRequest)

    /**
     * Removes server-side presence for the supplied sharing identity.
     *
     * Implementations that cannot revoke presence must fail closed rather than silently
     * ignoring the request, because callers use this before rotating a ReferenceCode.
     */
    suspend fun unregister(request: UnregisterRequest) {
        throw UnsupportedOperationException("Signaling UNREGISTER is not supported by this client")
    }

    suspend fun lookup(request: LookupRequest): LookupResponse
    suspend fun relay(request: RelayRequest)
    suspend fun close()
}
