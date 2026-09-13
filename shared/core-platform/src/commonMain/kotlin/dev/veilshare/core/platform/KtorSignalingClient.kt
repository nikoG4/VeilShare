package dev.veilshare.core.platform

import dev.veilshare.core.model.ErrorMessage
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.UnregisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KtorSignalingClient(
    private val httpClient: HttpClient,
    private val endpointUrl: String,
    private val random: RandomBytesSource,
    private val timeoutMillis: Long = 30_000,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) : SignalingClient {
    private val events = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    private val pending = mutableMapOf<MessageId, CompletableDeferred<SignalingEnvelope>>()
    private val pendingMutex = Mutex()
    private var scope: CoroutineScope? = null
    private var session: DefaultClientWebSocketSession? = null

    override val incoming: Flow<SignalingEnvelope> = events

    override suspend fun connect() {
        check(session == null) { "Client is already connected" }
        val opened = httpClient.webSocketSession { url(endpointUrl) }
        session = opened
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { clientScope ->
            clientScope.launch {
                receiveLoop(opened)
            }
        }
    }

    override suspend fun register(request: RegisterRequest) {
        val response = sendRequest(MessageType.REGISTER, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
    }

    override suspend fun unregister(request: UnregisterRequest) {
        val response = sendRequest(MessageType.UNREGISTER, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
    }

    override suspend fun lookup(request: LookupRequest): LookupResponse {
        val response = sendRequest(MessageType.LOOKUP, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
        return json.decodeFromString(response.payload.decodeToString())
    }

    override suspend fun relay(request: RelayRequest) {
        val envelope = envelope(MessageType.RELAY, request)
        sendEnvelope(envelope)
    }

    override suspend fun close() {
        val opened = session
        session = null
        scope?.cancel()
        scope = null
        pendingMutex.withLock {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
        opened?.close()
    }

    private suspend fun receiveLoop(opened: DefaultClientWebSocketSession) {
        try {
            for (frame in opened.incoming) {
                if (frame !is Frame.Text) continue
                val envelope = json.decodeFromString<SignalingEnvelope>(frame.readText())
                val deferred = pendingMutex.withLock { pending.remove(envelope.messageId) }
                if (deferred != null) {
                    deferred.complete(envelope)
                } else {
                    events.emit(envelope)
                }
            }
        } catch (failure: Throwable) {
            pendingMutex.withLock {
                pending.values.forEach { it.completeExceptionally(failure) }
                pending.clear()
            }
        }
    }

    private suspend inline fun <reified T> sendRequest(type: MessageType, payload: T): SignalingEnvelope {
        val envelope = envelope(type, payload)
        val deferred = CompletableDeferred<SignalingEnvelope>()
        pendingMutex.withLock { pending[envelope.messageId] = deferred }
        try {
            sendEnvelope(envelope)
            return withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pendingMutex.withLock { pending.remove(envelope.messageId) }
        }
    }

    private inline fun <reified T> envelope(type: MessageType, payload: T): SignalingEnvelope =
        SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = OpaqueIds.messageId(random),
            type = type,
            payload = json.encodeToString(payload).encodeToByteArray(),
        )

    private suspend fun sendEnvelope(envelope: SignalingEnvelope) {
        val opened = checkNotNull(session) { "Client is not connected" }
        opened.send(Frame.Text(json.encodeToString(envelope)))
    }

    private fun SignalingEnvelope.asClientException(): SignalingClientException {
        val error = runCatching { json.decodeFromString<ErrorMessage>(payload.decodeToString()) }.getOrNull()
        return SignalingClientException(error ?: ErrorMessage(dev.veilshare.core.model.ErrorCode.INVALID_MESSAGE, "Signaling error"))
    }
}

class SignalingClientException(val error: ErrorMessage) : RuntimeException(error.details)
