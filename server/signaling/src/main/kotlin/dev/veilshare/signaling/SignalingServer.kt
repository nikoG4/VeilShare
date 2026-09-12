package dev.veilshare.signaling

import dev.veilshare.core.model.ErrorCode
import dev.veilshare.core.model.ErrorMessage
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.PingMessage
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.model.ConnectionId
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SignalingServerState(
    clock: SignalingClock = SignalingClock { System.currentTimeMillis() },
    val limits: SignalingLimits = SignalingLimits(),
) {
    val presence = PresenceRegistry(clock, limits)
    val sessions = SessionRegistry(clock, limits)
    val lookupLimiter = FixedWindowRateLimiter(clock, limits.lookupWindowMillis, limits.maxLookupsPerWindow)

    // Reserved for explicit session-establishment operations. RELAY must not consume this
    // budget because a legitimate encrypted chunk can require many transport fragments.
    val sessionLimiter = FixedWindowRateLimiter(clock, limits.sessionCreateWindowMillis, limits.maxSessionCreatesPerWindow)

    val relayLimiter = FixedWindowBudgetLimiter(
        clock = clock,
        windowMillis = limits.relayWindowMillis,
        maxEvents = limits.maxRelayMessagesPerWindow,
        maxCost = limits.maxRelayPayloadBytesPerWindow,
    )
    val sockets = ConcurrentHashMap<ConnectionId, DefaultWebSocketServerSession>()
}

private val json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

fun Application.signalingModule(state: SignalingServerState = SignalingServerState()) {
    install(WebSockets)
    routing {
        webSocket("/v1/ws") {
            val connectionId = ConnectionId(UUID.randomUUID().toString())
            state.sockets[connectionId] = this
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        sendError(MessageId("unknown"), ErrorCode.INVALID_MESSAGE, "Only text frames are accepted")
                        continue
                    }
                    val text = frame.readText()
                    // SignalingEnvelope.payload is Base64 on the JSON wire, so the outer text
                    // may legitimately exceed the decoded 64 KiB payload cap by ~4/3.
                    // 2x remains a conservative hard bound against oversized text frames.
                    if (text.encodeToByteArray().size > SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES * 2) {
                        sendError(MessageId("unknown"), ErrorCode.INVALID_MESSAGE, "Frame too large")
                        continue
                    }
                    handleFrame(state, connectionId, text)
                }
            } catch (_: ClosedReceiveChannelException) {
            } finally {
                state.presence.unregisterConnection(connectionId)
                state.sessions.closeConnection(connectionId)
                state.sockets.remove(connectionId)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleFrame(
    state: SignalingServerState,
    connectionId: ConnectionId,
    text: String,
) {
    val envelope = try {
        json.decodeFromString<SignalingEnvelope>(text)
    } catch (_: IllegalArgumentException) {
        sendError(MessageId("unknown"), ErrorCode.INVALID_MESSAGE, "Malformed envelope")
        return
    } catch (_: Exception) {
        sendError(MessageId("unknown"), ErrorCode.INVALID_MESSAGE, "Malformed envelope")
        return
    }
    try {
        when (envelope.type) {
            MessageType.REGISTER -> handleRegister(state, connectionId, envelope)
            MessageType.UNREGISTER -> handleUnregister(state, connectionId, envelope)
            MessageType.LOOKUP -> handleLookup(state, connectionId, envelope)
            MessageType.RELAY -> handleRelay(state, connectionId, envelope)
            MessageType.PING -> handlePing(envelope)
            MessageType.ERROR -> sendError(envelope.messageId, ErrorCode.INVALID_MESSAGE, "Client ERROR frames are not accepted")
        }
    } catch (_: IllegalArgumentException) {
        sendError(envelope.messageId, ErrorCode.INVALID_MESSAGE, "Invalid message")
    }
}

private suspend fun DefaultWebSocketServerSession.handleRegister(
    state: SignalingServerState,
    connectionId: ConnectionId,
    envelope: SignalingEnvelope,
) {
    val request = decodePayload<RegisterRequest>(envelope)
    state.presence.register(
        referenceCode = request.referenceCode,
        connectionId = connectionId,
        sharingIdentityId = request.sharingIdentityId,
        sharingPublicKey = request.sharingPublicKey,
    )
    sendEnvelope(envelope.copy(payload = ByteArray(0)))
}

private suspend fun DefaultWebSocketServerSession.handleUnregister(
    state: SignalingServerState,
    connectionId: ConnectionId,
    envelope: SignalingEnvelope,
) {
    val request = decodePayload<UnregisterRequest>(envelope)
    val owned = state.presence.lookupByIdentity(request.sharingIdentityId)
        .filter { it.connectionId == connectionId }
        .map { it.referenceCode }
    owned.forEach { state.presence.unregister(it, connectionId) }
    sendEnvelope(envelope.copy(payload = ByteArray(0)))
}

private suspend fun DefaultWebSocketServerSession.handleLookup(
    state: SignalingServerState,
    connectionId: ConnectionId,
    envelope: SignalingEnvelope,
) {
    if (!state.lookupLimiter.allow(connectionId.value)) {
        sendError(envelope.messageId, ErrorCode.RATE_LIMITED, "Rate limited")
        return
    }
    val request = decodePayload<LookupRequest>(envelope)
    val entry = state.presence.lookup(request.referenceCode)
    val response = if (entry == null) {
        LookupResponse(status = LookupStatus.NOT_FOUND)
    } else {
        LookupResponse(
            status = LookupStatus.FOUND,
            sharingIdentityId = entry.sharingIdentityId,
            sharingPublicKey = entry.sharingPublicKey,
        )
    }
    sendEnvelope(envelope.copy(payload = json.encodeToString(response).encodeToByteArray()))
}

private suspend fun DefaultWebSocketServerSession.handleRelay(
    state: SignalingServerState,
    connectionId: ConnectionId,
    envelope: SignalingEnvelope,
) {
    val request = decodePayload<RelayRequest>(envelope)

    // Rate-limit the data plane by both messages and actual decoded request bytes.
    // This permits bounded fragmentation while preventing an authenticated connection
    // from relaying unbounded traffic in one window.
    if (!state.relayLimiter.allow(connectionId.value, envelope.payload.size.toLong())) {
        sendError(envelope.messageId, ErrorCode.RATE_LIMITED, "Relay rate limited")
        return
    }

    val target = state.presence.lookup(request.toReferenceCode)
    val socket = target?.let { state.sockets[it.connectionId] }
    if (socket == null) {
        sendError(envelope.messageId, ErrorCode.SESSION_NOT_FOUND, "Target unavailable")
        return
    }
    socket.sendEnvelope(envelope.copy(sessionId = request.sessionId, payload = request.opaquePayload))
}

private suspend fun DefaultWebSocketServerSession.handlePing(envelope: SignalingEnvelope) {
    decodePayload<PingMessage>(envelope)
    sendEnvelope(envelope.copy(payload = ByteArray(0)))
}

private inline fun <reified T> decodePayload(envelope: SignalingEnvelope): T =
    json.decodeFromString(envelope.payload.decodeToString())

private suspend fun DefaultWebSocketServerSession.sendEnvelope(envelope: SignalingEnvelope) {
    send(Frame.Text(json.encodeToString(envelope)))
}

private suspend fun DefaultWebSocketServerSession.sendError(
    messageId: MessageId,
    code: ErrorCode,
    details: String,
) {
    val error = ErrorMessage(code, details)
    sendEnvelope(
        SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = messageId,
            type = MessageType.ERROR,
            payload = json.encodeToString(error).encodeToByteArray(),
        ),
    )
}
