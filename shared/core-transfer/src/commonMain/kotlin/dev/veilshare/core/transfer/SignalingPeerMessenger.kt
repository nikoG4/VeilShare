package dev.veilshare.core.transfer

import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.platform.SignalingClient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Typed bridge between peer protocol messages and the blind signaling relay.
 * UI/session code sends DecodedPeerMessage values rather than hand-building nested JSON.
 */
class SignalingPeerMessenger(
    private val signalingClient: SignalingClient,
    private val sessionId: SessionId,
    private val transferId: TransferId,
    private val peerReferenceCode: ReferenceCode,
    private val peerCodec: PeerMessageCodec = PeerMessageCodec(),
    private val json: Json = strictPeerJson(),
) {
    suspend fun send(message: DecodedPeerMessage) {
        val peerEnvelope = peerCodec.encode(sessionId, transferId, message)
        val opaquePayload = json.encodeToString(peerEnvelope).encodeToByteArray()
        require(opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized PeerEnvelope is ${opaquePayload.size} bytes, exceeds relay payload limit ${SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES}"
        }

        val request = RelayRequest(
            toReferenceCode = peerReferenceCode,
            sessionId = sessionId,
            opaquePayload = opaquePayload,
        )
        val serializedRequest = json.encodeToString(request).encodeToByteArray()
        require(serializedRequest.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized RelayRequest is ${serializedRequest.size} bytes, exceeds signaling envelope payload limit ${SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES}"
        }
        signalingClient.relay(request)
    }
}

/** Strictly decodes server-forwarded RELAY envelopes back into peer envelopes. */
class SignalingPeerInbox(
    private val json: Json = strictPeerJson(),
) {
    fun decodeRelay(envelope: SignalingEnvelope): PeerEnvelope {
        require(envelope.type == MessageType.RELAY) { "Expected RELAY signaling envelope" }
        require(envelope.payload.isNotEmpty()) { "RELAY payload is empty" }
        val peerEnvelope = json.decodeFromString<PeerEnvelope>(envelope.payload.decodeToString())
        envelope.sessionId?.let { routedSession ->
            require(peerEnvelope.sessionId == routedSession) {
                "Peer envelope session does not match signaling route"
            }
        }
        return peerEnvelope
    }
}

internal fun strictPeerJson(): Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}
