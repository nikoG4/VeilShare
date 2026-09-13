package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.HandshakeKeys
import dev.veilshare.core.crypto.HandshakeProtocol
import dev.veilshare.core.crypto.HandshakeTranscript
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.X25519KeyAgreement
import dev.veilshare.core.crypto.X25519KeyPair
import dev.veilshare.core.crypto.toBase64
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.crypto.verifySessionConfirmFromPinnedIdentity
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EstablishedPeerSession internal constructor(
    val sessionId: SessionId,
    val peer: PinnedPeerIdentity,
    val localIdentity: SharingPublicIdentity,
    val localReferenceCode: ReferenceCode,
    val peerReferenceCode: ReferenceCode,
    val keys: HandshakeKeys,
) : AutoCloseable {
    private var closed = false
    val isOpen: Boolean get() = !closed
    override fun close() {
        if (closed) return
        closed = true
        keys.senderToReceiverDataKey.fill(0)
        keys.receiverToSenderDataKey.fill(0)
        keys.senderToReceiverEnvelopeKey.fill(0)
        keys.receiverToSenderEnvelopeKey.fill(0)
    }
}

data class OutboundHandshakeCompletion(val ack: SessionConfirmAck, val session: EstablishedPeerSession)

class TrustedOutboundHandshakeCompleter(
    private val signalingClient: SignalingClient,
    private val identities: SharingIdentityManager,
    private val handshake: HandshakeProtocol,
    private val signer: Ed25519Signer,
    private val keyAgreement: X25519KeyAgreement,
    private val keyDeriver: KeyDeriver,
) {
    suspend fun complete(started: OutboundSessionStartResult.Started, routedConfirm: RoutedHandshakeMessage): OutboundHandshakeCompletion {
        val confirm = (routedConfirm.message as? DecodedPeerMessage.Confirm)?.value
            ?: throw IllegalArgumentException("Expected SESSION_CONFIRM")
        val peerRoute = routedConfirm.replyReferenceCode ?: started.peerReferenceCode
        val verifiedConfirm = handshake.verifySessionConfirmFromPinnedIdentity(
            confirm,
            started.peer.sharingIdentityIdHash,
            sessionHash(started.sessionId),
            started.peer.publicKey,
            signer,
        )
        val senderEphemeral = keyAgreement.generateKeyPair()
        val localHandle = identities.getOrCreate(started.localContextId)
        try {
            val currentLocal = localHandle.publicIdentity
            require(currentLocal.identityId == started.localIdentity.identityId) { "Local sharing identity changed during handshake" }
            require(currentLocal.publicKey.bytes.contentEquals(started.localIdentity.publicKey.bytes)) { "Local sharing public key changed during handshake" }
            val ack = localHandle.withKeyPair { keyPair ->
                handshake.createSessionConfirmAck(
                    currentLocal.identityId,
                    keyPair,
                    started.peer.sharingIdentityIdHash,
                    started.sessionId,
                    senderEphemeral,
                    verifiedConfirm.receiverEphemeralPublicKey,
                    signer,
                )
            }
            HandshakeSignalingMessenger(signalingClient, started.sessionId, peerRoute, started.localReferenceCode)
                .send(DecodedPeerMessage.ConfirmAck(ack))
            val transcript = HandshakeTranscript(
                ack.protocolVersion,
                identityHash(currentLocal),
                started.peer.sharingIdentityIdHash,
                sessionHash(started.sessionId),
                senderEphemeral.publicKey.bytes.toBase64(),
                verifiedConfirm.receiverEphemeralPublicKey.bytes.toBase64(),
            )
            require(ack.transcriptHash == transcript.computeTranscriptHash()) { "Local ACK transcript does not match reconstructed transcript" }
            val keys = handshake.deriveHandshakeKeys(
                senderEphemeral.privateKey,
                verifiedConfirm.receiverEphemeralPublicKey,
                transcript,
                keyAgreement,
                keyDeriver,
            )
            return OutboundHandshakeCompletion(
                ack,
                EstablishedPeerSession(started.sessionId, started.peer, currentLocal, started.localReferenceCode, peerRoute, keys),
            )
        } finally {
            localHandle.close()
            senderEphemeral.privateKey.material.close()
        }
    }
}

sealed interface InboundSessionBeginResult {
    data class Pending(val handshake: PendingInboundHandshake) : InboundSessionBeginResult
    data class UnknownPeer(val result: InboundHelloTrustResult.UnknownIdentity) : InboundSessionBeginResult
    data class ReplayRejected(val sessionId: SessionId) : InboundSessionBeginResult
    data class CapacityRejected(val maxSeenSessions: Int) : InboundSessionBeginResult
}

class TrustedInboundSessionResponder(
    private val signalingClient: SignalingClient,
    private val identities: SharingIdentityManager,
    private val presence: SharingPresenceManager,
    private val helloVerifier: TrustedInboundHelloVerifier,
    private val handshake: HandshakeProtocol,
    private val signer: Ed25519Signer,
    private val keyAgreement: X25519KeyAgreement,
    private val keyDeriver: KeyDeriver,
    private val replayGuard: HandshakeReplayGuard = HandshakeReplayGuard(),
) {
    suspend fun begin(localContextId: SharingContextId, sessionId: SessionId, routedHello: RoutedHandshakeMessage): InboundSessionBeginResult {
        val hello = (routedHello.message as? DecodedPeerMessage.Hello)?.value
            ?: throw IllegalArgumentException("Expected SESSION_HELLO")
        val peerRoute = requireNotNull(routedHello.replyReferenceCode) { "Inbound SESSION_HELLO is missing replyReferenceCode" }
        return when (val trusted = helloVerifier.verify(sessionId, hello)) {
            is InboundHelloTrustResult.UnknownIdentity -> InboundSessionBeginResult.UnknownPeer(trusted)
            is InboundHelloTrustResult.Trusted -> {
                when (replayGuard.claim(sessionId)) {
                    HandshakeSessionClaim.REPLAY -> return InboundSessionBeginResult.ReplayRejected(sessionId)
                    HandshakeSessionClaim.CAPACITY_EXCEEDED -> return InboundSessionBeginResult.CapacityRejected(HandshakeReplayPolicy.MAX_SEEN_SESSIONS)
                    HandshakeSessionClaim.CLAIMED -> Unit
                }
                val localHandle = identities.getOrCreate(localContextId)
                val localPresence = presence.getOrCreate(localContextId)
                val receiverEphemeral = keyAgreement.generateKeyPair()
                try {
                    val localIdentity = localHandle.publicIdentity
                    val confirm = localHandle.withKeyPair { keyPair ->
                        handshake.createSessionConfirm(localIdentity.identityId, keyPair, sessionId, receiverEphemeral, signer)
                    }
                    HandshakeSignalingMessenger(
                        signalingClient,
                        sessionId,
                        peerRoute,
                        localPresence.referenceCode,
                    ).send(DecodedPeerMessage.Confirm(confirm))
                    InboundSessionBeginResult.Pending(
                        PendingInboundHandshake(
                            sessionId,
                            trusted.peer,
                            localIdentity,
                            localPresence.referenceCode,
                            peerRoute,
                            receiverEphemeral,
                            handshake,
                            signer,
                            keyAgreement,
                            keyDeriver,
                        ),
                    )
                } catch (failure: Throwable) {
                    receiverEphemeral.privateKey.material.close()
                    throw failure
                } finally {
                    localHandle.close()
                }
            }
        }
    }
}

class PendingInboundHandshake internal constructor(
    val sessionId: SessionId,
    val peer: PinnedPeerIdentity,
    val localIdentity: SharingPublicIdentity,
    val localReferenceCode: ReferenceCode,
    val peerReferenceCode: ReferenceCode,
    private val receiverEphemeral: X25519KeyPair,
    private val handshake: HandshakeProtocol,
    private val signer: Ed25519Signer,
    private val keyAgreement: X25519KeyAgreement,
    private val keyDeriver: KeyDeriver,
) : AutoCloseable {
    private val mutex = Mutex()
    private var consumed = false

    suspend fun complete(ack: SessionConfirmAck): EstablishedPeerSession = mutex.withLock {
        check(!consumed) { "Inbound handshake is already terminal" }
        consumed = true
        try {
            val verified = handshake.verifySessionConfirmAck(
                ack,
                peer.sharingIdentityIdHash,
                identityHash(localIdentity),
                sessionHash(sessionId),
                receiverEphemeral.publicKey,
                peer.publicKey,
                signer,
            )
            val keys = handshake.deriveHandshakeKeys(
                receiverEphemeral.privateKey,
                verified.senderEphemeralPublicKey,
                verified.transcript,
                keyAgreement,
                keyDeriver,
            )
            EstablishedPeerSession(sessionId, peer, localIdentity, localReferenceCode, peerReferenceCode, keys)
        } finally {
            receiverEphemeral.privateKey.material.close()
        }
    }

    override fun close() {
        if (consumed) return
        consumed = true
        receiverEphemeral.privateKey.material.close()
    }
}

private fun identityHash(identity: SharingPublicIdentity): String = Hash.sha256(identity.identityId.value.encodeToByteArray()).toHex()
private fun sessionHash(sessionId: SessionId): String = Hash.sha256(sessionId.value.encodeToByteArray()).toHex()
