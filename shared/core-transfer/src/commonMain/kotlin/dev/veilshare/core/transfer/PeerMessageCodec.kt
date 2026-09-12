package dev.veilshare.core.transfer

import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.PeerMessageType
import dev.veilshare.core.model.SessionConfirm
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionHello
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.TransferAccept
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferComplete
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferFailure
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.model.TransferReject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Typed peer payload after envelope/type/version validation. */
sealed interface DecodedPeerMessage {
    val protocolVersion: Int

    data class Hello(val value: SessionHello) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Confirm(val value: SessionConfirm) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class ConfirmAck(val value: SessionConfirmAck) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Offer(val value: TransferOffer) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Accept(val value: TransferAccept) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Reject(val value: TransferReject) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Data(val value: TransferData) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Complete(val value: TransferComplete) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Cancel(val value: TransferCancel) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }

    data class Failure(val value: TransferFailure) : DecodedPeerMessage {
        override val protocolVersion: Int get() = value.protocolVersion
    }
}

/**
 * Canonical JSON gateway for peer messages.
 *
 * This codec only validates structure/type/version. Cryptographic verification of
 * handshake messages remains in HandshakeProtocol, while DATA AEAD verification remains
 * in TransferReceiver.
 */
class PeerMessageCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    },
) {
    fun decode(envelope: PeerEnvelope): DecodedPeerMessage {
        SharingProtocol.requireSupported(envelope.protocolVersion)
        require(envelope.payload.isNotEmpty()) { "Peer payload is required" }

        val decoded = when (envelope.messageType) {
            PeerMessageType.SESSION_HELLO -> DecodedPeerMessage.Hello(decodePayload(envelope.payload))
            PeerMessageType.SESSION_CONFIRM -> DecodedPeerMessage.Confirm(decodePayload(envelope.payload))
            PeerMessageType.SESSION_CONFIRM_ACK -> DecodedPeerMessage.ConfirmAck(decodePayload(envelope.payload))
            PeerMessageType.OFFER -> DecodedPeerMessage.Offer(decodePayload(envelope.payload))
            PeerMessageType.ACCEPT -> DecodedPeerMessage.Accept(decodePayload(envelope.payload))
            PeerMessageType.REJECT -> DecodedPeerMessage.Reject(decodePayload(envelope.payload))
            PeerMessageType.DATA -> DecodedPeerMessage.Data(decodePayload(envelope.payload))
            PeerMessageType.COMPLETE -> DecodedPeerMessage.Complete(decodePayload(envelope.payload))
            PeerMessageType.CANCEL -> DecodedPeerMessage.Cancel(decodePayload(envelope.payload))
            PeerMessageType.FAILURE -> DecodedPeerMessage.Failure(decodePayload(envelope.payload))
        }

        require(decoded.protocolVersion == envelope.protocolVersion) {
            "Peer payload protocol version does not match envelope"
        }
        return decoded
    }

    fun encode(
        sessionId: SessionId,
        transferId: TransferId,
        message: DecodedPeerMessage,
    ): PeerEnvelope {
        SharingProtocol.requireSupported(message.protocolVersion)
        val (type, payload) = when (message) {
            is DecodedPeerMessage.Hello -> PeerMessageType.SESSION_HELLO to encodePayload(message.value)
            is DecodedPeerMessage.Confirm -> PeerMessageType.SESSION_CONFIRM to encodePayload(message.value)
            is DecodedPeerMessage.ConfirmAck -> PeerMessageType.SESSION_CONFIRM_ACK to encodePayload(message.value)
            is DecodedPeerMessage.Offer -> PeerMessageType.OFFER to encodePayload(message.value)
            is DecodedPeerMessage.Accept -> PeerMessageType.ACCEPT to encodePayload(message.value)
            is DecodedPeerMessage.Reject -> PeerMessageType.REJECT to encodePayload(message.value)
            is DecodedPeerMessage.Data -> PeerMessageType.DATA to encodePayload(message.value)
            is DecodedPeerMessage.Complete -> PeerMessageType.COMPLETE to encodePayload(message.value)
            is DecodedPeerMessage.Cancel -> PeerMessageType.CANCEL to encodePayload(message.value)
            is DecodedPeerMessage.Failure -> PeerMessageType.FAILURE to encodePayload(message.value)
        }
        return PeerEnvelope(
            protocolVersion = message.protocolVersion,
            messageType = type,
            sessionId = sessionId,
            transferId = transferId,
            payload = payload,
        )
    }

    private inline fun <reified T> decodePayload(payload: ByteArray): T =
        json.decodeFromString(payload.decodeToString())

    private inline fun <reified T> encodePayload(value: T): ByteArray =
        json.encodeToString(value).encodeToByteArray()
}
