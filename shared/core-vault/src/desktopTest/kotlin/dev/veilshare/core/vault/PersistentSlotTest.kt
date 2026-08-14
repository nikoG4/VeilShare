package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentSlotTest { @Test fun persistentDualSlotsUnlockAfterRestart()=runTest { val root=Files.createTempDirectory("veil-slots-"); val crypto=DesktopProductionCrypto.create(); val policy=Argon2Policy(Argon2Parameters(8192,1,1)); CreateVaultSetUseCase(DesktopVaultSlotStore(root),crypto.random,crypto.passwordKdf,Wrapper(crypto.cipher),crypto.cipher,policy).create(SensitiveChars("000123".toCharArray()),SensitiveChars("000124".toCharArray())); val unlock=UnlockVaultUseCase(DesktopVaultSlotStore(root),crypto.passwordKdf,Wrapper(crypto.cipher),crypto.cipher,policy); assertEquals(VaultType.REAL,(unlock.unlock(SensitiveChars("000123".toCharArray())) as UnlockResult.Success).session.descriptor.type); assertEquals(VaultType.DECOY,(unlock.unlock(SensitiveChars("000124".toCharArray())) as UnlockResult.Success).session.descriptor.type); assertIs<UnlockResult.InvalidCredential>(unlock.unlock(SensitiveChars("bad".toCharArray()))) }
private class Wrapper(private val c:AuthenticatedCipher):KeyWrapper { override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=c.seal(kek.material,vaultKey.material.copy(),aad); override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(c.open(kek.material,wrapped,aad))) } }
