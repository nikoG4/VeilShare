package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.vault.DesktopLocalVaultService
import dev.veilshare.core.vault.LocalUnlockResult
import dev.veilshare.core.vault.VaultItem
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SecureSharingE2ETest {
    @Test
    fun `authenticated handshake derived key protects transfer imported into durable vault`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val signer = JvmEd25519Signer()
        val agreement = JvmX25519KeyAgreement()
        val keyDeriver = JvmHkdfSha256KeyDeriver()
        val handshake = JvmHandshakeProtocol()

        val senderIdentityId = SharingIdentityId("secure-e2e-sender")
        val receiverIdentityId = SharingIdentityId("secure-e2e-receiver")
        val senderIdentityKeys = signer.generateKeyPair()
        val receiverIdentityKeys = signer.generateKeyPair()
        val sessionId = SessionId("secure-e2e-session")

        val senderIdentityHash = Hash.sha256(senderIdentityId.value.encodeToByteArray()).toHex()
        val receiverIdentityHash = Hash.sha256(receiverIdentityId.value.encodeToByteArray()).toHex()
        val sessionHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex()

        // 1. Sender authenticates its long-term sharing identity.
        val hello = handshake.createSessionHello(senderIdentityId, senderIdentityKeys, sessionId, signer)
        handshake.verifySessionHello(
            hello,
            senderIdentityHash,
            sessionHash,
            senderIdentityKeys.publicKey,
            signer,
        )

        // 2. Receiver authenticates its identity plus fresh X25519 ephemeral key.
        val receiverEphemeral = agreement.generateKeyPair()
        val confirm = handshake.createSessionConfirm(
            receiverIdentityId,
            receiverIdentityKeys,
            sessionId,
            receiverEphemeral,
            signer,
        )
        handshake.verifySessionConfirm(
            confirm,
            receiverIdentityHash,
            sessionHash,
            receiverEphemeral.publicKey,
            receiverIdentityKeys.publicKey,
            signer,
        )

        // 3. Sender signs its own ephemeral key together with the full transcript.
        val senderEphemeral = agreement.generateKeyPair()
        val ack = handshake.createSessionConfirmAck(
            senderSharingIdentityId = senderIdentityId,
            senderSharingKeyPair = senderIdentityKeys,
            receiverSharingIdentityIdHash = receiverIdentityHash,
            sessionId = sessionId,
            senderEphemeralKeyPair = senderEphemeral,
            receiverEphemeralPublicKey = receiverEphemeral.publicKey,
            signer = signer,
        )
        val verifiedAck = handshake.verifySessionConfirmAck(
            ack = ack,
            expectedSenderSharingIdentityIdHash = senderIdentityHash,
            expectedReceiverSharingIdentityIdHash = receiverIdentityHash,
            expectedSessionIdHash = sessionHash,
            expectedReceiverEphemeralPublicKey = receiverEphemeral.publicKey,
            expectedSenderIdentityPublicKey = senderIdentityKeys.publicKey,
            signer = signer,
        )

        // 4. Both sides independently derive the same transcript-bound directional keys.
        val senderHandshakeKeys = handshake.deriveHandshakeKeys(
            senderEphemeral.privateKey,
            receiverEphemeral.publicKey,
            verifiedAck.transcript,
            agreement,
            keyDeriver,
        )
        val receiverHandshakeKeys = handshake.deriveHandshakeKeys(
            receiverEphemeral.privateKey,
            verifiedAck.senderEphemeralPublicKey,
            verifiedAck.transcript,
            agreement,
            keyDeriver,
        )
        assertContentEquals(
            senderHandshakeKeys.senderToReceiverKey,
            receiverHandshakeKeys.senderToReceiverKey,
        )
        assertEquals(senderHandshakeKeys.transcriptHash, receiverHandshakeKeys.transcriptHash)

        val senderTrafficKey = SensitiveBytes(senderHandshakeKeys.senderToReceiverKey.copyOf())
        val receiverTrafficKey = SensitiveBytes(receiverHandshakeKeys.senderToReceiverKey.copyOf())
        val root = Files.createTempDirectory("secure-sharing-e2e-")
        val plaintext = ByteArray(96 * 1024 + 37) { index -> ((index * 13 + 91) and 0xff).toByte() }
        val transferId = TransferId("secure-e2e-transfer")
        val fileId = FileId("secure-e2e-file")

        try {
            // 5. Transfer uses the key that came from the authenticated handshake.
            val receiver = InMemoryTransferReceiver(
                DefaultTransferDecryptor(crypto.cipher, receiverTrafficKey),
            )
            val result = DefaultTransferSender(crypto.random).send(
                transferId = transferId,
                fileId = fileId,
                source = ByteArrayTransferSource(plaintext),
                encryptor = DefaultTransferEncryptor(crypto.cipher, senderTrafficKey),
                sender = LoopbackNetwork(receiver),
            )
            assertEquals(1, result.totalChunks)

            // 6. Validated E2E offer metadata feeds the crash-safe vault import pipeline.
            val offer = TransferOffer(
                fileId = fileId,
                displayName = "secure-received.bin",
                mimeHint = "application/octet-stream",
                sizeBytes = plaintext.size.toLong(),
                totalChunks = result.totalChunks,
            )
            val service = DesktopLocalVaultService(root)
            service.createPair("9011".toCharArray(), "9022".toCharArray())
            val vault = assertIs<LocalUnlockResult.Ready>(service.unlock("9011".toCharArray())).vault
            val imported = ReceivedTransferVaultImporter(receiver).importCompleted(
                transferId = transferId,
                offer = offer,
                vault = vault,
            )
            assertContentEquals(plaintext, readAll(vault, imported))
            vault.close()

            // 7. Persistence proves the result reached encrypted vault storage, not only RAM.
            val reopened = assertIs<LocalUnlockResult.Ready>(
                DesktopLocalVaultService(root).unlock("9011".toCharArray()),
            ).vault
            val durable = assertIs<VaultItem.File>(reopened.find(imported.id))
            assertEquals("secure-received.bin", durable.displayName)
            assertContentEquals(plaintext, readAll(reopened, durable))
            reopened.close()
        } finally {
            senderTrafficKey.close()
            receiverTrafficKey.close()
            senderHandshakeKeys.senderToReceiverKey.fill(0)
            senderHandshakeKeys.receiverToSenderKey.fill(0)
            receiverHandshakeKeys.senderToReceiverKey.fill(0)
            receiverHandshakeKeys.receiverToSenderKey.fill(0)
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun readAll(
        vault: dev.veilshare.core.vault.VaultHandle,
        item: VaultItem.File,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        vault.readFile(item.id) { output.write(it) }
        return output.toByteArray()
    }

    private class LoopbackNetwork(
        private val receiver: TransferReceiver,
    ) : TransferNetworkSender {
        override suspend fun send(data: TransferData) {
            when (val result = receiver.receive(data)) {
                is ReceiveResult.Error -> throw TransferException(result.error)
                else -> Unit
            }
        }

        override suspend fun complete(
            transferIdHash: String,
            fileIdHash: String,
            totalChunks: Int,
        ) = Unit

        override suspend fun cancel(transferIdHash: String, reason: String) {
            receiver.abort(transferIdHash, reason)
        }
    }
}
