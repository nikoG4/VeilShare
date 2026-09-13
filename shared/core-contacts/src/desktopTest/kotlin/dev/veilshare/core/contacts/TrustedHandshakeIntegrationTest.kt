package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TrustedHandshakeIntegrationTest {
    @Test
    fun verifiedContactProvidesExpectedIdentityForSessionHello() = runTest {
        val signer = JvmEd25519Signer()
        val random = CountingRandom()
        val identityManager = SharingIdentityManager(
            store = InMemorySharingIdentityStore(),
            random = random,
            signer = signer,
        )
        val senderHandle = identityManager.getOrCreate(SharingContextId("sender-context"))
        val contactManager = TrustedContactManager(InMemoryTrustedContactStore(), random)
        val senderPublic = senderHandle.publicIdentity
        val candidate = PeerIdentityCandidate(
            sharingIdentityId = senderPublic.identityId,
            publicKey = senderPublic.publicKey,
        )
        contactManager.addVerified(
            alias = "Verified sender",
            candidate = candidate,
            verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
        )
        val trusted = assertIs<PeerTrustDecision.Trusted>(contactManager.evaluate(candidate))

        val sessionId = SessionId("trusted-handshake-session")
        val handshake = JvmHandshakeProtocol()
        val hello = senderHandle.withKeyPair { keyPair ->
            handshake.createSessionHello(
                sharingIdentityId = senderPublic.identityId,
                sharingKeyPair = keyPair,
                sessionId = sessionId,
                signer = signer,
            )
        }

        handshake.verifySessionHello(
            hello = hello,
            expectedSharingIdentityIdHash = trusted.binding.sharingIdentityIdHash,
            expectedSessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
            expectedSenderPublicKey = trusted.binding.publicKey,
            signer = signer,
        )

        senderHandle.close()
    }

    @Test
    fun signalingKeySubstitutionCannotReplacePinnedHandshakeKey() = runTest {
        val signer = JvmEd25519Signer()
        val random = CountingRandom()
        val identityManager = SharingIdentityManager(
            store = InMemorySharingIdentityStore(),
            random = random,
            signer = signer,
        )
        val legitimate = identityManager.getOrCreate(SharingContextId("legitimate-context"))
        val attacker = identityManager.getOrCreate(SharingContextId("attacker-context"))
        val contacts = TrustedContactManager(InMemoryTrustedContactStore(), random)

        val pinnedCandidate = PeerIdentityCandidate(
            sharingIdentityId = legitimate.publicIdentity.identityId,
            publicKey = legitimate.publicIdentity.publicKey,
        )
        contacts.addVerified(
            alias = "Legitimate peer",
            candidate = pinnedCandidate,
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )
        val trusted = assertIs<PeerTrustDecision.Trusted>(contacts.evaluate(pinnedCandidate))

        // Simulate a malicious/compromised lookup returning an attacker key while reusing
        // the legitimate SharingIdentityId. Trust evaluation catches it before handshake.
        val substitutedCandidate = PeerIdentityCandidate(
            sharingIdentityId = legitimate.publicIdentity.identityId,
            publicKey = attacker.publicIdentity.publicKey,
        )
        assertIs<PeerTrustDecision.KeyMismatch>(contacts.evaluate(substitutedCandidate))

        // Even if a caller ignored that decision and built a signed HELLO with the attacker
        // key, verification against the pinned contact key still fails closed.
        val sessionId = SessionId("substitution-session")
        val handshake = JvmHandshakeProtocol()
        val forgedHello = attacker.withKeyPair { attackerKeys ->
            handshake.createSessionHello(
                sharingIdentityId = legitimate.publicIdentity.identityId,
                sharingKeyPair = attackerKeys,
                sessionId = sessionId,
                signer = signer,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            handshake.verifySessionHello(
                hello = forgedHello,
                expectedSharingIdentityIdHash = trusted.binding.sharingIdentityIdHash,
                expectedSessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
                expectedSenderPublicKey = trusted.binding.publicKey,
                signer = signer,
            )
        }

        legitimate.close()
        attacker.close()
    }

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 37 + index) and 0xff).toByte() }
        }
    }
}
