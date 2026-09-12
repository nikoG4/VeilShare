package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.decodeFromBase64
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.ReferenceCode

sealed interface LookupTrustResult {
    data class Peer(val decision: PeerTrustDecision) : LookupTrustResult
    data class Unavailable(val status: LookupStatus) : LookupTrustResult
}

/**
 * Converts an untrusted signaling LOOKUP response into the trust decision that session
 * establishment must honor. LOOKUP itself never grants authenticity.
 */
class LookupTrustResolver(
    private val contacts: TrustedContactManager,
) {
    suspend fun resolve(
        requestedReferenceCode: ReferenceCode,
        response: LookupResponse,
    ): LookupTrustResult {
        if (response.status != LookupStatus.FOUND) {
            return LookupTrustResult.Unavailable(response.status)
        }

        val identityId = requireNotNull(response.sharingIdentityId) { "FOUND lookup missing identity" }
        val encodedPublicKey = requireNotNull(response.sharingPublicKey) { "FOUND lookup missing public key" }
        val publicKeyBytes = try {
            encodedPublicKey.decodeFromBase64()
        } catch (e: Exception) {
            throw IllegalArgumentException("LOOKUP returned malformed sharing public key", e)
        }
        require(publicKeyBytes.size == PeerIdentityCandidate.ED25519_PUBLIC_KEY_BYTES) {
            "LOOKUP returned invalid Ed25519 public key length"
        }

        return LookupTrustResult.Peer(
            contacts.evaluate(
                PeerIdentityCandidate(
                    sharingIdentityId = identityId,
                    publicKey = Ed25519PublicKey(publicKeyBytes),
                    referenceCode = requestedReferenceCode,
                ),
            ),
        )
    }
}
