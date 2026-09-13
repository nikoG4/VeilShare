package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.platform.SignalingClient

/** Which side of the authenticated handshake is local for this session. */
enum class EstablishedSessionSide {
    INITIATOR,
    RESPONDER,
}

/**
 * Owns short-lived key copies used by one transfer over an EstablishedPeerSession.
 *
 * The established session remains the source of truth for long-lived derived key arrays;
 * this bundle copies only the four keys needed by the transfer adapters and zeroizes those
 * copies on close. DATA and outer ENVELOPE crypto remain on independent keys.
 */
class EstablishedTransferCrypto private constructor(
    val messenger: SecureSignalingPeerMessenger,
    val inbox: SecureSignalingPeerInbox,
    val encryptor: TransferEncryptor,
    val decryptor: TransferDecryptor,
    private val secrets: List<SensitiveBytes>,
) : AutoCloseable {
    private var closed = false

    val isOpen: Boolean get() = !closed

    override fun close() {
        if (closed) return
        closed = true
        secrets.forEach { it.close() }
    }

    companion object {
        fun open(
            session: EstablishedPeerSession,
            side: EstablishedSessionSide,
            transferId: TransferId,
            signalingClient: SignalingClient,
            cipher: AuthenticatedCipher,
            random: SecureRandom,
        ): EstablishedTransferCrypto {
            check(session.isOpen) { "Established peer session is closed" }

            val outboundEnvelopeBytes: ByteArray
            val inboundEnvelopeBytes: ByteArray
            val outboundDataBytes: ByteArray
            val inboundDataBytes: ByteArray
            val outboundDirection: PeerDirection
            val inboundDirection: PeerDirection

            when (side) {
                EstablishedSessionSide.INITIATOR -> {
                    outboundEnvelopeBytes = session.keys.senderToReceiverEnvelopeKey
                    inboundEnvelopeBytes = session.keys.receiverToSenderEnvelopeKey
                    outboundDataBytes = session.keys.senderToReceiverDataKey
                    inboundDataBytes = session.keys.receiverToSenderDataKey
                    outboundDirection = PeerDirection.SENDER_TO_RECEIVER
                    inboundDirection = PeerDirection.RECEIVER_TO_SENDER
                }
                EstablishedSessionSide.RESPONDER -> {
                    outboundEnvelopeBytes = session.keys.receiverToSenderEnvelopeKey
                    inboundEnvelopeBytes = session.keys.senderToReceiverEnvelopeKey
                    outboundDataBytes = session.keys.receiverToSenderDataKey
                    inboundDataBytes = session.keys.senderToReceiverDataKey
                    outboundDirection = PeerDirection.RECEIVER_TO_SENDER
                    inboundDirection = PeerDirection.SENDER_TO_RECEIVER
                }
            }

            val outboundEnvelopeKey = SensitiveBytes(outboundEnvelopeBytes.copyOf())
            val inboundEnvelopeKey = SensitiveBytes(inboundEnvelopeBytes.copyOf())
            val outboundDataKey = SensitiveBytes(outboundDataBytes.copyOf())
            val inboundDataKey = SensitiveBytes(inboundDataBytes.copyOf())
            val secrets = listOf(outboundEnvelopeKey, inboundEnvelopeKey, outboundDataKey, inboundDataKey)

            return try {
                val outboundChannel = SecurePeerChannel(
                    sessionId = session.sessionId,
                    direction = outboundDirection,
                    cipher = cipher,
                    sessionKey = outboundEnvelopeKey,
                    random = random,
                )
                val inboundChannel = SecurePeerChannel(
                    sessionId = session.sessionId,
                    direction = inboundDirection,
                    cipher = cipher,
                    sessionKey = inboundEnvelopeKey,
                    random = random,
                )
                EstablishedTransferCrypto(
                    messenger = SecureSignalingPeerMessenger(
                        signalingClient = signalingClient,
                        sessionId = session.sessionId,
                        transferId = transferId,
                        peerReferenceCode = session.peerReferenceCode,
                        channel = outboundChannel,
                    ),
                    inbox = SecureSignalingPeerInbox(
                        expectedSessionId = session.sessionId,
                        channel = inboundChannel,
                    ),
                    encryptor = DefaultTransferEncryptor(cipher, outboundDataKey),
                    decryptor = DefaultTransferDecryptor(cipher, inboundDataKey),
                    secrets = secrets,
                )
            } catch (failure: Throwable) {
                secrets.forEach { it.close() }
                throw failure
            }
        }
    }
}
