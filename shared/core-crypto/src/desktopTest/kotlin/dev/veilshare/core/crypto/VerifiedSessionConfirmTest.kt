package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class VerifiedSessionConfirmTest {
    private val signer = JvmEd25519Signer()
    private val keyAgreement = JvmX25519KeyAgreement()
    private val handshake = JvmHandshakeProtocol()

    @Test
    fun `signed confirm returns authenticated receiver ephemeral`() = runTest {
        val receiverIdentity = signer.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        try {
            val identityId = SharingIdentityId("receiver")
            val sessionId = SessionId("session-a")
            val confirm = handshake.createSessionConfirm(
                sharingIdentityId = identityId,
                sharingKeyPair = receiverIdentity,
                sessionId = sessionId,
                receiverEphemeralKeyPair = receiverEphemeral,
                signer = signer,
            )

            val verified = handshake.verifySessionConfirmFromPinnedIdentity(
                confirm = confirm,
                expectedSharingIdentityIdHash = Hash.sha256(identityId.value.encodeToByteArray()).toHex(),
                expectedSessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
                expectedReceiverIdentityPublicKey = receiverIdentity.publicKey,
                signer = signer,
            )

            assertContentEquals(receiverEphemeral.publicKey.bytes, verified.receiverEphemeralPublicKey.bytes)
        } finally {
            receiverIdentity.privateKey.material.close()
            receiverEphemeral.privateKey.material.close()
        }
    }

    @Test
    fun `ephemeral substitution invalidates receiver signature`() = runTest {
        val receiverIdentity = signer.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        val attackerEphemeral = keyAgreement.generateKeyPair()
        try {
            val identityId = SharingIdentityId("receiver")
            val sessionId = SessionId("session-a")
            val confirm = handshake.createSessionConfirm(
                identityId,
                receiverIdentity,
                sessionId,
                receiverEphemeral,
                signer,
            )
            val forged = confirm.copy(
                receiverEphemeralPublicKey = attackerEphemeral.publicKey.bytes.toBase64(),
            )

            assertFailsWith<IllegalArgumentException> {
                handshake.verifySessionConfirmFromPinnedIdentity(
                    forged,
                    Hash.sha256(identityId.value.encodeToByteArray()).toHex(),
                    Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
                    receiverIdentity.publicKey,
                    signer,
                )
            }
        } finally {
            receiverIdentity.privateKey.material.close()
            receiverEphemeral.privateKey.material.close()
            attackerEphemeral.privateKey.material.close()
        }
    }

    @Test
    fun `attacker identity cannot self authenticate confirm for pinned receiver`() = runTest {
        val receiverIdentity = signer.generateKeyPair()
        val attackerIdentity = signer.generateKeyPair()
        val attackerEphemeral = keyAgreement.generateKeyPair()
        try {
            val identityId = SharingIdentityId("receiver")
            val sessionId = SessionId("session-a")
            val forged = handshake.createSessionConfirm(
                identityId,
                attackerIdentity,
                sessionId,
                attackerEphemeral,
                signer,
            )

            assertFailsWith<IllegalArgumentException> {
                handshake.verifySessionConfirmFromPinnedIdentity(
                    forged,
                    Hash.sha256(identityId.value.encodeToByteArray()).toHex(),
                    Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
                    receiverIdentity.publicKey,
                    signer,
                )
            }
        } finally {
            receiverIdentity.privateKey.material.close()
            attackerIdentity.privateKey.material.close()
            attackerEphemeral.privateKey.material.close()
        }
    }

    @Test
    fun `confirm cannot be replayed into another session`() = runTest {
        val receiverIdentity = signer.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        try {
            val identityId = SharingIdentityId("receiver")
            val confirm = handshake.createSessionConfirm(
                identityId,
                receiverIdentity,
                SessionId("session-a"),
                receiverEphemeral,
                signer,
            )

            assertFailsWith<IllegalArgumentException> {
                handshake.verifySessionConfirmFromPinnedIdentity(
                    confirm,
                    Hash.sha256(identityId.value.encodeToByteArray()).toHex(),
                    Hash.sha256("session-b".encodeToByteArray()).toHex(),
                    receiverIdentity.publicKey,
                    signer,
                )
            }
        } finally {
            receiverIdentity.privateKey.material.close()
            receiverEphemeral.privateKey.material.close()
        }
    }
}
