package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.toBase64
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LookupTrustResolverTest {
    @Test
    fun unavailableLookupDoesNotProducePeerIdentity() = runTest {
        val resolver = LookupTrustResolver(manager())
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")

        val result = resolver.resolve(code, LookupResponse(status = LookupStatus.NOT_FOUND))

        assertEquals(LookupStatus.NOT_FOUND, assertIs<LookupTrustResult.Unavailable>(result).status)
    }

    @Test
    fun unknownFoundLookupRequiresVerificationThenBecomesTrustedAfterPinning() = runTest {
        val contacts = manager()
        val resolver = LookupTrustResolver(contacts)
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val candidate = candidate("peer-a", 1, code)
        val response = LookupResponse(
            status = LookupStatus.FOUND,
            sharingIdentityId = candidate.sharingIdentityId,
            sharingPublicKey = candidate.publicKey.bytes.toBase64(),
        )

        val first = assertIs<LookupTrustResult.Peer>(resolver.resolve(code, response))
        assertIs<PeerTrustDecision.NeedsVerification>(first.decision)

        contacts.addVerified(
            alias = "Alice",
            candidate = candidate,
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )

        val second = assertIs<LookupTrustResult.Peer>(resolver.resolve(code, response))
        assertIs<PeerTrustDecision.Trusted>(second.decision)
    }

    @Test
    fun lookupKeySubstitutionAgainstPinnedIdentityIsMismatch() = runTest {
        val contacts = manager()
        val resolver = LookupTrustResolver(contacts)
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val pinned = candidate("peer-a", 2, code)
        contacts.addVerified(
            alias = "Alice",
            candidate = pinned,
            verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
        )

        val attackerKey = Ed25519PublicKey(ByteArray(32) { index -> ((99 * 13 + index) and 0xff).toByte() })
        val response = LookupResponse(
            status = LookupStatus.FOUND,
            sharingIdentityId = pinned.sharingIdentityId,
            sharingPublicKey = attackerKey.bytes.toBase64(),
        )

        val result = assertIs<LookupTrustResult.Peer>(resolver.resolve(code, response))
        assertIs<PeerTrustDecision.KeyMismatch>(result.decision)
    }

    @Test
    fun malformedLookupPublicKeyFailsClosed() = runTest {
        val resolver = LookupTrustResolver(manager())
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val malformed = LookupResponse(
            status = LookupStatus.FOUND,
            sharingIdentityId = SharingIdentityId("peer-a"),
            sharingPublicKey = ByteArray(8) { 1 }.toBase64(),
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(code, malformed)
        }
    }

    private fun manager() = TrustedContactManager(InMemoryTrustedContactStore(), CountingRandom())

    private fun candidate(
        identity: String,
        marker: Int,
        code: dev.veilshare.core.model.ReferenceCode,
    ) = PeerIdentityCandidate(
        sharingIdentityId = SharingIdentityId(identity),
        publicKey = Ed25519PublicKey(ByteArray(32) { index -> ((marker * 13 + index) and 0xff).toByte() }),
        referenceCode = code,
    )

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 19 + index) and 0xff).toByte() }
        }
    }
}
