package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.HandshakeProtocol
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.decodeFromBase64
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.SessionHello
import dev.veilshare.core.model.SessionId

sealed interface InboundHelloTrustResult {
    data class Trusted(
        val peer: PinnedPeerIdentity,
    ) : InboundHelloTrustResult

    /**
     * The identity hash is not pinned locally. V1 does not auto-create a contact from an
     * inbound HELLO because the relay provides no authenticated raw SharingIdentityId.
     * A separate out-of-band verification/lookup must establish trust first.
     */
    data class UnknownIdentity(
        val sharingIdentityIdHash: String,
        val presentedFingerprint: Fingerprint,
    ) : InboundHelloTrustResult
}

/**
 * Trust gate for inbound SESSION_HELLO.
 *
 * The Ed25519 key carried inside HELLO is never used as the expected verification key.
 * For known peers, the expected key comes exclusively from the locally pinned contact.
 */
class TrustedInboundHelloVerifier(
    private val contacts: TrustedContactManager,
    private val handshake: HandshakeProtocol,
    private val signer: Ed25519Signer,
) {
    suspend fun verify(
        sessionId: SessionId,
        hello: SessionHello,
    ): InboundHelloTrustResult {
        val pinned = contacts.findPinnedByIdentityHash(hello.sharingIdentityIdHash)
        if (pinned == null) {
            val presentedKey = decodePresentedKey(hello)
            return InboundHelloTrustResult.UnknownIdentity(
                sharingIdentityIdHash = hello.sharingIdentityIdHash,
                presentedFingerprint = Fingerprint(Hash.sha256(presentedKey.bytes).toHex()),
            )
        }

        handshake.verifySessionHello(
            hello = hello,
            expectedSharingIdentityIdHash = pinned.sharingIdentityIdHash,
            expectedSessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
            expectedSenderPublicKey = pinned.publicKey,
            signer = signer,
        )
        return InboundHelloTrustResult.Trusted(pinned)
    }

    private fun decodePresentedKey(hello: SessionHello): Ed25519PublicKey {
        val bytes = try {
            hello.sharingPublicKey.decodeFromBase64()
        } catch (e: Exception) {
            throw IllegalArgumentException("SESSION_HELLO contains malformed sharing public key", e)
        }
        require(bytes.size == PeerIdentityCandidate.ED25519_PUBLIC_KEY_BYTES) {
            "SESSION_HELLO contains invalid Ed25519 public key length"
        }
        return Ed25519PublicKey(bytes)
    }
}
