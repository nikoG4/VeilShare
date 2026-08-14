package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import kotlin.test.*

class VaultBootstrapTest {
    private val random = object : SecureRandom { private var n = 0; override fun bytes(size: Int) = ByteArray(size) { (n++).toByte() } }
    private val cipher = object : AuthenticatedCipher { override suspend fun seal(key: SensitiveBytes, plaintext: ByteArray, aad: ByteArray): SealedBytes = SealedBytes(Nonce(ByteArray(12)), xor(plaintext, key.copy(), aad)); override suspend fun sealWithNonce(key: SensitiveBytes, nonce: Nonce, plaintext: ByteArray, aad: ByteArray) = SealedBytes(nonce, xor(plaintext, key.copy(), aad)); override suspend fun open(key: SensitiveBytes, sealed: SealedBytes, aad: ByteArray) = xor(sealed.ciphertext, key.copy(), aad) }
    private val kdf = object : PasswordKdf { override suspend fun derive(secret: SensitiveChars, salt: Salt, policy: Argon2Policy): KeyEncryptionKey { val seed = secret.copy().fold(0) { a, c -> a + c.code } + salt.bytes.sum(); return KeyEncryptionKey(SensitiveBytes(ByteArray(32) { (seed + it).toByte() })) } }
    private val wrapper = object : KeyWrapper { override suspend fun wrap(kek: KeyEncryptionKey, vaultKey: VaultKey, aad: ByteArray) = cipher.seal(kek.material, vaultKey.material.copy(), aad); override suspend fun unwrap(kek: KeyEncryptionKey, wrapped: SealedBytes, aad: ByteArray) = VaultKey(SensitiveBytes(cipher.open(kek.material, wrapped, aad))) }
    private val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
    @Test fun realAndDecoyUseIndependentCryptographicSlots() = kotlinx.coroutines.test.runTest {
        val slots = MemorySlots(); val create = CreateVaultSetUseCase(slots, random, kdf, wrapper, cipher, policy)
        val created = create.create(SensitiveChars("111111".toCharArray()), SensitiveChars("222222".toCharArray()))
        assertEquals(2, created.map { it.vaultId }.toSet().size); assertEquals(2, slots.all().map { it.second.salt.bytes.toList() }.toSet().size)
        assertEquals(VaultType.REAL, (UnlockVaultUseCase(slots,kdf,wrapper,cipher,policy).unlock(SensitiveChars("111111".toCharArray())) as UnlockResult.Success).session.descriptor.type)
        assertEquals(VaultType.DECOY, (UnlockVaultUseCase(slots,kdf,wrapper,cipher,policy).unlock(SensitiveChars("222222".toCharArray())) as UnlockResult.Success).session.descriptor.type)
        assertIs<UnlockResult.InvalidCredential>(UnlockVaultUseCase(slots,kdf,wrapper,cipher,policy).unlock(SensitiveChars("999999".toCharArray())))
    }
    private fun xor(input: ByteArray, key: ByteArray, aad: ByteArray) = input.mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt() xor (aad.getOrElse(i % (aad.size.coerceAtLeast(1))) { 0 }.toInt())).toByte() }.toByteArray()
    private class MemorySlots : VaultSlotStore { private val slots = mutableListOf<Pair<String,VaultBootstrapSlot>>(); override suspend fun write(slotId:String, slot:VaultBootstrapSlot) { slots += slotId to slot }; override suspend fun all() = slots.toList() }
}
