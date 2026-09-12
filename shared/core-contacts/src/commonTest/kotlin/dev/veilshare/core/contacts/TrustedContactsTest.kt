package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TrustedContactsTest {
    @Test
    fun unknownPeerRequiresVerificationAndVerifiedPeerProducesHandshakeBinding() = runTest {
        val manager = manager()
        val candidate = candidate(
            identity = "peer-a",
            keyMarker = 1,
            code = "2345-6789-ABCD-EFGH",
        )

        val first = assertIs<PeerTrustDecision.NeedsVerification>(manager.evaluate(candidate))
        assertEquals(VerificationReason.NEW_PEER, first.reason)

        val contact = manager.addVerified(
            alias = "Alice",
            candidate = candidate,
            verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
        )
        val trusted = assertIs<PeerTrustDecision.Trusted>(manager.evaluate(candidate))

        assertEquals(contact.contactId, trusted.contact.contactId)
        assertEquals(candidate.sharingIdentityId, trusted.binding.sharingIdentityId)
        assertContentEquals(candidate.publicKey.bytes, trusted.binding.publicKey.bytes)
        assertEquals(candidate.fingerprint, trusted.binding.fingerprint)
        assertEquals(64, trusted.binding.sharingIdentityIdHash.length)
    }

    @Test
    fun sameIdentityWithDifferentPublicKeyIsHardMismatch() = runTest {
        val manager = manager()
        val original = candidate("peer-a", keyMarker = 7)
        val attacker = candidate("peer-a", keyMarker = 99)

        val contact = manager.addVerified(
            alias = "Alice",
            candidate = original,
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )

        val decision = assertIs<PeerTrustDecision.KeyMismatch>(manager.evaluate(attacker))
        assertEquals(contact.contactId, decision.contact.contactId)
        assertNotEquals(contact.fingerprint, decision.presentedFingerprint)
    }

    @Test
    fun sameRoutingCodeWithDifferentIdentityNeverAutoUpdatesTrust() = runTest {
        val manager = manager()
        val code = "2345-6789-ABCD-EFGH"
        val original = candidate("peer-a", keyMarker = 1, code = code)
        val replacement = candidate("peer-b", keyMarker = 2, code = code)

        val contact = manager.addVerified(
            alias = "Alice",
            candidate = original,
            verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
        )

        val decision = assertIs<PeerTrustDecision.NeedsVerification>(manager.evaluate(replacement))
        assertEquals(VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE, decision.reason)
        assertEquals(contact.contactId, decision.existingContact?.contactId)

        val stillPinned = assertIs<PeerTrustDecision.Trusted>(manager.evaluate(original))
        assertContentEquals(original.publicKey.bytes, stillPinned.binding.publicKey.bytes)
    }

    @Test
    fun referenceCodeMayRotateOnlyThroughExplicitAuthenticatedUpdate() = runTest {
        val manager = manager()
        val original = candidate("peer-a", keyMarker = 3, code = "2345-6789-ABCD-EFGH")
        val contact = manager.addVerified(
            alias = "Alice",
            candidate = original,
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )
        val newCode = ReferenceCodes.parse("JKLM-NPQR-STUV-WXYZ")

        val updated = manager.updateReferenceCodeFromAuthenticatedSession(contact.contactId, newCode)
        assertEquals(newCode, updated.referenceCode)

        val sameIdentityNewRoute = PeerIdentityCandidate(
            sharingIdentityId = original.sharingIdentityId,
            publicKey = Ed25519PublicKey(original.publicKey.bytes.copyOf()),
            referenceCode = newCode,
        )
        assertIs<PeerTrustDecision.Trusted>(manager.evaluate(sameIdentityNewRoute))
    }

    @Test
    fun identityChangeRequiresExplicitConfirmation() = runTest {
        val manager = manager()
        val oldCandidate = candidate("peer-old", keyMarker = 4)
        val newCandidate = candidate("peer-new", keyMarker = 5)
        val contact = manager.addVerified(
            alias = "Alice",
            candidate = oldCandidate,
            verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
        )

        val replacement = manager.confirmIdentityChange(
            contactId = contact.contactId,
            candidate = newCandidate,
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )

        assertEquals(newCandidate.sharingIdentityId, replacement.sharingIdentityId)
        assertContentEquals(newCandidate.publicKey.bytes, replacement.pinnedPublicKey)
        assertIs<PeerTrustDecision.Trusted>(manager.evaluate(newCandidate))
        assertIs<PeerTrustDecision.NeedsVerification>(manager.evaluate(oldCandidate))
    }

    @Test
    fun fingerprintFormattingIsHumanReadableWithoutChangingTrustValue() {
        val fingerprint = candidate("peer-format", keyMarker = 8).fingerprint
        val displayed = fingerprint.toDisplayGroups(4)

        assertEquals(fingerprint.value.uppercase().replace(Regex("(.{4})(?!$)"), "$1 "), displayed)
        assertEquals(64 / 4, displayed.split(' ').size)
    }

    private fun manager(): TrustedContactManager = TrustedContactManager(
        store = InMemoryTrustedContactStore(),
        random = CountingRandom(),
    )

    private fun candidate(
        identity: String,
        keyMarker: Int,
        code: String? = null,
    ): PeerIdentityCandidate = PeerIdentityCandidate(
        sharingIdentityId = SharingIdentityId(identity),
        publicKey = Ed25519PublicKey(ByteArray(32) { index -> ((keyMarker * 13 + index) and 0xff).toByte() }),
        referenceCode = code?.let(ReferenceCodes::parse),
    )

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 23 + index) and 0xff).toByte() }
        }
    }
}
