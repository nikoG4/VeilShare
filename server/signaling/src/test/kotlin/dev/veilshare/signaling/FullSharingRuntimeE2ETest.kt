package dev.veilshare.signaling

import dev.veilshare.app.DefaultSharingRuntime
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.JvmChaCha20Poly1305Cipher
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmSecureRandom
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.identity.InMemorySharingContextBindingStore
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.InMemorySharingPresenceStore
import dev.veilshare.core.identity.SharingContextBindingManager
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.BlobId
import dev.veilshare.core.model.ErrorCode
import dev.veilshare.core.model.ErrorMessage
import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.platform.KtorSignalingClient
import dev.veilshare.core.platform.SignalingClient
import dev.veilshare.core.platform.SignalingClientException
import dev.veilshare.core.vault.ImportProgress
import dev.veilshare.core.vault.ImportSource
import dev.veilshare.core.vault.VaultDirectoryId
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import dev.veilshare.core.vault.VaultItemId
import dev.veilshare.ui.features.SharingPeerLookupResult
import dev.veilshare.ui.features.SharingPickedFile
import dev.veilshare.ui.features.SharingProgress
import dev.veilshare.ui.features.SharingRuntimeActivation
import dev.veilshare.ui.features.SharingRuntimeEvent
import dev.veilshare.ui.features.SharingSendResult
import dev.veilshare.ui.features.SharingVerificationResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class FullSharingRuntimeE2ETest {
    @Test
    fun staleRegistrationCollisionRotatesOnlyReferenceCodeAndRecoversPresence() = testApplication {
        application { signalingModule() }
        val http = createClient { install(WebSockets) }
        val delegate = KtorSignalingClient(
            httpClient = http,
            endpointUrl = "/v1/ws",
            random = SecureIdRandom(JvmSecureRandom()),
            timeoutMillis = 5_000,
        )
        val collision = RejectSecondRegisterClient(delegate)
        val alice = RuntimeFixture(http, LocalPersonaId("f".repeat(64)), collision)

        try {
            val first = assertIs<SharingRuntimeActivation.Ready>(alice.runtime.activate(alice.personaId, alice.vault))
            val recovered = assertIs<SharingRuntimeActivation.Ready>(alice.runtime.refreshPresence())
            assertNotEquals(first.referenceCode, recovered.referenceCode)
            assertEquals(3, collision.registerAttempts)
        } finally {
            alice.close()
        }
    }

    @Test
    fun idleRuntimeReconnectsAndReregistersSamePresenceAfterTransportLoss() = testApplication {
        val state = SignalingServerState(clock = SignalingClock { System.currentTimeMillis() })
        application { signalingModule(state) }
        val http = createClient { install(WebSockets) }
        val alice = RuntimeFixture(http, LocalPersonaId("c".repeat(64)))

        try {
            val activation = assertIs<SharingRuntimeActivation.Ready>(
                alice.runtime.activate(alice.personaId, alice.vault),
            )
            val original = assertNotNull(state.presence.lookup(activation.referenceCode))
            assertNotNull(state.sockets[original.connectionId]).close(
                CloseReason(CloseReason.Codes.GOING_AWAY, "idle transport loss"),
            )

            withTimeout(10_000) {
                while (true) {
                    val recovered = state.presence.lookup(activation.referenceCode)
                    if (recovered != null && recovered.connectionId != original.connectionId) break
                    delay(20)
                }
            }
            val recovered = assertNotNull(state.presence.lookup(activation.referenceCode))
            assertEquals(original.sharingIdentityId, recovered.sharingIdentityId)
            assertEquals(original.sharingPublicKey, recovered.sharingPublicKey)
            assertTrue(state.sockets.containsKey(recovered.connectionId))
        } finally {
            alice.close()
        }
    }

    @Test
    fun authenticatedActiveTransferDoesNotReconnectUnderTransportLoss() = testApplication {
        val state = SignalingServerState(clock = SignalingClock { System.currentTimeMillis() })
        application { signalingModule(state) }
        val http = createClient { install(WebSockets) }
        val alice = RuntimeFixture(http, LocalPersonaId("d".repeat(64)))
        val bob = RuntimeFixture(http, LocalPersonaId("e".repeat(64)))

        try {
            val aliceActivation = assertIs<SharingRuntimeActivation.Ready>(alice.runtime.activate(alice.personaId, alice.vault))
            val bobActivation = assertIs<SharingRuntimeActivation.Ready>(bob.runtime.activate(bob.personaId, bob.vault))
            assertIs<SharingPeerLookupResult.NeedsVerification>(alice.runtime.inspectPeer(bobActivation.referenceCode))
            assertIs<SharingVerificationResult.Verified>(alice.runtime.confirmPendingPeer("Bob"))
            assertIs<SharingPeerLookupResult.NeedsVerification>(bob.runtime.inspectPeer(aliceActivation.referenceCode))
            assertIs<SharingVerificationResult.Verified>(bob.runtime.confirmPendingPeer("Alice"))

            coroutineScope {
                val incomingOffer = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) {
                        bob.runtime.events.filterIsInstance<SharingRuntimeEvent.IncomingOffer>().first()
                    }
                }
                val sending = async {
                    alice.runtime.send(
                        bobActivation.referenceCode,
                        BytesSharingFile("blocked.bin", ByteArray(32) { it.toByte() }),
                    ) { }
                }
                incomingOffer.await()
                val transportFailure = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        alice.runtime.events.filterIsInstance<SharingRuntimeEvent.Failed>().first()
                    }
                }
                val alicePresence = assertNotNull(state.presence.lookup(aliceActivation.referenceCode))
                assertNotNull(state.sockets[alicePresence.connectionId]).close(
                    CloseReason(CloseReason.Codes.GOING_AWAY, "active transport loss"),
                )
                transportFailure.await()
                delay(500)
                assertNull(state.presence.lookup(aliceActivation.referenceCode))
                sending.cancel()
            }
        } finally {
            alice.close()
            bob.close()
        }
    }

    @Test
    fun trustedPeersTransferAndImportMultiChunkFileThroughRealRelay() = testApplication {
        application { signalingModule() }
        val http = createClient { install(WebSockets) }
        val alice = RuntimeFixture(http, LocalPersonaId("a".repeat(64)))
        val bob = RuntimeFixture(http, LocalPersonaId("b".repeat(64)))

        try {
            val aliceActivation = assertIs<SharingRuntimeActivation.Ready>(
                alice.runtime.activate(alice.personaId, alice.vault),
            )
            val bobActivation = assertIs<SharingRuntimeActivation.Ready>(
                bob.runtime.activate(bob.personaId, bob.vault),
            )

            // ReferenceCode is routing only. Pin both identities through the same explicit
            // verification boundary exposed to the UI before any authenticated handshake.
            assertIs<SharingPeerLookupResult.NeedsVerification>(
                alice.runtime.inspectPeer(bobActivation.referenceCode),
            )
            assertIs<SharingVerificationResult.Verified>(alice.runtime.confirmPendingPeer("Bob"))
            assertIs<SharingPeerLookupResult.NeedsVerification>(
                bob.runtime.inspectPeer(aliceActivation.referenceCode),
            )
            assertIs<SharingVerificationResult.Verified>(bob.runtime.confirmPendingPeer("Alice"))

            assertIs<SharingPeerLookupResult.Trusted>(
                alice.runtime.inspectPeer(bobActivation.referenceCode),
            )
            assertIs<SharingPeerLookupResult.Trusted>(
                bob.runtime.inspectPeer(aliceActivation.referenceCode),
            )

            // Larger than Sharing V1's 1 MiB chunk so the E2E necessarily exercises DATA
            // sequencing, multiple AEAD records, COMPLETE, receiver import and source close.
            val payload = ByteArray(1_200_123) { index -> ((index * 31 + 17) and 0xff).toByte() }
            val selected = BytesSharingFile("multi-chunk.bin", payload)
            val senderProgress = mutableListOf<SharingProgress>()

            coroutineScope {
                val receiving = async(start = CoroutineStart.UNDISPATCHED) {
                    val offer = withTimeoutOrNull(10_000) {
                        bob.runtime.events
                            .filterIsInstance<SharingRuntimeEvent.IncomingOffer>()
                            .first()
                    }
                    assertNotNull(offer, "receiver never observed authenticated IncomingOffer")
                    assertEquals("Alice", offer.senderIdentity)
                    assertEquals(selected.displayName, offer.fileName)
                    assertEquals(payload.size.toLong(), offer.fileSize)

                    val receiverFinished = withTimeoutOrNull(30_000) {
                        bob.runtime.acceptIncoming()
                        true
                    }
                    assertEquals(true, receiverFinished, "receiver timed out after accepting offer")
                }

                val sendResult = withTimeoutOrNull(30_000) {
                    alice.runtime.send(bobActivation.referenceCode, selected) { progress ->
                        senderProgress += progress
                    }
                }
                assertNotNull(sendResult, "sender timed out during handshake/offer/DATA")
                assertIs<SharingSendResult.Completed>(sendResult)
                receiving.await()
            }

            assertEquals(1, selected.closeCalls, "runtime must close sender source exactly once")
            assertTrue(senderProgress.any { it.totalChunks >= 2 }, "transfer must exercise multiple chunks")
            assertEquals(payload.size.toLong(), senderProgress.last().bytesTransferred)
            assertEquals(1, bob.vault.importedFiles.size)
            assertEquals("multi-chunk.bin", bob.vault.importedFiles.single().displayName)
            assertContentEquals(payload, bob.vault.importedBytes.single())
        } finally {
            alice.close()
            bob.close()
        }
    }
}

private class RuntimeFixture(
    http: HttpClient,
    val personaId: LocalPersonaId,
    signalingClientOverride: SignalingClient? = null,
) {
    private val cryptoRandom = JvmSecureRandom()
    private val idRandom = SecureIdRandom(cryptoRandom)
    private val signer = JvmEd25519Signer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val vault = CapturingVault()

    private val signalingClient = signalingClientOverride ?: KtorSignalingClient(
            httpClient = http,
            endpointUrl = "/v1/ws",
            random = idRandom,
            timeoutMillis = 5_000,
            registrationKeepAliveMillis = 1_000,
        )

    val runtime = DefaultSharingRuntime(
        signalingClient = signalingClient,
        contextBindings = SharingContextBindingManager(InMemorySharingContextBindingStore(), idRandom),
        identities = SharingIdentityManager(InMemorySharingIdentityStore(), idRandom, signer),
        presence = SharingPresenceManager(InMemorySharingPresenceStore(), idRandom),
        contacts = TrustedContactManager(InMemoryTrustedContactStore(), idRandom),
        handshake = JvmHandshakeProtocol(),
        signer = signer,
        keyAgreement = JvmX25519KeyAgreement(),
        keyDeriver = JvmHkdfSha256KeyDeriver(),
        cipher = JvmChaCha20Poly1305Cipher(cryptoRandom),
        cryptoRandom = cryptoRandom,
        idRandom = idRandom,
        scope = scope,
        handshakeTimeoutMs = 10_000,
        offerResponseTimeoutMs = 10_000,
    )

    suspend fun close() {
        runCatching { runtime.deactivate() }
        runtime.close()
        scope.cancel()
        vault.close()
    }
}

private class RejectSecondRegisterClient(
    private val delegate: SignalingClient,
) : SignalingClient by delegate {
    var registerAttempts = 0
        private set

    override suspend fun register(request: RegisterRequest) {
        registerAttempts++
        if (registerAttempts == 2) {
            throw SignalingClientException(ErrorMessage(ErrorCode.INVALID_MESSAGE, "Invalid message"))
        }
        delegate.register(request)
    }
}

private class SecureIdRandom(private val secure: JvmSecureRandom) : RandomBytesSource {
    override fun nextBytes(size: Int): ByteArray = secure.bytes(size)
}

private class BytesSharingFile(
    override val displayName: String,
    private val bytes: ByteArray,
) : SharingPickedFile {
    override val size: Long = bytes.size.toLong()
    override val mimeType: String = "application/octet-stream"
    var closeCalls: Int = 0
        private set

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        require(offset >= 0)
        require(size > 0)
        if (offset >= bytes.size) return ByteArray(0)
        val start = offset.toInt()
        return bytes.copyOfRange(start, minOf(bytes.size, start + size))
    }

    override suspend fun close() {
        closeCalls++
    }
}

private class CapturingVault : VaultHandle {
    private val entries = mutableListOf<VaultItem>()
    private val bytesById = linkedMapOf<String, ByteArray>()
    private var nextId = 1
    override var isOpen: Boolean = true
        private set

    val importedFiles: List<VaultItem.File>
        get() = entries.filterIsInstance<VaultItem.File>()
    val importedBytes: List<ByteArray>
        get() = importedFiles.map { bytesById.getValue(it.id.value).copyOf() }

    override suspend fun recover() = Unit
    override suspend fun reload() = Unit

    override fun items(parent: VaultDirectoryId?): List<VaultItem> =
        entries.filter { it.parentId == parent }

    override fun find(id: VaultItemId): VaultItem? = entries.firstOrNull { it.id == id }

    override suspend fun createDirectory(parent: VaultDirectoryId?, name: String): VaultItem.Directory {
        val item = VaultItem.Directory(VaultItemId("dir-${nextId++}"), parent, name)
        entries += item
        return item
    }

    override suspend fun rename(id: VaultItemId, name: String) {
        val index = entries.indexOfFirst { it.id == id }
        require(index >= 0) { "Item not found" }
        entries[index] = when (val item = entries[index]) {
            is VaultItem.Directory -> item.copy(displayName = name)
            is VaultItem.File -> item.copy(displayName = name)
        }
    }

    override suspend fun import(
        source: ImportSource,
        parent: VaultDirectoryId?,
        progress: suspend (ImportProgress) -> Unit,
    ): VaultItem.File {
        check(isOpen)
        progress(ImportProgress.Preparing)
        val handle = source.openRead()
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val chunk = handle.read(128 * 1024)
                if (chunk.isEmpty()) break
                output.write(chunk)
                progress(ImportProgress.Encrypting(output.size().toLong(), source.sizeHint))
            }
        } finally {
            handle.close()
        }
        val bytes = output.toByteArray()
        source.sizeHint?.let { assertEquals(it, bytes.size.toLong(), "import source size changed") }
        val id = VaultItemId("file-${nextId++}")
        val file = VaultItem.File(
            id = id,
            parentId = parent,
            displayName = source.displayName,
            mimeType = source.mimeHint,
            size = bytes.size.toLong(),
            blobId = BlobId("blob-${nextId++}"),
        )
        progress(ImportProgress.Committing)
        entries += file
        bytesById[id.value] = bytes
        progress(ImportProgress.Complete(file))
        return file
    }

    override suspend fun deleteFile(id: VaultItemId) {
        entries.removeAll { it.id == id }
        bytesById.remove(id.value)
    }

    override suspend fun deleteEmptyDirectory(id: VaultItemId) {
        require(entries.none { it.parentId?.value == id.value }) { "Directory is not empty" }
        entries.removeAll { it.id == id && it is VaultItem.Directory }
    }

    override suspend fun readFile(id: VaultItemId, consume: suspend (ByteArray) -> Unit): Long {
        val bytes = bytesById[id.value] ?: error("File not found")
        consume(bytes.copyOf())
        return bytes.size.toLong()
    }

    override suspend fun changeCredential(newCredential: CharArray) = Unit

    override fun close() {
        isOpen = false
        bytesById.values.forEach { it.fill(0) }
        bytesById.clear()
    }
}
