package dev.veilshare.app

import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.HandshakeProtocol
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.X25519KeyAgreement
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.platform.SignalingClient
import dev.veilshare.core.transfer.DecodedPeerMessage
import dev.veilshare.core.transfer.EstablishedSessionSide
import dev.veilshare.core.transfer.EstablishedTransferCrypto
import dev.veilshare.core.transfer.HandshakeSignalingInbox
import dev.veilshare.core.transfer.InMemoryTransferReceiver
import dev.veilshare.core.transfer.IncomingDispatchResult
import dev.veilshare.core.transfer.IncomingSharingTransfer
import dev.veilshare.core.transfer.ManagedInboundBeginResult
import dev.veilshare.core.transfer.ManagedTrustedSessionCoordinator
import dev.veilshare.core.transfer.ReceiveResult
import dev.veilshare.core.transfer.TransferReceiverProgress
import dev.veilshare.core.transfer.TrustedInboundHelloVerifier
import dev.veilshare.core.transfer.TrustedInboundSessionResponder
import dev.veilshare.core.transfer.TrustedSessionRegistry
import dev.veilshare.core.transfer.decodeIncomingTransferOffer
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.ui.features.SharingProgress
import dev.veilshare.ui.features.SharingRuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Receiver-side application orchestration built only from the trusted/session/transfer
 * primitives validated in PR #3. No handshake/session key or PendingInboundHandshake is
 * exposed outside this class.
 */
internal class InboundSharingCoordinator(
    identities: SharingIdentityManager,
    presence: SharingPresenceManager,
    private val contacts: TrustedContactManager,
    handshake: HandshakeProtocol,
    signer: Ed25519Signer,
    keyAgreement: X25519KeyAgreement,
    keyDeriver: KeyDeriver,
    private val cipher: AuthenticatedCipher,
    private val cryptoRandom: SecureRandom,
    private val idRandom: RandomBytesSource,
    private val scope: CoroutineScope,
    private val signalingClient: SignalingClient,
    private val claimInbox: suspend (SessionId) -> Channel<SignalingEnvelope>,
    private val releaseInbox: suspend (Channel<SignalingEnvelope>) -> Unit,
    private val eventSink: suspend (SharingRuntimeEvent) -> Unit,
    private val handshakeTimeoutMs: Long,
) : AutoCloseable {
    private val handshakeInbox = HandshakeSignalingInbox()
    private val registry = TrustedSessionRegistry()
    private val sessions = ManagedTrustedSessionCoordinator(
        inboundResponder = TrustedInboundSessionResponder(
            signalingClient = signalingClient,
            identities = identities,
            presence = presence,
            helloVerifier = TrustedInboundHelloVerifier(contacts, handshake, signer),
            handshake = handshake,
            signer = signer,
            keyAgreement = keyAgreement,
            keyDeriver = keyDeriver,
        ),
        registry = registry,
    )
    private val mutex = Mutex()
    private val handshakeJobs = linkedMapOf<String, Job>()
    private var activeContext: SharingContextId? = null
    private var activeVault: VaultHandle? = null
    private var current: CurrentInbound? = null
    private var closed = false

    init {
        require(handshakeTimeoutMs > 0)
    }

    suspend fun activate(contextId: SharingContextId, vault: VaultHandle) {
        mutex.withLock {
            check(!closed) { "Inbound sharing coordinator is closed" }
            activeContext = contextId
            activeVault = vault
        }
    }

    /**
     * Returns true only when [envelope] is an inbound SESSION_HELLO that this coordinator
     * has consumed. Other handshake/secure frames are left to the caller's bounded early
     * relay queue so outbound CONFIRM races remain lossless.
     */
    suspend fun consumeHelloIfPresent(sessionId: SessionId, envelope: SignalingEnvelope): Boolean {
        val routed = try {
            handshakeInbox.decodeRoutedRelay(envelope)
        } catch (_: Throwable) {
            return false
        }
        if (routed.message !is DecodedPeerMessage.Hello) return false

        val context = mutex.withLock {
            if (closed) return@withLock null
            activeContext
        } ?: return true

        val job = scope.launch {
            processHello(context, sessionId, routed)
        }
        val accepted = mutex.withLock {
            if (closed || activeContext != context || handshakeJobs.containsKey(sessionId.value)) {
                false
            } else {
                handshakeJobs[sessionId.value] = job
                true
            }
        }
        if (!accepted) job.cancel()
        job.invokeOnCompletion {
            scope.launch {
                mutex.withLock {
                    if (handshakeJobs[sessionId.value] === job) handshakeJobs.remove(sessionId.value)
                }
            }
        }
        return true
    }

    private suspend fun processHello(
        contextId: SharingContextId,
        sessionId: SessionId,
        routedHello: dev.veilshare.core.transfer.RoutedHandshakeMessage,
    ) {
        var channel: Channel<SignalingEnvelope>? = null
        var established = false
        var promoted = false
        var transferCrypto: EstablishedTransferCrypto? = null
        try {
            when (sessions.beginInbound(contextId, sessionId, routedHello)) {
                is ManagedInboundBeginResult.Pending -> Unit
                is ManagedInboundBeginResult.UnknownPeer,
                is ManagedInboundBeginResult.ReplayRejected,
                is ManagedInboundBeginResult.ReplayCapacityRejected,
                is ManagedInboundBeginResult.RegistryDuplicate,
                is ManagedInboundBeginResult.RegistryCapacityRejected -> return
            }

            channel = claimInbox(sessionId)
            val ackEnvelope = withTimeout(handshakeTimeoutMs) { channel.receive() }
            val routedAck = handshakeInbox.decodeRoutedRelay(ackEnvelope)
            val ack = (routedAck.message as? DecodedPeerMessage.ConfirmAck)?.value
                ?: throw IllegalArgumentException("Expected SESSION_CONFIRM_ACK")
            val session = sessions.completeInbound(sessionId, ack)
            established = true

            val offerEnvelope = withTimeout(handshakeTimeoutMs) { channel.receive() }
            // Envelope authentication is session+direction bound; TransferId lives inside the
            // encrypted envelope. Use a disposable messenger id only to obtain the responder
            // inbox, then reopen the crypto bundle with the authenticated actual TransferId.
            val probeCrypto = EstablishedTransferCrypto.open(
                session = session,
                side = EstablishedSessionSide.RESPONDER,
                transferId = OpaqueIds.transferId(idRandom),
                signalingClient = signalingClient,
                cipher = cipher,
                random = cryptoRandom,
            )
            val incomingOffer = try {
                decodeIncomingTransferOffer(offerEnvelope, session, probeCrypto)
            } finally {
                probeCrypto.close()
            }

            transferCrypto = EstablishedTransferCrypto.open(
                session = session,
                side = EstablishedSessionSide.RESPONDER,
                transferId = incomingOffer.transferId,
                signalingClient = signalingClient,
                cipher = cipher,
                random = cryptoRandom,
            )
            val receiver = InMemoryTransferReceiver(transferCrypto.decryptor)
            val transfer = IncomingSharingTransfer(
                session = session,
                transferId = incomingOffer.transferId,
                offer = incomingOffer.offer,
                crypto = transferCrypto,
                receiver = receiver,
            )
            val vault = mutex.withLock {
                if (activeContext != contextId) null else activeVault
            } ?: throw IllegalStateException("Vault locked while receiving offer")
            val alias = contacts.all()
                .firstOrNull { it.contactId == session.peer.contactId }
                ?.alias
                ?: session.peer.fingerprint.value
            val candidate = CurrentInbound(
                sessionId = sessionId,
                channel = channel,
                transfer = transfer,
                receiver = receiver,
                vault = vault,
            )
            val installed = mutex.withLock {
                if (closed || activeContext != contextId || current != null) false
                else {
                    current = candidate
                    true
                }
            }
            if (!installed) {
                runCatching { transfer.reject("receiver busy") }
                return
            }
            promoted = true
            transferCrypto = null // CurrentInbound/IncomingSharingTransfer owns it now.
            eventSink(
                SharingRuntimeEvent.IncomingOffer(
                    senderIdentity = alias,
                    fileName = incomingOffer.offer.displayName,
                    fileSize = incomingOffer.offer.sizeBytes,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (mutex.withLock { activeContext == contextId && !closed }) {
                eventSink(SharingRuntimeEvent.Failed("No se pudo establecer la transferencia entrante."))
            }
        } finally {
            if (!promoted) {
                transferCrypto?.close()
                if (!established) runCatching { sessions.cancelPending(sessionId) }
                else runCatching { sessions.closeSession(sessionId) }
                channel?.let { runCatching { releaseInbox(it) } }
            }
        }
    }

    suspend fun acceptIncoming() {
        val inbound = mutex.withLock {
            val value = current ?: throw IllegalStateException("No incoming offer is pending")
            check(!value.accepted) { "Incoming offer is already being processed" }
            value.accepted = true
            value
        }
        try {
            inbound.transfer.accept()
            while (true) {
                val envelope = withTimeout(INBOUND_IDLE_TIMEOUT_MS) { inbound.channel.receive() }
                when (val result = inbound.transfer.dispatch(envelope)) {
                    is IncomingDispatchResult.Data -> {
                        emitReceiverProgress(inbound)
                        if (result.result is ReceiveResult.Error) {
                            eventSink(SharingRuntimeEvent.Failed("La recepción falló."))
                            finish(inbound)
                            return
                        }
                    }
                    IncomingDispatchResult.ReadyToImport -> {
                        inbound.transfer.importIntoVault(inbound.vault)
                        eventSink(
                            SharingRuntimeEvent.Receiving(
                                SharingProgress(
                                    bytesTransferred = inbound.transfer.offer.sizeBytes,
                                    totalBytes = inbound.transfer.offer.sizeBytes,
                                    currentChunk = inbound.transfer.offer.totalChunks,
                                    totalChunks = inbound.transfer.offer.totalChunks,
                                ),
                            ),
                        )
                        eventSink(SharingRuntimeEvent.IncomingCompleted)
                        finish(inbound)
                        return
                    }
                    is IncomingDispatchResult.RemoteCancel -> {
                        eventSink(SharingRuntimeEvent.Cancelled)
                        finish(inbound)
                        return
                    }
                    is IncomingDispatchResult.RemoteFailure -> {
                        eventSink(SharingRuntimeEvent.Failed("El remitente informó un fallo."))
                        finish(inbound)
                        return
                    }
                    is IncomingDispatchResult.Control -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { inbound.transfer.cancel("local cancellation") }
                finish(inbound)
            }
            throw cancelled
        } catch (_: Throwable) {
            withContext(NonCancellable) {
                runCatching { inbound.transfer.cancel("receiver failure") }
                finish(inbound)
            }
            eventSink(SharingRuntimeEvent.Failed("No se pudo recibir el archivo."))
        }
    }

    suspend fun rejectIncoming() {
        val inbound = mutex.withLock {
            val value = current ?: throw IllegalStateException("No incoming offer is pending")
            check(!value.accepted) { "Incoming transfer was already accepted" }
            value
        }
        try {
            inbound.transfer.reject("user rejected")
        } finally {
            finish(inbound)
        }
    }

    suspend fun cancelCurrent() {
        val snapshot = mutex.withLock { current }
        if (snapshot != null) {
            runCatching { snapshot.transfer.cancel("local cancellation") }
            finish(snapshot)
        }

        val jobs = mutex.withLock { handshakeJobs.toMap() }
        jobs.values.forEach { it.cancel() }
        jobs.values.forEach { runCatching { it.cancelAndJoin() } }
        jobs.keys.forEach { sessionValue ->
            runCatching { sessions.cancelPending(SessionId(sessionValue)) }
        }
    }

    suspend fun deactivate() {
        mutex.withLock {
            activeContext = null
            activeVault = null
        }
        cancelCurrent()
    }

    private suspend fun emitReceiverProgress(inbound: CurrentInbound) {
        val progress = inbound.receiver.getProgress(inbound.transfer.transferId).value ?: return
        val ui = when (progress) {
            is TransferReceiverProgress.ChunkReceived -> SharingProgress(
                bytesTransferred = progress.bytesReceived.coerceAtMost(inbound.transfer.offer.sizeBytes),
                totalBytes = inbound.transfer.offer.sizeBytes,
                currentChunk = progress.chunkIndex + 1,
                totalChunks = progress.totalChunks,
            )
            is TransferReceiverProgress.TransferComplete -> SharingProgress(
                bytesTransferred = progress.totalBytes,
                totalBytes = inbound.transfer.offer.sizeBytes,
                currentChunk = progress.totalChunks,
                totalChunks = progress.totalChunks,
            )
            is TransferReceiverProgress.Error -> return
        }
        eventSink(SharingRuntimeEvent.Receiving(ui))
    }

    private suspend fun finish(inbound: CurrentInbound) {
        val removed = mutex.withLock {
            if (current === inbound) {
                current = null
                true
            } else false
        }
        if (!removed) return
        inbound.transfer.closeCrypto()
        runCatching { sessions.closeSession(inbound.sessionId) }
        runCatching { releaseInbox(inbound.channel) }
    }

    override fun close() {
        if (closed) return
        closed = true
        handshakeJobs.values.forEach { it.cancel() }
        handshakeJobs.clear()
        val inbound = current
        current = null
        inbound?.transfer?.closeCrypto()
        activeContext = null
        activeVault = null
        registry.close()
    }

    private data class CurrentInbound(
        val sessionId: SessionId,
        val channel: Channel<SignalingEnvelope>,
        val transfer: IncomingSharingTransfer,
        val receiver: InMemoryTransferReceiver,
        val vault: VaultHandle,
        var accepted: Boolean = false,
    )

    private companion object {
        const val INBOUND_IDLE_TIMEOUT_MS = 2L * 60L * 1000L
    }
}
