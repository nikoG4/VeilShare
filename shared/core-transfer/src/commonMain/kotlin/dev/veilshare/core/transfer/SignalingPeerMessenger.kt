package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.HandshakeMessageType
import dev.veilshare.core.model.HandshakePeerEnvelope
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SecurePeerEnvelope
import dev.veilshare.core.model.SessionConfirm
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionHello
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.platform.SignalingClient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PeerDirection(internal val wireValue: Int) {
    SENDER_TO_RECEIVER(1),
    RECEIVER_TO_SENDER(2),
}

/**
 * Post-handshake confidentiality/integrity layer for the complete serialized PeerEnvelope.
 * DATA keeps its inner chunk AEAD; this outer layer hides message type, TransferId, OFFER
 * metadata and DATA metadata from the blind signaling relay.
 */
class SecurePeerChannel(
    private val sessionId: SessionId,
    private val direction: PeerDirection,
    private val cipher: AuthenticatedCipher,
    private val sessionKey: SensitiveBytes,
    private val random: SecureRandom,
    private val json: Json = strictPeerJson(),
) {
    suspend fun seal(envelope: PeerEnvelope): SecurePeerEnvelope {
        require(envelope.sessionId == sessionId) { "Peer envelope session does not match secure channel" }
        val plaintext = json.encodeToString(envelope).encodeToByteArray()
        val nonce = Nonce(random.bytes(TransferProtocol.NONCE_SIZE))
        val aad = securePeerAad(envelope.protocolVersion, sessionId, direction)
        return try {
            val sealed = cipher.sealWithNonce(sessionKey, nonce, plaintext, aad)
            SecurePeerEnvelope(
                protocolVersion = envelope.protocolVersion,
                nonce = sealed.nonce.bytes.copyOf(),
                ciphertext = sealed.ciphertext,
            )
        } finally {
            plaintext.fill(0)
            aad.fill(0)
        }
    }

    suspend fun open(secure: SecurePeerEnvelope): PeerEnvelope {
        SharingProtocol.requireSupported(secure.protocolVersion)
        val aad = securePeerAad(secure.protocolVersion, sessionId, direction)
        val plaintext = try {
            cipher.open(
                sessionKey,
                SealedBytes(Nonce(secure.nonce), secure.ciphertext),
                aad,
            )
        } finally {
            aad.fill(0)
        }
        return try {
            val envelope = json.decodeFromString<PeerEnvelope>(plaintext.decodeToString())
            require(envelope.protocolVersion == secure.protocolVersion) {
                "Secure peer protocol version does not match encrypted envelope"
            }
            require(envelope.sessionId == sessionId) {
                "Encrypted peer envelope belongs to a different session"
            }
            envelope
        } finally {
            plaintext.fill(0)
        }
    }
}

/**
 * Pre-key messenger. It accepts only the three signed handshake messages and deliberately
 * uses HandshakePeerEnvelope, which contains no TransferId or file metadata.
 *
 * localReferenceCode is routing metadata only; it lets the peer reply during this handshake
 * and is never treated as an identity assertion.
 */
class HandshakeSignalingMessenger(
    private val signalingClient: SignalingClient,
    private val sessionId: SessionId,
    private val peerReferenceCode: ReferenceCode,
    private val localReferenceCode: ReferenceCode? = null,
    private val json: Json = strictPeerJson(),
) {
    suspend fun send(message: DecodedPeerMessage) {
        val (type, payload) = when (message) {
            is DecodedPeerMessage.Hello -> HandshakeMessageType.SESSION_HELLO to json.encodeToString(message.value).encodeToByteArray()
            is DecodedPeerMessage.Confirm -> HandshakeMessageType.SESSION_CONFIRM to json.encodeToString(message.value).encodeToByteArray()
            is DecodedPeerMessage.ConfirmAck -> HandshakeMessageType.SESSION_CONFIRM_ACK to json.encodeToString(message.value).encodeToByteArray()
            else -> throw IllegalArgumentException("Post-handshake message must use SecureSignalingPeerMessenger")
        }
        val handshake = HandshakePeerEnvelope(
            protocolVersion = message.protocolVersion,
            messageType = type,
            sessionId = sessionId,
            replyReferenceCode = localReferenceCode,
            payload = payload,
        )
        relaySerialized(json.encodeToString(handshake).encodeToByteArray())
    }

    private suspend fun relaySerialized(opaquePayload: ByteArray) {
        require(opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized handshake envelope exceeds relay payload limit"
        }
        val request = RelayRequest(peerReferenceCode, sessionId, opaquePayload)
        require(json.encodeToString(request).encodeToByteArray().size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized handshake RelayRequest exceeds signaling envelope payload limit"
        }
        signalingClient.relay(request)
    }
}

data class RoutedHandshakeMessage(
    val message: DecodedPeerMessage,
    val replyReferenceCode: ReferenceCode?,
)

class HandshakeSignalingInbox(
    private val json: Json = strictPeerJson(),
) {
    fun decodeRelay(envelope: SignalingEnvelope): DecodedPeerMessage =
        decodeRoutedRelay(envelope).message

    fun decodeRoutedRelay(envelope: SignalingEnvelope): RoutedHandshakeMessage {
        require(envelope.type == MessageType.RELAY) { "Expected RELAY signaling envelope" }
        require(envelope.payload.isNotEmpty()) { "RELAY payload is empty" }
        val handshake = json.decodeFromString<HandshakePeerEnvelope>(envelope.payload.decodeToString())
        envelope.sessionId?.let { routedSession ->
            require(handshake.sessionId == routedSession) { "Handshake session does not match signaling route" }
        }
        val decoded = when (handshake.messageType) {
            HandshakeMessageType.SESSION_HELLO ->
                DecodedPeerMessage.Hello(json.decodeFromString<SessionHello>(handshake.payload.decodeToString()))
            HandshakeMessageType.SESSION_CONFIRM ->
                DecodedPeerMessage.Confirm(json.decodeFromString<SessionConfirm>(handshake.payload.decodeToString()))
            HandshakeMessageType.SESSION_CONFIRM_ACK ->
                DecodedPeerMessage.ConfirmAck(json.decodeFromString<SessionConfirmAck>(handshake.payload.decodeToString()))
        }
        require(decoded.protocolVersion == handshake.protocolVersion) {
            "Handshake payload protocol version does not match envelope"
        }
        return RoutedHandshakeMessage(
            message = decoded,
            replyReferenceCode = handshake.replyReferenceCode,
        )
    }
}

/** Post-handshake peer messages are always encrypted before entering RelayRequest. */
class SecureSignalingPeerMessenger(
    private val signalingClient: SignalingClient,
    private val sessionId: SessionId,
    private val transferId: TransferId,
    private val peerReferenceCode: ReferenceCode,
    private val channel: SecurePeerChannel,
    private val peerCodec: PeerMessageCodec = PeerMessageCodec(),
    private val json: Json = strictPeerJson(),
) {
    suspend fun send(message: DecodedPeerMessage) {
        requirePostHandshake(message)
        val peerEnvelope = peerCodec.encode(sessionId, transferId, message)
        val secure = channel.seal(peerEnvelope)
        val opaquePayload = json.encodeToString(secure).encodeToByteArray()
        require(opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized SecurePeerEnvelope is ${opaquePayload.size} bytes, exceeds relay payload limit ${SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES}"
        }
        val request = RelayRequest(peerReferenceCode, sessionId, opaquePayload)
        val serializedRequest = json.encodeToString(request).encodeToByteArray()
        require(serializedRequest.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Serialized secure RelayRequest is ${serializedRequest.size} bytes, exceeds signaling envelope payload limit ${SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES}"
        }
        signalingClient.relay(request)
    }

    private fun requirePostHandshake(message: DecodedPeerMessage) {
        when (message) {
            is DecodedPeerMessage.Hello,
            is DecodedPeerMessage.Confirm,
            is DecodedPeerMessage.ConfirmAck ->
                throw IllegalArgumentException("Handshake messages must use HandshakeSignalingMessenger")
            else -> Unit
        }
    }
}

class SecureSignalingPeerInbox(
    private val expectedSessionId: SessionId,
    private val channel: SecurePeerChannel,
    private val json: Json = strictPeerJson(),
) {
    suspend fun decodeRelay(envelope: SignalingEnvelope): PeerEnvelope {
        require(envelope.type == MessageType.RELAY) { "Expected RELAY signaling envelope" }
        require(envelope.sessionId == expectedSessionId) { "Signaling route belongs to a different session" }
        require(envelope.payload.isNotEmpty()) { "RELAY payload is empty" }
        val secure = json.decodeFromString<SecurePeerEnvelope>(envelope.payload.decodeToString())
        return channel.open(secure)
    }
}

internal fun strictPeerJson(): Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private fun securePeerAad(
    protocolVersion: Int,
    sessionId: SessionId,
    direction: PeerDirection,
): ByteArray {
    SharingProtocol.requireSupported(protocolVersion)
    val domain = "VEILSHARE/PEER-ENVELOPE/V1".encodeToByteArray()
    val session = sessionId.value.encodeToByteArray()
    val out = ByteArray(4 + domain.size + 4 + 4 + session.size + 4)
    var offset = 0
    offset = writeLengthPrefixed(out, offset, domain)
    offset = writeInt(out, offset, protocolVersion)
    offset = writeLengthPrefixed(out, offset, session)
    writeInt(out, offset, direction.wireValue)
    return out
}

private fun writeLengthPrefixed(out: ByteArray, offset: Int, value: ByteArray): Int {
    var cursor = writeInt(out, offset, value.size)
    value.copyInto(out, destinationOffset = cursor)
    cursor += value.size
    return cursor
}

private fun writeInt(out: ByteArray, offset: Int, value: Int): Int {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
    return offset + 4
}
