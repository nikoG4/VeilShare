package dev.veilshare.app

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.LookupTrustResult
import dev.veilshare.core.contacts.PeerTrustDecision
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.contacts.VerificationReason
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
import dev.veilshare.core.model.LookupRequest
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
import dev.veilshare.ui.features.SharingPeerLookupResult
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.SharingProgress
import dev.veilshare.ui.features.SharingRuntime
import dev.veilshare.ui.features.SharingRuntimeActivation
import dev.veilshare.ui.features.SharingRuntimeEvent
import dev.veilshare.ui.features.SharingSendResult
import dev.veilshare.ui.features.SharingVerificationReason
import dev.veilshare.ui.features.SharingVerificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val contacts: TrustedContactManager,
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
        claimInbox = ::claimSessionInbox,
        releaseInbox = ::releaseSessionInbox,
        eventSink = { mutableEvents.emit(it) },
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private var collectorJob: Job? = null
    private var activeContext: SharingContextId? = null
    private var activeVault: VaultHandle? = null
    private var currentOutgoing: OutgoingSharingTransfer? = null
    private var pendingVerification: PeerTrustDecision.NeedsVerification? = null
    private var closed = false

    init {
        require(handshakeTimeoutMs > 0)
        require(offerResponseTimeoutMs > 0)
    }

    override suspend fun activate(personaId: LocalPersonaId, vault: VaultHandle): SharingRuntimeActivation {
        if (closed) return SharingRuntimeActivation.Unavailable("Sharing runtime is closed")
        deactivate()
        return try {
            val contextId = contextBindings.getOrCreate(personaId)
            val registered = restoreTransport(contextId)
            stateMutex.withLock {
                activeContext = contextId
                activeVault = vault
                pendingVerification = null
            }
            inbound.activate(contextId, vault)
            SharingRuntimeActivation.Ready(registered.presence.referenceCode)
        } catch (_: Throwable) {
            stateMutex.withLock {
                activeContext = null
                activeVault = null
                pendingVerification = null
            }
            runCatching { signalingClient.close() }
            SharingRuntimeActivation.Unavailable("No se pudo activar el canal de compartir.")
        }
    }

    private suspend fun restoreTransport(contextId: SharingContextId): dev.veilshare.core.transfer.ActiveSharingPresence {
        signalingClient.connect()
        ensureCollector()
        return lifecycle.ensureRegistered(contextId)
    }

    override suspend fun refreshPresence(): SharingRuntimeActivation {
        if (closed) return SharingRuntimeActivation.Unavailable("Sharing runtime is closed")
        val contextId = stateMutex.withLock { activeContext }
            ?: return SharingRuntimeActivation.Unavailable("Compartir no está activo.")
        if (sendMutex.isLocked || inbound.hasActiveWork()) {
            return SharingRuntimeActivation.Unavailable("Hay una transferencia activa.")
        }
        return try {
            signalingClient.connect()
            ensureCollector()
            val registered = lifecycle.ensureRegistered(contextId)
            SharingRuntimeActivation.Ready(registered.presence.referenceCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SharingRuntimeActivation.Unavailable("No se pudo restablecer el canal de compartir.")
        }
    }

    override suspend fun inspectPeer(referenceCode: ReferenceCode): SharingPeerLookupResult {
        val contextId = stateMutex.withLock {
            pendingVerification = null
            activeContext
        } ?: return SharingPeerLookupResult.Unavailable("Compartir no está activo.")
        if (sendMutex.isLocked) return SharingPeerLookupResult.Unavailable("Hay una transferencia activa.")
        try {
            restoreTransport(contextId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return SharingPeerLookupResult.Failed("No se pudo restablecer el canal de compartir.")
        }
        val localIdentity = identities.publicIdentity(contextId)
            ?: return SharingPeerLookupResult.Unavailable("No se encontró la identidad local de compartir.")
        return try {
            val response = signalingClient.lookup(
                LookupRequest(
                    referenceCode = referenceCode,
                    requestorSharingIdentityId = localIdentity.identityId,
                ),
            )
            when (val resolved = LookupTrustResolver(contacts).resolve(referenceCode, response)) {
                is LookupTrustResult.Unavailable -> {
                    stateMutex.withLock { pendingVerification = null }
                    SharingPeerLookupResult.Unavailable("No hay un dispositivo disponible con ese código.")
                }
                is LookupTrustResult.Peer -> when (val decision = resolved.decision) {
                    is PeerTrustDecision.Trusted -> {
                        stateMutex.withLock { pendingVerification = null }
                        SharingPeerLookupResult.Trusted(decision.contact.alias)
                    }
                    is PeerTrustDecision.NeedsVerification -> {
                        stateMutex.withLock { pendingVerification = decision }
                        SharingPeerLookupResult.NeedsVerification(
                            fingerprint = decision.candidate.fingerprint.value,
                            reason = when (decision.reason) {
                                VerificationReason.NEW_PEER -> SharingVerificationReason.NEW_PEER
                                VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE -> SharingVerificationReason.IDENTITY_CHANGED
                            },
                            existingAlias = decision.existingContact?.alias,
                        )
                    }
                    is PeerTrustDecision.KeyMismatch -> {
                        stateMutex.withLock { pendingVerification = null }
                        SharingPeerLookupResult.KeyMismatch(
                            expectedFingerprint = decision.contact.fingerprint.value,
                            presentedFingerprint = decision.presentedFingerprint.value,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            stateMutex.withLock { pendingVerification = null }
            SharingPeerLookupResult.Failed("No se pudo comprobar la identidad del contacto.")
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
            val contextId = stateMutex.withLock {
                pendingVerification = null
                activeContext
            } ?: return SharingSendResult.Unavailable("Compartir no está activo.")

            try {
                restoreTransport(contextId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return SharingSendResult.Unavailable("Se perdió la conexión de compartir. Vuelve a intentarlo.")
            }

            val start = starter.start(contextId, referenceCode)
            val started = when (start) {
                is OutboundSessionStartResult.NeedsVerification -> {
                    stateMutex.withLock { pendingVerification = start.decision }
                    return SharingSendResult.NeedsVerification(
                        fingerprint = start.decision.candidate.fingerprint.value,
                        reason = when (start.decision.reason) {
                            VerificationReason.NEW_PEER -> SharingVerificationReason.NEW_PEER
                            VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE -> SharingVerificationReason.IDENTITY_CHANGED
                        },
                        existingAlias = start.decision.existingContact?.alias,
                    )
                }
                is OutboundSessionStartResult.KeyMismatch -> {
                    stateMutex.withLock { pendingVerification = null }
                    return SharingSendResult.KeyMismatch(
                        expectedFingerprint = start.decision.contact.fingerprint.value,
                        presentedFingerprint = start.decision.presentedFingerprint.value,
                    )
                }
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

    override suspend fun confirmPendingPeer(alias: String): SharingVerificationResult {
        val pending = stateMutex.withLock { pendingVerification }
            ?: return SharingVerificationResult.Failed("No hay una verificación pendiente.")
        return try {
            when (pending.reason) {
                VerificationReason.NEW_PEER -> contacts.addVerified(
                    alias = alias,
                    candidate = pending.candidate,
                    verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
                )
                VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE -> {
                    val existing = pending.existingContact
                        ?: return SharingVerificationResult.Failed("No se pudo verificar el contacto existente.")
                    contacts.confirmIdentityChange(
                        contactId = existing.contactId,
                        candidate = pending.candidate,
                        verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
                    )
                }
            }
            stateMutex.withLock {
                if (pendingVerification === pending) pendingVerification = null
            }
            SharingVerificationResult.Verified
        } catch (_: Throwable) {
            SharingVerificationResult.Failed("No se pudo guardar la verificación.")
        }
    }

    override suspend fun dismissPendingPeerVerification() {
        stateMutex.withLock { pendingVerification = null }
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
            pendingVerification = null
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
        pendingVerification = null
        inbound.close()
    }

    private fun ensureCollector() {
        if (collectorJob?.isActive == true) return
        // SignalingClient.incoming is intentionally non-replaying. Subscribe inline before
        // returning so the first fast RELAY/SESSION_HELLO cannot land in the tiny window
        // between launch() and collector startup and disappear.
        collectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
            // A claimed session is the data plane. Applying backpressure here is intentional:
            // trySend() could silently discard fragment 65+ when the bounded inbox fills.
            alreadyClaimed.send(envelope)
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
        // A channel can be claimed between the first lookup and handshake classification.
        // Suspend instead of dropping if its bounded buffer is temporarily full.
        channel?.send(envelope)
    }

    private suspend fun claimSessionInbox(sessionId: SessionId): Channel<SignalingEnvelope> {
        val channel = Channel<SignalingEnvelope>(Channel.BUFFERED)
        val pending = stateMutex.withLock {
            check(sessionInboxes.put(sessionId.value, channel) == null) { "Session inbox already claimed" }
            earlyRelays.remove(sessionId.value)?.toList().orEmpty()
        }
        pending.forEach { channel.send(it) }
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
