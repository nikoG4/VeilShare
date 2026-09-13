package dev.veilshare.core.identity

import dev.veilshare.core.crypto.toBase64
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.RegisterRequest

/**
 * Builds the public signaling registration from a loaded local sharing identity.
 * No private key material crosses this boundary.
 */
object SharingRegistrationFactory {
    fun create(
        identity: SharingPublicIdentity,
        referenceCode: ReferenceCode,
    ): RegisterRequest = RegisterRequest(
        sharingIdentityId = identity.identityId,
        referenceCode = referenceCode,
        sharingPublicKey = identity.publicKey.bytes.toBase64(),
    )
}
