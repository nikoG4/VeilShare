package dev.veilshare.core.platform

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import kotlinx.coroutines.flow.Flow

interface SignalingClient {
    val incoming: Flow<SignalingEnvelope>

    suspend fun connect()
    suspend fun register(request: RegisterRequest)
    suspend fun lookup(request: LookupRequest): LookupResponse
    suspend fun relay(request: RelayRequest)
    suspend fun close()
}
