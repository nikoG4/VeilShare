package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionConfirm
import dev.veilshare.core.model.SharingProtocol

data class VerifiedSessionConfirm(
    val receiverEphemeralPublicKey: X25519PublicKey,
)

/**
 * Verifies SESSION_CONFIRM without requiring the caller to know the receiver's ephemeral
 * X25519 key in advance.
 *
 * Trust comes exclusively from the already-pinned Ed25519 identity key. The ephemeral
 * carried by SESSION_CONFIRM is covered by that identity signature and is returned only
 * after the signature and session/identity bindings are verified.
 *
 * This is the safe path for session orchestration. The older HandshakeProtocol member
 * taking expectedReceiverEphemeralPublicKey remains temporarily for source compatibility
 * with the already-validated baseline and should not be used for newly received peers.
 */
fun HandshakeProtocol.verifySessionConfirmFromPinnedIdentity(
    confirm: SessionConfirm,
    expectedSharingIdentityIdHash: String,
    expectedSessionIdHash: String,
    expectedReceiverIdentityPublicKey: Ed25519PublicKey,
    signer: Ed25519Signer,
): VerifiedSessionConfirm {
    SharingProtocol.requireSupported(confirm.protocolVersion)
    require(confirm.sharingIdentityIdHash == expectedSharingIdentityIdHash) {
        "Sharing identity ID hash mismatch"
    }
    require(confirm.sessionIdHash == expectedSessionIdHash) {
        "Session ID hash mismatch"
    }

    val actualIdentityBytes = try {
        confirm.sharingPublicKey.decodeFromBase64()
    } catch (e: Exception) {
        throw IllegalArgumentException("SESSION_CONFIRM contains malformed identity public key", e)
    }
    require(actualIdentityBytes.size == 32) { "SESSION_CONFIRM contains invalid Ed25519 public key length" }
    require(actualIdentityBytes.contentEquals(expectedReceiverIdentityPublicKey.bytes)) {
        "Receiver identity public key does not match expected peer identity"
    }

    val receiverEphemeralBytes = try {
        confirm.receiverEphemeralPublicKey.decodeFromBase64()
    } catch (e: Exception) {
        throw IllegalArgumentException("SESSION_CONFIRM contains malformed receiver ephemeral key", e)
    }
    require(receiverEphemeralBytes.size == 32) { "SESSION_CONFIRM contains invalid X25519 public key length" }

    val signingBytes = HandshakeCanonical.confirmSigningBytes(
        identityHash = confirm.sharingIdentityIdHash,
        sessionHash = confirm.sessionIdHash,
        receiverEphemeralPublicKey = confirm.receiverEphemeralPublicKey,
        protocolVersion = confirm.protocolVersion,
    )
    val signature = try {
        confirm.signature.decodeFromBase64()
    } catch (e: Exception) {
        throw IllegalArgumentException("SESSION_CONFIRM contains malformed signature", e)
    }
    require(signer.verify(expectedReceiverIdentityPublicKey, signingBytes, signature)) {
        "Invalid signature on SESSION_CONFIRM"
    }

    return VerifiedSessionConfirm(
        receiverEphemeralPublicKey = X25519PublicKey(receiverEphemeralBytes),
    )
}
