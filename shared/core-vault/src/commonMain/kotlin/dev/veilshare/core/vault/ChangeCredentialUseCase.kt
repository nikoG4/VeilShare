package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*

/** Rewraps the unchanged VMK; catalog, blobs, and wrapped file keys are untouched. */
class ChangeCredentialUseCase(private val slots:VaultSlotStore, private val random:SecureRandom, private val kdf:PasswordKdf, private val wrapper:KeyWrapper, private val policy:Argon2Policy) {
 suspend fun change(session:VaultSession,newPin:SensitiveChars) { check(session.isOpen); val target=slots.all().firstOrNull { (_,s)->s.vaultId==session.descriptor.vaultId } ?: throw VaultFormatException("Active slot missing"); val salt=Salt(random.bytes(16));val kek=kdf.derive(newPin,salt,policy);try { val replacement=target.second.copy(salt=salt,wrappedVaultKey=wrapper.wrap(kek,session.key(),session.descriptor.vaultId.value.encodeToByteArray())); slots.write(target.first,replacement) } finally {kek.material.close()} }
}
