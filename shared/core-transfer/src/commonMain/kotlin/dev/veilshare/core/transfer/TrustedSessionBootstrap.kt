package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.LookupTrustResult
import dev.veilshare.core.contacts.PeerTrustDecision
import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.HandshakeProtocol
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.SessionHello
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.platform.SignalingClient

sealed interface OutboundSessionStartResult {
    data class Started(
        val sessionId: SessionId,
        val localContextId: SharingContextId,
        val localIdentity: SharingPublicIdentity,
        val localReferenceCode: ReferenceCode,
        val peer: PinnedPeerIdentity,
        val hello: SessionHello,
        val peerReferenceCode: ReferenceCode,
    ) : OutboundSessionStartResult

    data class NeedsVerification(
        val decision: PeerTrustDecision.NeedsVerification,
    ) : OutboundSessionStartResult

    data class KeyMismatch(
        val decision: PeerTrustDecision.KeyMismatch,
    ) : OutboundSessionStartResult

    data class Unavailable(
        val status: LookupStatus,
    ) : OutboundSessionStartResult
}

/**
 * Starts an outbound sharing handshake only after the signaling LOOKUP result has been
 * matched against a previously verified/pinned contact.
 *
 * LOOKUP is discovery only. A peer that is unknown, whose routing code now resolves to a
 * different identity, or whose pinned public key changed never receives SESSION_HELLO.
 */
class TrustedOutboundSessionStarter(
    private val signalingClient: SignalingClient,
    private val identities: SharingIdentityManager,
    private val presence: SharingPresenceManager,
    private val trustResolver: LookupTrustResolver,
    private val handshake: HandshakeProtocol,
    private val signer: Ed25519Signer,
    private val random: RandomBytesSource,
) {
    suspend fun start(
        localContextId: SharingContextId,
        peerReferenceCode: ReferenceCode,
    ): OutboundSessionStartResult {
        val localHandle = identities.getOrCreate(localContextId)
        return try {
            val localIdentity = localHandle.publicIdentity
            val localPresence = presence.getOrCreate(localContextId)
            val lookup = signalingClient.lookup(
                LookupRequest(
                    referenceCode = peerReferenceCode,
                    requestorSharingIdentityId = localIdentity.identityId,
                ),
            )

            when (val resolved = trustResolver.resolve(peerReferenceCode, lookup)) {
                is LookupTrustResult.Unavailable ->
                    OutboundSessionStartResult.Unavailable(resolved.status)

                is LookupTrustResult.Peer -> when (val decision = resolved.decision) {
                    is PeerTrustDecision.NeedsVerification ->
                        OutboundSessionStartResult.NeedsVerification(decision)

                    is PeerTrustDecision.KeyMismatch ->
                        OutboundSessionStartResult.KeyMismatch(decision)

                    is PeerTrustDecision.Trusted -> {
                        val sessionId = OpaqueIds.sessionId(random)
                        val hello = localHandle.withKeyPair { keyPair ->
                            handshake.createSessionHello(
                                sharingIdentityId = localIdentity.identityId,
                                sharingKeyPair = keyPair,
                                sessionId = sessionId,
                                signer = signer,
                            )
                        }

                        HandshakeSignalingMessenger(
                            signalingClient = signalingClient,
                            sessionId = sessionId,
                            peerReferenceCode = peerReferenceCode,
                            localReferenceCode = localPresence.referenceCode,
                        ).send(DecodedPeerMessage.Hello(hello))

                        OutboundSessionStartResult.Started(
                            sessionId = sessionId,
                            localContextId = localContextId,
                            localIdentity = localIdentity,
                            localReferenceCode = localPresence.referenceCode,
                            peer = decision.binding,
                            hello = hello,
                            peerReferenceCode = peerReferenceCode,
                        )
                    }
                }
            }
        } finally {
            localHandle.close()
        }
    }
}
