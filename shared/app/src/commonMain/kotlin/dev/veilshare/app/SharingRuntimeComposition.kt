package dev.veilshare.app

import dev.veilshare.core.contacts.PersistentTrustedContactStore
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.DefaultHandshakeProtocol
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.X25519KeyAgreement
import dev.veilshare.core.identity.PersistentSharingContextBindingStore
import dev.veilshare.core.identity.PersistentSharingIdentityStore
import dev.veilshare.core.identity.PersistentSharingPresenceStore
import dev.veilshare.core.identity.SharingContextBindingManager
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.platform.SignalingClient
import dev.veilshare.core.securestore.ProtectedStateStore
import dev.veilshare.ui.features.SharingRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Builds the app runtime exclusively from the persistent stores and crypto validated by earlier sharing gates. */
internal fun composeDefaultSharingRuntime(
    protectedStateStore: ProtectedStateStore,
    signalingClient: SignalingClient,
    signer: Ed25519Signer,
    keyAgreement: X25519KeyAgreement,
    keyDeriver: KeyDeriver,
    cipher: AuthenticatedCipher,
    cryptoRandom: SecureRandom,
    idRandom: RandomBytesSource,
    runtimeScope: CoroutineScope,
): DefaultSharingRuntime {
    val identities = SharingIdentityManager(
        PersistentSharingIdentityStore(protectedStateStore),
        idRandom,
        signer,
    )
    val presence = SharingPresenceManager(
        PersistentSharingPresenceStore(protectedStateStore),
        idRandom,
    )
    val contacts = TrustedContactManager(
        PersistentTrustedContactStore(protectedStateStore),
        idRandom,
    )
    val bindings = SharingContextBindingManager(
        PersistentSharingContextBindingStore(protectedStateStore),
        idRandom,
    )
    return DefaultSharingRuntime(
        signalingClient = signalingClient,
        contextBindings = bindings,
        identities = identities,
        presence = presence,
        contacts = contacts,
        handshake = DefaultHandshakeProtocol(),
        signer = signer,
        keyAgreement = keyAgreement,
        keyDeriver = keyDeriver,
        cipher = cipher,
        cryptoRandom = cryptoRandom,
        idRandom = idRandom,
        scope = runtimeScope,
    )
}

internal class SecureRandomIdSource(private val random: SecureRandom) : RandomBytesSource {
    override fun nextBytes(size: Int): ByteArray = random.bytes(size)
}

/**
 * Owns the transport and the runtime coroutine scope. close() is synchronous at the UI
 * boundary but schedules the suspend UNREGISTER/transport shutdown on a scope that is not
 * tied to Compose disposal, avoiding the previous controller-scope teardown race.
 */
internal class OwnedSharingRuntime(
    private val delegate: SharingRuntime,
    private val runtimeScope: CoroutineScope,
    private val closeTransport: () -> Unit,
) : SharingRuntime by delegate {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runtimeScope.launch {
            try {
                runCatching { delegate.cancelCurrent() }
                runCatching { delegate.deactivate() }
            } finally {
                delegate.close()
                closeTransport()
                runtimeScope.cancel()
            }
        }
    }
}
