package dev.veilshare.core.platform

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.UnregisterRequest
import kotlinx.coroutines.flow.Flow

interface SignalingClient {
    val incoming: Flow<SignalingEnvelope>

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
