package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.HandshakeKeys
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.crypto.X25519KeyPair
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustedSessionRegistryTest {
    @Test
    fun `expired pending handshake closes receiver ephemeral`() = runTest {
        val clock = MutableClock()
        val registry = TrustedSessionRegistry(clock, pendingTimeoutMs = 1_000)
        val (pending, ephemeral) = pending(SessionId("pending-expire"))
        assertFalse(ephemeral.privateKey.material.copy().all { it == 0.toByte() })

        assertIs<PendingRegistrationResult.Registered>(registry.registerPending(pending))
        clock.advance(1_000)

        assertEquals(1, registry.sweepExpired())
        assertEquals(0, registry.pendingCount())
        assertTrue(ephemeral.privateKey.material.copy().all { it == 0.toByte() })
        registry.close()
    }

    @Test
    fun `duplicate pending session closes rejected ephemeral`() = runTest {
        val registry = TrustedSessionRegistry(MutableClock(), maxPending = 2)
        val (first, firstEphemeral) = pending(SessionId("duplicate"))
        val (second, secondEphemeral) = pending(SessionId("duplicate"))

        assertIs<PendingRegistrationResult.Registered>(registry.registerPending(first))
        assertIs<PendingRegistrationResult.Duplicate>(registry.registerPending(second))

        assertFalse(firstEphemeral.privateKey.material.copy().all { it == 0.toByte() })
        assertTrue(secondEphemeral.privateKey.material.copy().all { it == 0.toByte() })
        registry.close()
        assertTrue(firstEphemeral.privateKey.material.copy().all { it == 0.toByte() })
    }

    @Test
    fun `pending capacity fails closed and closes rejected ephemeral`() = runTest {
        val registry = TrustedSessionRegistry(MutableClock(), maxPending = 1)
        val (first, _) = pending(SessionId("capacity-a"))
        val (second, secondEphemeral) = pending(SessionId("capacity-b"))

        assertIs<PendingRegistrationResult.Registered>(registry.registerPending(first))
        val rejected = assertIs<PendingRegistrationResult.CapacityExceeded>(registry.registerPending(second))
        assertEquals(1, rejected.maxPending)
        assertTrue(secondEphemeral.privateKey.material.copy().all { it == 0.toByte() })
        registry.close()
    }

    @Test
    fun `removing established session zeroizes all four keys`() = runTest {
        val registry = TrustedSessionRegistry(MutableClock(), maxEstablished = 2)
        val session = established(SessionId("established-remove"), 1)
        val keys = session.keys

        registry.registerEstablished(session)
        assertEquals(1, registry.establishedCount())
        assertTrue(registry.removeEstablished(session.sessionId))
        assertEquals(0, registry.establishedCount())

        assertAllZero(keys)
        registry.close()
    }

    @Test
    fun `established capacity rejects second session without destroying caller owned session`() = runTest {
        val registry = TrustedSessionRegistry(MutableClock(), maxEstablished = 1)
        val first = established(SessionId("established-a"), 1)
        val second = established(SessionId("established-b"), 2)

        registry.registerEstablished(first)
        assertFailsWith<IllegalStateException> {
            registry.registerEstablished(second)
        }
        assertFalse(second.keys.senderToReceiverDataKey.all { it == 0.toByte() })

        second.close()
        registry.close()
        assertAllZero(first.keys)
        assertAllZero(second.keys)
    }

    private fun pending(sessionId: SessionId): Pair<PendingInboundHandshake, X25519KeyPair> {
        val agreement = JvmX25519KeyAgreement()
        val ephemeral = agreement.generateKeyPair()
        return PendingInboundHandshake(
            sessionId = sessionId,
            peer = peer(50),
            localIdentity = localIdentity(60),
            localReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
            peerReferenceCode = ReferenceCodes.parse("JKLM-NPQR-STUV-WXYZ"),
            receiverEphemeral = ephemeral,
            handshake = JvmHandshakeProtocol(),
            signer = JvmEd25519Signer(),
            keyAgreement = agreement,
            keyDeriver = JvmHkdfSha256KeyDeriver(),
        ) to ephemeral
    }

    private fun established(sessionId: SessionId, marker: Int): EstablishedPeerSession =
        EstablishedPeerSession(
            sessionId = sessionId,
            peer = peer(marker + 20),
            localIdentity = localIdentity(marker),
            localReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
            peerReferenceCode = ReferenceCodes.parse("JKLM-NPQR-STUV-WXYZ"),
            keys = HandshakeKeys(
                senderToReceiverDataKey = ByteArray(32) { (marker + it + 1).toByte() },
                receiverToSenderDataKey = ByteArray(32) { (marker + it + 41).toByte() },
                senderToReceiverEnvelopeKey = ByteArray(32) { (marker + it + 81).toByte() },
                receiverToSenderEnvelopeKey = ByteArray(32) { (marker + it + 121).toByte() },
                transcriptHash = "transcript-$marker",
            ),
        )

    private fun peer(marker: Int) = PinnedPeerIdentity(
        contactId = ContactId("contact-$marker"),
        sharingIdentityId = SharingIdentityId("peer-$marker"),
        sharingIdentityIdHash = "peer-hash-$marker",
        publicKey = Ed25519PublicKey(ByteArray(32) { (marker + it).toByte() }),
        fingerprint = Fingerprint("fingerprint-$marker"),
    )

    private fun localIdentity(marker: Int) = SharingPublicIdentity(
        contextId = SharingContextId("context-$marker"),
        identityId = SharingIdentityId("local-$marker"),
        publicKey = Ed25519PublicKey(ByteArray(32) { (marker + it + 3).toByte() }),
        fingerprint = Fingerprint("local-fingerprint-$marker"),
    )

    private fun assertAllZero(keys: HandshakeKeys) {
        assertTrue(keys.senderToReceiverDataKey.all { it == 0.toByte() })
        assertTrue(keys.receiverToSenderDataKey.all { it == 0.toByte() })
        assertTrue(keys.senderToReceiverEnvelopeKey.all { it == 0.toByte() })
        assertTrue(keys.receiverToSenderEnvelopeKey.all { it == 0.toByte() })
    }

    private class MutableClock : TransferClock {
        private var now = 0L
        override fun nowMillis(): Long = now
        fun advance(delta: Long) {
            now += delta
        }
    }
}
