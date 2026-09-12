package dev.veilshare.core.identity

import dev.veilshare.core.crypto.Ed25519KeyPair
import dev.veilshare.core.crypto.Ed25519PrivateKey
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.Ed25519Signer
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opaque local context identifier for one independent sharing identity.
 *
 * A context may later be associated by the app with a REAL/DECOY session or another
 * local persona, but this identifier is random and MUST NOT be derived from VaultId,
 * VMK, device identifiers, account identifiers, or hardware fingerprints.
 */
@JvmInline
value class SharingContextId(val value: String) {
    init {
        require(value.isNotBlank()) { "SharingContextId is required" }
        require(value.length <= 128) { "SharingContextId is too long" }
    }
}

data class SharingPublicIdentity(
    val contextId: SharingContextId,
    val identityId: SharingIdentityId,
    val publicKey: Ed25519PublicKey,
    val fingerprint: Fingerprint,
)

/**
 * Storage representation. privateKeySeed is the 32-byte Ed25519 private seed.
 * Persistent implementations MUST protect it with platform secure storage or an
 * application encryption key independent from vault keys.
 */
data class StoredSharingIdentity(
    val contextId: SharingContextId,
    val identityId: SharingIdentityId,
    val privateKeySeed: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(privateKeySeed.size == ED25519_KEY_BYTES) { "Ed25519 private seed must be 32 bytes" }
        require(publicKey.size == ED25519_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
    }

    fun deepCopy(): StoredSharingIdentity = copy(
        privateKeySeed = privateKeySeed.copyOf(),
        publicKey = publicKey.copyOf(),
    )

    fun clearPrivateMaterial() {
        privateKeySeed.fill(0)
    }

    companion object {
        const val ED25519_KEY_BYTES = 32
    }
}

interface SharingIdentityStore {
    suspend fun load(contextId: SharingContextId): StoredSharingIdentity?
    suspend fun replace(record: StoredSharingIdentity)
    suspend fun delete(contextId: SharingContextId)
}

/** In-memory implementation for tests and non-persistent sessions. */
class InMemorySharingIdentityStore : SharingIdentityStore {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, StoredSharingIdentity>()

    override suspend fun load(contextId: SharingContextId): StoredSharingIdentity? = mutex.withLock {
        records[contextId.value]?.deepCopy()
    }

    override suspend fun replace(record: StoredSharingIdentity) {
        mutex.withLock {
            val previous = records.put(record.contextId.value, record.deepCopy())
            previous?.clearPrivateMaterial()
        }
    }

    override suspend fun delete(contextId: SharingContextId) {
        mutex.withLock {
            records.remove(contextId.value)?.clearPrivateMaterial()
        }
    }
}

/**
 * Owns one loaded private identity seed. Callers can temporarily materialize an
 * Ed25519KeyPair for handshake/signing through withKeyPair(); the temporary private
 * copy is closed immediately after the block.
 */
class SharingIdentityHandle internal constructor(
    val publicIdentity: SharingPublicIdentity,
    private val privateSeed: SensitiveBytes,
) : AutoCloseable {
    private var closed = false

    val isOpen: Boolean get() = !closed

    suspend fun <T> withKeyPair(block: suspend (Ed25519KeyPair) -> T): T {
        check(!closed) { "Sharing identity handle is closed" }
        val temporarySeed = SensitiveBytes(privateSeed.copy())
        return try {
            block(
                Ed25519KeyPair(
                    privateKey = Ed25519PrivateKey(temporarySeed),
                    publicKey = Ed25519PublicKey(publicIdentity.publicKey.bytes.copyOf()),
                ),
            )
        } finally {
            temporarySeed.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        privateSeed.close()
    }
}

/**
 * Creates and rotates sharing identities without any vault-key dependency.
 * Rotation always creates both a fresh SharingIdentityId and a fresh Ed25519 keypair.
 */
class SharingIdentityManager(
    private val store: SharingIdentityStore,
    private val random: RandomBytesSource,
    private val signer: Ed25519Signer,
) {
    private val mutex = Mutex()

    suspend fun getOrCreate(contextId: SharingContextId): SharingIdentityHandle = mutex.withLock {
        val existing = store.load(contextId)
        if (existing != null) {
            return@withLock try {
                existing.toHandle()
            } finally {
                existing.clearPrivateMaterial()
            }
        }

        val created = generate(contextId)
        return@withLock try {
            store.replace(created)
            created.toHandle()
        } finally {
            created.clearPrivateMaterial()
        }
    }

    suspend fun publicIdentity(contextId: SharingContextId): SharingPublicIdentity? = mutex.withLock {
        val loaded = store.load(contextId) ?: return@withLock null
        try {
            loaded.toPublicIdentity()
        } finally {
            loaded.clearPrivateMaterial()
        }
    }

    suspend fun rotate(contextId: SharingContextId): SharingIdentityHandle = mutex.withLock {
        val replacement = generate(contextId)
        return@withLock try {
            store.replace(replacement)
            replacement.toHandle()
        } finally {
            replacement.clearPrivateMaterial()
        }
    }

    suspend fun delete(contextId: SharingContextId) {
        mutex.withLock {
            store.delete(contextId)
        }
    }

    private fun generate(contextId: SharingContextId): StoredSharingIdentity {
        val keyPair = signer.generateKeyPair()
        val privateSeed = keyPair.privateKey.material.copy()
        return try {
            StoredSharingIdentity(
                contextId = contextId,
                identityId = OpaqueIds.sharingIdentityId(random),
                privateKeySeed = privateSeed,
                publicKey = keyPair.publicKey.bytes.copyOf(),
            )
        } finally {
            keyPair.privateKey.material.close()
        }
    }

    private fun StoredSharingIdentity.toHandle(): SharingIdentityHandle = SharingIdentityHandle(
        publicIdentity = toPublicIdentity(),
        privateSeed = SensitiveBytes(privateKeySeed.copyOf()),
    )

    private fun StoredSharingIdentity.toPublicIdentity(): SharingPublicIdentity = SharingPublicIdentity(
        contextId = contextId,
        identityId = identityId,
        publicKey = Ed25519PublicKey(publicKey.copyOf()),
        fingerprint = Fingerprint(Hash.sha256(publicKey).toHex()),
    )
}
