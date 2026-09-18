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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

class KtorSignalingClient(
    private val httpClient: HttpClient,
    private val endpointUrl: String,
    private val random: RandomBytesSource,
    private val timeoutMillis: Long = 30_000,
    private val registrationKeepAliveMillis: Long = 45_000,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) : SignalingClient {
    private val events = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 64)
    private val transportLifecycle = MutableSharedFlow<SignalingTransportEvent>(extraBufferCapacity = 8)
    private val pending = mutableMapOf<MessageId, CompletableDeferred<SignalingEnvelope>>()
    private val pendingMutex = Mutex()
    private val connectionMutex = Mutex()
    private var scope: CoroutineScope? = null
    private var session: DefaultClientWebSocketSession? = null
    private var registeredPresence: RegisterRequest? = null
    private var keepAliveJob: Job? = null

    init {
        require(timeoutMillis > 0)
        require(registrationKeepAliveMillis > 0)
    }

    override val incoming: Flow<SignalingEnvelope> = events
    override val transportEvents: Flow<SignalingTransportEvent> = transportLifecycle

    override suspend fun connect() {
        connectionMutex.withLock {
            val current = session
            if (current?.coroutineContext?.get(Job)?.isActive == true) {
                VeilShareDiagnostics.signal("ws_connect_reuse", "sessionJobActive=true")
                return
            }

            keepAliveJob?.cancel()
            keepAliveJob = null
            scope?.cancel()
            scope = null
            runCatching { current?.close() }
            session = null

            val start = TimeSource.Monotonic.markNow()
            VeilShareDiagnostics.signal("ws_connect_start", "endpointConfigured=true")
            val opened = try {
                httpClient.webSocketSession { url(endpointUrl) }
            } catch (failure: Throwable) {
                VeilShareDiagnostics.signal(
                    "ws_connect_failure",
                    "elapsedMs=${start.elapsedNow().inWholeMilliseconds} errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}",
                )
                throw failure
            }
            VeilShareDiagnostics.signal(
                "ws_connect_success",
                "elapsedMs=${start.elapsedNow().inWholeMilliseconds} sessionJobActive=${opened.coroutineContext[Job]?.isActive == true}",
            )
            session = opened
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { clientScope ->
                clientScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    VeilShareDiagnostics.signal("receive_loop_launch", "sessionJobActive=${opened.coroutineContext[Job]?.isActive == true}")
                    receiveLoop(opened)
                }
            }
            transportLifecycle.tryEmit(SignalingTransportEvent.Connected)
        }
    }

    override suspend fun register(request: RegisterRequest) {
        VeilShareDiagnostics.signal("register_start")
        val response = sendRequest(MessageType.REGISTER, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
        registeredPresence = request
        startRegistrationKeepAlive()
        VeilShareDiagnostics.signal("register_complete")
    }

    override suspend fun unregister(request: UnregisterRequest) {
        VeilShareDiagnostics.signal("unregister_start")
        val response = sendRequest(MessageType.UNREGISTER, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
        if (registeredPresence?.sharingIdentityId == request.sharingIdentityId) {
            registeredPresence = null
            keepAliveJob?.cancel()
            keepAliveJob = null
        }
    }

    override suspend fun lookup(request: LookupRequest): LookupResponse {
        VeilShareDiagnostics.signal("lookup_start")
        val response = sendRequest(MessageType.LOOKUP, request)
        if (response.type == MessageType.ERROR) throw response.asClientException()
        val decoded = json.decodeFromString<LookupResponse>(response.payload.decodeToString())
        VeilShareDiagnostics.signal("lookup_complete", "status=${decoded.status}")
        return decoded
    }

    override suspend fun relay(request: RelayRequest) {
        VeilShareDiagnostics.signal("relay_start", "sessionId=${diagnosticId(request.sessionId.value)} bytes=${request.opaquePayload.size}")
        val envelope = envelope(MessageType.RELAY, request)
        sendEnvelope(envelope)
    }

    override suspend fun close() {
        VeilShareDiagnostics.signal("connection_close_start")
        val (opened, oldScope) = connectionMutex.withLock {
            val active = session
            val activeScope = scope
            session = null
            scope = null
            active to activeScope
        }
        keepAliveJob?.cancel()
        keepAliveJob = null
        registeredPresence = null
        oldScope?.cancel()
        failPending(CancellationException("Signaling client closed"))
        opened?.close()
        VeilShareDiagnostics.signal("connection_close_complete")
    }

    private fun startRegistrationKeepAlive() {
        keepAliveJob?.cancel()
        val clientScope = scope ?: return
        keepAliveJob = clientScope.launch {
            while (isActive) {
                delay(registrationKeepAliveMillis)
                val registration = registeredPresence ?: return@launch
                try {
                    val response = sendRequest(MessageType.REGISTER, registration)
                    if (response.type == MessageType.ERROR) throw response.asClientException()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // Never reconnect here: a transport loss may have happened during an
                    // authenticated transfer. The current operation must fail first; a later
                    // explicit runtime action can establish and register a fresh transport.
                    VeilShareDiagnostics.signal("registration_keepalive_failure", "errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}")
                    return@launch
                }
            }
        }
    }

    private suspend fun receiveLoop(opened: DefaultClientWebSocketSession) {
        var terminalFailure: Throwable? = null
        VeilShareDiagnostics.signal("receive_loop_start")
        try {
            for (frame in opened.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                VeilShareDiagnostics.signal("frame_rx", "bytes=${text.encodeToByteArray().size}")
                val envelope = try {
                    json.decodeFromString<SignalingEnvelope>(text)
                } catch (failure: Throwable) {
                    VeilShareDiagnostics.signal("frame_decode_failure", "errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}")
                    throw failure
                }
                val deferred = pendingMutex.withLock { pending.remove(envelope.messageId) }
                VeilShareDiagnostics.signal(
                    "response_match",
                    "type=${envelope.type} messageId=${diagnosticId(envelope.messageId.value)} matchedPending=${deferred != null}",
                )
                if (deferred != null) {
                    deferred.complete(envelope)
                } else {
                    VeilShareDiagnostics.signal("event_dispatch", "type=${envelope.type} sessionId=${envelope.sessionId?.value?.let(::diagnosticId) ?: "none"}")
                    events.emit(envelope)
                }
            }
            terminalFailure = SignalingConnectionClosedException("Signaling connection closed")
            VeilShareDiagnostics.signal("receive_loop_closed", "reason=channel_complete")
        } catch (cancelled: CancellationException) {
            VeilShareDiagnostics.signal("receive_loop_cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            terminalFailure = failure
            VeilShareDiagnostics.signal("receive_loop_failure", "errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}")
        } finally {
            // A stale loop must not invalidate a replacement session.
            terminalFailure?.let { disconnectCurrent(opened, it) }
        }
    }

    private suspend fun failPending(failure: Throwable) {
        val waiting = pendingMutex.withLock {
            pending.values.toList().also { pending.clear() }
        }
        waiting.forEach { it.completeExceptionally(failure) }
    }

    private suspend inline fun <reified T> sendRequest(type: MessageType, payload: T): SignalingEnvelope {
        val envelope = envelope(type, payload)
        val deferred = CompletableDeferred<SignalingEnvelope>()
        pendingMutex.withLock { pending[envelope.messageId] = deferred }
        val start = TimeSource.Monotonic.markNow()
        VeilShareDiagnostics.signal("request_prepare", "type=$type messageId=${diagnosticId(envelope.messageId.value)} pendingRegistered=true")
        try {
            VeilShareDiagnostics.signal("request_send_start", "type=$type messageId=${diagnosticId(envelope.messageId.value)}")
            sendEnvelope(envelope)
            VeilShareDiagnostics.signal("request_send_complete", "type=$type messageId=${diagnosticId(envelope.messageId.value)}")
            return try {
                withTimeout(timeoutMillis) { deferred.await() }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.TimeoutCancellationException) {
                    VeilShareDiagnostics.signal("request_timeout", "type=$type messageId=${diagnosticId(envelope.messageId.value)} elapsedMs=${start.elapsedNow().inWholeMilliseconds}")
                } else {
                    VeilShareDiagnostics.signal("request_failure", "type=$type messageId=${diagnosticId(envelope.messageId.value)} elapsedMs=${start.elapsedNow().inWholeMilliseconds} errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}")
                }
                throw failure
            }.also {
                VeilShareDiagnostics.signal("request_complete", "type=$type messageId=${diagnosticId(envelope.messageId.value)} elapsedMs=${start.elapsedNow().inWholeMilliseconds} responseType=${it.type}")
            }
        } finally {
            pendingMutex.withLock { pending.remove(envelope.messageId) }
            VeilShareDiagnostics.signal("request_cleanup", "type=$type messageId=${diagnosticId(envelope.messageId.value)}")
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
        val opened = connectionMutex.withLock { session }
            ?: throw SignalingConnectionClosedException("Signaling client is not connected")
        try {
            val serialized = json.encodeToString(envelope)
            VeilShareDiagnostics.signal("frame_tx", "type=${envelope.type} messageId=${diagnosticId(envelope.messageId.value)} bytes=${serialized.encodeToByteArray().size}")
            opened.send(Frame.Text(serialized))
            VeilShareDiagnostics.signal("frame_tx_success", "type=${envelope.type} messageId=${diagnosticId(envelope.messageId.value)}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            VeilShareDiagnostics.signal("frame_tx_failure", "type=${envelope.type} messageId=${diagnosticId(envelope.messageId.value)} errorClass=${failure::class.simpleName ?: "Unknown"} message=${failure.message.safeDiagnostic()}")
            val closed = SignalingConnectionClosedException("Signaling send failed", failure)
            disconnectCurrent(opened, closed)
            throw closed
        }
    }

    private suspend fun disconnectCurrent(
        opened: DefaultClientWebSocketSession,
        failure: Throwable,
    ) {
        val wasCurrent = connectionMutex.withLock {
            if (session !== opened) false else {
                session = null
                true
            }
        }
        if (!wasCurrent) return
        // incoming can complete while the underlying peer still believes this socket is
        // alive. Explicitly close it so the signaling server can immediately revoke presence.
        runCatching { opened.close() }
        keepAliveJob?.cancel()
        keepAliveJob = null
        registeredPresence = null
        failPending(failure)
        val reason = failure::class.simpleName ?: "closed"
        VeilShareDiagnostics.signal("ws_disconnect", "reason=$reason")
        transportLifecycle.emit(SignalingTransportEvent.Disconnected(reason))
    }

    private fun SignalingEnvelope.asClientException(): SignalingClientException {
        val error = runCatching { json.decodeFromString<ErrorMessage>(payload.decodeToString()) }.getOrNull()
        return SignalingClientException(error ?: ErrorMessage(dev.veilshare.core.model.ErrorCode.INVALID_MESSAGE, "Signaling error"))
    }
}

private fun String?.safeDiagnostic(): String = this
    ?.replace(Regex("[\\r\\n\\t]"), " ")
    ?.take(160)
    ?.ifBlank { "none" }
    ?: "none"

class SignalingClientException(val error: ErrorMessage) : RuntimeException(error.details)
class SignalingConnectionClosedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
