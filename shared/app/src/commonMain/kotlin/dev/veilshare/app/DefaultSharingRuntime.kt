package dev.veilshare.app

import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.HandshakeProtocol
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.X25519KeyAgreement
import dev.veilshare.core.identity.SharingContextBindingManager
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.platform.SignalingClient
import dev.veilshare.core.transfer.DefaultTransferSender
import dev.veilshare.core.transfer.EstablishedPeerSession
import dev.veilshare.core.transfer.EstablishedSessionSide
import dev.veilshare.core.transfer.EstablishedTransferCrypto
import dev.veilshare.core.transfer.HandshakeSignalingInbox
import dev.veilshare.core.transfer.OutgoingControlResult
import dev.veilshare.core.transfer.OutgoingSharingTransfer
import dev.veilshare.core.transfer.OutgoingTransferState
import dev.veilshare.core.transfer.OutboundSessionStartResult
import dev.veilshare.core.transfer.SharingPresenceLifecycle
import dev.veilshare.core.transfer.TransferProtocol
import dev.veilshare.core.transfer.TransferResult
import dev.veilshare.core.transfer.TransferSender
import dev.veilshare.core.transfer.TransferSource
import dev.veilshare.core.transfer.TrustedOutboundHandshakeCompleter
import dev.veilshare.core.transfer.TrustedOutboundSessionStarter
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.SharingProgress
import dev.veilshare.ui.features.SharingRuntime
import dev.veilshare.ui.features.SharingRuntimeActivation
import dev.veilshare.ui.features.SharingRuntimeEvent
import dev.veilshare.ui.features.SharingSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Application-level owner of Sharing V1 state.
 *
 * Compose never sees handshake/session keys. This class delegates all cryptographic and
 * transfer transitions to the already validated core implementations and only translates
 * their terminal states into the high-level UI contract.
 */
class DefaultSharingRuntime(
    private val signalingClient: SignalingClient,
    private val contextBindings: SharingContextBindingManager,
    private val identities: SharingIdentityManager,
    private val presence: SharingPresenceManager,
    contacts: TrustedContactManager,
    handshake: HandshakeProtocol,
    signer: Ed25519Signer,
    keyAgreement: X25519KeyAgreement,
    keyDeriver: KeyDeriver,
    private val cipher: AuthenticatedCipher,
    private val cryptoRandom: SecureRandom,
    private val idRandom: RandomBytesSource,
    private val scope: CoroutineScope,
    private val transferSender: TransferSender = DefaultTransferSender(cryptoRandom),
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val offerResponseTimeoutMs: Long = DEFAULT_OFFER_RESPONSE_TIMEOUT_MS,
) : SharingRuntime {
    private val lifecycle = SharingPresenceLifecycle(signalingClient, identities, presence)
    private val starter = TrustedOutboundSessionStarter(
        signalingClient,
        identities,
        presence,
        LookupTrustResolver(contacts),
        handshake,
        signer,
        idRandom,
    )
    private val completer = TrustedOutboundHandshakeCompleter(
        signalingClient,
        identities,
        handshake,
        signer,
        keyAgreement,
        keyDeriver,
    )
    private val handshakeInbox = HandshakeSignalingInbox()

    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private val sessionInboxes = mutableMapOf<String, Channel<SignalingEnvelope>>()
    private val earlyRelays = linkedMapOf<String, ArrayDeque<SignalingEnvelope>>()
    private val mutableEvents = MutableSharedFlow<SharingRuntimeEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SharingRuntimeEvent> = mutableEvents.asSharedFlow()
    private val inbound = InboundSharingCoordinator(
        signalingClient = signalingClient,
        identities = identities,
        presence = presence,
        contacts = contacts,
        handshake = handshake,
        signer = signer,
        keyAgreement = keyAgreement,
        keyDeriver = keyDeriver,
        cipher = cipher,
        cryptoRandom = cryptoRandom,
        idRandom = idRandom,
        scope = scope,
        signalingClient = signalingClient,
        claimInbox = ::claimSessionInbox,
        releaseInbox = ::releaseSessionInbox,
        eventSink = { mutableEvents.emit(it) },
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private var collectorJob: Job? = null
    private var activeContext: SharingContextId? = null
    private var activeVault: VaultHandle? = null
    private var currentOutgoing: OutgoingSharingTransfer? = null
    private var closed = false

    init {
        require(handshakeTimeoutMs > 0)
        require(offerResponseTimeoutMs > 0)
    }

    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation {
        if (closed) return SharingRuntimeActivation.Unavailable("Sharing runtime is closed")
        deactivate()
        return try {
            signalingClient.connect()
            ensureCollector()
            val contextId = contextBindings.getOrCreate(personaId)
            val registered = lifecycle.ensureRegistered(contextId)
            stateMutex.withLock {
                activeContext = contextId
                activeVault = vault
            }
            inbound.activate(contextId, vault)
            SharingRuntimeActivation.Ready(registered.presence.referenceCode)
        } catch (_: Throwable) {
            stateMutex.withLock {
                activeContext = null
                activeVault = null
            }
            runCatching { signalingClient.close() }
            SharingRuntimeActivation.Unavailable("No se pudo activar el canal de compartir.")
        }
    }

    override suspend fun send(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult = sendMutex.withLock {
        sendLocked(referenceCode, file, onProgress)
    }

    private suspend fun sendLocked(
        referenceCode: ReferenceCode,
        file: SharingPickedFile,
        onProgress: suspend (SharingProgress) -> Unit,
    ): SharingSendResult {
        var transferOwnsFile = false
        var channel: Channel<SignalingEnvelope>? = null
        var session: EstablishedPeerSession? = null
        var transferCrypto: EstablishedTransferCrypto? = null
        var outgoingTransfer: OutgoingSharingTransfer? = null
        try {
            val contextId = stateMutex.withLock { activeContext }
                ?: return SharingSendResult.Unavailable("Compartir no está activo.")

            val start = starter.start(contextId, referenceCode)
            val started = when (start) {
                is OutboundSessionStartResult.NeedsVerification ->
                    return SharingSendResult.NeedsVerification(start.decision.candidate.fingerprint.value)
                is OutboundSessionStartResult.KeyMismatch ->
                    return SharingSendResult.KeyMismatch(
                        expectedFingerprint = start.decision.contact.fingerprint.value,
                        presentedFingerprint = start.decision.presentedFingerprint.value,
                    )
                is OutboundSessionStartResult.Unavailable ->
                    return SharingSendResult.Unavailable("El destinatario no está disponible.")
                is OutboundSessionStartResult.Started -> start
            }

            channel = claimSessionInbox(started.sessionId)
            val confirmEnvelope = withTimeout(handshakeTimeoutMs) { channel.receive() }
            val routedConfirm = handshakeInbox.decodeRoutedRelay(confirmEnvelope)
            val completion = completer.complete(started, routedConfirm)
            session = completion.session

            val transferId = OpaqueIds.transferId(idRandom)
            val fileId = FileId(OpaqueIds.fromRandom(idRandom))
            transferCrypto = EstablishedTransferCrypto.open(
                session = session,
                side = EstablishedSessionSide.INITIATOR,
                transferId = transferId,
                signalingClient = signalingClient,
                cipher = cipher,
                random = cryptoRandom,
            )

            val source = UiTransferSource(file, onProgress)
            val outgoing = OutgoingSharingTransfer(
                session = session,
                transferId = transferId,
                fileId = fileId,
                source = source,
                crypto = transferCrypto,
                sender = transferSender,
            )
            outgoingTransfer = outgoing
            transferOwnsFile = true
            stateMutex.withLock { currentOutgoing = outgoing }
            outgoing.sendOffer()

            val control = withTimeout(offerResponseTimeoutMs) {
                while (true) {
                    when (val result = outgoing.handleControl(channel.receive())) {
                        OutgoingControlResult.Accepted -> return@withTimeout result
                        is OutgoingControlResult.Rejected -> return@withTimeout result
                        is OutgoingControlResult.RemoteCancel -> return@withTimeout result
                        is OutgoingControlResult.RemoteFailure -> return@withTimeout result
                        is OutgoingControlResult.Ignored -> Unit
                    }
                }
                error("Unreachable")
            }

            when (control) {
                OutgoingControlResult.Accepted -> {
                    val result = try {
                        sendAcceptedWithRemoteControl(outgoing, channel)
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                        when (outgoing.state) {
                            OutgoingTransferState.CANCELLED ->
                                return SharingSendResult.Failed("El destinatario canceló la transferencia.")
                            OutgoingTransferState.FAILED ->
                                return SharingSendResult.Failed("La transferencia remota falló.")
                            else -> throw cancelled
                        }
                    }
                    onProgress(
                        SharingProgress(
                            bytesTransferred = result.totalBytes,
                            totalBytes = result.totalBytes,
                            currentChunk = result.totalChunks,
                            totalChunks = result.totalChunks,
                        ),
                    )
                    return SharingSendResult.Completed
                }
                is OutgoingControlResult.Rejected ->
                    return SharingSendResult.Failed("El destinatario rechazó el archivo.")
                is OutgoingControlResult.RemoteCancel ->
                    return SharingSendResult.Failed("El destinatario canceló la transferencia.")
                is OutgoingControlResult.RemoteFailure ->
                    return SharingSendResult.Failed("La transferencia remota falló.")
                is OutgoingControlResult.Ignored -> error("Terminal wait returned ignored control")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return SharingSendResult.Failed("No se pudo completar la transferencia.")
        } finally {
            val outgoing = outgoingTransfer
            stateMutex.withLock {
                if (currentOutgoing === outgoing) currentOutgoing = null
            }
            if (outgoing != null && outgoing.state != OutgoingTransferState.COMPLETE) {
                withContext(NonCancellable) { runCatching { outgoing.abandonBeforeSend() } }
            }
            transferCrypto?.close()
            session?.close()
            channel?.let { releaseSessionInbox(it) }
            if (!transferOwnsFile) {
                withContext(NonCancellable) { runCatching { file.close() } }
            }
        }
    }

    /**
     * DATA sending and post-ACCEPT control reception must run concurrently. PR #3's
     * state-aware sender stops at the next DATA/COMPLETE boundary only after
     * OutgoingSharingTransfer.handleControl observes remote CANCEL/FAILURE.
     */
    private suspend fun sendAcceptedWithRemoteControl(
        outgoing: OutgoingSharingTransfer,
        channel: Channel<SignalingEnvelope>,
    ): TransferResult = coroutineScope {
        val controlJob = launch {
            while (true) {
                when (outgoing.handleControl(channel.receive())) {
                    is OutgoingControlResult.RemoteCancel,
                    is OutgoingControlResult.RemoteFailure -> return@launch
                    is OutgoingControlResult.Ignored -> Unit
                    OutgoingControlResult.Accepted,
                    is OutgoingControlResult.Rejected ->
                        throw IllegalStateException("Unexpected terminal OFFER response after ACCEPT")
                }
            }
        }
        try {
            outgoing.sendAccepted()
        } finally {
            controlJob.cancelAndJoin()
        }
    }

    override suspend fun acceptIncoming() {
        inbound.acceptIncoming()
    }

    override suspend fun rejectIncoming() {
        inbound.rejectIncoming()
    }

    override suspend fun cancelCurrent() {
        val outgoing = stateMutex.withLock { currentOutgoing }
        if (outgoing != null) runCatching { outgoing.cancel("local cancellation") }
        inbound.cancelCurrent()
    }

    override suspend fun deactivate() {
        val context = stateMutex.withLock {
            val value = activeContext
            activeContext = null
            activeVault = null
            value
        }
        runCatching { cancelCurrent() }
        runCatching { inbound.deactivate() }
        if (context != null) runCatching { lifecycle.deactivate(context) }
        clearSessionInboxes()
        runCatching { signalingClient.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        collectorJob?.cancel()
        collectorJob = null
        sessionInboxes.values.forEach { it.close() }
        sessionInboxes.clear()
        earlyRelays.clear()
        inbound.close()
    }

    private fun ensureCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            signalingClient.incoming.collect { envelope ->
                if (envelope.type != MessageType.RELAY) return@collect
                val sessionId = envelope.sessionId ?: return@collect
                routeRelay(sessionId, envelope)
            }
        }
    }

    private suspend fun routeRelay(sessionId: SessionId, envelope: SignalingEnvelope) {
        val alreadyClaimed = stateMutex.withLock { sessionInboxes[sessionId.value] }
        if (alreadyClaimed != null) {
            alreadyClaimed.trySend(envelope)
            return
        }

        if (inbound.consumeHelloIfPresent(sessionId, envelope)) return

        // Re-check after handshake classification: an outbound/inbound waiter may have
        // claimed the session while the envelope was being authenticated/decoded.
        val channel = stateMutex.withLock {
            sessionInboxes[sessionId.value]?.also { return@withLock it }

            val currentTotal = totalEarlyRelaysLocked()
            val existing = earlyRelays[sessionId.value]
            if (existing != null) {
                if (existing.size < MAX_EARLY_RELAYS_PER_SESSION && currentTotal < MAX_EARLY_RELAYS_TOTAL) {
                    existing.addLast(envelope)
                }
            } else if (currentTotal < MAX_EARLY_RELAYS_TOTAL) {
                earlyRelays[sessionId.value] = ArrayDeque<SignalingEnvelope>().apply { addLast(envelope) }
            }
            null
        }
        channel?.trySend(envelope)
    }

    private suspend fun claimSessionInbox(sessionId: SessionId): Channel<SignalingEnvelope> {
        val channel = Channel<SignalingEnvelope>(Channel.BUFFERED)
        val pending = stateMutex.withLock {
            check(sessionInboxes.put(sessionId.value, channel) == null) { "Session inbox already claimed" }
            earlyRelays.remove(sessionId.value)?.toList().orEmpty()
        }
        pending.forEach { channel.trySend(it) }
        return channel
    }

    private suspend fun releaseSessionInbox(channel: Channel<SignalingEnvelope>) {
        stateMutex.withLock {
            val entry = sessionInboxes.entries.firstOrNull { it.value === channel }
            if (entry != null) sessionInboxes.remove(entry.key)
        }
        channel.close()
    }

    private suspend fun clearSessionInboxes() {
        val channels = stateMutex.withLock {
            val values = sessionInboxes.values.toList()
            sessionInboxes.clear()
            earlyRelays.clear()
            values
        }
        channels.forEach { it.close() }
    }

    private fun totalEarlyRelaysLocked(): Int = earlyRelays.values.sumOf { it.size }

    private class UiTransferSource(
        private val file: SharingPickedFile,
        private val onProgress: suspend (SharingProgress) -> Unit,
    ) : TransferSource {
        override val fileSize: Long = file.size
        override val displayName: String = file.displayName
        override val mimeHint: String? = file.mimeType
        private val totalChunks = if (fileSize == 0L) 0 else (((fileSize - 1L) / TransferProtocol.CHUNK_SIZE) + 1L).toInt()
        private var furthestByte = 0L

        override suspend fun readChunk(offset: Long, size: Int): ByteArray {
            val bytes = file.readChunk(offset, size)
            furthestByte = maxOf(furthestByte, offset + bytes.size)
            val chunk = if (bytes.isEmpty()) 0 else ((offset / TransferProtocol.CHUNK_SIZE) + 1L).toInt()
            onProgress(
                SharingProgress(
                    bytesTransferred = furthestByte.coerceAtMost(fileSize),
                    totalBytes = fileSize,
                    currentChunk = chunk,
                    totalChunks = totalChunks,
                ),
            )
            return bytes
        }

        override suspend fun close() = file.close()
    }

    companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 2L * 60L * 1000L
        const val DEFAULT_OFFER_RESPONSE_TIMEOUT_MS = 2L * 60L * 1000L
        const val MAX_EARLY_RELAYS_PER_SESSION = 8
        const val MAX_EARLY_RELAYS_TOTAL = 32
    }
}
