package dev.veilshare.core.vault

import dev.veilshare.core.crypto.Argon2Policy
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.KeyEncryptionKey
import dev.veilshare.core.crypto.KeyWrapper
import dev.veilshare.core.crypto.PasswordKdf
import dev.veilshare.core.crypto.Salt
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.SensitiveChars
import dev.veilshare.core.crypto.VaultKey
import dev.veilshare.core.crypto.VaultKeyGenerator
import dev.veilshare.core.model.BlobId
import dev.veilshare.core.model.VaultId

interface VaultSlotStore { suspend fun write(slotId: String, slot: VaultBootstrapSlot); suspend fun all(): List<Pair<String, VaultBootstrapSlot>> }
/** Creates the authenticated empty catalog before its slot becomes reachable. */
interface VaultCatalogBootstrap { suspend fun create(descriptor: VaultDescriptor, vaultKey: VaultKey) }
sealed interface UnlockResult { data class Success(val session: VaultSession) : UnlockResult; data object InvalidCredential : UnlockResult; data object CorruptedStorage : UnlockResult }
class VaultSession internal constructor(val descriptor: VaultDescriptor, private val key: VaultKey) : AutoCloseable { var isOpen = true; private set; internal fun key(): VaultKey { check(isOpen); return key }; override fun close() { if (isOpen) { key.material.close(); isOpen = false } } }

class CreateVaultSetUseCase(private val slots: VaultSlotStore, private val random: SecureRandom, private val kdf: PasswordKdf, private val wrapper: KeyWrapper, private val cipher: AuthenticatedCipher, private val policy: Argon2Policy, private val catalogs: VaultCatalogBootstrap? = null) {
    suspend fun create(realPin: SensitiveChars, decoyPin: SensitiveChars): List<VaultDescriptor> = listOf(VaultType.REAL to realPin, VaultType.DECOY to decoyPin).map { (type, pin) -> createOne(type, pin) }
    private suspend fun createOne(type: VaultType, pin: SensitiveChars): VaultDescriptor {
        val vaultId = VaultId(random.bytes(16).toHex())
        val key = VaultKeyGenerator(random).generate(); val salt = Salt(random.bytes(16)); val kek = kdf.derive(pin, salt, policy)
        val descriptor = VaultDescriptor(vaultId, type, VaultFormatVersion(1), VaultMetadataVersion(1), BlobId(random.bytes(16).toHex()), random.bytes(16).toHex())
        val aad = vaultId.value.encodeToByteArray(); val wrapped = wrapper.wrap(kek, key, aad)
        val sealedDescriptor = cipher.seal(key.material, descriptor.encode(), aad)
        catalogs?.create(descriptor, key)
        slots.write(random.bytes(12).toHex(), VaultBootstrapSlot(vaultId = vaultId, formatVersion = VaultFormatVersion(1), metadataVersion = VaultMetadataVersion(1), kdfParameters = policy.parameters, salt = salt, wrappedVaultKey = wrapped, encryptedDescriptor = sealedDescriptor))
        kek.material.close(); return descriptor
    }
}
class UnlockVaultUseCase(private val slots: VaultSlotStore, private val kdf: PasswordKdf, private val wrapper: KeyWrapper, private val cipher: AuthenticatedCipher, private val policy: Argon2Policy) {
    suspend fun unlock(pin: SensitiveChars): UnlockResult {
        val candidates = slots.all(); var success: VaultSession? = null
        candidates.forEach { (_, slot) ->
            try { val aad = slot.vaultId.value.encodeToByteArray(); val kek = kdf.derive(pin, slot.salt, policy); val key = wrapper.unwrap(kek, slot.wrappedVaultKey, aad); kek.material.close(); val descriptor = decodeDescriptor(cipher.open(key.material, slot.encryptedDescriptor, aad)); success = VaultSession(descriptor, key) } catch (_: Exception) { }
        }
        return success?.let(UnlockResult::Success) ?: UnlockResult.InvalidCredential
    }
}
class LockVaultUseCase { fun lock(session: VaultSession) = session.close() }

private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
private fun VaultDescriptor.encode() = listOf(vaultId.value, type.name, formatVersion.value, metadataVersion.value, catalogBlob.value, blobNamespace).joinToString("|").encodeToByteArray()
private fun decodeDescriptor(bytes: ByteArray): VaultDescriptor { val p = bytes.decodeToString().split("|"); if (p.size != 6) throw VaultFormatException("Invalid descriptor"); return VaultDescriptor(VaultId(p[0]), VaultType.valueOf(p[1]), VaultFormatVersion(p[2].toInt()), VaultMetadataVersion(p[3].toInt()), BlobId(p[4]),p[5]) }
